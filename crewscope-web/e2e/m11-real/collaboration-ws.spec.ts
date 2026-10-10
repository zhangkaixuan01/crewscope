import { expect, test, type Browser, type BrowserContext, type Page } from '@playwright/test'
import { baseURL, currentSession, onlyTeam, register, type Session } from '../real-backend'

/**
 * M11-I01c channel-open browser contract: through the four-service Compose stack's
 * published Nginx entry point (upgrade routing included), a real browser session
 * handshakes the collaboration WebSocket on its own origin, runs the I01 product
 * protocol — welcome, subscribe with its onboarding Team coordinates, subscribed ack,
 * server ping answered with pong — and closes cleanly. This is the deployment-level
 * proof the loopback integration tests cannot give: session cookies, CSRF posture and
 * the upgrade hop all behave as browsers actually see them.
 */

test.describe.configure({ mode: 'serial' })

let context: BrowserContext
let page: Page
let session: Session
let teamId: string

test.beforeAll(async ({ browser }: { browser: Browser }) => {
  const suffix = `i01c-${Date.now()}`.toLowerCase().replace(/[^a-z0-9]+/g, '-')
  context = await browser.newContext({
    baseURL,
    viewport: { width: 1440, height: 960 },
    timezoneId: 'Asia/Shanghai',
    reducedMotion: 'reduce',
  })
  page = await context.newPage()
  await register(page, `owner-${suffix}`.slice(0, 48), `owner-${suffix}@example.test`, 'I01c Owner', 'Correct-Horse-Battery-Staple-47', false)
  await expect(page).toHaveURL(/\/onboarding$/)
  await page.getByRole('textbox', { name: '团队名称' }).fill(`I01c 协作 ${suffix}`.slice(0, 96))
  await page.getByRole('button', { name: '创建团队' }).click()
  await expect(page.getByRole('heading', { name: '你的工作入口已经就绪' })).toBeVisible()
  session = await currentSession(page)
  teamId = onlyTeam(session).teamId
})

test.afterAll(async () => {
  await context.close()
})

type WsOutcome = {
  opened: boolean
  welcome: { connectionId: string, heartbeatIntervalSeconds: number, presenceTtlSeconds: number } | null
  subscribed: { subscriptionId: string, scope: Record<string, string> } | null
  pings: number
  errors: Array<{ code: string }>
  closeCode: number | null
}

test('the real browser rides the collaboration WebSocket through Nginx', async () => {
  const organizationId = session.principal?.organizationId
  expect(organizationId).toBeTruthy()

  const outcome = await page.evaluate(({ organizationId: organization, team }: { organizationId: string, team: string }) => {
    const url = `${location.origin.replace(/^http/, 'ws')}/api/v1/collaboration/ws`
    return new Promise<WsOutcome>(resolve => {
      const result: WsOutcome = {
        opened: false, welcome: null, subscribed: null, pings: 0, errors: [], closeCode: null,
      }
      const timeout = window.setTimeout(() => {
        try { socket.close() } catch { /* never opened */ }
        resolve(result)
      }, 60_000)
      const socket = new WebSocket(url)
      socket.onopen = () => { result.opened = true }
      socket.onmessage = message => {
        const frame = JSON.parse(String(message.data)) as Record<string, unknown>
        if (frame.type === 'welcome') {
          result.welcome = frame as unknown as WsOutcome['welcome']
          socket.send(JSON.stringify({ type: 'subscribe', scope: { organization, team } }))
        } else if (frame.type === 'subscribed') {
          result.subscribed = frame as unknown as WsOutcome['subscribed']
        } else if (frame.type === 'ping') {
          result.pings += 1
          socket.send(JSON.stringify({ type: 'pong' }))
          // A second ping after the first pong proves the connection survived a full
          // heartbeat round — the handshake, the subscribe and the pong all held.
          if (result.pings >= 2 && result.subscribed) socket.close(1000, 'spec complete')
        } else if (frame.type === 'error') {
          result.errors.push(frame as unknown as { code: string })
        }
      }
      socket.onclose = event => {
        window.clearTimeout(timeout)
        result.closeCode = event.code
        resolve(result)
      }
      socket.onerror = () => { /* onclose carries the verdict */ }
    })
  }, { organizationId: organizationId!, team: teamId })

  expect(outcome.errors).toEqual([])
  expect(outcome.opened).toBe(true)
  expect(outcome.welcome?.connectionId).toBeTruthy()
  expect(outcome.welcome?.heartbeatIntervalSeconds).toBe(1)
  expect(outcome.welcome?.presenceTtlSeconds).toBeGreaterThan(0)
  expect(outcome.subscribed?.subscriptionId).toBeTruthy()
  expect(outcome.subscribed?.scope).toEqual({ organization: organizationId, team: teamId })
  expect(outcome.pings).toBeGreaterThanOrEqual(2)
  expect(outcome.closeCode).toBe(1000)
})
