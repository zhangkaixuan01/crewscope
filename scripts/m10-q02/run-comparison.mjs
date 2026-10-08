#!/usr/bin/env node
/**
 * M10-Q02 real-model comparison driver (S01 §4 frozen protocol).
 *
 * Drives the frozen M4 coding suite (12 tasks × 3 repetitions) through the product's
 * public API against the running augmented stack, one arm per invocation. The arms are
 * deployment-level switch states of the SAME stack (`quickstart up` toggling only
 * CREWSCOPE_KNOWLEDGE_RETRIEVAL/INJECTION) — the driver never resets data between arms.
 *
 * It reuses everything the loop spec provisioned (model connections, coding profile,
 * repository binding, reviewer profile) via the coordinates file and never touches a
 * real provider key: connections already exist, sessions are throwaway gate accounts.
 *
 * Per run: carrier work item → task (suite instruction verbatim) → settle loop with one
 * CONFIRMATION resume → evidence capture (coding view, patch artifact, injection
 * references, cost-month snapshot diff) → gate-side judge → review create/execute and a
 * mechanical member-B gate decision (no BLOCKER finding → APPROVED). One infra retry per
 * run when the attempt settled without any tool evidence (m9b-q02 precedent).
 *
 * Progress is append-only JSONL keyed `arm:task:rep` — restart skips completed keys.
 *
 * Usage:
 *   node scripts/m10-q02/run-comparison.mjs --arm off|on
 *   node scripts/m10-q02/run-comparison.mjs --list
 * Environment:
 *   CREWSCOPE_M10Q02_BASE_URL     default http://127.0.0.1:18090
 *   CREWSCOPE_M10Q02_COORDINATES  default var/release/m10-q02/loop-coordinates.json
 *   CREWSCOPE_M10Q02_REPORT_DIR   default var/release/m10-q02/reports
 *   CREWSCOPE_M10Q02_TASKS        comma-separated task-id subset (smoke)
 *   CREWSCOPE_M10Q02_REPS         repetition count override (default 3)
 *   CREWSCOPE_M10Q02_SKIP_WARM    =1 skips the judge .m2 volume warm-up
 */
import { appendFileSync, existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import { join, resolve } from 'node:path'
import { ApiClient, awaitTerminal } from './lib/api.mjs'
import { BASELINE_COMMIT, SUITE_TASKS, judgeRun, judgeVolumeReady, judgeWorkspaceFor, persistPatch, warmJudgeVolume } from './lib/judge.mjs'

const REPO_ROOT = resolve(import.meta.dirname, '..', '..')
const BASE_URL = process.env.CREWSCOPE_M10Q02_BASE_URL || 'http://127.0.0.1:18090'
const COORDINATES_FILE = resolve(REPO_ROOT, process.env.CREWSCOPE_M10Q02_COORDINATES || 'var/release/m10-q02/loop-coordinates.json')
const REPORT_DIR = resolve(REPO_ROOT, process.env.CREWSCOPE_M10Q02_REPORT_DIR || 'var/release/m10-q02/reports')
const REPS = Number(process.env.CREWSCOPE_M10Q02_REPS || 3)
const TASK_FILTER = process.env.CREWSCOPE_M10Q02_TASKS ? process.env.CREWSCOPE_M10Q02_TASKS.split(',').map(s => s.trim()).filter(Boolean) : null

const args = process.argv.slice(2)
const listOnly = args.includes('--list')
const backfillOnly = args.includes('--backfill-reviews')
const arm = args.includes('--arm') ? args[args.indexOf('--arm') + 1] : null
if (!listOnly && !['off', 'on'].includes(arm)) {
  console.error('usage: run-comparison.mjs --arm off|on [--backfill-reviews] | --list')
  process.exit(2)
}
const armsToList = listOnly ? (arm ? [arm] : ['off', 'on']) : [arm]

const tasks = TASK_FILTER ? SUITE_TASKS.filter(t => TASK_FILTER.includes(t.id)) : SUITE_TASKS
if (!tasks.length) {
  console.error(`no suite tasks matched CREWSCOPE_M10Q02_TASKS=${process.env.CREWSCOPE_M10Q02_TASKS}`)
  process.exit(2)
}
const reps = Array.from({ length: REPS }, (_, i) => i + 1)
const runKeys = []
for (const listedArm of armsToList) {
  for (const task of tasks) for (const rep of reps) runKeys.push({ key: `${listedArm}:${task.id}:${rep}`, task, rep })
}

if (listOnly) {
  for (const entry of runKeys) console.log(entry.key)
  console.error(`${runKeys.length} runs (arms=${armsToList.join('+')} tasks=${tasks.length} reps=${reps.length})`)
  process.exit(0)
}

if (!existsSync(COORDINATES_FILE)) {
  console.error(`missing coordinates file ${COORDINATES_FILE} — run the loop spec (s2) first`)
  process.exit(2)
}
const coordinates = JSON.parse(readFileSync(COORDINATES_FILE, 'utf8'))

mkdirSync(REPORT_DIR, { recursive: true })
const jsonlPath = join(REPORT_DIR, `runs-${arm}.jsonl`)
const doneKeys = new Set(
  (existsSync(jsonlPath) ? readFileSync(jsonlPath, 'utf8') : '')
    .split('\n').filter(Boolean)
    .map(line => { try { return JSON.parse(line).key } catch { return null } })
    .filter(Boolean),
)

const orgRoot = `/api/v1/organizations/${coordinates.orgId}`
const teamRoot = `${orgRoot}/teams/${coordinates.teamId}`
const tasksPath = `${teamRoot}/tasks`
const projectsPath = `${teamRoot}/work-projects`
const workItemsPath = `${projectsPath}/${coordinates.projectId}/work-items`
const agentsPath = `${teamRoot}/agent-profiles`

const client = new ApiClient(BASE_URL, coordinates.memberA)
const clientB = new ApiClient(BASE_URL, coordinates.memberB)

function log(entry) {
  console.error(`[${new Date().toISOString()}] [${arm}] ${typeof entry === 'string' ? entry : JSON.stringify(entry)}`)
}

/** Cost-month snapshot: per (role|model|attempt|currency) token triplets, summed for diffing. */
async function costSnapshot() {
  const month = new Date().toISOString().slice(0, 7)
  const detail = await client.getJson(`${teamRoot}/observability/cost/months/${month}`)
  const rows = new Map()
  for (const row of detail.rows ?? []) {
    const key = `${row.role}|${row.providerKey}|${row.modelId}|${row.attempt}|${row.currencyCode}`
    const prior = rows.get(key) ?? { input: 0, output: 0, cached: 0, facts: 0, unpriced: 0 }
    rows.set(key, {
      input: prior.input + (row.inputTokens ?? 0),
      output: prior.output + (row.outputTokens ?? 0),
      cached: prior.cached + (row.cachedTokens ?? 0),
      facts: prior.facts + (row.factCount ?? 0),
      unpriced: prior.unpriced + (row.costStatus === 'UNPRICED' ? 1 : 0),
    })
  }
  return { month, rows }
}

function costDiff(before, after) {
  const totals = { input: 0, output: 0, cached: 0, facts: 0, unpriced: 0 }
  if (before.month !== after.month) return { ...totals, monthCrossed: `${before.month}->${after.month}` }
  for (const [key, afterRow] of after.rows) {
    const beforeRow = before.rows.get(key) ?? { input: 0, output: 0, cached: 0, facts: 0, unpriced: 0 }
    totals.input += afterRow.input - beforeRow.input
    totals.output += afterRow.output - beforeRow.output
    totals.cached += afterRow.cached - beforeRow.cached
    totals.facts += afterRow.facts - beforeRow.facts
    totals.unpriced += Math.max(0, afterRow.unpriced - beforeRow.unpriced)
  }
  return totals
}

/** Comparison purity: the loop spec's t8 appended an approved skill to the shared coding
 *  profile. The comparison arms must run with an identical, empty skill list — pin the
 *  effective configuration revision after resetting approvedSkillKeys to []. The saved
 *  body is the closed configuration field set (m9b precedent); projections never feed back. */
async function resolveComparisonConfiguration() {
  const profileRoot = `${agentsPath}/${coordinates.executorAgentProfileId}`
  const current = await client.getJson(`${profileRoot}/configurations/current`)
  if (!current.approvedSkillKeys?.length) return { revision: current.revision, resetApplied: false }
  const saved = await client.command(`${profileRoot}/configurations`, {
    // Projection names the fields personalBinding/teamBinding; the command body re-posts them
    // under personalModelBinding/teamModelBinding (AgentConfigurationController DTO pair).
    personalModelBinding: current.personalBinding,
    teamModelBinding: current.teamBinding,
    supplementalInstructions: current.supplementalInstructions ?? null,
    approvedSkillKeys: [],
    memoryPolicy: current.memoryPolicy ?? null,
    budgetPolicy: current.budgetPolicy ?? null,
    generateOptions: current.generateOptions ?? null,
  }, { ifMatch: current.revision })
  log({ configurationReset: 'approvedSkillKeys -> []', receipt: saved.body })
  for (let i = 0; i < 20; i++) {
    await new Promise(r => setTimeout(r, 3000))
    const next = await client.getJson(`${profileRoot}/configurations/current`)
    if (!next.approvedSkillKeys?.length && next.revision > current.revision) {
      return { revision: next.revision, resetApplied: true }
    }
  }
  throw new Error('approvedSkillKeys reset did not converge')
}

async function ensureCarrierItem(task) {
  const title = `Q02CMP ${arm} ${task.id}`.slice(0, 96)
  const list = await client.getJson(`${workItemsPath}?limit=50`)
  const existing = (list.items ?? []).find(item => item.title === title)
  if (existing) return existing.id
  await client.command(workItemsPath, {
    type: 'FEATURE', title, description: `M10-Q02 comparison carrier (${arm})`, priority: 'MEDIUM', labels: ['q02-cmp'], dueAt: null,
  })
  for (let i = 0; i < 20; i++) {
    await new Promise(r => setTimeout(r, 3000))
    const page = await client.getJson(`${workItemsPath}?limit=50`)
    const found = (page.items ?? []).find(item => item.title === title)
    if (found) return found.id
  }
  throw new Error(`carrier work item did not appear: ${title}`)
}

/** Seat attach that tolerates the seat being already held: 409, or 422 invalid_value with
 *  an "already has an active … assignment" message when the carrier survived an earlier
 *  interrupted run (gate and advisory seats share the error shape). */
async function attachSeat(itemRoot, seat, principalId) {
  try {
    await client.command(`${itemRoot}/responsibilities/${seat}`, { actorPrincipalId: principalId })
  } catch (error) {
    const alreadyHeld = error.status === 409
      || (error.status === 422 && String(error.body?.message ?? '').includes('already has an active'))
    if (!alreadyHeld) throw error
  }
}

async function attachGateSeat(itemRoot) {
  await attachSeat(itemRoot, 'gate-reviewers', coordinates.memberB.principalId)
}

async function createTask(itemRoot, task, rep, marker) {
  const target = coordinates.repositoryBinding
  await client.command(`${itemRoot}/tasks`, {
    objective: `[${marker}] ${task.instruction}`,
    acceptanceCriteria: [
      task.instruction,
      `改动仅限这些路径内的文件：${task.allowedPaths.join('、')}，以及 src/test/java 下为本任务自建的定向单元测试；不要改动其它文件。`,
      '仓库当前没有任何测试：请在 src/test/java 下为交付物自建一个针对性的 JUnit 单元测试，并通过 tests 选择器只运行该自建测试完成验证；不要全量运行测试套件，也不要尝试需要容器环境的集成测试。',
    ],
    executorAgentProfileId: coordinates.executorAgentProfileId,
    agentConfigurationRevision: comparisonConfig.revision,
    executorAssignment: { agentProfileId: coordinates.executorAgentProfileId },
    conversationSource: null,
    providerBindingIds: coordinates.providerBindingGrantId ? [coordinates.providerBindingGrantId] : [],
    codingTarget: {
      repositoryBindingId: target.bindingId,
      baselineRef: target.baselineRef,
      allowedPaths: [...task.allowedPaths, 'src/test/java'],
      buildProfile: target.buildProfile,
    },
  }, { ifMatch: 0 })
  for (let i = 0; i < 30; i++) {
    await new Promise(r => setTimeout(r, 5000))
    const page = await client.getJson(`${tasksPath}?projectId=${coordinates.projectId}&limit=50`)
    const row = (page.items ?? []).find(item => item.objective.includes(`[${marker}]`))
    if (row?.currentExecutionId) return { taskId: row.id, executionId: row.currentExecutionId }
  }
  throw new Error(`task did not open an execution for marker ${marker}`)
}

async function captureCodingView(taskId, executionId) {
  return client.getJson(`${tasksPath}/${taskId}/attempts/${executionId}/coding`)
}

async function fetchPatch(taskId, executionId) {
  try {
    return await client.getText(`${tasksPath}/${taskId}/attempts/${executionId}/coding/artifacts/patch`)
  } catch {
    return ''
  }
}

async function captureInjection(taskId, executionId) {
  try {
    const view = await client.getJson(`${tasksPath}/${taskId}/attempts/${executionId}/injection-references`)
    const byType = {}
    let injected = 0
    const degradations = []
    for (const attempt of view.attempts ?? []) {
      degradations.push(...(attempt.degradations ?? []))
      for (const reference of attempt.references ?? []) {
        if (reference.stage === 'INJECTED') {
          injected += 1
          byType[reference.type] = (byType[reference.type] ?? 0) + 1
        }
      }
    }
    return { attemptCount: (view.attempts ?? []).length, injected, byType, degradations: [...new Set(degradations)] }
  } catch (error) {
    return { attemptCount: 0, injected: 0, byType: {}, degradations: [`unavailable:${error.status ?? error.message}`] }
  }
}

/** Review: create → execute → poll COMPLETED → member-B mechanical gate decision. */
async function runReview(taskId, executionId) {
  const reviewsPath = `${tasksPath}/${taskId}/attempts/${executionId}/reviews`
  const review = { opened: false, firstPass: null, findings: null }
  try {
    await client.command(reviewsPath, {})
  } catch (error) {
    review.unavailable = `create:${error.status}:${JSON.stringify(error.body).slice(0, 200)}`
    return review
  }
  review.opened = true
  let row = null
  for (let i = 0; i < 20 && !row; i++) {
    await new Promise(r => setTimeout(r, 3000))
    const page = await client.getJson(reviewsPath)
    row = (page.items ?? [])[0] ?? null
  }
  if (!row) { review.unavailable = 'create:row-never-appeared'; return review }
  try {
    await client.command(`${reviewsPath}/${row.id}/execute`, undefined, { ifMatch: row.version, expected: 200 })
  } catch (error) {
    review.unavailable = `execute:${error.status}:${JSON.stringify(error.body).slice(0, 200)}`
    return review
  }
  let detail = null
  for (let i = 0; i < 60; i++) {
    await new Promise(r => setTimeout(r, 3000))
    detail = await client.getJson(`${reviewsPath}/${row.id}`)
    if (detail.status === 'COMPLETED') break
    if (detail.status === 'FAILED' || detail.status === 'CANCELLED') break
  }
  review.executeStatus = detail?.status ?? 'MISSING'
  if (detail?.status !== 'COMPLETED') { review.unavailable = `execute:settled-${detail?.status}`; return review }
  const findings = detail.findings ?? []
  const blockers = findings.filter(f => f.severity === 'BLOCKER').length
  review.findings = { total: findings.length, blockers, bySeverity: findings.reduce((acc, f) => ({ ...acc, [f.severity]: (acc[f.severity] ?? 0) + 1 }), {}) }
  const decisionType = blockers === 0 ? 'APPROVED' : 'CHANGES_REQUESTED'
  await clientB.ensureSession()
  try {
    await clientB.command(`${reviewsPath}/${row.id}/decisions`, {
      type: decisionType,
      rationale: `M10-Q02 comparison driver mechanical gate: ${blockers} blocker finding(s)`,
    }, { ifMatch: detail.version, expected: 202 })
    review.decisionType = decisionType
    review.firstPass = decisionType === 'APPROVED'
  } catch (error) {
    review.unavailable = `decision:${error.status}:${JSON.stringify(error.body).slice(0, 200)}`
  }
  return review
}

/** Repairs the status/productOutcome columns the pre-fix driver recorded as null (the
 *  attempt.settled typo) and runs the review + gate decision that same typo skipped on
 *  every COMPLETED execution. The arms themselves must not be re-executed just to
 *  re-collect their verdicts: settle evidence, patches and judge outcomes are already
 *  sealed in the JSONL, only the live-API verdicts need a second pass. */
async function backfillReviews() {
  await client.ensureSession()
  // Review create demands an advisory Reviewer Agent seat on the carrier item. The loop
  // spec's carrier got one in t1, but the comparison carriers only ever held the member-B
  // gate seat — the settled typo meant review create never ran during the arms, so the
  // gap surfaces only here. Resolve the loop's reviewer agent principal once.
  const profiles = await client.getJson(`${agentsPath}?limit=50`)
  const reviewerPrincipal = (profiles.items ?? [])
    .find(item => item.id === coordinates.reviewerAgentProfileId)?.principalId
  if (!reviewerPrincipal) throw new Error('reviewer agent profile not found on the coordinates team')
  const lines = existsSync(jsonlPath) ? readFileSync(jsonlPath, 'utf8').split('\n').filter(Boolean) : []
  const repaired = []
  for (const line of lines) {
    const record = JSON.parse(line)
    if (record.executionId && record.status == null) {
      // A 404 here means the task no longer lives on this stack — the record is a stale
      // smoke leftover (same JSONL key, pre-reset stack) and carries no live verdict.
      let attempts = null
      try {
        attempts = await client.getJson(`${tasksPath}/${record.taskId}/attempts`)
      } catch (error) {
        if (error.status !== 404) throw error
        record.notes.push('task not found on this stack — stale record, no live status to backfill')
      }
      const live = attempts?.find(item => item.id === record.executionId)?.status ?? null
      if (live) {
        record.status = live
        record.notes.push(`status backfilled from live attempts API: ${live}`)
        const evidenceCount = (record.evidence?.commandEvidenceCount ?? 0)
          + (record.evidence?.testEvidenceCount ?? 0)
        record.productOutcome = (live === 'COMPLETED' && evidenceCount > 0 && record.evidence?.hasDiffManifest)
          ? 'PASS' : 'FAIL'
      }
    }
    if (record.status === 'COMPLETED' && record.review?.opened !== true) {
      const itemId = await ensureCarrierItem({ id: record.task })
      const itemRoot = `${projectsPath}/${coordinates.projectId}/work-items/${itemId}`
      await attachSeat(itemRoot, 'advisory-reviewers', reviewerPrincipal)
      record.review = await runReview(record.taskId, record.executionId)
    }
    repaired.push(record)
    log({ backfilled: record.key, status: record.status ?? 'UNKNOWN', review: record.review?.opened === true ? 'opened' : (record.review?.unavailable ?? 'opened') })
  }
  writeFileSync(jsonlPath, repaired.map(entry => JSON.stringify(entry)).join('\n') + '\n')
  log({ backfillFinished: { arm, records: repaired.length } })
}

// ---- boot ----
log({ boot: { baseUrl: BASE_URL, tasks: tasks.length, reps: reps.length, doneKeys: doneKeys.size, jsonl: jsonlPath } })
if (coordinates.repositoryBinding.baselineCommit !== BASELINE_COMMIT) {
  console.error(`coordinates baselineCommit ${coordinates.repositoryBinding.baselineCommit} != frozen ${BASELINE_COMMIT}`)
  process.exit(2)
}

if (backfillOnly) {
  await backfillReviews()
  process.exit(0)
}

if (!process.env.CREWSCOPE_M10Q02_SKIP_WARM && !judgeVolumeReady()) {
  log('warming judge .m2 volume (one-time, network-enabled)…')
  const warmed = warmJudgeVolume()
  log({ warm: warmed.outcome, durationMs: warmed.durationMs })
  if (!warmed.ok) process.exit(2)
}

// Login after the (minutes-long) warm: a session established earlier would hold a
// keep-alive connection idle through it, and undici reusing a socket the server has
// since closed fails the next request with UND_ERR_SOCKET ("other side closed").
await client.ensureSession()

const comparisonConfig = await resolveComparisonConfiguration()
log({ comparisonConfiguration: comparisonConfig })

const onArmStart = arm === 'on'
  ? await (async () => {
      const preview = await client.command(`${teamRoot}/knowledge/knowledge-retrieval:preview`, { query: 'cache key tenant coordinates', topK: 3 }, { expected: 200 })
      const degraded = preview.body?.degraded ?? []
      log({ armOnReadiness: { degraded, candidates: preview.body?.candidates?.length ?? 0 } })
      return degraded.length === 0
    })()
  : true
if (!onArmStart) {
  console.error('on-arm prerequisite failed: retrieval preview answered degraded — the stack is not in the augmented configuration')
  process.exit(2)
}

let completed = 0
for (const entry of runKeys) {
  if (doneKeys.has(entry.key)) { log({ skip: entry.key }); continue }
  const { task, rep, key } = entry
  const record = {
    key, arm, task: task.id, rep, category: task.category,
    startedAt: new Date().toISOString(), status: null, retried: false,
    productOutcome: null, judge: null, injection: null, tokens: null,
    review: null, notes: [],
  }
  try {
    const itemId = await ensureCarrierItem(task)
    const itemRoot = `${projectsPath}/${coordinates.projectId}/work-items/${itemId}`
    await attachGateSeat(itemRoot)

    let attempt = null
    let marker = `q02-${arm}-${task.id}-r${rep}`
    for (let tries = 0; tries < 2; tries++) {
      const costBefore = await costSnapshot()
      const wallStart = Date.now()
      const created = await createTask(itemRoot, task, rep, marker)
      const settled = await awaitTerminal(client, {
        taskId: created.taskId, executionId: created.executionId, tasksPath,
      })
      const wallMs = Date.now() - wallStart
      const codingView = await captureCodingView(created.taskId, created.executionId)
      const evidenceCount = (codingView.details?.commandEvidenceCount ?? 0) + (codingView.details?.testEvidenceCount ?? 0)
      const hasDiff = Boolean(codingView.details?.diffManifest)
      attempt = { ...created, ...settled, wallMs, codingView, evidenceCount, hasDiff, costBefore }
      // m9b-q02 precedent: a terminal state without any tool-session evidence means the
      // model never engaged (observed fast TASK_PLAN_INVALID flake) — retry the run once.
      if (settled.status !== 'COMPLETED' && evidenceCount === 0 && !hasDiff && tries === 0) {
        record.retried = true
        record.notes.push(`first attempt settled ${settled.status} without evidence; retried once`)
        marker = `${marker}x`
        continue
      }
      break
    }

    const costAfter = await costSnapshot()
    // awaitTerminal returns { status, ... }; the spread named it status on the attempt.
    record.status = attempt.status
    record.taskId = attempt.taskId
    record.executionId = attempt.executionId
    record.wallMs = attempt.wallMs
    record.resumedConfirmation = attempt.resumedConfirmation
    record.evidence = {
      commandEvidenceCount: attempt.codingView.details?.commandEvidenceCount ?? 0,
      testEvidenceCount: attempt.codingView.details?.testEvidenceCount ?? 0,
      hasDiffManifest: attempt.hasDiff,
    }
    record.productOutcome = (attempt.status === 'COMPLETED' && attempt.evidenceCount > 0 && attempt.hasDiff) ? 'PASS' : 'FAIL'
    record.tokens = costDiff(attempt.costBefore, costAfter)

    const patch = await fetchPatch(attempt.taskId, attempt.executionId)
    persistPatch(join(REPORT_DIR, 'patches', `${key}.patch`), patch)
    record.judge = judgeRun(task, patch, judgeWorkspaceFor(REPORT_DIR, arm, task.id, rep))
    if (record.judge.logTail) {
      writeFileSync(join(REPORT_DIR, 'judge', `${arm}-${task.id}-rep${rep}.judge.log`), record.judge.logTail)
    }

    record.injection = await captureInjection(attempt.taskId, attempt.executionId)
    if (attempt.status === 'COMPLETED') {
      record.review = await runReview(attempt.taskId, attempt.executionId)
    } else {
      record.review = { opened: false, firstPass: null, unavailable: `attempt-${attempt.status}` }
    }
  } catch (error) {
    record.fatal = `${error.status ?? ''} ${error.message}`.slice(0, 400)
  }
  record.finishedAt = new Date().toISOString()
  appendFileSync(jsonlPath, `${JSON.stringify(record)}\n`)
  completed += 1
  log({ done: key, status: record.status, judge: record.judge?.outcome, product: record.productOutcome, injection: record.injection?.injected ?? 0, review: record.review?.decisionType ?? record.review?.unavailable ?? '-', fatal: record.fatal ?? '-' })
}

log({ finished: { arm, completed, expected: runKeys.length, jsonl: jsonlPath } })
