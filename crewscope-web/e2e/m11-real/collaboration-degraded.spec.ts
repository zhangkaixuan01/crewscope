import { expect, test, type Browser, type BrowserContext, type Page } from '@playwright/test'
import { baseURL, command, currentSession, getJson, onlyTeam, register, teamPath, type Session } from '../real-backend'

/**
 * M11-I01c degradation contract: on a stack with CREWSCOPE_COLLABORATION_REALTIME_ENABLED
 * left at its closed default, the browser sees the collaboration WebSocket fail
 * explicitly (an abnormal 1006 close — the endpoint answers a plain 404, no protocol
 * frames, so presence/typing are visibly unavailable rather than silently hanging),
 * while the durable Team Activity SSE flow keeps carrying authoritative updates
 * end-to-end: a WorkItem created through the REST plane arrives on the open stream.
 * That is the ADR-032 degradation line — the WS channel is an enhancement; the
 * authoritative recovery path never depended on it.
 */

test.describe.configure({ mode: 'serial' })

let context: BrowserContext
let page: Page
let session: Session
let teamId: string

test.beforeAll(async ({ browser }: { browser: Browser }) => {
  const suffix = `i01cd-${Date.now()}`.toLowerCase().replace(/[^a-z0-9]+/g, '-')
  context = await browser.newContext({
    baseURL,
    viewport: { width: 1440, height: 960 },
    timezoneId: 'Asia/Shanghai',
    reducedMotion: 'reduce',
  })
  page = await context.newPage()
  await register(page, `owner-${suffix}`.slice(0, 48), `owner-${suffix}@example.test`, 'I01c Degraded Owner', 'Correct-Horse-Battery-Staple-47', false)
  await expect(page).toHaveURL(/\/onboarding$/)
  await page.getByRole('textbox', { name: '团队名称' }).fill(`I01c 降级 ${suffix}`.slice(0, 96))
  await page.getByRole('button', { name: '创建团队' }).click()
  await expect(page.getByRole('heading', { name: '你的工作入口已经就绪' })).toBeVisible()
  session = await currentSession(page)
  teamId = onlyTeam(session).teamId
})

test.afterAll(async () => {
  await context.close()
})

type SseWindow = {
  source: EventSource
  events: Array<{ eventId: string, eventType: string, category: string, subject: { type: string, id: string } }>
}

test('the closed channel fails the WebSocket handshake explicitly', async () => {
  const outcome = await page.evaluate(() => {
    const url = `${location.origin.replace(/^http/, 'ws')}/api/v1/collaboration/ws`
    return new Promise<{ opened: boolean, frames: string[], closeCode: number | null }>(resolve => {
      const result = { opened: false, frames: [] as string[], closeCode: null as number | null }
      const socket = new WebSocket(url)
      const timeout = window.setTimeout(() => {
        try { socket.close() } catch { /* never opened */ }
        resolve(result)
      }, 15_000)
      socket.onopen = () => { result.opened = true }
      socket.onmessage = message => { result.frames.push(String(message.data)) }
      socket.onclose = event => {
        window.clearTimeout(timeout)
        result.closeCode = event.code
        resolve(result)
      }
      socket.onerror = () => { /* onclose carries the verdict */ }
    })
  })

  // No upgrade, no protocol frames — the channel is explicitly unavailable.
  expect(outcome.opened).toBe(false)
  expect(outcome.frames).toEqual([])
  expect(outcome.closeCode).toBe(1006)
})

test('the SSE flow keeps delivering authoritative updates without the channel', async () => {
  const organizationId = session.principal?.organizationId
  expect(organizationId).toBeTruthy()

  // Open the durable stream first; events created after the open arrive on it.
  // The stream names every frame after its activity event type (the heartbeat frames
  // are "heartbeat"), and a native EventSource only hands *unnamed* frames to
  // onmessage — so the listener must subscribe to the event name itself.
  await page.evaluate((path: string) => {
    return new Promise<void>((resolve, reject) => {
      const holder: SseWindow = { source: null as unknown as EventSource, events: [] }
      ;(window as unknown as Record<string, unknown>)['i01cSse'] = holder
      const source = new EventSource(path)
      holder.source = source
      const guard = window.setTimeout(() => {
        source.close()
        reject(new Error('sse stream did not open within 15s'))
      }, 15_000)
      source.onopen = () => {
        window.clearTimeout(guard)
        resolve()
      }
      source.addEventListener('WORK_ITEM_CREATED', message => {
        holder.events.push(JSON.parse(String((message as MessageEvent).data)))
      })
    })
  }, teamPath(session, teamId, 'activity/events'))

  const suffix = `i01cd-${Date.now()}`.toLowerCase().replace(/[^a-z0-9]+/g, '-')
  const projectsPath = teamPath(session, teamId, 'work-projects')
  await command(page, session, projectsPath, { name: `I01c 降级计划 ${suffix}`.slice(0, 96) })
  const projectId = await waitForItem(page, projectsPath, suffix)
  const workItemsPath = `${projectsPath}/${projectId}/work-items`
  await command(page, session, workItemsPath, {
    type: 'FEATURE',
    title: `I01c 降级载体 ${suffix}`.slice(0, 96),
    description: 'M11-I01c degradation carrier work item',
    priority: 'MEDIUM',
    labels: ['i01c-degraded'],
    dueAt: null,
  })
  const workItemId = await waitForItem(page, workItemsPath, suffix)

  const handle = await page.waitForFunction((itemId: string) => {
    const holder = (window as unknown as Record<string, SseWindow>)['i01cSse']
    return holder?.events.find(event => event.subject?.id === itemId) ?? false
  }, workItemId, { timeout: 30_000 })
  const delivered = await handle.jsonValue()
  expect(delivered, 'the created work item arrived on the SSE stream').not.toBe(false)
  if (delivered === false) throw new Error('unreachable after the arrival assertion')

  expect(delivered.subject.id).toBe(workItemId)
  expect(delivered.eventType).toBeTruthy()
  expect(delivered.category).toBeTruthy()

  await page.evaluate(() => {
    (window as unknown as { i01cSse?: { source: EventSource } }).i01cSse?.source.close()
  })
})

async function waitForItem(page: Page, listPath: string, suffix: string): Promise<string> {
  const deadline = Date.now() + 30_000
  for (;;) {
    const list = await getJson<{ items: Array<{ id: string, name?: string, title?: string }> }>(page, `${listPath}?limit=50`)
    const found = (list.items ?? []).find(item => (item.name ?? item.title ?? '').includes(suffix))?.id
    if (found) return found
    if (Date.now() > deadline) throw new Error(`item with suffix ${suffix} never appeared`)
    await page.waitForTimeout(1000)
  }
}
