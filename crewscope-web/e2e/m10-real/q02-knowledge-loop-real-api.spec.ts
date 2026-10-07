import { expect, test, type BrowserContext, type Page } from '@playwright/test'
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs'
import { resolve, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'
import {
  baseURL,
  command,
  cookieHeader,
  currentSession,
  getJson,
  onlyTeam,
  register,
  teamPath,
  type Session,
} from '../real-backend'

/**
 * M10-Q02 real-model closed loop (:157 contract item 2): knowledge + repository ingestion →
 * retrieval → injection → Review → Skill publish → second execution loading the published
 * Skill — all six stages on real PostgreSQL/Redis with real embedding and chat models, driven
 * through public APIs only. The augmented stack must come up with the six switches open
 * (PGVECTOR + KNOWLEDGE_INDEX + INDEX_WORKER + RETRIEVAL + INJECTION + SKILL; MEMORY stays
 * off per ruling B1) and the two bare mirrors (java-spring-lab @ f053fd1e…, crewscope-java @
 * HEAD) already placed under the runtime root by the gate's s1 phase.
 *
 * Provider credentials arrive via environment only (CREWSCOPE_Q02_DEEPSEEK_API_KEY /
 * CREWSCOPE_Q02_DASHSCOPE_API_KEY) and are submitted through Node-side fetch — never into a
 * browser context, URL, log line, or the coordinates file. The coordinates file carries only
 * throwaway stack accounts and API coordinates, lives under gitignored var/, and feeds the
 * comparison driver and the upgrade recheck spec.
 *
 * The loop is serial: t1 provisions, t2/t3 ingest, t4 proves retrieval, t5 proves injection
 * (source/version field-by-field — never "either one hit"), t6 Review, t7 distill+publish,
 * t8 second execution loading the published revision, t9 observability + coordinates.
 */

const deepseekKey = process.env.CREWSCOPE_Q02_DEEPSEEK_API_KEY ?? ''
const dashscopeKey = process.env.CREWSCOPE_Q02_DASHSCOPE_API_KEY ?? ''
const hasKeys = deepseekKey.length > 0 && dashscopeKey.length > 0
const REPO_ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..', '..', '..')
const COORDINATES_FILE = resolve(REPO_ROOT, 'var/release/m10-q02/loop-coordinates.json')
const LAB_CORPUS = JSON.parse(
  readFileSync(resolve(REPO_ROOT, 'scripts/m10-q02/lab-knowledge-corpus.json'), 'utf8'),
) as { entries: Array<{ entryKey: string, title: string, category: string, content: string }> }
const S01_KNOWLEDGE = JSON.parse(
  readFileSync(resolve(REPO_ROOT, 'scripts/m10-s01/dataset/knowledge-entries.json'), 'utf8'),
) as Array<{ key: string, status: string, title: string, content: string }>
const SUITE = JSON.parse(
  readFileSync(resolve(REPO_ROOT, 'evaluation/m4/coding-v1/suite.json'), 'utf8'),
) as {
  fixture: { baselineCommit: string },
  tasks: Array<{ id: string, instruction: string, allowedPaths: string[] }>,
}
const BASELINE_COMMIT = SUITE.fixture.baselineCommit
const FIRST_TASK = SUITE.tasks.find(task => task.id === 'java-username-normalization')!
const SECOND_TASK = SUITE.tasks.find(task => task.id === 'java-retry-backoff')!

test.skip(!hasKeys, 'CREWSCOPE_Q02_DEEPSEEK_API_KEY / CREWSCOPE_Q02_DASHSCOPE_API_KEY 未提供——真实模型闭环需两把 key')

test.describe.configure({ mode: 'serial' })

let contextA: BrowserContext
let pageA: Page
let sessionA: Session
let contextB: BrowserContext
let pageB: Page
let sessionB: Session
let teamId = ''
let orgRoot = ''
let projectId = ''
let workItemId = ''

const passwordA = 'Correct-Horse-Battery-Staple-47'
const passwordB = 'Quiet-Otter-Lantern-Press-92'
let identifierA = ''
let identifierB = ''

// Model plumbing resolved in t1.
let userConnectionId = ''
let teamChatConnectionId = ''
let teamEmbeddingConnectionId = ''
let personalProfileId = ''
let reviewerProfileId = ''
let reviewerPrincipalId = ''
let buildProfile: Record<string, unknown> = {}

// Ingestion facts resolved in t2/t3 (the injection assertions in t5 pin exact versions).
let labBindingId = ''
let indexKey: { bindingId: string, commit: string, chunkPolicyHash: string, modelKey: string, modelRevision: number, buildSequence: number } | null = null
const publishedLabEntries: Array<{ entryKey: string, entryId: string, version: number }> = []

// Loop facts resolved in t5/t7.
let firstExecutionId = ''
let firstTaskId = ''
let publishedSkill = { skillId: '', skillKey: '', revision: 0, contentHash: '' }

/** Node-side fetch for credential-bearing submissions: nothing enters a browser context. */
async function secureFetch(path: string, init: { method: string, body?: unknown, ifMatch?: number }): Promise<Response> {
  const headers: Record<string, string> = {
    'Content-Type': 'application/json',
    Cookie: await cookieHeader(contextA),
    [sessionA.csrf.headerName]: sessionA.csrf.token,
  }
  if (init.method !== 'GET') headers['Idempotency-Key'] = crypto.randomUUID()
  if (init.ifMatch !== undefined) headers['If-Match'] = `"${init.ifMatch}"`
  return fetch(`${baseURL}${path}`, {
    method: init.method,
    headers,
    body: init.body === undefined ? undefined : JSON.stringify(init.body),
  })
}

async function waitFor<T>(description: string, probe: () => Promise<T | null | undefined>, timeout = 60_000): Promise<T> {
  const deadline = Date.now() + timeout
  let last: T | null | undefined = null
  while (Date.now() < deadline) {
    last = await probe()
    if (last !== null && last !== undefined) return last
    await pageA.waitForTimeout(3000)
  }
  throw new Error(`timed out waiting for ${description}`)
}

/** Create → verify (bounded reverify, m9b precedent) → HEALTHY for one model connection. */
async function createVerifiedConnection(body: Record<string, unknown>, description: string): Promise<string> {
  const created = await secureFetch(`${orgRoot}/model-connections?ownerType=${body.ownerType}`, { method: 'POST', body })
  expect(created.status, `${description} create failed: ${await created.text()}`).toBe(202)
  const ownerQuery = body.ownerType === 'TEAM'
    ? `?ownerType=TEAM&teamId=${teamId}`
    : '?ownerType=USER'
  const connectionId = await waitFor(`${description} to appear`, async () => {
    const list = await getJson<{ items: Array<{ id: string, ownerType: string, ownerId: string }> }>(
      pageA, `${orgRoot}/model-connections${ownerQuery}`)
    return (list.items ?? []).find(item => item.ownerType === body.ownerType)?.id ?? null
  })
  const connectionRoot = `${orgRoot}/model-connections/${connectionId}`
  let reverifyBudget = 6
  await expect.poll(async () => {
    const detail = await pageA.request.get(connectionRoot)
    const health = await detail.json() as { healthStatus: string, credentialVersion: number }
    if (health.healthStatus !== 'HEALTHY' && reverifyBudget > 0) {
      reverifyBudget -= 1
      const etag = Number((detail.headers()['etag'] ?? '').replace(/"/g, ''))
      const again = await secureFetch(`${connectionRoot}/verify`, {
        method: 'POST', body: { credentialVersion: health.credentialVersion }, ifMatch: etag,
      })
      expect([200, 202]).toContain(again.status)
    }
    return health.healthStatus
  }, { timeout: 240_000 }).toBe('HEALTHY')
  return connectionId
}

const knowledgeRoot = () => teamPath(sessionA, teamId, 'knowledge/entries')
const indexRoot = () => teamPath(sessionA, teamId, 'knowledge/index')
const agentsRoot = () => teamPath(sessionA, teamId, 'agent-profiles')
const profileConfigRoot = () => `${agentsRoot()}/${personalProfileId}`
const tasksRoot = () => teamPath(sessionA, teamId, 'tasks')
const reviewsRoot = (taskId: string, executionId: string) =>
  `${tasksRoot()}/${taskId}/attempts/${executionId}/reviews`
const skillsRoot = () => teamPath(sessionA, teamId, 'skills')

test('t1 bootstrap: accounts, project, three verified connections, profiles, seats', async ({ browser }) => {
  const suffix = `q02l-${Date.now()}`.toLowerCase().replace(/[^a-z0-9]+/g, '-')
  contextA = await browser.newContext({
    baseURL,
    viewport: { width: 1440, height: 960 },
    timezoneId: 'Asia/Shanghai',
    reducedMotion: 'reduce',
  })
  pageA = await contextA.newPage()
  identifierA = `owner-${suffix}`.slice(0, 48)
  await register(pageA, identifierA, `owner-${suffix}@example.test`, 'Q02 Loop Owner', passwordA, false)
  await expect(pageA).toHaveURL(/\/onboarding$/)
  await pageA.getByRole('textbox', { name: '团队名称' }).fill(`Q02 闭环 ${suffix}`.slice(0, 96))
  await pageA.getByRole('button', { name: '创建团队' }).click()
  await expect(pageA.getByRole('heading', { name: '你的工作入口已经就绪' })).toBeVisible()
  sessionA = await currentSession(pageA)
  teamId = onlyTeam(sessionA).teamId
  orgRoot = `/api/v1/organizations/${sessionA.principal?.organizationId}`

  // A second member joins through the real invitation flow: the mechanical gate reviewer of
  // t6 and the comparison driver's decision identity.
  await pageA.goto(`/team/members?team=${teamId}&tab=invitations`)
  await pageA.getByRole('button', { name: '创建邀请' }).first().click()
  await pageA.locator('input[name="invitationEmail"]').fill(`member-${suffix}@example.test`)
  await pageA.locator('select[name="invitationRole"]').selectOption('MEMBER')
  await pageA.getByRole('button', { name: '创建邀请链接' }).click()
  const invitationLink = await pageA.getByRole('textbox', { name: '一次性邀请链接' }).inputValue()

  contextB = await browser.newContext({ baseURL, timezoneId: 'Asia/Shanghai', reducedMotion: 'reduce' })
  pageB = await contextB.newPage()
  await pageB.goto(invitationLink)
  await pageB.getByRole('button', { name: '创建账号并加入团队' }).click()
  await expect(pageB).toHaveURL(/\/register$/)
  identifierB = `member-${suffix}`.slice(0, 48)
  await register(pageB, identifierB, `member-${suffix}@example.test`, 'Q02 Loop Member', passwordB, true)
  await expect(pageB).not.toHaveURL(/\/register$/)
  sessionB = await currentSession(pageB)
  expect(onlyTeam(sessionB).teamId).toBe(teamId)

  // Project + carrier work item for the coding loop.
  const projectsPath = teamPath(sessionA, teamId, 'work-projects')
  await command(pageA, sessionA, projectsPath, { name: `Q02 闭环计划 ${suffix}`.slice(0, 96) })
  projectId = await waitFor('the project to appear', async () => {
    const page = await getJson<{ items: Array<{ id: string, name: string }> }>(pageA, `${projectsPath}?limit=50`)
    return (page.items ?? []).find(item => item.name.includes(suffix))?.id ?? null
  })
  const workItemsPath = `${projectsPath}/${projectId}/work-items`
  await command(pageA, sessionA, workItemsPath, {
    type: 'FEATURE',
    title: `Q02 闭环载体 ${suffix}`.slice(0, 96),
    description: 'M10-Q02 closed-loop carrier work item',
    priority: 'MEDIUM',
    labels: ['q02-loop'],
    dueAt: null,
  })
  workItemId = await waitFor('the carrier work item to appear', async () => {
    const page = await getJson<{ items: Array<{ id: string, title: string }> }>(pageA, `${workItemsPath}?limit=50`)
    return (page.items ?? []).find(item => item.title.includes(suffix))?.id ?? null
  })
  const itemRoot = `${projectsPath}/${projectId}/work-items/${workItemId}`

  // Three connections: USER chat (executor personal binding), TEAM chat (reviewer), TEAM
  // embedding (DashScope text-embedding-v4 — the S01-frozen embedding model, and the only
  // provider with an embedding capability in the catalog).
  const providers = await getJson<{ items: Array<{ key: string, status: string, availableRegions: string[] }> }>(
    pageA, `${orgRoot}/model-providers`)
  const deepseek = providers.items.find(provider => provider.key.toLowerCase().includes('deepseek'))!
  const dashscope = providers.items.find(provider => provider.key.toLowerCase().includes('dashscope'))!
  expect(deepseek && dashscope, 'DeepSeek + DashScope providers are seeded').toBeTruthy()

  userConnectionId = await createVerifiedConnection({
    providerKey: deepseek.key, ownerType: 'USER', region: deepseek.availableRegions[0]!,
    apiKey: deepseekKey, credentialExpiresAt: null,
  }, 'the USER DeepSeek connection')
  teamChatConnectionId = await createVerifiedConnection({
    providerKey: deepseek.key, ownerType: 'TEAM', teamId, region: deepseek.availableRegions[0]!,
    apiKey: deepseekKey, credentialExpiresAt: null,
  }, 'the TEAM DeepSeek connection')
  // The embedding verify leg runs a real one-shot embedding probe (I01a): HEALTHY here is
  // the cheapest exposure point for the whole embedding governance chain (R1).
  teamEmbeddingConnectionId = await createVerifiedConnection({
    providerKey: dashscope.key, ownerType: 'TEAM', teamId, region: dashscope.availableRegions[0]!,
    apiKey: dashscopeKey, credentialExpiresAt: null,
  }, 'the TEAM DashScope embedding connection')

  // The seeded personal coding agent, bound DIRECT to the flash catalog entry.
  const agentProfiles = await getJson<{ items: Array<{ id: string, runtimeRole: string }> }>(pageA, agentsRoot())
  const personal = agentProfiles.items.find(profile => profile.runtimeRole === 'PERSONAL_ASSISTANT')!
  expect(personal, 'the seeded personal agent profile exists').toBeTruthy()
  personalProfileId = personal.id
  const modelCatalog = await getJson<{ items: Array<{ id: string, providerKey: string, catalogRevision: number }> }>(
    pageA, `${orgRoot}/model-providers/${encodeURIComponent(deepseek.key)}/catalog`)
  const flashEntry = (modelCatalog.items ?? []).find(item => item.id.toLowerCase().includes('flash'))!
  expect(flashEntry, 'the DeepSeek catalog carries the flash entry').toBeTruthy()
  const profileDetail = await pageA.request.get(profileConfigRoot())
  const profileEtag = profileDetail.headers()['etag']
  const bound = await pageA.request.post(`${profileConfigRoot()}/configurations`, {
    data: {
      personalModelBinding: {
        kind: 'DIRECT',
        primary: { connectionId: userConnectionId, catalogEntryId: flashEntry.id, catalogRevision: flashEntry.catalogRevision },
        fallback: null,
      },
      teamModelBinding: null,
      supplementalInstructions: null,
      approvedSkillKeys: [],
      memoryPolicy: null,
      budgetPolicy: null,
      generateOptions: null,
    },
    headers: {
      [sessionA.csrf.headerName]: sessionA.csrf.token,
      'Idempotency-Key': crypto.randomUUID(),
      'If-Match': profileEtag!,
    },
  })
  expect(bound.status, `executor binding failed: ${await bound.text()}`).toBe(202)
  await waitFor('the executor binding to land', async () => {
    const current = await getJson<{ personalModelBinding: { primary: { connectionId: string } } | null }>(
      pageA, `${profileConfigRoot()}/configurations/current`)
    return current.personalModelBinding?.primary?.connectionId === userConnectionId ? true : null
  })

  // The reviewer agent (template v1) with a TEAM binding, plus its advisory seat on the
  // carrier item — prepared before any coding run (m9b lesson: the health probe cannot
  // ride a busy JVM).
  await command(pageA, sessionA, agentsRoot(), {
    publisherType: 'ORGANIZATION',
    templateKey: 'reviewer',
    templateVersion: 1,
    ownershipType: 'TEAM',
    displayName: `Q02 Loop Reviewer ${suffix}`.slice(0, 96),
  })
  reviewerProfileId = await waitFor('the reviewer profile to appear', async () => {
    const page = await getJson<{ items: Array<{ id: string, displayName: string, principalId: string, status: string }> }>(
      pageA, `${agentsRoot()}?limit=50`)
    const found = (page.items ?? []).find(item => item.displayName.includes(suffix))
    return found?.id ?? null
  })
  const reviewerRow = (await getJson<{ items: Array<{ id: string, displayName: string, principalId: string, status: string }> }>(
    pageA, `${agentsRoot()}?limit=50`)).items.find(item => item.id === reviewerProfileId)!
  expect(reviewerRow.status, 'the reviewer agent is active').toBe('ACTIVE')
  reviewerPrincipalId = reviewerRow.principalId
  const reviewerDetail = await pageA.request.get(`${agentsRoot()}/${reviewerProfileId}`)
  const reviewerEtag = reviewerDetail.headers()['etag']
  const reviewerBound = await pageA.request.post(`${agentsRoot()}/${reviewerProfileId}/configurations`, {
    data: {
      personalModelBinding: {
        kind: 'DIRECT',
        primary: { connectionId: teamChatConnectionId, catalogEntryId: flashEntry.id, catalogRevision: flashEntry.catalogRevision },
        fallback: null,
      },
      teamModelBinding: {
        kind: 'DIRECT',
        primary: { connectionId: teamChatConnectionId, catalogEntryId: flashEntry.id, catalogRevision: flashEntry.catalogRevision },
        fallback: null,
      },
      supplementalInstructions: null,
      approvedSkillKeys: [],
      memoryPolicy: null,
      budgetPolicy: null,
      generateOptions: null,
    },
    headers: {
      [sessionA.csrf.headerName]: sessionA.csrf.token,
      'Idempotency-Key': crypto.randomUUID(),
      'If-Match': reviewerEtag!,
    },
  })
  expect(reviewerBound.status, `reviewer binding failed: ${await reviewerBound.text()}`).toBe(202)
  await command(pageA, sessionA, `${itemRoot}/responsibilities/advisory-reviewers`, { actorPrincipalId: reviewerPrincipalId })
  await command(pageA, sessionA, `${itemRoot}/responsibilities/gate-reviewers`, { actorPrincipalId: sessionB.principal?.principalId })

  // The build profile the coding target will pin (product catalog; its image sha matches the
  // frozen m4 sandbox image by construction).
  const buildProfiles = await getJson<{ items: Array<Record<string, unknown> & { buildTool: string }> }>(
    pageA, `${itemRoot}/coding-target/build-profiles`)
  buildProfile = (buildProfiles.items ?? []).find(item => item.buildTool === 'MAVEN') ?? buildProfiles.items[0]!
  expect(buildProfile, 'a MAVEN build profile is offered for the lab fixture').toBeTruthy()
})

test('t2 knowledge ingestion: publish the corpus, rebuild the index, land INDEXED', async () => {
  test.setTimeout(900_000)
  const ingest = [
    ...LAB_CORPUS.entries.map(entry => ({
      entryKey: entry.entryKey, title: entry.title, category: entry.category, content: entry.content, lab: true,
    })),
    ...S01_KNOWLEDGE
      .filter(entry => entry.status === 'PUBLISHED')
      .map(entry => ({ entryKey: entry.key, title: entry.title, category: 'OTHER', content: entry.content, lab: false })),
  ]
  expect(ingest.length, 'the ingest corpus is non-empty').toBeGreaterThan(10)

  for (const entry of ingest) {
    await command(pageA, sessionA, knowledgeRoot(), {
      entryKey: entry.entryKey, category: entry.category, title: entry.title, content: entry.content,
    })
    const head = await waitFor(`entry ${entry.entryKey} to appear`, async () => {
      const page = await getJson<{ items: Array<{ id: string, entryKey: string, version: number, status: string }> }>(
        pageA, `${knowledgeRoot()}?limit=100`)
      return (page.items ?? []).find(item => item.entryKey === entry.entryKey) ?? null
    }, 30_000)
    expect(head.status).toBe('DRAFT')
    const published = await pageA.request.post(`${knowledgeRoot()}/${head.id}/publish`, {
      headers: {
        [sessionA.csrf.headerName]: sessionA.csrf.token,
        'Idempotency-Key': crypto.randomUUID(),
        'If-Match': `"${head.version}"`,
      },
    })
    expect(published.status, `publish failed for ${entry.entryKey}: ${await published.text()}`).toBe(202)
    if (entry.lab) publishedLabEntries.push({ entryKey: entry.entryKey, entryId: head.id, version: 1 })
  }
  expect(publishedLabEntries.length, 'the lab corpus is fully published').toBe(LAB_CORPUS.entries.length)

  // One rebuild covers every published entry; the worker is on in this stack, so the job
  // must run to SUCCEEDED with real embeddings (DashScope) behind it.
  const rebuild = await pageA.request.post(`${indexRoot()}/rebuilds`, {
    headers: { [sessionA.csrf.headerName]: sessionA.csrf.token, 'Idempotency-Key': crypto.randomUUID() },
  })
  expect(rebuild.status, `rebuild failed: ${await rebuild.text()}`).toBe(202)
  const job = await waitFor('the knowledge rebuild to succeed', async () => {
    const page = await getJson<{ items: Array<{ id: string, status: string, failureCode: string | null }> }>(
      pageA, `${indexRoot()}/jobs?source=KNOWLEDGE&limit=10`)
    return (page.items ?? []).find(item => item.status !== 'QUEUED' && item.status !== 'RUNNING') ?? null
  }, 600_000)
  expect(job.status, `knowledge rebuild ended ${job.status} (${job.failureCode})`).toBe('SUCCEEDED')

  await expect.poll(async () => {
    const page = await getJson<{ items: Array<{ entryKey: string, indexStatus: string }> }>(
      pageA, `${knowledgeRoot()}?limit=100`)
    const lab = (page.items ?? []).filter(item => LAB_CORPUS.entries.some(e => e.entryKey === item.entryKey))
    return lab.filter(item => item.indexStatus === 'INDEXED').length
  }, { timeout: 120_000 }).toBe(LAB_CORPUS.entries.length)
})

test('t3 repository indexing: preflight the frozen commit, build the index, record the IndexKey', async () => {
  test.setTimeout(900_000)
  const bindingRoot = teamPath(sessionA, teamId, `work-projects/${projectId}/repository-bindings`)

  // Preflight first: chunk injection requires baselineRef → commit to resolve to exactly the
  // frozen baseline (single-commit fixture: main == f053fd1e…).
  const preflight = await pageA.request.post(`${bindingRoot}/preflight`, {
    data: { repositoryKey: 'java-spring-lab', defaultBranch: 'main' },
    headers: { [sessionA.csrf.headerName]: sessionA.csrf.token },
  })
  expect(preflight.status, `preflight failed: ${await preflight.text()}`).toBe(200)
  const preflightBody = await preflight.json() as { ready: boolean, baselineCommit: string }
  expect(preflightBody.ready).toBe(true)
  expect(preflightBody.baselineCommit, 'the lab mirror HEAD is exactly the frozen baseline').toBe(BASELINE_COMMIT)

  await command(pageA, sessionA, bindingRoot, { repositoryKey: 'java-spring-lab', defaultBranch: 'main' })
  labBindingId = await waitFor('the lab binding to appear', async () => {
    const page = await getJson<{ items: Array<{ id: string, repositoryKey: string, status: string }> }>(
      pageA, `${bindingRoot}?limit=50`)
    return (page.items ?? []).find(item => item.repositoryKey === 'java-spring-lab')?.id ?? null
  })
  const bindingRow = (await getJson<{ items: Array<{ id: string, repositoryKey: string, status: string, version: number }> }>(
    pageA, `${bindingRoot}?limit=50`)).items.find(item => item.id === labBindingId)!
  if (bindingRow.status !== 'ACTIVE') {
    const activated = await pageA.request.post(`${bindingRoot}/${labBindingId}/activate`, {
      headers: {
        [sessionA.csrf.headerName]: sessionA.csrf.token,
        'Idempotency-Key': crypto.randomUUID(),
        'If-Match': `"${bindingRow.version}"`,
      },
    })
    expect(activated.status, `activate failed: ${await activated.text()}`).toBe(202)
    await expect.poll(async () => {
      const detail = await getJson<{ status: string }>(pageA, `${bindingRoot}/${labBindingId}`)
      return detail.status
    }).toBe('ACTIVE')
  }

  const built = await pageA.request.post(`${indexRoot()}/repository-builds`, {
    data: { projectId, bindingId: labBindingId, commit: BASELINE_COMMIT },
    headers: { [sessionA.csrf.headerName]: sessionA.csrf.token, 'Idempotency-Key': crypto.randomUUID() },
  })
  expect(built.status, `repository build failed: ${await built.text()}`).toBe(202)
  const accepted = await built.json() as { accepted: number, job: { id: string } | null }
  expect(accepted.accepted, 'the repository build was accepted').toBe(1)
  expect(accepted.job, 'the build acceptance carries the job handle').toBeTruthy()
  const jobId = accepted.job!.id
  const buildJob = await waitFor('the repository build to reach a terminal state', async () => {
    const job = await getJson<{ status: string, failureCode: string | null }>(pageA, `${indexRoot()}/jobs/${jobId}`)
    return job.status !== 'QUEUED' && job.status !== 'RUNNING' ? job : null
  }, 600_000)
  expect(buildJob.status, `repository build ended ${buildJob.status} (${buildJob.failureCode})`).toBe('SUCCEEDED')
  const jobDetail = await getJson<{
    generationBuildSequence: number,
    indexKey: { bindingId: string, commit: string, chunkPolicyHash: string, modelKey: string, modelRevision: number },
  }>(pageA, `${indexRoot()}/jobs/${jobId}`)
  indexKey = { ...jobDetail.indexKey, buildSequence: jobDetail.generationBuildSequence }
  expect(indexKey!.modelKey, 'the index records the embedding model key').toBeTruthy()
  expect(indexKey!.modelRevision, 'the index records the embedding model revision').toBeGreaterThan(0)

  // Second corpus, fire-and-forget: the whole-repo crewscope-java index runs in the
  // background through the comparison phases (s5 waits for it). The build body needs a
  // resolved sha (40/64-hex contract), so preflight resolves the mirror HEAD first.
  const crewPreflight = await pageA.request.post(`${bindingRoot}/preflight`, {
    data: { repositoryKey: 'crewscope-java', defaultBranch: 'main' },
    headers: { [sessionA.csrf.headerName]: sessionA.csrf.token },
  })
  expect(crewPreflight.status, `crewscope-java preflight failed: ${await crewPreflight.text()}`).toBe(200)
  const crewHead = await crewPreflight.json() as { ready: boolean, baselineCommit: string }
  expect(crewHead.ready, 'the crewscope-java mirror is resolvable').toBe(true)
  await command(pageA, sessionA, bindingRoot, { repositoryKey: 'crewscope-java', defaultBranch: 'main' })
  const crewBindingId = await waitFor('the crewscope-java binding to appear', async () => {
    const page = await getJson<{ items: Array<{ id: string, repositoryKey: string, status: string, version: number }> }>(
      pageA, `${bindingRoot}?limit=50`)
    return (page.items ?? []).find(item => item.repositoryKey === 'crewscope-java')?.id ?? null
  })
  const crewRow = (await getJson<{ items: Array<{ id: string, repositoryKey: string, status: string, version: number }> }>(
    pageA, `${bindingRoot}?limit=50`)).items.find(item => item.id === crewBindingId)!
  if (crewRow.status !== 'ACTIVE') {
    const activated = await pageA.request.post(`${bindingRoot}/${crewBindingId}/activate`, {
      headers: {
        [sessionA.csrf.headerName]: sessionA.csrf.token,
        'Idempotency-Key': crypto.randomUUID(),
        'If-Match': `"${crewRow.version}"`,
      },
    })
    expect([200, 202]).toContain(activated.status)
  }
  const enqueued = await pageA.request.post(`${indexRoot()}/repository-builds`, {
    data: { projectId, bindingId: crewBindingId, commit: crewHead.baselineCommit },
    headers: { [sessionA.csrf.headerName]: sessionA.csrf.token, 'Idempotency-Key': crypto.randomUUID() },
  })
  expect([200, 202]).toContain(enqueued.status)
  const crewCoordinates = { bindingId: crewBindingId, commit: crewHead.baselineCommit }
  ;(globalThis as unknown as Record<string, unknown>).__q02CrewBinding = crewCoordinates
})

test('t4 retrieval: both sources answer undegraded with versioned candidates', async () => {
  type Candidate = {
    source: string,
    entry: { entryId: string, revision: number } | null,
    fragments: Array<{ commit: string, generationBuildSequence: number }>,
  }
  const preview = async (body: Record<string, unknown>): Promise<{ candidates: Candidate[], degraded: string[] }> => {
    const response = await pageA.request.post(teamPath(sessionA, teamId, 'knowledge/knowledge-retrieval:preview'), {
      data: body,
      headers: { [sessionA.csrf.headerName]: sessionA.csrf.token, 'Content-Type': 'application/json' },
    })
    expect(response.status(), `preview answered ${response.status()}: ${await response.text()}`).toBe(200)
    return await response.json() as { candidates: Candidate[], degraded: string[] }
  }
  const repository = { projectId, bindingId: labBindingId, commit: BASELINE_COMMIT }

  // Knowledge-domain query (no repository target: the entries alone are reachable, so an
  // empty degradation list is meaningful): the candidate must carry the entry projection
  // with the published revision (retrieval source version == published version).
  const knowledgeAnswer = await preview({ query: '用户名规范化 NFKC Unicode 空白折叠 Locale', topK: 8 })
  expect(knowledgeAnswer.degraded, 'no degraded source on the augmented stack').toEqual([])
  const labEntryIds = new Map(publishedLabEntries.map(entry => [entry.entryId, entry.version]))
  const labCandidate = knowledgeAnswer.candidates.find(candidate =>
    candidate.source === 'KNOWLEDGE_ENTRY' && candidate.entry && labEntryIds.has(candidate.entry.entryId))
  expect(labCandidate, 'a published lab convention surfaces as a knowledge candidate').toBeTruthy()
  expect(labCandidate!.entry!.revision, 'the retrieved revision == the published revision').toBe(labEntryIds.get(labCandidate!.entry!.entryId))

  // Repository-domain query: REPOSITORY_CHUNK candidates must carry fragments pinned to the
  // indexed commit and the generation the IndexKey recorded.
  const codeAnswer = await preview({
    query: 'UserNameNormalizer 规范化 normalize',
    sources: ['REPOSITORY_CHUNK'],
    topK: 8,
    repository,
  })
  expect(codeAnswer.degraded).toEqual([])
  const chunk = codeAnswer.candidates.find(candidate => candidate.source === 'REPOSITORY_CHUNK')
  expect(chunk, 'repository chunks surface for the indexed commit').toBeTruthy()
  expect(chunk!.fragments.length, 'chunk candidates carry fragments').toBeGreaterThan(0)
  for (const fragment of chunk!.fragments) {
    expect(fragment.commit, 'fragments are pinned to the indexed commit').toBe(BASELINE_COMMIT)
    expect(fragment.generationBuildSequence, 'fragments carry the indexed generation').toBe(indexKey!.buildSequence)
  }

  // Joint query: both sources in one answer without degradation.
  const jointAnswer = await preview({
    query: '用户名规范化 UserNameNormalizer normalize 规则',
    sources: ['KNOWLEDGE_ENTRY', 'REPOSITORY_CHUNK'],
    topK: 8,
    repository,
  })
  expect(jointAnswer.degraded, 'the joint query degrades neither source').toEqual([])
  expect(jointAnswer.candidates.some(candidate => candidate.source === 'KNOWLEDGE_ENTRY')
    && jointAnswer.candidates.some(candidate => candidate.source === 'REPOSITORY_CHUNK'),
  'the joint answer carries both source kinds').toBe(true)
})

/** One full coding run on the loop's carrier item: create → settle (resuming once at the
 *  CONFIRMATION gate) → terminal. Mirrors the m9b-q02 t4 precedent verbatim. */
async function runCodingTask(task: { id: string, instruction: string, allowedPaths: string[] }, marker: string) {
  const itemRoot = teamPath(sessionA, teamId, `work-projects/${projectId}/work-items/${workItemId}`)
  const current = await getJson<{ revision: number }>(pageA, `${profileConfigRoot()}/configurations/current`)
  const created = await pageA.request.post(`${itemRoot}/tasks`, {
    data: {
      objective: `[${marker}] ${task.instruction}`,
      acceptanceCriteria: [
        task.instruction,
        `改动仅限这些路径内的文件：${task.allowedPaths.join('、')}；不要改动其它文件。`,
        '本仓库的集成测试需要 Docker 等容器环境，执行沙箱内不可用；验收测试请通过 tests 选择器只定向运行与交付物相关的单元测试，不要全量运行测试套件',
      ],
      executorAgentProfileId: personalProfileId,
      agentConfigurationRevision: current.revision,
      executorAssignment: { agentProfileId: personalProfileId },
      conversationSource: null,
      providerBindingIds: [],
      codingTarget: {
        repositoryBindingId: labBindingId,
        baselineRef: 'main',
        allowedPaths: task.allowedPaths,
        buildProfile,
      },
    },
    headers: {
      [sessionA.csrf.headerName]: sessionA.csrf.token,
      'Idempotency-Key': crypto.randomUUID(),
      'If-Match': '"0"',
    },
  })
  expect(created.status, `task create failed: ${await created.text()}`).toBe(202)
  const executionId = await waitFor(`execution for ${marker}`, async () => {
    const page = await getJson<{ items: Array<{ id: string, objective: string, currentExecutionId: string | null }> }>(
      pageA, `${tasksRoot()}?projectId=${projectId}&limit=50`)
    return (page.items ?? []).find(item => item.objective.includes(`[${marker}]`))?.currentExecutionId ?? null
  }, 90_000)
  const taskRow = (await getJson<{ items: Array<{ id: string, objective: string }> }>(
    pageA, `${tasksRoot()}?projectId=${projectId}&limit=50`)).items.find(item => item.objective.includes(`[${marker}]`))!

  let resumedOnce = false
  let settled = ''
  const deadline = Date.now() + 1_500_000
  while (Date.now() < deadline) {
    const attempts = await getJson<Array<{ id: string, status: string, version: number, waiting?: { reason: string } | null }>>(
      pageA, `${tasksRoot()}/${taskRow.id}/attempts`)
    const attempt = attempts.find(item => item.id === executionId)
    settled = attempt?.status ?? 'MISSING'
    if (settled === 'COMPLETED') break
    if (settled === 'FAILED' || settled === 'CANCELLED' || settled === 'RECOVERING') {
      await pageA.waitForTimeout(30_000)
      const recheck = await getJson<Array<{ id: string, status: string }>>(
        pageA, `${tasksRoot()}/${taskRow.id}/attempts`)
      settled = recheck.find(item => item.id === executionId)?.status ?? settled
      if (settled !== 'RUNNING' && settled !== 'WAITING') break
    }
    if (settled === 'WAITING' && attempt?.waiting?.reason === 'CONFIRMATION' && !resumedOnce) {
      resumedOnce = true
      const resumed = await pageA.request.post(`${tasksRoot()}/${taskRow.id}/attempts/${executionId}/resume`, {
        headers: {
          [sessionA.csrf.headerName]: sessionA.csrf.token,
          'Idempotency-Key': crypto.randomUUID(),
          'If-Match': `"${attempt.version}"`,
        },
      })
      expect([200, 201, 202]).toContain(resumed.status())
    }
    await pageA.waitForTimeout(15_000)
  }
  expect(['COMPLETED', 'FAILED'], `the attempt settled as ${settled}`).toContain(settled)
  const codingView = await getJson<{
    coding: boolean, executionStatus: string,
    details: { diffManifest: unknown, commandEvidenceCount: number, testEvidenceCount: number } | null,
  }>(pageA, `${tasksRoot()}/${taskRow.id}/attempts/${executionId}/coding`)
  return { taskId: taskRow.id, executionId, settled, codingView }
}

type InjectionReference = { stage: string, type: string, sourceId: string, version: number, contentHash: string }

async function injectionReferences(taskId: string, executionId: string) {
  return getJson<{ attempts: Array<{ attempt: number, references: InjectionReference[], degradations: string[], budget: unknown, trims: unknown[] }> }>(
    pageA, `${tasksRoot()}/${taskId}/attempts/${executionId}/injection-references`)
}

test('t5 first execution: the manifest injects the published knowledge and the indexed chunks', async () => {
  test.setTimeout(1_800_000)
  const run = await runCodingTask(FIRST_TASK, 'q02-loop-first')
  firstTaskId = run.taskId
  firstExecutionId = run.executionId
  test.info().annotations.push({ type: 'note', description: `first loop execution settled ${run.settled}` })
  expect(run.settled, 'the loop execution completes on the real model').toBe('COMPLETED')
  expect(run.codingView.details?.diffManifest, 'the loop execution delivered a diff').toBeTruthy()

  // Field-by-field injection evidence (:157 red line): the KNOWLEDGE_ENTRY refs must carry
  // the exact published versions and the REPOSITORY_CHUNK refs the exact index coordinates —
  // "knowledge or skill either one hit" is not a substitute.
  const references = await injectionReferences(run.taskId, run.executionId)
  expect(references.attempts.length, 'the execution sealed injection manifests').toBeGreaterThan(0)
  const injected = references.attempts.flatMap(attempt => attempt.references.filter(ref => ref.stage === 'INJECTED'))
  expect(injected.length, 'the manifest carries INJECTED references').toBeGreaterThan(0)

  const knowledgeRefs = injected.filter(ref => ref.type === 'KNOWLEDGE_ENTRY')
  expect(knowledgeRefs.length, 'knowledge entries were actually injected').toBeGreaterThan(0)
  const labEntryIds = new Set(publishedLabEntries.map(entry => entry.entryId))
  expect(knowledgeRefs.some(ref => labEntryIds.has(ref.sourceId)), 'an injected knowledge ref points at a published lab entry').toBe(true)
  for (const ref of knowledgeRefs) {
    const published = publishedLabEntries.find(entry => entry.entryId === ref.sourceId)
    if (published) expect(ref.version, 'injected knowledge version == published version').toBe(published.version)
  }

  const chunkRefs = injected.filter(ref => ref.type === 'REPOSITORY_CHUNK')
  expect(chunkRefs.length, 'repository chunks were actually injected').toBeGreaterThan(0)
  expect(indexKey, 'the repository index key was recorded in t3').toBeTruthy()
  for (const ref of chunkRefs) {
    expect(ref.version, 'injected chunk version == the indexed model revision').toBe(indexKey!.modelRevision)
  }

  // One not-applicable feedback closes the I02c surface: the reference key is the whole
  // body (version as a numeric string; the endpoint's semantics ARE the not-applicable mark).
  const feedbackTarget = knowledgeRefs[0]!
  const feedback = await pageA.request.post(
    `${tasksRoot()}/${run.taskId}/attempts/${run.executionId}/injection-references/feedback`, {
      data: { type: feedbackTarget.type, sourceId: feedbackTarget.sourceId, version: String(feedbackTarget.version), contentHash: feedbackTarget.contentHash },
      headers: { [sessionA.csrf.headerName]: sessionA.csrf.token, 'Idempotency-Key': crypto.randomUUID() },
    })
  expect([200, 201, 202], `feedback failed: ${await feedback.text()}`).toContain(feedback.status())
})

test('t6 review: the real reviewer executes and member B records the gate decision', async () => {
  test.setTimeout(600_000)
  const reviewsPath = reviewsRoot(firstTaskId, firstExecutionId)
  const created = await pageA.request.post(reviewsPath, {
    data: {},
    headers: { [sessionA.csrf.headerName]: sessionA.csrf.token, 'Idempotency-Key': crypto.randomUUID() },
  })
  expect(created.status, `review create failed: ${await created.text()}`).toBe(202)
  const reviewId = await waitFor('the review request to appear', async () => {
    const page = await getJson<{ items: Array<{ id: string }> }>(pageA, reviewsPath)
    return (page.items ?? [])[0]?.id ?? null
  })
  const reviewRow = await waitFor('the review to leave OPEN', async () => {
    const page = await getJson<{ items: Array<{ id: string, status: string, version: number }> }>(pageA, reviewsPath)
    const row = (page.items ?? []).find(item => item.id === reviewId)
    return row && row.status !== 'OPEN' ? row : null
  }, 120_000)
  const executed = await pageA.request.post(`${reviewsPath}/${reviewId}/execute`, {
    headers: {
      [sessionA.csrf.headerName]: sessionA.csrf.token,
      'Idempotency-Key': crypto.randomUUID(),
      'If-Match': `"${reviewRow.version}"`,
    },
  })
  expect([200, 201, 202]).toContain(executed.status())
  const detail = await waitFor('the reviewer execution to complete', async () => {
    const body = await getJson<{ status: string, version: number, findings: Array<{ severity: string }>, decisions: unknown[] }>(
      pageA, `${reviewsPath}/${reviewId}`)
    return body.status === 'COMPLETED' ? body : null
  }, 300_000)
  test.info().annotations.push({
    type: 'note',
    description: `reviewer produced ${detail.findings.length} findings: ${detail.findings.map(f => f.severity).join(',') || 'none'}`,
  })
  const decided = await pageB.request.post(`${reviewsPath}/${reviewId}/decisions`, {
    data: { type: 'APPROVED', rationale: 'Q02 loop: approve the closed-loop delivery' },
    headers: {
      [sessionB.csrf.headerName]: sessionB.csrf.token,
      'Idempotency-Key': crypto.randomUUID(),
      'If-Match': `"${detail.version}"`,
    },
  })
  expect([200, 201, 202]).toContain(decided.status())
})

test('t7 distillation: the creator distils the completed attempt and publishes revision R', async () => {
  test.setTimeout(600_000)
  publishedSkill.skillKey = `q02-loop-${Date.now()}`.replace(/[^a-z0-9-]/g, '')
  const distilled = await pageA.request.post(`${skillsRoot()}/distillations`, {
    data: { taskExecutionId: firstExecutionId, skillKey: publishedSkill.skillKey },
    headers: { [sessionA.csrf.headerName]: sessionA.csrf.token, 'Idempotency-Key': crypto.randomUUID() },
  })
  expect(distilled.status, `distillation failed: ${await distilled.text()}`).toBe(202)
  const receipt = await distilled.json() as { skillId: string, status: string }
  publishedSkill.skillId = receipt.skillId
  expect(receipt.status).toBe('DRAFT')

  // The distillation endpoint commits the DRAFT skill synchronously with its 202, so the
  // head view is available immediately; publish then mints the first revision.
  const skillHead = await getJson<{ status: string, version: number, draft: { content: string } | null }>(
    pageA, `${skillsRoot()}/${publishedSkill.skillId}`)
  expect(skillHead.status, 'the distilled skill starts as DRAFT').toBe('DRAFT')
  expect(skillHead.draft?.content, 'the distiller produced draft content').toBeTruthy()

  const published = await pageA.request.post(`${skillsRoot()}/${publishedSkill.skillId}/publish`, {
    headers: {
      [sessionA.csrf.headerName]: sessionA.csrf.token,
      'Idempotency-Key': crypto.randomUUID(),
      'If-Match': `"${skillHead.version}"`,
    },
  })
  expect(published.status, `skill publish failed: ${await published.text()}`).toBe(202)
  const publishedHead = await getJson<{ status: string, effectiveRevision: number | null, version: number }>(
    pageA, `${skillsRoot()}/${publishedSkill.skillId}`)
  expect(publishedHead.status).toBe('PUBLISHED')
  expect(publishedHead.effectiveRevision, 'publishing minted revision 1').toBeGreaterThanOrEqual(1)
  publishedSkill.revision = publishedHead.effectiveRevision!
  const effective = await getJson<{ revision: number, contentHash: string }>(
    pageA, `${skillsRoot()}/${publishedSkill.skillId}/effective-version`)
  expect(effective.revision).toBe(publishedSkill.revision)
  expect(effective.contentHash, 'the effective version carries a 64-hex content hash').toMatch(/^[0-9a-f]{64}$/)
  publishedSkill.contentHash = effective.contentHash
})

test('t8 second execution: the approved Skill loads at the published revision', async () => {
  test.setTimeout(1_800_000)
  const before = await getJson<{
    revision: number, approvedSkillKeys: string[],
    personalModelBinding: unknown, teamModelBinding: unknown, supplementalInstructions: string | null,
    memoryPolicy: unknown, budgetPolicy: unknown, generateOptions: unknown,
  }>(pageA, `${profileConfigRoot()}/configurations/current`)
  const appended = await pageA.request.post(`${profileConfigRoot()}/configurations`, {
    data: {
      personalModelBinding: before.personalModelBinding,
      teamModelBinding: before.teamModelBinding,
      supplementalInstructions: before.supplementalInstructions,
      approvedSkillKeys: [...before.approvedSkillKeys, publishedSkill.skillKey],
      memoryPolicy: before.memoryPolicy,
      budgetPolicy: before.budgetPolicy,
      generateOptions: before.generateOptions,
    },
    headers: {
      [sessionA.csrf.headerName]: sessionA.csrf.token,
      'Idempotency-Key': crypto.randomUUID(),
      'If-Match': `"${before.revision}"`,
    },
  })
  expect(appended.status, `configuration append failed: ${await appended.text()}`).toBe(202)
  await waitFor('the approved skill key to land in the current configuration', async () => {
    const current = await getJson<{ approvedSkillKeys: string[] }>(
      pageA, `${profileConfigRoot()}/configurations/current`)
    return current.approvedSkillKeys.includes(publishedSkill.skillKey) ? true : null
  })

  const run = await runCodingTask(SECOND_TASK, 'q02-loop-second')
  test.info().annotations.push({ type: 'note', description: `second loop execution settled ${run.settled}` })
  expect(run.settled, 'the second execution completes on the real model').toBe('COMPLETED')

  const references = await injectionReferences(run.taskId, run.executionId)
  const injected = references.attempts.flatMap(attempt => attempt.references.filter(ref => ref.stage === 'INJECTED'))
  // The built-in Coding Skill's source id is excluded from dynamic references
  // (TeamSkillExecutionSource contract), so every SKILL_INSTRUCTION reference must be the
  // published team skill itself, at the exact published coordinates.
  const skillRefs = injected.filter(ref => ref.type === 'SKILL_INSTRUCTION')
  expect(skillRefs.length, 'the execution loaded a SKILL_INSTRUCTION reference').toBeGreaterThan(0)
  for (const ref of skillRefs) {
    expect(ref.sourceId, 'the loaded skill is the published team skill key').toBe(publishedSkill.skillKey)
    expect(ref.version, 'the loaded skill revision == the published revision R').toBe(publishedSkill.revision)
    expect(ref.contentHash, 'the loaded skill hash == the published content hash').toBe(publishedSkill.contentHash)
  }

  const effective = await getJson<{ revision: number, contentHash: string }>(
    pageA, `${skillsRoot()}/${publishedSkill.skillId}/effective-version`)
  expect(effective.revision, 'the catalog still serves the published revision').toBe(publishedSkill.revision)
})

test('t9 observability and coordinates: three-source costs and the driver coordinates file', async () => {
  const month = new Date().toISOString().slice(0, 7)
  const costMonths = await getJson<{ months: Array<{ month: string }> }>(
    pageA, teamPath(sessionA, teamId, `observability/cost/months`))
  expect((costMonths.months ?? []).some(entry => entry.month === month), 'the loop month shows up in cost months').toBe(true)
  const costDetail = await getJson<{ rows: Array<{ role: string, inputTokens: number, outputTokens: number }> }>(
    pageA, teamPath(sessionA, teamId, `observability/cost/months/${month}`))
  const byRole = (costDetail.rows ?? []).reduce((accumulator, row) => ({
    ...accumulator, [row.role]: (accumulator[row.role] ?? 0) + row.inputTokens + row.outputTokens,
  }), {} as Record<string, number>)
  test.info().annotations.push({ type: 'note', description: `cost by role: ${JSON.stringify(byRole)}` })
  expect(byRole.EMBEDDING ?? 0, 'real embedding usage was recorded').toBeGreaterThan(0)
  expect(byRole.EXECUTION ?? 0, 'real execution usage was recorded').toBeGreaterThan(0)
  expect(byRole.DISTILLATION ?? 0, 'real distillation usage was recorded').toBeGreaterThan(0)

  const quality = await getJson<Record<string, unknown>>(
    pageA, teamPath(sessionA, teamId, `observability/quality/months/${month}`))
  expect(quality, 'the quality month answers with a structure').toBeTruthy()

  const crewBinding = (globalThis as unknown as Record<string, { bindingId: string, commit: string }>).__q02CrewBinding
  const coordinates = {
    baseUrl: baseURL,
    orgId: sessionA.principal!.organizationId,
    teamId,
    projectId,
    codingWorkItemId: workItemId,
    executorAgentProfileId: personalProfileId,
    reviewerAgentProfileId: reviewerProfileId,
    memberA: { principalId: sessionA.principal?.principalId, identifier: identifierA, password: passwordA },
    memberB: { principalId: sessionB.principal?.principalId, identifier: identifierB, password: passwordB },
    providerBindingGrantId: null,
    repositoryBinding: {
      bindingId: labBindingId, baselineRef: 'main', baselineCommit: BASELINE_COMMIT, buildProfile,
    },
    repositoryIndex: {
      bindingId: labBindingId, commit: BASELINE_COMMIT,
      modelKey: indexKey?.modelKey, modelRevision: indexKey?.modelRevision,
      chunkPolicyHash: indexKey?.chunkPolicyHash, buildSequence: indexKey?.buildSequence,
    },
    crewscopeJavaBinding: crewBinding ?? null,
    publishedSkill: { skillId: publishedSkill.skillId, skillKey: publishedSkill.skillKey, revision: publishedSkill.revision, contentHash: publishedSkill.contentHash },
    publishedKnowledgeCount: publishedLabEntries.length,
    firstLoop: { taskId: firstTaskId, executionId: firstExecutionId },
    generatedAt: new Date().toISOString(),
  }
  mkdirSync(dirname(COORDINATES_FILE), { recursive: true })
  writeFileSync(COORDINATES_FILE, JSON.stringify(coordinates, null, 2))
  test.info().annotations.push({ type: 'note', description: `coordinates written for the comparison driver` })
})
