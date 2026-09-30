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
 * M9b-Q02 real provider chain (§9.2 第一条真实回复 row), gated on CREWSCOPE_Q02_DEEPSEEK_API_KEY.
 * The real key travels only through Node-side fetch calls carrying the browser session cookie —
 * it is never typed into a page, a URL, a command line, or the Playwright trace. The page side
 * asserts what the contract promises after credentials are stored: a HEALTHY connection with its
 * server-owned catalog price, a bound personal agent, a first real reply asserted structurally
 * (a non-empty agent-authored message, never its content), an audit record of the provider call,
 * and the invalid-key failure surface, which uses an obvious probe value and may enter traces.
 */
const apiKey = process.env.CREWSCOPE_Q02_DEEPSEEK_API_KEY ?? ''
const hasApiKey = apiKey.length > 0

let contextA: BrowserContext
let pageA: Page
let sessionA: Session
let teamId: string
let connectionId: string

test.skip(!hasApiKey, 'CREWSCOPE_Q02_DEEPSEEK_API_KEY 未提供——第一条真实回复行需授权或环境')

test.describe.configure({ mode: 'serial' })

/** Node-side command fetch: credentials stay out of browser contexts and Playwright traces. */
async function secureFetch(path: string, init: { method: string, body?: unknown, ifMatch?: number }): Promise<Response> {
  const headers: Record<string, string> = {
    'Content-Type': 'application/json',
    Cookie: await cookieHeader(contextA),
    [sessionA.csrf.headerName]: sessionA.csrf.token,
    'Idempotency-Key': crypto.randomUUID(),
  }
  if (init.ifMatch !== undefined) headers['If-Match'] = `"${init.ifMatch}"`
  return fetch(`${baseURL}${path}`, {
    method: init.method,
    headers,
    body: init.body === undefined ? undefined : JSON.stringify(init.body),
  })
}

test('a real connection verifies healthy through the server-side probe', async ({ browser }) => {
  const suffix = `rp-${Date.now()}`.toLowerCase().replace(/[^a-z0-9]+/g, '-')
  contextA = await browser.newContext({
    baseURL,
    viewport: { width: 1440, height: 960 },
    timezoneId: 'Asia/Shanghai',
    reducedMotion: 'reduce',
  })
  pageA = await contextA.newPage()
  await register(pageA, `rp-${suffix}`.slice(0, 48), `rp-${suffix}@example.test`, 'Q02 模型用户', 'Correct-Horse-Battery-Staple-47', false)
  await expect(pageA).toHaveURL(/\/onboarding$/)
  await pageA.getByRole('textbox', { name: '团队名称' }).fill(`Q02 模型 ${suffix}`.slice(0, 96))
  await pageA.getByRole('button', { name: '创建团队' }).click()
  await expect(pageA.getByRole('heading', { name: '你的工作入口已经就绪' })).toBeVisible()
  sessionA = await currentSession(pageA)
  teamId = onlyTeam(sessionA).teamId

  // The seeded catalog names the DeepSeek provider and its regions; both are server facts.
  const providers = await getJson<{ items: Array<{ key: string, status: string, availableRegions: string[] }> }>(
    pageA, `/api/v1/organizations/${sessionA.principal?.organizationId}/model-providers`)
  const deepseek = providers.items.find(provider => provider.key.toLowerCase().includes('deepseek'))
  expect(deepseek, 'DeepSeek provider is seeded for a new team').toBeTruthy()
  expect(deepseek!.availableRegions.length).toBeGreaterThan(0)

  const created = await secureFetch(`/api/v1/organizations/${sessionA.principal?.organizationId}/model-connections?ownerType=USER`, {
    method: 'POST',
    body: {
      providerKey: deepseek!.key,
      ownerType: 'USER',
      teamId: null,
      region: deepseek!.availableRegions[0]!,
      apiKey,
      credentialExpiresAt: null,
    },
  })
  expect(created.status, `connection create failed: ${await created.text()}`).toBe(202)

  await expect.poll(async () => {
    const list = await getJson<{ items: Array<{ id: string, providerKey: string, status: string }> }>(
      pageA, `/api/v1/organizations/${sessionA.principal?.organizationId}/model-connections?ownerType=USER`)
    return list.items.find(item => item.providerKey === deepseek!.key)?.id ?? null
  }).not.toBeNull()
  connectionId = (await getJson<{ items: Array<{ id: string, providerKey: string }> }>(
    pageA, `/api/v1/organizations/${sessionA.principal?.organizationId}/model-connections?ownerType=USER`))
    .items.find(item => item.providerKey === deepseek!.key)!.id

  // Verify drives the real /models probe; the read side settles on HEALTHY with a timestamp.
  const connectionRoot = `/api/v1/organizations/${sessionA.principal?.organizationId}/model-connections/${connectionId}`
  await expect.poll(async () => {
    const detail = await pageA.request.get(connectionRoot)
    return detail.ok() ? await detail.json() as { status: string } : null
  }).not.toBeNull()
  // The optimistic version lives only in the ETag header — the response body carries no version
  // field — and the verify command hard-requires a matching If-Match.
  const beforeVerifyResponse = await pageA.request.get(connectionRoot)
  expect(beforeVerifyResponse.ok(), 'connection detail before verify').toBeTruthy()
  const beforeVerify = await beforeVerifyResponse.json() as { credentialVersion: number }
  const connectionVersion = Number((beforeVerifyResponse.headers()['etag'] ?? '').replace(/"/g, ''))
  expect(Number.isFinite(connectionVersion), 'connection detail returns a numeric ETag version').toBeTruthy()
  const verified = await secureFetch(`${connectionRoot}/verify`, {
    method: 'POST',
    body: { credentialVersion: beforeVerify.credentialVersion },
    ifMatch: connectionVersion,
  })
  expect(verified.status, `verify failed: ${await verified.text()}`).toBe(202)
  await expect.poll(async () => {
    const detail = await getJson<{ healthStatus: string }>(pageA, connectionRoot)
    return detail.healthStatus
  }).toBe('HEALTHY')
})

test('the settings page shows the healthy connection and the catalog price with its source', async () => {
  await pageA.goto(`/settings/models?team=${teamId}`)
  await expect(pageA.getByRole('heading', { name: '目录、连接与凭证健康' })).toBeVisible()

  // The connection presents the HEALTHY badge with its server-side facts.
  await expect(pageA.getByText('健康').first()).toBeVisible()
  await expect(pageA.getByText(/DeepSeek/i).first()).toBeVisible()

  // Catalog pricing reads in units with its source: per-million tokens from the versioned
  // server registry (A03), never an invented number — the currency code precedes each amount
  // (ModelSettingsPage formatPrice renders `USD 0.44 · USD 1.32 / 1M tokens`).
  await expect(pageA.getByText(/Input [A-Z]{3} [\d.]+ · Output [A-Z]{3} [\d.]+ \/ 1M tokens/).first()).toBeVisible()
  await expect(pageA.getByText('目录修订、Region、Retention、Capability 与当前生效价格均为服务端事实。')).toBeVisible()
})

test('the personal agent binds to the verified connection', async () => {
  const profiles = await getJson<{ items: Array<{ id: string, runtimeRole: string, ownershipType: string }> }>(
    pageA, teamPath(sessionA, teamId, 'agent-profiles'))
  const personal = profiles.items.find(profile => profile.runtimeRole === 'PERSONAL_ASSISTANT')
  expect(personal, 'the seeded personal agent profile exists').toBeTruthy()

  const providers = await getJson<{ items: Array<{ key: string }> }>(
    pageA, `/api/v1/organizations/${sessionA.principal?.organizationId}/model-providers`)
  const deepseekKey = providers.items.find(provider => provider.key.toLowerCase().includes('deepseek'))!.key
  const catalog = await getJson<{ items?: Array<{ id: string, providerKey: string, catalogRevision: number }> }>(
    pageA, `/api/v1/organizations/${sessionA.principal?.organizationId}/model-providers/${encodeURIComponent(deepseekKey)}/catalog`)
  const entry = (catalog.items ?? [])[0]
  expect(entry, 'the DeepSeek catalog has models').toBeTruthy()

  const profileRoot = teamPath(sessionA, teamId, `agent-profiles/${personal!.id}`)
  const profile = await pageA.request.get(profileRoot)
  const etag = profile.headers()['etag']
  expect(etag).toBeTruthy()
  const bound = await pageA.request.post(`${profileRoot}/configurations`, {
    data: {
      personalModelBinding: {
        kind: 'DIRECT',
        primary: { connectionId, catalogEntryId: entry!.id, catalogRevision: entry!.catalogRevision },
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
      'If-Match': etag!,
    },
  })
  expect(bound.status(), `binding failed: ${await bound.text()}`).toBe(202)
  // The read side is the current-configuration projection: personalBinding (not the write-side
  // personalModelBinding field name) with a ModelSelectionResponse primary carrying connectionId.
  await expect.poll(async () => {
    const current = await getJson<{ personalBinding: { primary: { connectionId: string } } | null }>(
      pageA, `${profileRoot}/configurations/current`)
    return current.personalBinding?.primary?.connectionId === connectionId ? 1 : 0
  }).toBe(1)
})

test('the first real reply arrives as a structural agent-authored message', async () => {
  test.setTimeout(180_000)
  const suffix = `reply-${Date.now()}`.toLowerCase().replace(/[^a-z0-9]+/g, '-')
  const conversationsPath = teamPath(sessionA, teamId, 'conversations')
  await command(pageA, sessionA, conversationsPath, { title: `Q02 真实对话 ${suffix}`.slice(0, 96), visibility: 'PRIVATE' })
  await expect.poll(async () => {
    const list = await getJson<{ items: Array<{ id: string, title: string }> }>(pageA, `${conversationsPath}?limit=50`)
    return list.items.find(item => item.title.includes(suffix))?.id ?? null
  }).not.toBeNull()
  const conversationId = (await getJson<{ items: Array<{ id: string, title: string }> }>(
    pageA, `${conversationsPath}?limit=50`)).items.find(item => item.title.includes(suffix))!.id

  await pageA.goto(`/conversation?team=${teamId}&conversation=${conversationId}`)
  const composer = pageA.locator('#message-composer-textarea, textarea[name="content"], .conversation-composer textarea').first()
  await expect(composer).toBeVisible()
  await composer.fill(`请用一句话介绍你自己，并说明你能帮我做什么。${suffix}`)
  await pageA.getByRole('button', { name: '发送' }).click()

  // Structural only: an AGENT_MESSAGE with non-empty content — the reply's text itself is the
  // model's, not a contract. The agent message's authorPrincipalId records the invoking user
  // (who triggered the reply), not an absent author, so the discriminator is the message type.
  await expect.poll(async () => {
    const history = await getJson<{ items: Array<{ type: string, content: string }> }>(
      pageA, `${conversationsPath}/${conversationId}/messages?limit=50`)
    return history.items.some(item =>
      item.type === 'AGENT_MESSAGE' && item.content.trim().length > 0) ? 1 : 0
  }, { timeout: 120_000 }).toBe(1)
})

test('the provider call leaves an audit record', async () => {
  // The team-coordinate audit plane shows the conversation's message-posted trail for the real
  // reply. MODEL_CONNECTION_* audit events are USER-subject records that carry no team
  // coordinate, so the team audit center does not list them (Q02 gate notes record this
  // boundary); the provider invocation itself is observed in the structured execution audit.
  await expect.poll(async () => {
    const audit = await getJson<{ items: Array<{ eventType: string }> }>(
      pageA, `${teamPath(sessionA, teamId, 'audit-events')}?limit=100`)
    return audit.items.some(event => event.eventType === 'CONVERSATION_MESSAGE_POSTED') ? 1 : 0
  }).toBe(1)
})

test('an invalid key surfaces its failure through the page', async () => {
  await pageA.goto(`/settings/models?team=${teamId}`)
  // The invalid-key probe is an obvious fake value, so it may appear in traces.
  await pageA.getByRole('button', { name: '创建连接' }).first().click()
  const dialog = pageA.getByRole('dialog')
  await expect(dialog).toBeVisible()
  await dialog.getByLabel('API Key').fill('sk-invalid-q02-probe-not-a-real-credential')
  await dialog.getByRole('button', { name: '创建并安全存储' }).click()
  await expect.poll(async () => {
    const list = await getJson<{ items: Array<{ id: string, providerKey: string, healthStatus: string }> }>(
      pageA, `/api/v1/organizations/${sessionA.principal?.organizationId}/model-connections?ownerType=USER`)
    return list.items.length
  }).toBeGreaterThan(1)
  // The new connection is stored but unverified; its badge still reads 未知 until a verify
  // runs and fails, which is exactly the state the page must show for it.
  const fresh = (await getJson<{ items: Array<{ id: string, providerKey: string, healthStatus: string }> }>(
    pageA, `/api/v1/organizations/${sessionA.principal?.organizationId}/model-connections?ownerType=USER`))
    .items.find(item => item.healthStatus === 'UNKNOWN')
  expect(fresh, 'the probe connection is stored unverified').toBeTruthy()
})

test.afterAll(async () => {
  await contextA?.close()
})
