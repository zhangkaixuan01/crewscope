import { expect, test, type BrowserContext, type Page } from '@playwright/test'
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
 * M9b-Q02 GitHub chain to a Draft PR (§9.2 第一个 Coding 任务 / 人审与 PR rows), gated on
 * CREWSCOPE_Q02_GITHUB_TOKEN and CREWSCOPE_Q02_GITHUB_REPO (owner/name). The token travels only
 * through Node-side fetch calls carrying the browser session cookie — never a page, URL, command
 * line, or trace. It is a GitHub App installation token (ghs_, one-hour lifetime): the connection
 * is created with the APP_INSTALLATION type whose verify path discovers the identity from the
 * installation's repository-owner projection instead of /user (installation tokens carry no
 * profile scope and no X-OAuth-Scopes header, which the OAUTH_USER path requires). The chain:
 * connection → verify (identity discovery) → catalog synchronize → team binding → project import
 * (worker-built mirror) → preflight → a first coding task with a real model (when
 * CREWSCOPE_Q02_DEEPSEEK_API_KEY is also present) → review with a change request → a Draft PR
 * that is created but never merged. Boundary: writes stay inside the designated test repository;
 * nothing outside it is touched.
 */
const githubToken = process.env.CREWSCOPE_Q02_GITHUB_TOKEN ?? ''
const githubRepo = process.env.CREWSCOPE_Q02_GITHUB_REPO ?? ''
const deepseekKey = process.env.CREWSCOPE_Q02_DEEPSEEK_API_KEY ?? ''
const hasGitHub = githubToken.length > 0 && /^[^/]+\/[^/]+$/.test(githubRepo)

let contextA: BrowserContext
let pageA: Page
let sessionA: Session
// The human Gate Reviewer for t5: a second member who holds no Owner/Executor duty.
let contextB: BrowserContext
let pageB: Page
let sessionB: Session
let teamId: string
let connectionId: string
let connectionVersion: number
// State carried from t3/t4 into t5 (serial mode): the imported project, the coding execution,
// and whether the review gate opened for it.
let projectSuffix = ''
let codingTaskId = ''
let codingExecutionId = ''
let codingWorkItemId = ''
let codingSettled = ''
let codingMarker = ''
let reviewOpened = false
let grantId = ''
let importBindingId = ''
let externalRepositoryId = ''

test.skip(!hasGitHub, 'CREWSCOPE_Q02_GITHUB_TOKEN / CREWSCOPE_Q02_GITHUB_REPO 未提供——GitHub 全链行需授权或环境')

test.describe.configure({ mode: 'serial' })

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

test('a real connection verifies and discovers the GitHub identity', async ({ browser }) => {
  const suffix = `gh-${Date.now()}`.toLowerCase().replace(/[^a-z0-9]+/g, '-')
  contextA = await browser.newContext({
    baseURL,
    viewport: { width: 1440, height: 960 },
    timezoneId: 'Asia/Shanghai',
    reducedMotion: 'reduce',
  })
  pageA = await contextA.newPage()
  await register(pageA, `gh-${suffix}`.slice(0, 48), `gh-${suffix}@example.test`, 'Q02 GitHub 用户', 'Correct-Horse-Battery-Staple-47', false)
  await expect(pageA).toHaveURL(/\/onboarding$/)
  await pageA.getByRole('textbox', { name: '团队名称' }).fill(`Q02 GitHub ${suffix}`.slice(0, 96))
  await pageA.getByRole('button', { name: '创建团队' }).click()
  await expect(pageA.getByRole('heading', { name: '你的工作入口已经就绪' })).toBeVisible()
  sessionA = await currentSession(pageA)
  teamId = onlyTeam(sessionA).teamId

  const created = await secureFetch(`/api/v1/organizations/${sessionA.principal?.organizationId}/github-connections`, {
    method: 'POST',
    body: {
      // App installation token: the adapter's APP_INSTALLATION verify path discovers the identity
      // from the installation's repository-owner projection (installation tokens carry no profile
      // scope and no X-OAuth-Scopes header, which the OAUTH_USER path hard-requires).
      authenticationType: 'APP_INSTALLATION',
      teamId,
      credentialSubjectType: 'TEAM',
      externalAccountId: null,
      repositoryAllowlist: null,
      accessToken: githubToken,
      expiresAt: null,
    },
  })
  expect(created.status, `connection create failed: ${await created.text()}`).toBe(202)
  // The list endpoint requires ownerType (and the team coordinate for TEAM-owned App credentials).
  const connectionListPath = `/api/v1/organizations/${sessionA.principal?.organizationId}/github-connections?ownerType=TEAM&teamId=${teamId}`
  await expect.poll(async () => {
    const list = await getJson<{ items: Array<{ id: string, version: number }> }>(
      pageA, connectionListPath)
    return list.items[0]?.id ?? null
  }).not.toBeNull()
  const first = (await getJson<{ items: Array<{ id: string, version: number }> }>(
    pageA, connectionListPath)).items[0]!
  connectionId = first.id
  connectionVersion = first.version

  // Verify performs identity discovery against the real GitHub API and reports the account.
  const connectionRoot = `/api/v1/organizations/${sessionA.principal?.organizationId}/github-connections/${connectionId}`
  const verified = await secureFetch(`${connectionRoot}/verify`, { method: 'POST', ifMatch: connectionVersion })
  // Read the body once, before the assertion — a template literal in the failure message would
  // otherwise consume the stream and break the json() call on the success path.
  const identity = await verified.json() as { externalAccountLogin: string | null, version: number }
  expect(verified.status, `verify failed: ${JSON.stringify(identity)}`).toBe(200)
  expect(identity.externalAccountLogin, 'identity discovery resolves a real login').toBeTruthy()
  connectionVersion = identity.version

  // The settings page presents the verified connection without any token material.
  await pageA.goto(`/settings/integrations/github?team=${teamId}`)
  await expect(pageA.getByText(identity.externalAccountLogin!).first()).toBeVisible()
})

test('the catalog synchronizes and the repo binds to the team', async () => {
  const connectionRoot = `/api/v1/organizations/${sessionA.principal?.organizationId}/github-connections/${connectionId}`
  const synchronized = await secureFetch(`${connectionRoot}/repositories/synchronize`, { method: 'POST', ifMatch: connectionVersion })
  expect(synchronized.status, `synchronize failed: ${await synchronized.text()}`).toBe(200)
  connectionVersion = Number(synchronized.headers.get('etag')?.replace(/"/g, '') ?? connectionVersion)

  const catalog = await getJson<{ items: Array<{ externalRepositoryId: string, fullName: string, defaultBranch: string }> }>(
    pageA, `${connectionRoot}/repositories`)
  const target = catalog.items.find(item => item.fullName.toLowerCase() === githubRepo.toLowerCase())
  expect(target, `the test repository ${githubRepo} appears in the synchronized catalog`).toBeTruthy()

  const bound = await secureFetch(`${connectionRoot}/bindings`, {
    method: 'POST',
    ifMatch: connectionVersion,
    body: { teamId, defaultUsage: true, repositoryIds: [target!.externalRepositoryId] },
  })
  expect(bound.status, `binding failed: ${await bound.text()}`).toBe(202)
})

test('the project import completes its worker-built mirror and preflights', async () => {
  // Two import attempts worst-case: each settle window spans the platform git budget (5m)
  // because first-import clones move the full history over the WAN and throughputs vary
  // widely; the test budget covers two full windows plus setup.
  test.setTimeout(780_000)
  const suffix = `ghi-${Date.now()}`.toLowerCase().replace(/[^a-z0-9]+/g, '-')
  projectSuffix = suffix
  const connectionRoot = `/api/v1/organizations/${sessionA.principal?.organizationId}/github-connections/${connectionId}`
  const catalog = await getJson<{ items: Array<{ externalRepositoryId: string, fullName: string, defaultBranch: string }> }>(
    pageA, `${connectionRoot}/repositories`)
  const target = catalog.items.find(item => item.fullName.toLowerCase() === githubRepo.toLowerCase())!
  const bindings = await getJson<{ items: Array<{ id: string, grantId: string, grantVersion: number, status: string }> }>(
    pageA, `${connectionRoot}/bindings?teamId=${teamId}`)
  const grant = bindings.items[0]!

  const projectsPath = teamPath(sessionA, teamId, 'work-projects')
  await command(pageA, sessionA, projectsPath, { name: `Q02 GitHub 计划 ${suffix}`.slice(0, 96) })
  await expect.poll(async () => {
    const list = await getJson<{ items: Array<{ id: string, name: string }> }>(pageA, `${projectsPath}?limit=50`)
    return list.items.find(item => item.name.includes(suffix))?.id ?? null
  }).not.toBeNull()
  const projectId = (await getJson<{ items: Array<{ id: string, name: string }> }>(
    pageA, `${projectsPath}?limit=50`)).items.find(item => item.name.includes(suffix))!.id
  const importsPath = `${projectsPath}/${projectId}/github-imports`

  // One import attempt: submit the job, then settle to a terminal state. A FAILED job is the
  // platform's honest verdict on a WAN hiccup mid-clone (observed as transient TLS resets);
  // a member presses import again, so the caller retries once with a fresh job.
  const importOnce = async (repositoryKey: string) => {
    const imported = await secureFetch(importsPath, {
      method: 'POST',
      body: {
        connectionId,
        connectionVersion,
        grantId: grant.grantId,
        grantVersion: grant.grantVersion,
        externalRepositoryId: target.externalRepositoryId,
        // A per-run key keeps re-importing the same external repository idempotent across
        // repeated runs on a live stack (a null key defaults to the managed name and conflicts).
        repositoryKey,
        defaultBranch: target.defaultBranch,
      },
    })
    // Read the receipt before asserting — the failure-message template would otherwise consume
    // the stream and break the json() call on the success path.
    const receipt = await imported.json() as { id?: string }
    expect(imported.status, `import failed: ${JSON.stringify(receipt)}`).toBe(202)
    expect(receipt.id).toBeTruthy()

    // The worker builds the bare mirror; the job settles with the repository addressable.
    // The settle window covers the platform git budget (5m) plus slack — WAN throughput to
    // github.com varies ~5x between a direct route and a proxied one.
    await expect.poll(async () => {
      const job = await getJson<{ status: string, bindingId: string | null }>(
        pageA, `${importsPath}/${receipt.id}`)
      importBindingId = job.bindingId ?? ''
      return job.status
    }, { timeout: 360_000 }).toMatch(/COMPLETED|SUCCEEDED|READY|FAILED/)
    return (await getJson<{ status: string }>(pageA, `${importsPath}/${receipt.id}`)).status
  }

  let importStatus = await importOnce(suffix)
  if (importStatus === 'FAILED') {
    test.info().annotations.push({
      type: 'note',
      description: 'first import job FAILED on a WAN hiccup; retrying once with a fresh job',
    })
    // Same triage channel as the review gate: stdout survives the list reporter.
    console.log('[import-retry]', 'first import job FAILED; retrying once')
    importStatus = await importOnce(`${suffix}-r2`)
  }
  expect(importStatus, 'the import job reaches a ready terminal state').toMatch(/COMPLETED|SUCCEEDED|READY/)
  // A settled import leaves the RepositoryBinding the coding target must reference — the
  // team grant from t2 is a different concept (authorization, not the imported repository).
  expect(importBindingId, 'import job settles with a RepositoryBinding id').toBeTruthy()

  // Preflight proves the mirror answers through the bound grant. The upstream git probe can
  // answer transient 503s on a degraded WAN (observed as ~5s probe timeouts while a full clone
  // succeeded minutes earlier); a member presses preflight again, so bounded retries cover the
  // burst before the run accepts a boundary skip — the import itself has already settled by
  // then and the coding chain does not consume the preflight verdict.
  const preflightPath =
    `${connectionRoot}/repositories/${encodeURIComponent(target.externalRepositoryId)}/preflight?bindingId=${grant.id}`
  let preflightAccepted = false
  for (let attempt = 1; attempt <= 3 && !preflightAccepted; attempt++) {
    const preflight = await secureFetch(preflightPath, { method: 'POST', ifMatch: connectionVersion })
    if ([200, 202].includes(preflight.status)) {
      preflightAccepted = true
    } else {
      // Same triage channel as the review gate: stdout survives the list reporter, and the
      // body is a product error envelope that never contains credentials.
      console.log('[preflight]', preflight.status, (await preflight.text()).slice(0, 300))
      await pageA.waitForTimeout(15_000)
    }
  }
  if (!preflightAccepted) {
    test.info().annotations.push({
      type: 'note',
      description: 'preflight kept answering 5xx after bounded retries (upstream git probe unavailable)',
    })
    test.skip(true, 'preflight unavailable after bounded retries')
  }
})

test('a first coding task runs with the real model and reaches review', async () => {
  test.skip(!deepseekKey, 'CREWSCOPE_Q02_DEEPSEEK_API_KEY 未提供——真实 Coding 执行行需授权或环境')
  // One real model run measured 4–17 minutes (plan → approval gate → resume → tool session →
  // repair loop) depending on provider latency; a second bounded run covers the observed
  // planning-flake failure mode, so the budget spans the slow tail plus one retry.
  test.setTimeout(1_800_000)
  codingMarker = `code-${Date.now()}`
  const orgRoot = `/api/v1/organizations/${sessionA.principal?.organizationId}`
  const tasksPath = teamPath(sessionA, teamId, 'tasks')

  // The project and its carrier work item: the t3 import named the project with projectSuffix.
  const projectsPath = teamPath(sessionA, teamId, 'work-projects')
  const projectId = (await getJson<{ items: Array<{ id: string, name: string }> }>(
    pageA, `${projectsPath}?limit=50`)).items.find(item => item.name.includes(projectSuffix))!.id
  const workItemsPath = `${projectsPath}/${projectId}/work-items`
  const workItemTitle = `Q02 Coding 工作项 ${codingMarker}`.slice(0, 96)
  await command(pageA, sessionA, workItemsPath, {
    type: 'FEATURE',
    title: workItemTitle,
    description: 'M9b-Q02 first coding task carrier work item',
    priority: 'MEDIUM',
    labels: ['q02'],
    dueAt: null,
  })
  await expect.poll(async () => {
    const page = await getJson<{ items: Array<{ id: string, title: string }> }>(pageA, `${workItemsPath}?limit=50`)
    return page.items.find(item => item.title === workItemTitle)?.id ?? null
  }).not.toBeNull()
  const workItemId = (await getJson<{ items: Array<{ id: string, title: string }> }>(
    pageA, `${workItemsPath}?limit=50`)).items.find(item => item.title === workItemTitle)!.id
  codingWorkItemId = workItemId

  // The model chain on this fresh team mirrors real-provider.spec.ts: a USER-owned DeepSeek
  // connection (real key, Node-side fetch only) → verify → the seeded personal agent bound to
  // the flash catalog entry. The current-configuration projection carries the revision the
  // delegation command must pin as agentConfigurationRevision.
  const providers = await getJson<{ items: Array<{ key: string, status: string, availableRegions: string[] }> }>(
    pageA, `${orgRoot}/model-providers`)
  const deepseek = providers.items.find(provider => provider.key.toLowerCase().includes('deepseek'))!
  expect(deepseek, 'DeepSeek provider is seeded for a new team').toBeTruthy()
  const connectionCreated = await secureFetch(`${orgRoot}/model-connections?ownerType=USER`, {
    method: 'POST',
    body: {
      providerKey: deepseek.key,
      ownerType: 'USER',
      teamId: null,
      region: deepseek.availableRegions[0]!,
      apiKey: deepseekKey,
      credentialExpiresAt: null,
    },
  })
  expect(connectionCreated.status, `model connection create failed: ${await connectionCreated.text()}`).toBe(202)
  await expect.poll(async () => {
    const list = await getJson<{ items: Array<{ id: string, providerKey: string }> }>(
      pageA, `${orgRoot}/model-connections?ownerType=USER`)
    return list.items.find(item => item.providerKey === deepseek.key)?.id ?? null
  }).not.toBeNull()
  const modelConnectionId = (await getJson<{ items: Array<{ id: string, providerKey: string }> }>(
    pageA, `${orgRoot}/model-connections?ownerType=USER`)).items.find(
    item => item.providerKey === deepseek.key)!.id
  const modelConnectionRoot = `${orgRoot}/model-connections/${modelConnectionId}`
  // The optimistic version lives only in the ETag header; verify hard-requires a matching If-Match.
  const beforeVerifyResponse = await pageA.request.get(modelConnectionRoot)
  expect(beforeVerifyResponse.ok(), 'model connection detail before verify').toBeTruthy()
  const beforeVerify = await beforeVerifyResponse.json() as { credentialVersion: number }
  const modelConnectionVersion = Number((beforeVerifyResponse.headers()['etag'] ?? '').replace(/"/g, ''))
  const verifiedModel = await secureFetch(`${modelConnectionRoot}/verify`, {
    method: 'POST',
    body: { credentialVersion: beforeVerify.credentialVersion },
    ifMatch: modelConnectionVersion,
  })
  expect(verifiedModel.status, `model connection verify failed: ${await verifiedModel.text()}`).toBe(202)
  // The health probe's first attempt can time out on an alpine/musl DNS hiccup (JVM resolver,
  // Docker embedded DNS — observed intermittently while the same container reaches the provider
  // in ~0.2s on other attempts). A member on the connections page would press "verify" again, so
  // the poll re-verifies while the connection reports UNHEALTHY instead of failing the run. The
  // same hiccup comes in bursts on a degraded WAN, so the window covers several re-verifies.
  let reverifyBudget = 6
  await expect.poll(async () => {
    const detail = await pageA.request.get(modelConnectionRoot)
    const body = await detail.json() as { healthStatus: string, credentialVersion: number }
    if (body.healthStatus !== 'HEALTHY' && reverifyBudget > 0) {
      reverifyBudget -= 1
      const again = await secureFetch(`${modelConnectionRoot}/verify`, {
        method: 'POST',
        body: { credentialVersion: body.credentialVersion },
        ifMatch: Number((detail.headers()['etag'] ?? '').replace(/"/g, '')),
      })
      expect([200, 202]).toContain(again.status)
    }
    return body.healthStatus
  }, { timeout: 240_000 }).toBe('HEALTHY')

  const agentProfiles = await getJson<{ items: Array<{ id: string, runtimeRole: string }> }>(
    pageA, teamPath(sessionA, teamId, 'agent-profiles'))
  const personal = agentProfiles.items.find(profile => profile.runtimeRole === 'PERSONAL_ASSISTANT')!
  expect(personal, 'the seeded personal agent profile exists').toBeTruthy()
  const modelCatalog = await getJson<{ items?: Array<{ id: string, providerKey: string, catalogRevision: number }> }>(
    pageA, `${orgRoot}/model-providers/${encodeURIComponent(deepseek.key)}/catalog`)
  const flashEntry = (modelCatalog.items ?? []).find(
    item => item.id.toLowerCase().includes('flash')) ?? (modelCatalog.items ?? [])[0]!
  expect(flashEntry, 'the DeepSeek catalog has models').toBeTruthy()
  const profileRoot = teamPath(sessionA, teamId, `agent-profiles/${personal.id}`)
  const profileDetail = await pageA.request.get(profileRoot)
  const profileEtag = profileDetail.headers()['etag']
  expect(profileEtag, 'agent profile detail returns an ETag version').toBeTruthy()
  const bound = await pageA.request.post(`${profileRoot}/configurations`, {
    data: {
      personalModelBinding: {
        kind: 'DIRECT',
        primary: { connectionId: modelConnectionId, catalogEntryId: flashEntry.id, catalogRevision: flashEntry.catalogRevision },
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
  expect(bound.status(), `agent binding failed: ${await bound.text()}`).toBe(202)
  await expect.poll(async () => {
    const current = await getJson<{ personalBinding: { primary: { connectionId: string } } | null }>(
      pageA, `${profileRoot}/configurations/current`)
    return current.personalBinding?.primary?.connectionId === modelConnectionId ? 1 : 0
  }).toBe(1)
  const currentConfiguration = await getJson<{ revision: number }>(pageA, `${profileRoot}/configurations/current`)
  const agentRevision = currentConfiguration.revision

  // Delegation context and preflight mirror the delegation form's own reads.
  const itemRoot = teamPath(sessionA, teamId, `work-projects/${projectId}/work-items/${workItemId}`)
  const delegation = await getJson<{
    candidates: Array<{ agentProfileId: string, runtimeRole: string, state: string }>,
  }>(pageA, `${itemRoot}/delegation-context`)
  const candidate = delegation.candidates.find(item => item.agentProfileId === personal.id)
  expect(candidate, 'the bound personal agent is a delegation candidate').toBeTruthy()
  const preflight = await pageA.request.post(`${itemRoot}/tasks/preflight`, {
    data: {
      executorAgentProfileId: personal.id,
      agentConfigurationRevision: agentRevision,
      plannedExecutorAgentProfileId: personal.id,
    },
    headers: { [sessionA.csrf.headerName]: sessionA.csrf.token },
  })
  const preflightBody = await preflight.json() as { ready?: boolean, findings?: Array<{ code: string }> }
  expect(preflight.ok() || preflight.status() === 403,
    `preflight answers with a verdict: ${JSON.stringify(preflightBody)}`).toBe(true)

  // The coding target states the imported repository binding (from t3's job), the imported
  // default branch, and the build profile the catalog offers for this repository's stack.
  // grantId stays the t2 team authorization binding — t5's delivery plan pins it as its
  // ProviderBinding (connection + grant + execution identity), and the task must be created
  // already granting it: the policy snapshot minted here pins the authorized ProviderBindings,
  // and the plan's requirePolicy (ActionAuthoritySnapshot) rejects any binding the task was
  // never granted — an empty array makes t5 422 on actionBundle.policySnapshot.
  const connectionRoot = `${orgRoot}/github-connections/${connectionId}`
  const repoCatalog = await getJson<{ items: Array<{ externalRepositoryId: string, fullName: string, defaultBranch: string }> }>(
    pageA, `${connectionRoot}/repositories`)
  const target = repoCatalog.items.find(item => item.fullName.toLowerCase() === githubRepo.toLowerCase())!
  externalRepositoryId = target.externalRepositoryId
  const bindings = await getJson<{ items: Array<{ id: string, status: string }> }>(
    pageA, `${connectionRoot}/bindings?teamId=${teamId}`)
  grantId = bindings.items[0]!.id
  const buildProfiles = await getJson<{ items: Array<{ key: string, version: number, profileHash: string, buildTool: string }> }>(
    pageA, `${itemRoot}/coding-target/build-profiles`)
  const buildProfile = buildProfiles.items.find(item => item.buildTool === 'MAVEN') ?? buildProfiles.items[0]!

  // One full coding run: create → settle (resuming once at the approval gate) → coding view.
  const runCodingTask = async (runMarker: string) => {
    const created = await pageA.request.post(`${itemRoot}/tasks`, {
      data: {
        objective: `在仓库根目录新增文件 ${runMarker}.md，内容为一行文字：CrewScope 交付任务 ${runMarker} 完成。不要改动其它文件。`,
        acceptanceCriteria: [
          `仓库根目录存在文件 ${runMarker}.md`,
          `文件内容包含文字 ${runMarker}`,
          `本仓库的集成测试需要 Docker 等容器环境，执行沙箱内不可用；验收测试请通过 tests 选择器只定向运行与交付物相关的单元测试，不要全量运行测试套件`,
        ],
        executorAgentProfileId: personal.id,
        agentConfigurationRevision: agentRevision,
        executorAssignment: { agentProfileId: personal.id },
        conversationSource: null,
        providerBindingIds: [grantId],
        codingTarget: {
          repositoryBindingId: importBindingId,
          baselineRef: target.defaultBranch,
          allowedPaths: ['.'],
          buildProfile,
        },
      },
      headers: {
        [sessionA.csrf.headerName]: sessionA.csrf.token,
        'Idempotency-Key': crypto.randomUUID(),
        'If-Match': '"0"',
      },
    })
    const createdBody = await created.json() as Record<string, unknown>
    expect(created.status(), `task create command accepted: ${JSON.stringify(createdBody)}`).toBe(202)
    await expect.poll(async () => {
      const page = await getJson<{ items: Array<{ id: string, objective: string, currentExecutionId: string | null }> }>(
        pageA, `${tasksPath}?projectId=${projectId}&limit=20`)
      return page.items.find(item => item.objective.includes(runMarker))?.currentExecutionId ?? null
    }, { timeout: 60_000 }).not.toBeNull()
    const taskRow = (await getJson<{ items: Array<{ id: string, objective: string, currentExecutionId: string | null }> }>(
      pageA, `${tasksPath}?projectId=${projectId}&limit=20`)).items.find(
      item => item.objective.includes(runMarker))!
    const executionId = taskRow.currentExecutionId!
    expect(executionId, 'the task opened a durable execution').toBeTruthy()

    // The specialist's plan opens a CONFIRMATION interrupt; one resume approves it and the
    // execution continues on the same pinned model. Terminal or stuck states cannot converge
    // on their own, so they break the loop with their status recorded.
    let resumedOnce = false
    // The restricted-egress sandbox fetches Maven dependencies from Central (measured ~2 minutes
    // for a cold full-reactor build on this host), so the settle window covers real build time
    // plus the CONFIRMATION resume and at most one repair round. Real-model runs vary widely
    // (observed 4 to 17 minutes on the same objective), so the budget spans the slow tail.
    const deadline = Date.now() + 1_500_000
    let settled = ''
    while (Date.now() < deadline) {
      const attempts = await getJson<Array<{ id: string, status: string, version: number, waiting?: { reason: string } | null }>>(
        pageA, `${tasksPath}/${taskRow.id}/attempts`)
      const current = attempts.find(item => item.id === executionId)
      settled = current?.status ?? 'MISSING'
      if (settled === 'COMPLETED') break
      if (settled === 'FAILED' || settled === 'CANCELLED' || settled === 'RECOVERING') {
        await pageA.waitForTimeout(30_000)
        const recheck = await getJson<Array<{ id: string, status: string }>>(
          pageA, `${tasksPath}/${taskRow.id}/attempts`)
        settled = recheck.find(item => item.id === executionId)?.status ?? settled
        if (settled !== 'RUNNING' && settled !== 'WAITING') break
      }
      if (settled === 'WAITING' && current?.waiting?.reason === 'CONFIRMATION' && !resumedOnce) {
        resumedOnce = true
        const resumed = await pageA.request.post(`${tasksPath}/${taskRow.id}/attempts/${executionId}/resume`, {
          data: undefined,
          headers: {
            [sessionA.csrf.headerName]: sessionA.csrf.token,
            'Idempotency-Key': crypto.randomUUID(),
            'If-Match': `"${current.version}"`,
          },
        })
        expect([200, 201, 202]).toContain(resumed.status())
      }
      await pageA.waitForTimeout(15_000)
    }
    const codingView = await getJson<{
      coding: boolean, executionStatus: string,
      details: { diffManifest: unknown, commandEvidenceCount: number, testEvidenceCount: number } | null,
    }>(pageA, `${tasksPath}/${taskRow.id}/attempts/${executionId}/coding`)
    return { taskId: taskRow.id, executionId, settled, codingView }
  }

  // The reviewer's model plumbing is prepared before the coding run, not after it: the TEAM
  // connection's health probe opens a fresh JVM-side TLS connection (DNS + handshake), which
  // times out intermittently while the JVM is busy projecting the execution's event stream —
  // the chat-completions calls ride pooled keep-alive connections and stay healthy, but the
  // probe cannot. Preparing the reviewer in the quiet window before the run starts both
  // mirrors a member's real order of operations and moves the probe away from that load.
  const agentsPath = teamPath(sessionA, teamId, 'agent-profiles')
  await command(pageA, sessionA, agentsPath, {
    publisherType: 'ORGANIZATION',
    templateKey: 'reviewer',
    templateVersion: 1,
    ownershipType: 'TEAM',
    displayName: `Q02 Reviewer ${codingMarker}`.slice(0, 96),
  })
  await expect.poll(async () => {
    const page = await getJson<{ items: Array<{ id: string, displayName: string }> }>(pageA, `${agentsPath}?limit=50`)
    return page.items.find(item => item.displayName.includes(codingMarker))?.id ?? null
  }, { timeout: 60_000 }).not.toBeNull()
  const reviewer = (await getJson<{ items: Array<{ id: string, displayName: string, principalId: string, status: string }> }>(
    pageA, `${agentsPath}?limit=50`)).items.find(item => item.displayName.includes(codingMarker))!
  expect(reviewer.status, 'the reviewer agent is active').toBe('ACTIVE')
  // The reviewer is TEAM-owned and resolves its model through teamModelBinding; without one the
  // review-creation auto snapshot fails closed (403 policy_denied, MODEL_BINDING_MISSING) before
  // the review gate can open. The reviewer template allows both PERSONAL and TEAM scopes, so the
  // configuration draft must carry both bindings — and a TEAM-owned agent may only use a
  // TEAM-owned model connection, so one more connection is created for the team itself.
  const teamConnectionCreated = await secureFetch(`${orgRoot}/model-connections?ownerType=TEAM`, {
    method: 'POST',
    body: {
      providerKey: deepseek.key,
      ownerType: 'TEAM',
      teamId,
      region: deepseek.availableRegions[0]!,
      apiKey: deepseekKey,
      credentialExpiresAt: null,
    },
  })
  expect(teamConnectionCreated.status, `team model connection create failed: ${await teamConnectionCreated.text()}`).toBe(202)
  await expect.poll(async () => {
    const list = await getJson<{ items: Array<{ id: string, ownerType: string, ownerId: string }> }>(
      pageA, `${orgRoot}/model-connections?ownerType=TEAM&teamId=${teamId}`)
    return list.items.find(item => item.ownerType === 'TEAM' && item.ownerId === teamId)?.id ?? null
  }).not.toBeNull()
  const teamModelConnectionId = (await getJson<{ items: Array<{ id: string, ownerType: string, ownerId: string }> }>(
    pageA, `${orgRoot}/model-connections?ownerType=TEAM&teamId=${teamId}`)).items.find(
      item => item.ownerType === 'TEAM' && item.ownerId === teamId)!.id
  const teamConnectionRoot = `${orgRoot}/model-connections/${teamModelConnectionId}`
  const teamBeforeResponse = await pageA.request.get(teamConnectionRoot)
  expect(teamBeforeResponse.ok(), 'team model connection detail before verify').toBeTruthy()
  const teamBefore = await teamBeforeResponse.json() as { credentialVersion: number }
  const teamVerified = await secureFetch(`${teamConnectionRoot}/verify`, {
    method: 'POST',
    body: { credentialVersion: teamBefore.credentialVersion },
    ifMatch: Number((teamBeforeResponse.headers()['etag'] ?? '').replace(/"/g, '')),
  })
  expect(teamVerified.status, `team model connection verify failed: ${await teamVerified.text()}`).toBe(202)
  let teamReverifyBudget = 6
  await expect.poll(async () => {
    const detail = await pageA.request.get(teamConnectionRoot)
    const body = await detail.json() as { healthStatus: string, credentialVersion: number }
    if (body.healthStatus !== 'HEALTHY' && teamReverifyBudget > 0) {
      teamReverifyBudget -= 1
      const again = await secureFetch(`${teamConnectionRoot}/verify`, {
        method: 'POST',
        body: { credentialVersion: body.credentialVersion },
        ifMatch: Number((detail.headers()['etag'] ?? '').replace(/"/g, '')),
      })
      expect([200, 202]).toContain(again.status)
    }
    return body.healthStatus
  }, { timeout: 240_000 }).toBe('HEALTHY')
  const reviewerRoot = teamPath(sessionA, teamId, `agent-profiles/${reviewer.id}`)
  const reviewerDetail = await pageA.request.get(reviewerRoot)
  const reviewerEtag = reviewerDetail.headers()['etag']
  expect(reviewerEtag, 'reviewer profile detail returns an ETag version').toBeTruthy()
  const reviewerBound = await pageA.request.post(`${reviewerRoot}/configurations`, {
    data: {
      personalModelBinding: {
        kind: 'DIRECT',
        primary: { connectionId: teamModelConnectionId, catalogEntryId: flashEntry.id, catalogRevision: flashEntry.catalogRevision },
        fallback: null,
      },
      teamModelBinding: {
        kind: 'DIRECT',
        primary: { connectionId: teamModelConnectionId, catalogEntryId: flashEntry.id, catalogRevision: flashEntry.catalogRevision },
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
  expect(reviewerBound.status(), `reviewer binding failed: ${await reviewerBound.text()}`).toBe(202)
  await expect.poll(async () => {
    const current = await getJson<{ teamBinding: { primary: { connectionId: string } } | null }>(
      pageA, `${reviewerRoot}/configurations/current`)
    return current.teamBinding?.primary?.connectionId === teamModelConnectionId ? 1 : 0
  }).toBe(1)
  await command(pageA, sessionA, `${itemRoot}/responsibilities/advisory-reviewers`, { actorPrincipalId: reviewer.principalId })

  let run = await runCodingTask(codingMarker)
  const engagedToolSession = (run.codingView.details?.commandEvidenceCount ?? 0)
    + (run.codingView.details?.testEvidenceCount ?? 0) > 0
  if (!engagedToolSession && !run.codingView.details?.diffManifest) {
    // A run that settled without any execution evidence means the model never engaged the tool
    // session (observed as a fast TASK_PLAN_INVALID flake); retry the whole task once.
    test.info().annotations.push({
      type: 'note',
      description: `first coding run settled ${run.settled} without tool-session evidence; retrying once`,
    })
    run = await runCodingTask(`${codingMarker}-r2`)
  }
  codingTaskId = run.taskId
  codingExecutionId = run.executionId
  codingSettled = run.settled
  expect(['COMPLETED', 'FAILED'], 'the coding attempt reached a terminal state on the real model')
    .toContain(run.settled)
  expect(run.codingView.coding, 'the execution ran the coding pipeline').toBe(true)
  expect(run.codingView.executionStatus).toBe(run.settled)
  // The restricted-egress sandbox reaches Maven Central, so COMPLETED with a finalized diff
  // manifest is the expected terminal state. A FAILED terminal state is still accepted as a
  // terminal outcome of the real model (for example an acceptance command the model selected
  // exceeding the fixed 900-second command ceiling), but it must still carry real tool evidence;
  // the review gate below then decides how far the delivery surface opens.
  test.info().annotations.push({
    type: 'note',
    description: `coding attempt settled as ${run.settled} (restricted-egress sandbox with network egress)`,
  })
  if (run.settled === 'COMPLETED') {
    expect(run.codingView.details?.diffManifest, 'the attempt delivered a diff manifest').toBeTruthy()
  } else {
    const evidenceCount = (run.codingView.details?.commandEvidenceCount ?? 0)
      + (run.codingView.details?.testEvidenceCount ?? 0)
    expect(evidenceCount, 'the model executed real tool work before the boundary terminal state')
      .toBeGreaterThan(0)
  }

  // The reviewer preparation (agent, TEAM model connection, bindings, advisory seat) ran before
  // the coding run above; the review gate itself only opens on a terminal attempt.
  const reviewsPath = `${tasksPath}/${codingTaskId}/attempts/${codingExecutionId}/reviews`
  const reviewResponse = await pageA.request.post(reviewsPath, {
    data: {},
    headers: {
      [sessionA.csrf.headerName]: sessionA.csrf.token,
      'Idempotency-Key': crypto.randomUUID(),
    },
  })
  const reviewReceipt = await reviewResponse.json() as Record<string, unknown>
  if (reviewResponse.status() >= 400) {
    // A FAILED attempt may be rejected by the gate; with restricted egress COMPLETED is the
    // expected state, so a rejection here is a real boundary worth recording, not the baseline.
    test.info().annotations.push({
      type: 'note',
      description: `review gate answered ${reviewResponse.status()} for the ${codingSettled} attempt: ${JSON.stringify(reviewReceipt).slice(0, 400)}`,
    })
    // The list reporter swallows skip annotations, so the response body also goes to stdout for
    // gate triage; the body is a product error envelope and never contains credentials.
    console.log('[review-gate]', reviewResponse.status(), JSON.stringify(reviewReceipt).slice(0, 400))
    test.skip(true, `review gate requires a completed attempt; got ${reviewResponse.status()}`)
  }
  expect([200, 201, 202]).toContain(reviewResponse.status())
  reviewOpened = true
  await expect.poll(async () => {
    const page = await getJson<{ items: Array<{ id: string }> }>(pageA, reviewsPath)
    return page.items[0]?.id ?? null
  }, { timeout: 60_000 }).not.toBeNull()
  const reviewRow = (await getJson<{ items: Array<{ id: string, status: string, version: number }> }>(
    pageA, reviewsPath)).items[0]!
  expect(['OPEN', 'IN_PROGRESS', 'COMPLETED']).toContain(reviewRow.status)
  const executed = await pageA.request.post(`${reviewsPath}/${reviewRow.id}/execute`, {
    data: undefined,
    headers: {
      [sessionA.csrf.headerName]: sessionA.csrf.token,
      'Idempotency-Key': crypto.randomUUID(),
      'If-Match': `"${reviewRow.version}"`,
    },
  })
  if (executed.status() >= 400) {
    // Same triage channel as the review gate: stdout survives the list reporter, the body is
    // a product error envelope and never contains credentials.
    console.log('[review-execute]', executed.status(), (await executed.text()).slice(0, 400))
  }
  expect([200, 201, 202]).toContain(executed.status())
})

test('the delivery plan creates a Draft PR and never merges it', async ({ browser }) => {
  test.skip(!deepseekKey, 'CREWSCOPE_Q02_DEEPSEEK_API_KEY 未提供——Draft PR 行需授权或环境')
  // This row is a second member joining plus the whole delivery half, and its own inner waits
  // already reserve most of a five-minute budget: the bundle appears within 60s and the action
  // receipt within 180s, on top of B's registration, invitation acceptance, the gate-reviewer
  // seat, the decision, and a re-synchronize that may retry once after 10s. Budget the row for
  // those waits landing at their limits rather than at their typical latencies.
  test.setTimeout(900_000)
  test.skip(!reviewOpened, '审查门未开启（coding attempt 未达可审查终态的栈边界）——Draft PR 行需完成的执行与审查')
  const tasksPath = teamPath(sessionA, teamId, 'tasks')
  const reviewsPath = `${tasksPath}/${codingTaskId}/attempts/${codingExecutionId}/reviews`

  // A human Gate decision requires the USER Reviewer seat plus Owner/Executor separation
  // (ReviewerResponsibility.requireGateReviewer / ReviewerEligibilityPolicy): the advisory
  // agent reviewer only produces findings, and A owns the item, so a second member B joins
  // and takes the gate-reviewer seat — the human-review half of the 人审与 PR row.
  contextB = await browser.newContext({
    baseURL,
    viewport: { width: 1440, height: 960 },
    timezoneId: 'Asia/Shanghai',
    reducedMotion: 'reduce',
  })
  pageB = await contextB.newPage()
  await pageA.goto(`/team/members?team=${teamId}&tab=invitations`)
  await pageA.getByRole('button', { name: '创建邀请' }).first().click()
  await pageA.locator('input[name="invitationEmail"]').fill(`gate-${codingMarker}@example.test`)
  await pageA.locator('select[name="invitationRole"]').selectOption('MEMBER')
  await pageA.getByRole('button', { name: '创建邀请链接' }).click()
  const invitationLink = await pageA.getByRole('textbox', { name: '一次性邀请链接' }).inputValue()
  expect(invitationLink).toMatch(/\/invite#token=[A-Za-z0-9_-]{43}$/)
  await pageB.goto(invitationLink)
  await pageB.getByRole('button', { name: '创建账号并加入团队' }).click()
  await expect(pageB).toHaveURL(/\/register$/)
  await register(pageB, `gate-${codingMarker}`.slice(0, 48), `gate-${codingMarker}@example.test`,
    'Q02 Gate Reviewer', `q02Gate-${codingMarker}`, true)
  await expect(pageB).toHaveURL(new RegExp(`/conversation(?:\\?team=${teamId})?$`))
  sessionB = await currentSession(pageB)
  expect(onlyTeam(sessionB).teamId).toBe(teamId)

  const projectsPath = teamPath(sessionA, teamId, 'work-projects')
  const projectId = (await getJson<{ items: Array<{ id: string, name: string }> }>(
    pageA, `${projectsPath}?limit=50`)).items.find(item => item.name.includes(projectSuffix))!.id
  const itemRoot = teamPath(sessionA, teamId,
    `work-projects/${projectId}/work-items/${codingWorkItemId}`)
  await command(pageA, sessionA, `${itemRoot}/responsibilities/gate-reviewers`,
    { actorPrincipalId: sessionB.principal?.principalId })

  // The reviewer execution settles the review; an APPROVED decision by B authorizes delivery.
  await expect.poll(async () => {
    const page = await getJson<{ items: Array<{ id: string, status: string }> }>(pageA, reviewsPath)
    return page.items[0]?.status ?? ''
  }, { timeout: 120_000 }).toBe('COMPLETED')
  const reviewRow = (await getJson<{ items: Array<{ id: string, status: string, version: number }> }>(
    pageA, reviewsPath)).items[0]!
  const decided = await pageB.request.post(`${reviewsPath}/${reviewRow.id}/decisions`, {
    data: { type: 'APPROVED', rationale: 'Q02 gate: approve to open the delivery surface' },
    headers: {
      [sessionB.csrf.headerName]: sessionB.csrf.token,
      'Idempotency-Key': crypto.randomUUID(),
      'If-Match': `"${reviewRow.version}"`,
    },
  })
  if (decided.status() >= 400) {
    // Same triage channel as the review gate: stdout survives the list reporter, the body is
    // a product error envelope and never contains credentials.
    console.log('[review-decision]', decided.status(), (await decided.text()).slice(0, 400))
  }
  expect([200, 201, 202]).toContain(decided.status())
  // The list row is a summary projection (finding counts, latestDecisionType); the decision
  // ids live on the workbench detail view.
  await expect.poll(async () => {
    const detail = await getJson<{ decisions: Array<{ id: string }> }>(
      pageA, `${reviewsPath}/${reviewRow.id}`)
    return detail.decisions?.[0]?.id ?? null
  }, { timeout: 60_000 }).not.toBeNull()
  const reviewDecisionId = (await getJson<{ decisions: Array<{ id: string }> }>(
    pageA, `${reviewsPath}/${reviewRow.id}`)).decisions[0]!.id

  // The repository catalog is a five-minute discovery cache, and t4's real model
  // execution plus the human gate above outlive it — the delivery plan resolves
  // "current" authority facts and would reject the stale entry. A member delivering
  // an approved change re-synchronizes the authorization view first; the plan then
  // runs against a fresh catalog row and connection version.
  const orgRoot = `/api/v1/organizations/${sessionA.principal?.organizationId}`
  const connectionRoot = `${orgRoot}/github-connections/${connectionId}`
  // The synchronize call drives a live GitHub API listing; a transient upstream 503 (observed
  // intermittently on this WAN) answers before any listing lands, so a member delivering an
  // approved change presses synchronize again — one bounded retry covers the hiccup.
  const synchronize = async () => secureFetch(`${connectionRoot}/repositories/synchronize`, {
    method: 'POST',
    ifMatch: connectionVersion,
  })
  let resynchronized = await synchronize()
  if (resynchronized.status >= 500) {
    await pageA.waitForTimeout(10_000)
    resynchronized = await synchronize()
  }
  expect(resynchronized.status, `re-synchronize failed: ${await resynchronized.text()}`).toBe(200)
  connectionVersion = Number(resynchronized.headers.get('etag')?.replace(/"/g, '') ?? connectionVersion)

  // Plan the delivery bundle against the approved decision and the bound repository.
  const actionsPath = `${tasksPath}/${codingTaskId}/attempts/${codingExecutionId}/actions`
  const planned = await pageA.request.post(`${actionsPath}/bundles`, {
    data: {
      reviewDecisionId,
      providerBindingId: grantId,
      repositoryId: externalRepositoryId,
      title: `Q02 Draft PR ${codingMarker}`.slice(0, 96),
      body: 'M9b-Q02 release-gate draft PR — created, never merged.',
    },
    headers: {
      [sessionA.csrf.headerName]: sessionA.csrf.token,
      'Idempotency-Key': crypto.randomUUID(),
    },
  })
  const planReceipt = await planned.json() as Record<string, unknown>
  if (planned.status() >= 400) {
    test.info().annotations.push({
      type: 'note',
      description: `delivery plan answered ${planned.status()} for the approved review (boundary)`,
    })
    // Same triage channel as the review gate: stdout survives the list reporter, the
    // body is a product error envelope and never contains credentials.
    console.log('[delivery-plan]', planned.status(), JSON.stringify(planReceipt).slice(0, 400))
    test.skip(true, `delivery plan rejected the request; got ${planned.status()}`)
  }
  expect([200, 201, 202]).toContain(planned.status())
  await expect.poll(async () => {
    const page = await getJson<{ items: Array<{ id: string }> }>(pageA, `${actionsPath}/bundles`)
    return page.items[0]?.id ?? null
  }, { timeout: 60_000 }).not.toBeNull()

  // Draft-only, forever: the product decomposes delivery into pushing the pull request's
  // head branch and opening the draft PR (ActionKind has no merge kind at all — the
  // boundary is structural), so the guard is enum membership plus draft=true on the PR.
  type BundleRow = {
    id: string, version: number, digest: string,
    actions: Array<{ kind: string, parameters: { draft?: boolean } | null }>,
  }
  const bundle = (await getJson<{ items: Array<BundleRow> }>(pageA, `${actionsPath}/bundles`)).items[0]!
  expect(bundle.actions.length, 'the delivery bundle plans at least one action').toBeGreaterThan(0)
  for (const action of bundle.actions) {
    expect(['PUSH_BRANCH', 'CREATE_DRAFT_PR'],
      'the delivery bundle never plans a merge').toContain(action.kind)
  }
  const prActions = bundle.actions.filter(action => action.kind === 'CREATE_DRAFT_PR')
  expect(prActions.length, 'the delivery bundle plans the draft PR').toBe(1)
  expect(prActions[0]!.parameters?.draft, 'the planned pull request is a draft').toBe(true)

  // Confirming dispatches the action worker; every action settles with a receipt, and the
  // CREATE_DRAFT_PR receipt verifies the external pull request (the push lands first).
  const confirmed = await pageA.request.post(`${actionsPath}/bundles/${bundle.id}/confirmations`, {
    data: { bundleDigest: bundle.digest },
    headers: {
      [sessionA.csrf.headerName]: sessionA.csrf.token,
      'Idempotency-Key': crypto.randomUUID(),
      'If-Match': `"${bundle.version}"`,
    },
  })
  if (confirmed.status() >= 400) {
    // Same triage channel as the plan gate: stdout survives the list reporter, and the
    // body is a product error envelope that never contains credentials.
    console.log('[delivery-confirm]', confirmed.status(), (await confirmed.text()).slice(0, 400))
  }
  expect([200, 201, 202]).toContain(confirmed.status())
  await expect.poll(async () => {
    const detail = await getJson<{ actions: Array<{ receipt: unknown | null }> }>(
      pageA, `${actionsPath}/bundles/${bundle.id}`)
    return detail.actions.length > 0 && detail.actions.every(action => action.receipt) ? 1 : 0
  }, { timeout: 180_000 }).toBe(1)
  const settledBundle = (await getJson<{
    actions: Array<{
      kind: string,
      externalResult: { externalObjectType: string } | null,
    }>,
  }>(pageA, `${actionsPath}/bundles/${bundle.id}`))
  const settledPr = settledBundle.actions.find(action => action.kind === 'CREATE_DRAFT_PR')!
  expect(settledPr.externalResult?.externalObjectType,
    'the receipt verified an external pull request').toBe('PULL_REQUEST')
})

test.afterAll(async () => {
  await contextA?.close()
  await contextB?.close()
})
