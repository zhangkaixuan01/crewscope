import { expect, test, type Page } from '@playwright/test'
import { existsSync, readFileSync } from 'node:fs'
import { resolve, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'
import {
  baseURL,
  currentSession,
  getJson,
  login,
  onlyTeam,
  teamPath,
  type Session,
} from '../real-backend'

/**
 * M10-Q02 s7 quadrant recheck: after the full dual-image in-place upgrade (vector build →
 * vector up with data in place, switches on), the loop's durable facts must survive — the
 * published knowledge and repository indexes still answer undegraded previews, and the
 * published Skill still loads into a fresh execution at the exact recorded revision.
 *
 * Everything is pinned from the coordinates file the loop spec (s2) wrote: the same member
 * account, team, project, carrier item, executor profile, repository binding and skill key.
 * No provider key is needed here — the connections live server-side; this spec only rides
 * them. Runs against the augmented stack (18090) after the upgrade phase.
 */

const REPO_ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..', '..', '..')
const COORDINATES_FILE = resolve(REPO_ROOT, 'var/release/m10-q02/loop-coordinates.json')
const hasCoordinates = existsSync(COORDINATES_FILE)
const coordinates = hasCoordinates
  ? JSON.parse(readFileSync(COORDINATES_FILE, 'utf8')) as {
      orgId: string,
      teamId: string,
      projectId: string,
      codingWorkItemId: string,
      executorAgentProfileId: string,
      memberA: { identifier: string, password: string },
      repositoryBinding: { bindingId: string, baselineRef: string, baselineCommit: string, buildProfile: Record<string, unknown> },
      publishedSkill: { skillId: string, skillKey: string, revision: number, contentHash: string },
    }
  : null
const SUITE = JSON.parse(
  readFileSync(resolve(REPO_ROOT, 'evaluation/m4/coding-v1/suite.json'), 'utf8'),
) as { tasks: Array<{ id: string, instruction: string, allowedPaths: string[] }> }
const RECHECK_TASK = SUITE.tasks.find(task => task.id === 'java-secret-redaction')!

test.skip(!hasCoordinates, `missing ${COORDINATES_FILE} — the loop spec (s2) must run before the upgrade recheck`)

test.describe.configure({ mode: 'serial' })

let page: Page
let session: Session
const tasksRoot = () => teamPath(session, coordinates!.teamId, 'tasks')
const agentsRoot = () => teamPath(session, coordinates!.teamId, 'agent-profiles')
const skillsRoot = () => teamPath(session, coordinates!.teamId, 'skills')

test('u1 the upgraded stack still answers undegraded previews from both sources', async ({ browser }) => {
  const context = await browser.newContext({ baseURL, timezoneId: 'Asia/Shanghai', reducedMotion: 'reduce' })
  page = await context.newPage()
  await page.goto('/login')
  await login(page, coordinates!.memberA.identifier, coordinates!.memberA.password)
  session = await currentSession(page)
  expect(onlyTeam(session).teamId).toBe(coordinates!.teamId)

  const answer = await page.request.post(
    teamPath(session, coordinates!.teamId, 'knowledge/knowledge-retrieval:preview'), {
      data: {
        query: '用户名规范化 UserNameNormalizer normalize 规则',
        sources: ['KNOWLEDGE_ENTRY', 'REPOSITORY_CHUNK'],
        topK: 8,
        repository: {
          projectId: coordinates!.projectId,
          bindingId: coordinates!.repositoryBinding.bindingId,
          commit: coordinates!.repositoryBinding.baselineCommit,
        },
      },
      headers: { [session.csrf.headerName]: session.csrf.token, 'Content-Type': 'application/json' },
    })
  expect(answer.status(), `preview answered ${answer.status()}: ${await answer.text()}`).toBe(200)
  const body = await answer.json() as {
    candidates: Array<{ source: string, fragments: Array<{ commit: string }> }>,
    degraded: string[],
  }
  expect(body.degraded, 'the upgraded stack degrades neither source').toEqual([])
  expect(body.candidates.some(candidate => candidate.source === 'KNOWLEDGE_ENTRY'), 'knowledge entries still surface').toBe(true)
  const chunk = body.candidates.find(candidate => candidate.source === 'REPOSITORY_CHUNK')
  expect(chunk, 'repository chunks still surface').toBeTruthy()
  expect(chunk!.fragments.every(fragment => fragment.commit === coordinates!.repositoryBinding.baselineCommit),
    'fragments are still pinned to the frozen commit').toBe(true)
})

test('u2 the published Skill still loads into a fresh execution at the recorded revision', async () => {
  test.setTimeout(1_800_000)
  const profileConfigRoot = `${agentsRoot()}/${coordinates!.executorAgentProfileId}`
  const before = await getJson<{
    revision: number, approvedSkillKeys: string[],
    personalModelBinding: unknown, teamModelBinding: unknown, supplementalInstructions: string | null,
    memoryPolicy: unknown, budgetPolicy: unknown, generateOptions: unknown,
  }>(page, `${profileConfigRoot}/configurations/current`)
  if (!before.approvedSkillKeys.includes(coordinates!.publishedSkill.skillKey)) {
    // The comparison driver (s3/s4) resets the approved list for arm purity; re-approve the
    // loop's skill for this recheck run.
    const appended = await page.request.post(`${profileConfigRoot}/configurations`, {
      data: {
        personalModelBinding: before.personalModelBinding,
        teamModelBinding: before.teamModelBinding,
        supplementalInstructions: before.supplementalInstructions,
        approvedSkillKeys: [...before.approvedSkillKeys, coordinates!.publishedSkill.skillKey],
        memoryPolicy: before.memoryPolicy,
        budgetPolicy: before.budgetPolicy,
        generateOptions: before.generateOptions,
      },
      headers: {
        [session.csrf.headerName]: session.csrf.token,
        'Idempotency-Key': crypto.randomUUID(),
        'If-Match': `"${before.revision}"`,
      },
    })
    expect(appended.status, `configuration append failed: ${await appended.text()}`).toBe(202)
    await expect.poll(async () => {
      const current = await getJson<{ approvedSkillKeys: string[] }>(page, `${profileConfigRoot}/configurations/current`)
      return current.approvedSkillKeys.includes(coordinates!.publishedSkill.skillKey)
    }).toBe(true)
  }

  // The effective version must still serve the exact recorded revision — the upgrade never
  // rewrites published content.
  const effective = await getJson<{ revision: number, contentHash: string }>(
    page, `${skillsRoot()}/${coordinates!.publishedSkill.skillId}/effective-version`)
  expect(effective.revision, 'the effective revision survived the upgrade').toBe(coordinates!.publishedSkill.revision)
  expect(effective.contentHash, 'the content hash survived the upgrade').toBe(coordinates!.publishedSkill.contentHash)

  // One fresh coding run: create → settle (resuming once at the CONFIRMATION gate). Pin the
  // post-append configuration revision, not the pre-read one.
  const pinnedRevision = await getJson<{ revision: number, approvedSkillKeys: string[] }>(
    page, `${profileConfigRoot}/configurations/current`)
  expect(pinnedRevision.approvedSkillKeys.includes(coordinates!.publishedSkill.skillKey)).toBe(true)
  const itemRoot = teamPath(session, coordinates!.teamId,
    `work-projects/${coordinates!.projectId}/work-items/${coordinates!.codingWorkItemId}`)
  const marker = `q02-recheck-${Date.now()}`
  const created = await page.request.post(`${itemRoot}/tasks`, {
    data: {
      objective: `[${marker}] ${RECHECK_TASK.instruction}`,
      acceptanceCriteria: [
        RECHECK_TASK.instruction,
        `改动仅限这些路径内的文件：${RECHECK_TASK.allowedPaths.join('、')}；不要改动其它文件。`,
        '本仓库的集成测试需要 Docker 等容器环境，执行沙箱内不可用；验收测试请通过 tests 选择器只定向运行与交付物相关的单元测试，不要全量运行测试套件',
      ],
      executorAgentProfileId: coordinates!.executorAgentProfileId,
      agentConfigurationRevision: pinnedRevision.revision,
      executorAssignment: { agentProfileId: coordinates!.executorAgentProfileId },
      conversationSource: null,
      providerBindingIds: [],
      codingTarget: {
        repositoryBindingId: coordinates!.repositoryBinding.bindingId,
        baselineRef: coordinates!.repositoryBinding.baselineRef,
        allowedPaths: RECHECK_TASK.allowedPaths,
        buildProfile: coordinates!.repositoryBinding.buildProfile,
      },
    },
    headers: {
      [session.csrf.headerName]: session.csrf.token,
      'Idempotency-Key': crypto.randomUUID(),
      'If-Match': '"0"',
    },
  })
  expect(created.status, `task create failed: ${await created.text()}`).toBe(202)
  let executionId = ''
  let taskId = ''
  const deadlineAppear = Date.now() + 90_000
  while (Date.now() < deadlineAppear && !executionId) {
    const list = await getJson<{ items: Array<{ id: string, objective: string, currentExecutionId: string | null }> }>(
      page, `${tasksRoot()}?projectId=${coordinates!.projectId}&limit=50`)
    const row = (list.items ?? []).find(item => item.objective.includes(`[${marker}]`))
    if (row?.currentExecutionId) { taskId = row.id; executionId = row.currentExecutionId }
    else await page.waitForTimeout(5000)
  }
  expect(executionId, 'the recheck execution opened').toBeTruthy()

  let resumedOnce = false
  let settled = ''
  const deadline = Date.now() + 1_500_000
  while (Date.now() < deadline) {
    const attempts = await getJson<Array<{ id: string, status: string, version: number, waiting?: { reason: string } | null }>>(
      page, `${tasksRoot()}/${taskId}/attempts`)
    const attempt = attempts.find(item => item.id === executionId)
    settled = attempt?.status ?? 'MISSING'
    if (settled === 'COMPLETED') break
    if (settled === 'FAILED' || settled === 'CANCELLED' || settled === 'RECOVERING') {
      await page.waitForTimeout(30_000)
      const recheck = await getJson<Array<{ id: string, status: string }>>(page, `${tasksRoot()}/${taskId}/attempts`)
      settled = recheck.find(item => item.id === executionId)?.status ?? settled
      if (settled !== 'RUNNING' && settled !== 'WAITING') break
    }
    if (settled === 'WAITING' && attempt?.waiting?.reason === 'CONFIRMATION' && !resumedOnce) {
      resumedOnce = true
      const resumed = await page.request.post(`${tasksRoot()}/${taskId}/attempts/${executionId}/resume`, {
        headers: {
          [session.csrf.headerName]: session.csrf.token,
          'Idempotency-Key': crypto.randomUUID(),
          'If-Match': `"${attempt.version}"`,
        },
      })
      expect([200, 201, 202]).toContain(resumed.status())
    }
    await page.waitForTimeout(15_000)
  }
  test.info().annotations.push({ type: 'note', description: `recheck execution settled ${settled}` })
  expect(settled, 'the recheck execution completes on the real model').toBe('COMPLETED')

  const references = await getJson<{ attempts: Array<{ references: Array<{ stage: string, type: string, sourceId: string, version: number, contentHash: string }> }> }>(
    page, `${tasksRoot()}/${taskId}/attempts/${executionId}/injection-references`)
  const injected = references.attempts.flatMap(attempt => attempt.references.filter(ref => ref.stage === 'INJECTED'))
  const skillRefs = injected.filter(ref => ref.type === 'SKILL_INSTRUCTION'
    && ref.sourceId === coordinates!.publishedSkill.skillKey)
  expect(skillRefs.length, 'the published skill loaded into the post-upgrade execution').toBeGreaterThan(0)
  for (const ref of skillRefs) {
    expect(ref.version, 'the loaded revision == the recorded revision R').toBe(coordinates!.publishedSkill.revision)
    expect(ref.contentHash, 'the loaded hash == the recorded content hash').toBe(coordinates!.publishedSkill.contentHash)
  }
})
