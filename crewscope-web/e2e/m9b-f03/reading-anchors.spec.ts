import { expect, test, type Page, type Route } from '@playwright/test'
import { ids, mockA05App, origin, type A05World } from '../m9b-a05/fixtures'

/**
 * M9b-F03 reading anchors（契约 §4.4 / R19）：真实可变高度下的长消息阅读——500 行代码块按实测
 * 高度渲染不跳空；前插更早消息、窗口缩放、刷新恢复都以「首个可见消息 + 像素偏移」锚点保持
 * 阅读位置；距底 ≤80px 才跟随新消息，读历史的人只收到「新消息」入口不被拉底。
 */
const readIds = {
  conversation: '00000000-0000-0000-0000-00000000f341',
}
const TOTAL_MESSAGES = 120
/** The giant message sits inside the initial 50-message window so every test can reach it cheaply. */
const CODE_SEQUENCE = 100
const CODE_LINES = 500
const codeLine = (index: number) => `line-${String(index + 1).padStart(3, '0')}`

interface ReadingMessage {
  id: string
  conversationId: string
  sequence: number
  type: 'USER_MESSAGE' | 'AGENT_MESSAGE' | 'SYSTEM_NOTICE'
  participantId: string | null
  authorPrincipalId: string | null
  content: string
  createdAt: string
}

interface ReadingWorld extends A05World {
  messages: ReadingMessage[]
  pendingFrames: string[]
  gate: { promise: Promise<void>, resolve: () => void }
  eventCounter: number
}

function messageId(sequence: number): string {
  return `f03-read-${sequence}`
}

function messageRow(sequence: number, content: string): ReadingMessage {
  const authored = sequence % 3 === 0
  return {
    id: messageId(sequence),
    conversationId: readIds.conversation,
    sequence,
    type: authored ? 'AGENT_MESSAGE' : 'USER_MESSAGE',
    participantId: 'f03-participant-1',
    authorPrincipalId: authored ? ids.agentPersonalPrincipal : ids.principal,
    content,
    createdAt: new Date(Date.UTC(2026, 7, 8, 2, 0, 0) + sequence * 60_000).toISOString(),
  }
}

function fixtureMessages(): ReadingMessage[] {
  return Array.from({ length: TOTAL_MESSAGES }, (_, index) => {
    const sequence = index + 1
    if (sequence === CODE_SEQUENCE) {
      const code = Array.from({ length: CODE_LINES }, (_, line) => codeLine(line)).join('\n')
      return messageRow(sequence, '下面是这段实现的完整清单：\n\n```java\n' + code + '\n```')
    }
    return messageRow(sequence, `第 ${sequence} 条：固定内容，用于稳定的行高测量。`)
  })
}

function conversationRow() {
  return {
    id: readIds.conversation, organizationId: ids.organization, teamId: ids.team, workspaceId: ids.workspace,
    ownerMemberId: ids.member, ownerPrincipalId: ids.principal, personalAgentPrincipalId: ids.agentPersonalPrincipal,
    title: '阅读锚点验收对话', visibility: 'PRIVATE', status: 'ACTIVE',
    lastMessageSequence: TOTAL_MESSAGES, version: 0,
    createdAt: '2026-08-08T01:00:00Z', updatedAt: '2026-08-08T03:00:00Z',
  }
}

function participants() {
  return [
    {
      id: 'f03-participant-owner', conversationId: readIds.conversation, principalId: ids.principal,
      teamMemberId: ids.member, displayName: '张凯旋', principalType: 'USER',
      ownerPrincipalId: null, ownerDisplayName: null, role: 'OWNER', status: 'ACTIVE',
      joinedByPrincipalId: ids.principal, joinedAt: '2026-08-08T01:00:00Z', leftAt: null, version: 0,
    },
    {
      id: 'f03-participant-agent', conversationId: readIds.conversation, principalId: ids.agentPersonalPrincipal,
      teamMemberId: null, displayName: '张凯旋 · Personal Agent', principalType: 'PERSONAL_AGENT',
      ownerPrincipalId: ids.principal, ownerDisplayName: '张凯旋', role: 'AGENT', status: 'ACTIVE',
      joinedByPrincipalId: ids.principal, joinedAt: '2026-08-08T01:00:00Z', leftAt: null, version: 0,
    },
  ]
}

function freshGate() {
  let resolve!: () => void
  const promise = new Promise<void>(done => { resolve = done })
  return { promise, resolve }
}

function json(route: Route, body: unknown, status = 200) {
  return route.fulfill({ status, json: body })
}

/** Appends a message and releases one SSE frame so the page refreshes history in place. */
function pushMessage(world: ReadingWorld, sequence: number): void {
  world.messages.push(messageRow(sequence, `第 ${sequence} 条：实时到达的新消息。`))
  world.eventCounter += 1
  const envelope = {
    eventId: `f03-evt-${world.eventCounter}`,
    domainEventId: `f03-domain-${world.eventCounter}`,
    streamType: 'CONVERSATION',
    eventType: 'CONVERSATION_MESSAGE_POSTED',
    schemaVersion: '1',
    aggregateType: null,
    aggregateId: null,
    aggregateVersion: null,
    occurredAt: new Date().toISOString(),
  }
  world.pendingFrames.push(`id: f03-cursor-${world.eventCounter}\ndata: ${JSON.stringify(envelope)}\n\n`)
  world.gate.resolve()
}

async function mockReadingWorld(page: Page): Promise<ReadingWorld> {
  const world: ReadingWorld = {
    ...(await mockA05App(page)),
    messages: fixtureMessages(),
    pendingFrames: [],
    gate: freshGate(),
    eventCounter: 0,
  }
  const root = `/api/v1/organizations/${ids.organization}/teams/${ids.team}/conversations`
  const conversationPath = `${root}/${readIds.conversation}`

  await page.route(url => url.pathname === root, route => json(route, { items: [conversationRow()], nextCursor: null }))
  await page.route(url => url.pathname === conversationPath, route => json(route, { conversation: conversationRow(), participants: participants() }))
  // Newest-first page over the authoritative array; `after` is a plain offset cursor like the real API.
  await page.route(url => url.pathname === `${conversationPath}/messages`, route => {
    const urlObject = new URL(route.request().url())
    const limit = Number(urlObject.searchParams.get('limit') ?? 50)
    const after = urlObject.searchParams.get('after')
    const desc = [...world.messages].sort((left, right) => right.sequence - left.sequence)
    const offset = after ? Number(after.replace('f03-read-offset-', '')) : 0
    const items = desc.slice(offset, offset + limit)
    const next = offset + items.length
    return json(route, { items, nextCursor: next < desc.length ? `f03-read-offset-${next}` : null })
  })
  // The durable event stream hangs on a gate the test controls, so a frame is only delivered once
  // the scenario is ready; reconnects re-arm the gate and pick up whatever was pushed meanwhile.
  await page.route(url => url.pathname === `${conversationPath}/events`, async route => {
    if (world.pendingFrames.length === 0) await world.gate.promise
    const frames = world.pendingFrames.splice(0)
    world.gate = freshGate()
    return route.fulfill({
      status: 200, contentType: 'text/event-stream',
      headers: { 'Cache-Control': 'no-store' }, body: frames.join(''),
    })
  })
  await page.route(url => url.pathname === `${conversationPath}/work-items`, route => json(route, []))
  await page.route(url => url.pathname === `${conversationPath}/tasks`, route => json(route, { items: [], nextCursor: null }))
  return world
}

async function openReading(page: Page): Promise<ReadingWorld> {
  const world = await mockReadingWorld(page)
  await page.goto(`${origin}/conversation?team=${ids.team}&project=${ids.project}&conversation=${readIds.conversation}`)
  const history = page.locator('.message-history')
  await expect(history).toBeVisible()
  await expect(page.locator('[data-virtual-key]').first()).toBeVisible()
  // The restore chain lands on the latest messages; wait for the jump to settle before measuring.
  await expect.poll(() => history.evaluate(el => el.scrollHeight - el.scrollTop - el.clientHeight)).toBeLessThanOrEqual(2)
  return world
}

function rowOf(page: Page, sequence: number) {
  return page.locator(`[data-virtual-key="${messageId(sequence)}"]`)
}

/** Row top relative to the scroll container — stable comparisons survive viewport and layout shifts. */
function topEdge(page: Page, sequence: number): Promise<number> {
  return rowOf(page, sequence).evaluate((element, selector) =>
    element.getBoundingClientRect().top - document.querySelector(selector)!.getBoundingClientRect().top, '.message-history')
}

/**
 * Windowed rows render on demand: walk the container towards the row (windowed lists do not mount
 * far-away rows) and then pin it to the viewport top the way a reader would position it.
 */
async function revealRow(page: Page, sequence: number, direction: 'up' | 'down' = 'up'): Promise<void> {
  const history = page.locator('.message-history')
  for (let step = 0; step < 40; step += 1) {
    if (await rowOf(page, sequence).isVisible()) break
    await history.evaluate((element, dir) => {
      const delta = element.clientHeight * 1.5
      element.scrollTop = Math.max(0, Math.min(element.scrollHeight, element.scrollTop + (dir === 'up' ? -delta : delta)))
    }, direction)
    await page.waitForTimeout(80)
  }
  await rowOf(page, sequence).evaluate(element => element.scrollIntoView({ block: 'start' }))
  await page.waitForTimeout(150)
}

test.describe('reading anchors', () => {
  test('a 500-line code message renders at its real height without blank gaps', async ({ page }) => {
    await openReading(page)
    const history = page.locator('.message-history')
    const row = rowOf(page, CODE_SEQUENCE)
    await row.scrollIntoViewIfNeeded()
    await expect(row.getByText(codeLine(CODE_LINES - 1))).toBeVisible()

    // The measured row must occupy real vertical space — an estimated 108px row would blank it out.
    const codeBox = await row.locator('pre').boundingBox()
    expect(codeBox!.height).toBeGreaterThan(3000)

    // Scrolling deep inside the giant block still paints the message itself near the viewport top:
    // the virtual window must not replace the reading position with a spacer. The probe sits inside
    // the code block's left edge and below the container top padding, clear of the jump pill that
    // overlays the stage's top edge (on narrow viewports it covers the first rows).
    // scrollIntoViewIfNeeded only guarantees visibility of a huge row — its top may stay far above
    // the viewport — so the target comes from the <pre>'s absolute offset inside the scroll content.
    const preOffset = await page.evaluate(() => {
      const container = document.querySelector('.message-history') as HTMLElement
      const pre = container.querySelector('pre')!.getBoundingClientRect()
      return pre.top - container.getBoundingClientRect().top + container.scrollTop
    })
    await history.evaluate((element, offset) => { element.scrollTop = offset + 3000 }, preOffset)
    await page.waitForTimeout(150)
    const painted = await page.evaluate(() => {
      const container = document.querySelector('.message-history')!.getBoundingClientRect()
      const pre = document.querySelector('.message-history pre')!.getBoundingClientRect()
      const hit = document.elementFromPoint(pre.left + 80, container.top + 90)
      return hit?.closest('[data-virtual-key]')?.getAttribute('data-virtual-key') ?? null
    })
    expect(painted).toBe(messageId(CODE_SEQUENCE))
  })

  test('prepending older history keeps the reading anchor in place', async ({ page }) => {
    await openReading(page)
    const history = page.locator('.message-history')
    // Reading at the top of the loaded history — exactly where a member sits to load older messages.
    await history.evaluate(el => { el.scrollTop = 0 })
    await page.waitForTimeout(120)
    const before = await topEdge(page, 71)

    // A DOM click keeps the viewport exactly where it is; 50 older messages prepend above.
    await page.getByRole('button', { name: '加载更早消息' }).evaluate(element => (element as HTMLElement).click())
    await expect(page.locator('.message-list .virtual-spacer').first()).toBeVisible()
    // The anchor compensation re-lands on estimated offsets first and converges as rows measure.
    await expect.poll(async () => Math.abs((await topEdge(page, 71)) - before), { timeout: 5000 }).toBeLessThanOrEqual(4)
    await expect(rowOf(page, 71)).toContainText('第 71 条')
  })

  test('resizing the window does not move the reading position', async ({ page }) => {
    await openReading(page)
    await page.getByRole('button', { name: '加载更早消息' }).click()
    await expect(page.locator('.message-list .virtual-spacer').first()).toBeVisible()
    await page.waitForTimeout(300)
    await revealRow(page, 105, 'down')
    const before = await topEdge(page, 105)

    // A different viewport within the same breakpoint: rows reflow and the container resizes —
    // the anchor compensation must absorb both instead of letting the reading position drift.
    // (Crossing the ≤1280 breakpoint swaps the whole workspace layout — that reflow belongs to
    // the responsive-layout work, not to the reading-anchor contract under test here.)
    await page.setViewportSize({ width: 1281, height: 700 })
    await page.waitForTimeout(400)
    const after = await topEdge(page, 105)
    expect(Math.abs(after - before)).toBeLessThanOrEqual(8)
    await expect(rowOf(page, 105)).toContainText('第 105 条')
  })

  test('follows appended messages only within the 80px threshold', async ({ page }) => {
    const world = await openReading(page)
    const history = page.locator('.message-history')

    // At the bottom: an appended message is followed without any member action.
    pushMessage(world, TOTAL_MESSAGES + 1)
    await expect(rowOf(page, TOTAL_MESSAGES + 1)).toBeVisible()
    await expect.poll(() => history.evaluate(el => el.scrollHeight - el.scrollTop - el.clientHeight)).toBeLessThanOrEqual(2)

    // Reading history far above the threshold: two dimensions stay put when a new message lands,
    // and the only affordance is the new-message pill with its jump entry.
    await revealRow(page, 101)
    await expect(page.locator('.latest-jump')).toBeVisible()
    const before = await topEdge(page, 101)
    const scrollTopBefore = await history.evaluate(el => el.scrollTop)

    pushMessage(world, TOTAL_MESSAGES + 2)
    await expect(page.locator('.latest-jump')).toContainText('1 条新消息')
    expect(Math.abs((await history.evaluate(el => el.scrollTop)) - scrollTopBefore)).toBeLessThanOrEqual(1)
    expect(Math.abs((await topEdge(page, 101)) - before)).toBeLessThanOrEqual(2)

    await page.locator('.latest-jump').getByRole('button', { name: '跳到最新' }).click()
    await expect.poll(() => history.evaluate(el => el.scrollHeight - el.scrollTop - el.clientHeight)).toBeLessThanOrEqual(2)
    await expect(rowOf(page, TOTAL_MESSAGES + 2)).toBeVisible()
  })

  test('a reload restores the persisted reading anchor', async ({ page }) => {
    await openReading(page)
    await revealRow(page, 105)
    const before = await topEdge(page, 105)

    await page.reload()
    await expect(page.locator('.message-history')).toBeVisible()
    await expect(rowOf(page, 105)).toBeVisible()
    // The restore lands on estimated offsets first; measured corrections converge onto the anchor.
    await expect.poll(async () => Math.abs((await topEdge(page, 105)) - before), { timeout: 5000 }).toBeLessThanOrEqual(8)
    await expect(rowOf(page, 105)).toContainText('第 105 条')
  })
})
