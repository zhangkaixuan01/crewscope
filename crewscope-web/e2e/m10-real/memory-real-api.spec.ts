import { expect, test, type BrowserContext, type Page } from '@playwright/test'
import { baseURL, currentSession, onlyTeam, register, teamPath, type Session } from '../real-backend'

/**
 * M10-F01c real-stack contract: the agent memory view and its clear drive the real I02a API end
 * to end — no Vite server, no HTTP mocks. This covers what the mocked matrix structurally cannot
 * prove: the real wire shape of the naturally unconfigured view of the default Personal Agent,
 * the real policy-independent clear (structural idempotency, generation advance persisted in the
 * real store), and the cross-tenant-shaped 404 of the injection references read.
 *
 * The healthy/degraded branches are not reachable through the public API on this stack, by two
 * independent gates verified in the backend sources: appending a memoryPolicy requires the
 * template's KNOWLEDGE_SCOPE configurable slot (no built-in template declares it — the UI hides
 * the policy picker for the same reason), and any personal-scope binding must be DIRECT against
 * a verified model connection, which this stack does not have. The three-state matrix over the
 * DEFAULT policy catalog is therefore covered by the mocked spec (m10-f01c-injection-memory);
 * the same honesty applies to the injection plane — no model connection means no sealed manifest
 * (the real manifest loop belongs to Q02), so the coverage here is the same-shape 404.
 *
 * Lifecycle is serial: each test hands the profile to the next one.
 */

type ProfileSummary = { id: string, ownershipType: string, defaultProfile: boolean, currentConfigurationRevision: number | null }
type MemoryView = { policyReference: { policyId: string, version: number } | null, policy: unknown, degraded: string | null, clearanceGeneration: number, entries: unknown[], entryCount: number }
type AgentClearance = { clearedCount: number, clearanceGeneration: number }

test.describe.configure({ mode: 'serial' })

let context: BrowserContext
let page: Page
let session: Session
let teamId: string
let profileId: string

const profilesRoot = () => teamPath(session, teamId, 'agent-profiles')

test('the onboarding owner reads the naturally unconfigured memory of the default Personal Agent', async ({ browser }, testInfo) => {
  const suffix = `m10c-${Date.now()}`.toLowerCase().replace(/[^a-z0-9]+/g, '-')
  context = await browser.newContext({
    baseURL,
    viewport: testInfo.project.name.includes('Narrow') ? { width: 390, height: 844 } : { width: 1440, height: 960 },
    timezoneId: 'Asia/Shanghai',
    reducedMotion: 'reduce',
  })
  page = await context.newPage()

  await register(page, `owner-${suffix}`.slice(0, 48), `owner-${suffix}@example.test`, 'F01c Owner', 'Correct-Horse-Battery-Staple-47', false)
  await expect(page).toHaveURL(/\/onboarding$/)
  await page.getByRole('textbox', { name: '团队名称' }).fill(`F01c ${suffix}`.slice(0, 96))
  await page.getByRole('button', { name: '创建团队' }).click()
  await expect(page.getByRole('heading', { name: '你的工作入口已经就绪' })).toBeVisible()

  session = await currentSession(page)
  teamId = onlyTeam(session).teamId

  const directory = (await page.request.get(profilesRoot())).json() as Promise<{ items: ProfileSummary[] }>
  const personal = (await directory).items.find(item => item.ownershipType === 'USER')
  expect(personal).toBeDefined()
  profileId = personal!.id

  await page.goto(`/settings/agents?team=${teamId}&agent=${profileId}`)
  const region = page.getByRole('region', { name: '辅助记忆' })
  await expect(region).toBeVisible()
  // A fresh Team seeds no memoryPolicy: the switch is genuinely off, so no clearing entry exists.
  await expect(region.getByText('未启用辅助记忆')).toBeVisible()
  await expect(region.getByRole('button', { name: '清除我的辅助记忆' })).toBeHidden()

  // The real wire shape of the unconfigured branch: no reference, no policy, zero generation,
  // zero entries — a read never creates the owner row (contract: viewing is not using).
  const initial = await getMemoryView()
  expect(initial).toEqual({ policyReference: null, policy: null, degraded: null, clearanceGeneration: 0, entries: [], entryCount: 0 })
})

test('the real clear is policy-independent, structurally idempotent and advances the generation', async () => {
  // While unconfigured the UI honestly hides the clear entry (nothing was ever written to
  // clear), but the I02a clear itself is policy-independent: it works on the owner key alone.
  // The confirmation-gated UI loop lives in the mocked spec; here the page-out commands prove
  // the real receipts, the real store persistence and the header-less structural idempotency.
  await page.goto(`/settings/agents?team=${teamId}&agent=${profileId}`)
  const region = page.getByRole('region', { name: '辅助记忆' })
  await expect(region.getByRole('button', { name: '清除我的辅助记忆' })).toBeHidden()

  const first = await clearMyMemory()
  // No model ever wrote on this stack: zero entries cleared is the honest first receipt, and a
  // fresh owner row starts at generation 0 before the bump.
  expect(first).toEqual({ clearedCount: 0, clearanceGeneration: 1 })

  // The structural replay clears zero entries again and still advances the generation.
  const second = await clearMyMemory()
  expect(second).toEqual({ clearedCount: 0, clearanceGeneration: first.clearanceGeneration + 1 })

  // Both receipts are the real store's truth, not a page projection.
  const view = await getMemoryView()
  expect(view.entryCount).toBe(0)
  expect(view.clearanceGeneration).toBe(second.clearanceGeneration)

  // The real clears change nothing about the honest unconfigured card.
  await page.goto(`/settings/agents?team=${teamId}&agent=${profileId}`)
  await expect(region.getByText('未启用辅助记忆')).toBeVisible()
  await expect(region.getByRole('button', { name: '清除我的辅助记忆' })).toBeHidden()
})

test('an unknown execution answers the cross-tenant-shaped 404 on the injection references read', async () => {
  const unknownTask = crypto.randomUUID()
  const unknownExecution = crypto.randomUUID()
  const read = await page.request.get(teamPath(session, teamId, `tasks/${unknownTask}/attempts/${unknownExecution}/injection-references`))
  expect(read.status()).toBe(404)
  expect(((await read.json()) as { code: string }).code).toBe('aggregate_not_found')

  // The feedback POST anchors on the same execution coordinate, so it answers the same shape —
  // not the 422 the sealed-manifest union would give for a real execution.
  const feedback = await page.request.post(teamPath(session, teamId, `tasks/${unknownTask}/attempts/${unknownExecution}/injection-references/feedback`), {
    data: { type: 'KNOWLEDGE_ENTRY', sourceId: 'runbook', version: '1', contentHash: 'a'.repeat(64) },
    headers: { [session.csrf.headerName]: session.csrf.token },
  })
  expect(feedback.status()).toBe(404)
  expect(((await feedback.json()) as { code: string }).code).toBe('aggregate_not_found')

  await context.close()
})

/** The member's own memory view, read page-out against the real API (no negotiation headers). */
async function getMemoryView(): Promise<MemoryView> {
  const response = await page.request.get(`${profilesRoot()}/${profileId}/memory`)
  expect(response.status(), await response.text()).toBe(200)
  return response.json() as Promise<MemoryView>
}

/** The I02a clear rides structural idempotency: no Idempotency-Key, no If-Match (contract §4). */
async function clearMyMemory(): Promise<AgentClearance> {
  const response = await page.request.delete(`${profilesRoot()}/${profileId}/memory`, {
    headers: { [session.csrf.headerName]: session.csrf.token },
  })
  expect(response.status(), await response.text()).toBe(200)
  return response.json() as Promise<AgentClearance>
}
