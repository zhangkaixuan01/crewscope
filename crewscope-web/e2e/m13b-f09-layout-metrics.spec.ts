import { expect, test, type Route } from '@playwright/test'
import { authenticatedSession } from './auth-session'

/**
 * M13b-F09 布局契约的量测入档（可复跑）：数字来自 Review 契约 §7 与 R51，不从实现快照——
 * - 任何列表页首条数据距 context header ≤200px（1440 视口）；
 * - 页面 hero 不得在列表页出现（整卡 hero 收敛为单行 summary strip）；
 * - 扫读工具栏 ≤56px；
 * - 对话区：消息区高度 ≥ workspace 62%、消息列 ≥700px（1440 视口）。
 * 量测值同时 console.log 落进报告，方便入档比对。
 */
const ids = {
  organization: '00000000-4000-4000-8000-000000000001',
  team: '00000000-4000-4000-8000-000000000201',
  project: '00000000-4000-4000-8000-000000000401',
  workspace: '00000000-4000-4000-8000-000000000501',
  principal: '00000000-4000-4000-8000-000000000101',
  member: '00000000-4000-4000-8000-000000000301',
  personalAgent: '00000000-4000-4000-8000-000000000105',
  workItem: '00000000-4000-4000-8000-000000000601',
  conversation: '00000000-0000-0000-0000-000000001101',
  ownership: '00000000-4000-4000-8000-000000000901',
  source: '00000000-4000-4000-8000-000000000951',
}

function conversation(id: string, title: string) {
  return { id, organizationId: ids.organization, teamId: ids.team, workspaceId: ids.workspace, ownerMemberId: ids.member, ownerPrincipalId: ids.principal, personalAgentPrincipalId: ids.personalAgent, title, visibility: 'PRIVATE', status: 'ACTIVE', lastMessageSequence: 4, version: 0, createdAt: '2026-08-08T01:00:00Z', updatedAt: '2026-08-08T03:00:00Z' }
}

function participant(principalId: string, role: string) {
  const personalAgent = role === 'AGENT'
  return {
    id: crypto.randomUUID(), conversationId: ids.conversation, principalId, teamMemberId: personalAgent ? null : ids.member,
    displayName: personalAgent ? '张凯旋 · Personal Agent' : '张凯旋',
    principalType: personalAgent ? 'PERSONAL_AGENT' : 'USER',
    ownerPrincipalId: personalAgent ? ids.principal : null, ownerDisplayName: personalAgent ? '张凯旋' : null,
    role, status: 'ACTIVE', joinedByPrincipalId: ids.principal,
    joinedAt: '2026-08-08T01:00:00Z', leftAt: null, version: 0,
  }
}

function conversationMessage(id: string, sequence: number, type: string, authorPrincipalId: string | null, content: string) {
  return { id, conversationId: ids.conversation, sequence, type, participantId: type === 'SYSTEM_NOTICE' ? null : crypto.randomUUID(), authorPrincipalId, content, createdAt: `2026-08-08T01:1${sequence}:00Z` }
}

function inbox(inboxItemId: string, itemType: string, priority: string, sourceType: string, sourceRevision: number, deadline: string | null) {
  return {
    inboxItemId, itemType, priority, deadline, openedAt: `2026-08-27T0${sourceRevision}:00:00Z`,
    sourceStatus: 'OPEN', closeReason: null, closedAt: null,
    dispositionStatus: 'UNREAD', dispositionVersion: 0, etag: '"0"',
    source: { type: sourceType, id: ids.source, revision: sourceRevision },
  }
}

function fulfillJson(route: Route, value: unknown) {
  return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(value) })
}

function fulfillSse(route: Route, events: unknown[]) {
  const body = events.map((event, index) => {
    const envelope = event as { eventId?: string, eventType?: string }
    return `id:${envelope.eventId ?? `event-${index}`}\nevent:${envelope.eventType ?? 'message'}\ndata:${JSON.stringify(event)}\n\n`
  }).join('')
  return route.fulfill({ status: 200, contentType: 'text/event-stream', headers: { 'Cache-Control': 'no-store' }, body })
}

const conversations = [conversation(ids.conversation, '规划 GitHub Provider 接入')]
const messages = [
  conversationMessage('00000000-0000-0000-0000-000000001301', 1, 'USER_MESSAGE', ids.principal, '**目标**：规划 GitHub Provider 接入。'),
  conversationMessage('00000000-0000-0000-0000-000000001302', 2, 'AGENT_MESSAGE', ids.personalAgent, '已收到。我会先梳理 `Connection`、权限和审计边界。'),
  conversationMessage('00000000-0000-0000-0000-000000001303', 3, 'SYSTEM_NOTICE', null, 'Conversation 已切换为真实消息模式。'),
  conversationMessage('00000000-0000-0000-0000-000000001304', 4, 'USER_MESSAGE', ids.principal, '请保留团队协作与最小权限原则。'),
]
const inboxItems = [
  inbox(ids.ownership, 'OWNERSHIP', 'URGENT', 'RESPONSIBILITY_ASSIGNMENT', 3, '2026-08-27T09:00:00Z'),
  inbox('00000000-4000-4000-8000-000000000902', 'OWNERSHIP', 'NORMAL', 'RESPONSIBILITY_ASSIGNMENT', 2, null),
]

/** Work 页首条量测的最小工作项集合（结构对齐 m9-workdesk 的 workItemSummary）。 */
const workItems = [
  { id: ids.workItem, organizationId: ids.organization, teamId: ids.team, workspaceId: ids.workspace, projectId: ids.project, key: 'CRW-01', type: 'TASK', title: '结算看板主链路', description: '结算看板主链路的协作说明', status: 'IN_PROGRESS', priority: 'HIGH', labels: ['team-work'], dueAt: null, source: 'CREWSCOPE', sourceReference: null, version: 0, createdAt: '2026-09-01T01:00:00Z', createdByPrincipalId: ids.principal, updatedAt: '2026-09-02T01:00:00Z', updatedByPrincipalId: ids.principal, availableActions: [] },
  { id: '00000000-4000-4000-8000-000000000602', organizationId: ids.organization, teamId: ids.team, workspaceId: ids.workspace, projectId: ids.project, key: 'CRW-02', type: 'FEATURE', title: '执行看板迁移', description: '执行看板迁移的协作说明', status: 'OPEN', priority: 'MEDIUM', labels: [], dueAt: null, source: 'CREWSCOPE', sourceReference: null, version: 0, createdAt: '2026-09-01T01:00:00Z', createdByPrincipalId: ids.principal, updatedAt: '2026-09-02T01:00:00Z', updatedByPrincipalId: ids.principal, availableActions: [] },
]

/** Activity 首条量测的最小事件集（结构对齐 m6-activity 的 activity()）。 */
const activityEvents = [
  {
    eventId: 'event-review', domainEventId: 'event-review-domain', teamSequence: 4, eventType: 'REVIEW_APPROVED',
    category: 'REVIEW', visibility: 'TEAM', subject: { type: 'REVIEW_REQUEST', id: 'review-1' },
    actor: { type: 'MEMBER', principalId: ids.principal }, references: [{ type: 'WORK_ITEM', id: ids.workItem }],
    occurredAt: '2026-08-27T08:04:00Z', payload: { schemaName: 'activity-summary', schemaVersion: 1, values: { outcome: 'APPROVED' } },
  },
]

async function mockMetricsApi(page: import('@playwright/test').Page): Promise<void> {
  await page.route(/\/api\/v1\//, async route => {
    const request = route.request()
    const path = new URL(request.url()).pathname
    if (request.method() === 'GET' && path === '/api/v1/auth/session') return fulfillJson(route, authenticatedSession(ids.organization, ids.principal, ids.team))
    if (request.method() === 'GET' && path.endsWith('/teams')) {
      return fulfillJson(route, [{ id: ids.team, organizationId: ids.organization, name: 'Platform Engineering', status: 'ACTIVE', initializationStatus: 'READY', ownerMemberId: ids.member, defaultWorkspaceId: ids.workspace, version: 1 }])
    }
    if (request.method() === 'GET' && path.endsWith(`/${ids.team}/work-projects`)) {
      return fulfillJson(route, { items: [{ id: ids.project, organizationId: ids.organization, teamId: ids.team, workspaceId: ids.workspace, key: 'CRW', name: 'CrewScope', status: 'ACTIVE', version: 1, createdAt: '2026-08-27T07:00:00Z', createdByPrincipalId: ids.principal, updatedAt: '2026-08-27T07:00:00Z', updatedByPrincipalId: ids.principal }], nextCursor: null })
    }
    if (request.method() === 'GET' && path.endsWith('/members')) {
      return fulfillJson(route, [{ id: ids.member, userPrincipalId: ids.principal, displayName: 'Zhang Kaixuan', status: 'ACTIVE', joinMethod: 'CREATED_WITH_TEAM', joinedAt: '2026-08-08T01:00:00Z', version: 0 }])
    }
    if (request.method() === 'GET' && path.endsWith('/inbox/counts')) {
      return fulfillJson(route, { total: 2, unread: 2, byType: {} })
    }
    if (request.method() === 'GET' && path.endsWith('/inbox')) {
      return fulfillJson(route, { items: inboxItems, nextCursor: null })
    }
    if (request.method() === 'GET' && path.endsWith('/work-items')) {
      return fulfillJson(route, { items: workItems, nextCursor: null })
    }
    if (request.method() === 'GET' && path.endsWith('/activity/snapshot')) {
      return fulfillJson(route, { items: activityEvents, hasMore: false, nextCursor: null, snapshotCursor: 'snapshot-cursor' })
    }
    if (request.method() === 'GET' && path.endsWith('/activity/events')) {
      // 补发 snapshot 已有的一条：浏览器按耐久 Cursor 去重（m6-activity 同款语义）。
      return fulfillSse(route, activityEvents)
    }
    const inboxDetail = path.match(/\/inbox\/([^/]+)$/)
    if (request.method() === 'GET' && inboxDetail) return fulfillJson(route, inboxItems.find(item => item.inboxItemId === inboxDetail[1]))
    if (request.method() === 'GET' && path.endsWith('/conversations')) {
      return fulfillJson(route, { items: conversations, nextCursor: null })
    }
    const detail = path.match(/\/conversations\/([^/]+)$/)
    if (request.method() === 'GET' && detail) {
      return fulfillJson(route, { conversation: conversations[0], participants: [participant(ids.principal, 'OWNER'), participant(ids.personalAgent, 'AGENT')] })
    }
    const messageList = path.match(/\/conversations\/([^/]+)\/messages$/)
    if (request.method() === 'GET' && messageList) {
      return fulfillJson(route, { items: [...messages].sort((left, right) => right.sequence - left.sequence), nextCursor: null })
    }
    const conversationEvents = path.match(/\/conversations\/([^/]+)\/events$/)
    if (request.method() === 'GET' && conversationEvents) return fulfillSse(route, [])
    const conversationTasks = path.match(/\/conversations\/([^/]+)\/tasks$/)
    if (request.method() === 'GET' && conversationTasks) return fulfillJson(route, { items: [], nextCursor: null })
    const conversationWorkItems = path.match(/\/conversations\/([^/]+)\/work-items$/)
    if (request.method() === 'GET' && conversationWorkItems) return fulfillJson(route, [])
    return route.fulfill({ status: 404, contentType: 'application/json', body: JSON.stringify({ code: 'not_found', message: 'not_found', correlationId: crypto.randomUUID(), retryable: false, details: {} }) })
  })
}

test.beforeEach(async ({ page }) => {
  await page.clock.setFixedTime(new Date('2026-08-27T08:30:00Z'))
  await mockMetricsApi(page)
})

/** F09 契约 §7：列表页首条数据距 context header ≤200px；strip 与工具栏 ≤56px；hero 不再出现。 */
test('inbox scan layout meets the F09 contracts at the desktop viewport', async ({ page }, testInfo) => {
  test.skip(testInfo.project.name !== 'desktop-chromium', '契约 §7 的量测视口是 1440 宽桌面')
  await page.goto(`/inbox?team=${ids.team}&project=${ids.project}&inboxItem=${ids.ownership}`)

  const headerBox = await page.locator('.context-header').boundingBox()
  const stripBox = await page.locator('.summary-strip').boundingBox()
  const toolbarBox = await page.locator('.scan-toolbar').boundingBox()
  const firstItemBox = await page.locator('.inbox-list article').first().boundingBox()
  expect(headerBox && stripBox && toolbarBox && firstItemBox).toBeTruthy()

  const firstItemDistance = firstItemBox!.y - (headerBox!.y + headerBox!.height)
  // 量测值入档：跑报告里留数字，断言只对契约阈值负责。
  console.log(`[f09-metrics] header=${headerBox!.height}px strip=${stripBox!.height}px toolbar=${toolbarBox!.height}px first-item-distance=${firstItemDistance}px`)
  expect(stripBox!.height).toBeLessThanOrEqual(56)
  expect(toolbarBox!.height).toBeLessThanOrEqual(56)
  expect(firstItemDistance).toBeLessThanOrEqual(200)
})

/** F10（L08）：Work 工具栏压进 56px 后，置顶条是否吞掉首行预算由量测裁定——首条 ≤200px。 */
test('work scan layout meets the scan contracts at the desktop viewport', async ({ page }, testInfo) => {
  test.skip(testInfo.project.name !== 'desktop-chromium', '契约 §7 的量测视口是 1440 宽桌面')
  await page.goto(`/work?team=${ids.team}&project=${ids.project}`)
  await expect(page.locator('.work-item-card').first()).toBeVisible()

  const headerBox = await page.locator('.context-header').boundingBox()
  const toolbarBox = await page.locator('.work-toolbar').boundingBox()
  const pinsBox = await page.locator('.work-pins').boundingBox()
  const firstItemBox = await page.locator('.work-item-card').first().boundingBox()
  expect(headerBox && toolbarBox && firstItemBox).toBeTruthy()

  const firstItemDistance = firstItemBox!.y - (headerBox!.y + headerBox!.height)
  console.log(`[f10-metrics] work toolbar=${toolbarBox!.height}px pins=${pinsBox ? `${pinsBox.height}px` : 'none'} first-item-distance=${firstItemDistance}px`)
  expect(toolbarBox!.height).toBeLessThanOrEqual(56)
  expect(firstItemDistance).toBeLessThanOrEqual(200)
})

/** F10（L05）：Activity 三层页头合一后，首条事实距 context header ≤200px。 */
test('activity scan layout meets the scan contracts at the desktop viewport', async ({ page }, testInfo) => {
  test.skip(testInfo.project.name !== 'desktop-chromium', '契约 §7 的量测视口是 1440 宽桌面')
  await page.goto(`/activity?team=${ids.team}&project=${ids.project}`)
  await expect(page.locator('.activity-list article').first()).toBeVisible()
  // 连接态提示已收敛进 strip（L05），流内不再插恢复 panel——首条事实的位置是稳态量。
  await expect(page.locator('.activity-stream .state-panel')).toHaveCount(0)

  const headerBox = await page.locator('.context-header').boundingBox()
  const stripBox = await page.locator('.summary-strip').boundingBox()
  const toolbarBox = await page.locator('.scan-toolbar').boundingBox()
  const firstItemBox = await page.locator('.activity-list article').first().boundingBox()
  expect(headerBox && stripBox && toolbarBox && firstItemBox).toBeTruthy()

  const firstItemDistance = firstItemBox!.y - (headerBox!.y + headerBox!.height)
  const segments = await page.evaluate(() => {
    const pick = (selector: string) => {
      const element = document.querySelector(selector)
      return element ? { top: Math.round(element.getBoundingClientRect().top), bottom: Math.round(element.getBoundingClientRect().bottom) } : null
    }
    return {
      strip: pick('.summary-strip'), toolbar: pick('.scan-toolbar'),
      stream: pick('.activity-stream'), list: pick('.activity-list'),
    }
  })
  // 流组件内部页头已删（L05），实时徽标只出现在 strip 上。
  console.log(`[f10-metrics] activity strip=${stripBox!.height}px toolbar=${toolbarBox!.height}px first-item-distance=${firstItemDistance}px segments=${JSON.stringify(segments)}`)
  expect(page.locator('.activity-stream__header')).toHaveCount(0)
  expect(stripBox!.height).toBeLessThanOrEqual(56)
  expect(toolbarBox!.height).toBeLessThanOrEqual(56)
  expect(firstItemDistance).toBeLessThanOrEqual(200)
})

/** R51：对话区把空间还给消息列——消息区 ≥ workspace 62%、消息列 ≥700px（1440 视口）。 */
test('conversation workspace gives the message column the R51 space budget', async ({ page }, testInfo) => {
  test.skip(testInfo.project.name !== 'desktop-chromium', 'R51 的量测视口是 1440 宽桌面')
  await page.goto(`/conversation?team=${ids.team}&project=${ids.project}&conversation=${ids.conversation}`)
  await expect(page.locator('.message-history')).toBeVisible()

  const workspaceBox = await page.locator('.conversation-workspace').boundingBox()
  const historyBox = await page.locator('.message-history').boundingBox()
  const listBox = await page.locator('.message-list').boundingBox()
  expect(workspaceBox && historyBox && listBox).toBeTruthy()

  const historyRatio = historyBox!.height / workspaceBox!.height
  console.log(`[f09-metrics] workspace=${workspaceBox!.height}px history=${historyBox!.height}px ratio=${historyRatio.toFixed(3)} message-list=${listBox!.width}px`)
  expect(historyRatio).toBeGreaterThanOrEqual(0.62)
  expect(listBox!.width).toBeGreaterThanOrEqual(700)
})

/** 窄视口（390）：strip 与工具栏换行后仍锁在视口内，不产生横向溢出。 */
test('scan surfaces stay inside the phone viewport after wrapping', async ({ page }, testInfo) => {
  test.skip(testInfo.project.name !== 'narrow-chromium', '换行量测只对手机视口有意义')
  await page.goto(`/inbox?team=${ids.team}&project=${ids.project}`)

  await expect(page.locator('.summary-strip')).toBeVisible()
  await expect(page.locator('.scan-toolbar')).toBeVisible()
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth)
  console.log(`[f09-metrics] narrow-overflow=${overflow}px`)
  expect(overflow).toBeLessThanOrEqual(1)
})
