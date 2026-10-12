import { exec } from 'node:child_process'
import { promisify } from 'node:util'
import { expect, test, type Browser, type BrowserContext, type Page } from '@playwright/test'
import { baseURL, currentSession, command, getJson, onlyTeam, register, teamPath, type Session } from '../real-backend'

/**
 * M11-Q01 real-stack fault face ⑦ (Redis unavailable): the stack's redis container is
 * restarted underneath a genuinely connected product page — presence is lossy by contract
 * (ADR-032), so the page's channel must ride it out: no error UI, the pill may dip but
 * comes back live once Lettuce reconnects, presence re-lands through the heartbeat
 * refresh, and the member's session survives (the honest post-recovery check — a flushed
 * session store would surface here as a logout, and the matrix row would record the
 * degraded observation shape instead of passing silently).
 *
 * The drill drives `docker restart <project>-redis-1` from Node: same container, same
 * ports, no volume surgery — the closest a single-host outage gets to real.
 */
test.describe.configure({ mode: 'serial' })

const execAsync = promisify(exec)
const REDIS_CONTAINER = process.env.CREWSCOPE_M11Q01_REDIS_CONTAINER ?? 'm11-q01-redis-1'

let context: BrowserContext
let page: Page
let session: Session
let teamId: string
let conversationId: string
let suffix: string

const password = 'Correct-Horse-Battery-Staple-47'

test.beforeAll(async ({ browser }: { browser: Browser }) => {
  suffix = `q01x-${Date.now()}`.toLowerCase().replace(/[^a-z0-9]+/g, '-')
  context = await browser.newContext({
    baseURL,
    viewport: { width: 1440, height: 960 },
    timezoneId: 'Asia/Shanghai',
    reducedMotion: 'reduce',
  })
  page = await context.newPage()
  await register(page, `resilient-${suffix}`.slice(0, 48), `resilient-${suffix}@example.test`, 'Rita Q01 Resilient', password, false)
  await expect(page).toHaveURL(/\/onboarding$/)
  await page.getByRole('textbox', { name: '团队名称' }).fill(`Q01 重启 ${suffix}`.slice(0, 96))
  await page.getByRole('button', { name: '创建团队' }).click()
  await expect(page.getByRole('heading', { name: '你的工作入口已经就绪' })).toBeVisible()
  session = await currentSession(page)
  teamId = onlyTeam(session).teamId

  const conversationsPath = teamPath(session, teamId, 'conversations')
  await command(page, session, conversationsPath, { title: `Q01 重启会话 ${suffix}`.slice(0, 96), visibility: 'TEAM' })
  const conversations = await getJson<{ items: Array<{ id: string, title: string }> }>(page, `${conversationsPath}?limit=50`)
  conversationId = conversations.items.find(item => item.title.includes(suffix))!.id
})

test.afterAll(async () => {
  await context.close()
})

test('a redis restart under a connected page degrades quietly and self-heals', async () => {
  await page.goto(`/conversation?team=${teamId}&conversation=${conversationId}`)
  await expect(page.getByLabel('发送消息')).toBeVisible()
  await expect(page.locator('.collab-pill')).toContainText('实时协作在线')

  // The drill: restart the stack's redis in place (same container, same mapped port).
  const { stdout } = await execAsync(`docker restart ${REDIS_CONTAINER}`)
  expect(stdout.trim()).toBe(REDIS_CONTAINER)

  // Ride-out window (30s): no error UI — the page stays where it was, no global failure
  // surface appears, and the session is not logged out by the flush.
  await page.waitForTimeout(5_000)
  await expect(page).toHaveURL(new RegExp(`/conversation\\?team=${teamId}`))
  const sessionAfter = await currentSession(page)
  expect(sessionAfter.account?.accountId).toBe(session.account?.accountId)

  // Self-healing: once Lettuce reconnects, the heartbeat refresh re-lands presence and
  // the pill returns to its live state. The connection was never punished for the outage.
  await expect(page.locator('.collab-pill')).toContainText('实时协作在线', { timeout: 30_000 })
  await expect(page.locator('.collaboration-strip .presence-group'))
    .toHaveAttribute('aria-label', /Rita Q01 Resilient/, { timeout: 30_000 })

  // And the channel still carries work: a fresh REST read through the same origin answers.
  const conversationsPath = teamPath(session, teamId, 'conversations')
  const after = await getJson<{ items: Array<{ id: string }> }>(page, `${conversationsPath}?limit=50`)
  expect(after.items.some(item => item.id === conversationId)).toBe(true)
})
