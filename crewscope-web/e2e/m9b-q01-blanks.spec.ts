import { expect, test, type Page, type Route } from '@playwright/test'
import { authenticatedSession } from './auth-session'
import { ids, mockApi } from './menu-walk'

/**
 * M9b-Q01 五个 e2e 空白域的补位 spec（主计划 §9.1 与 Q01 计划分档裁定）：
 *
 * 1. **IME 组合输入** —— 命令面板消费 useImeGuard（组合中的 Enter 是候选确认，不是提交）；
 *    ConversationComposer 的 isComposing 分支由组件单测补位（见 src/components/domain/
 *    ConversationComposer.spec.ts 的 Q01 用例），两层各覆盖一个真实消费点。
 * 2. **跨午夜/时间可信** —— RelativeTime 的 60s tick 必须真的滚动标签（R32）。分钟差滚动
 *    与跨午夜同构（formatRelativeTime 无日期特判），小时边界翻转在这里用浏览器时钟证明；
 *    weekday/绝对日期分支由 RelativeTime.spec.ts 覆盖。
 * 3. **投影延迟** —— 命令提交后的第一次列表读取仍可能是旧投影（读侧落后写侧）：不得
 *    出现幽灵条目或错误面，重试收敛后新事实可见。
 * 4. **双窗口同账号** —— F05 视图存储是同账号单 scope（最后写赢、无跨 tab 主动同步）：
 *    A 窗口存的视图 B 窗口 reload 可见，B 的保存不得清掉 A 的条目。
 * 5. **Provider 自定义 Endpoint** —— 无新增浏览器用例（Q01 裁定）：Endpoint 由服务端
 *    Provider 定义、刻意不进浏览器（model/types.ts 公开投影注释），创建 payload 的键集合
 *    白名单断言已在 app-shell.spec.ts 的 model-connections POST mock 固定；重定向不跟随等
 *    Provider 行为由后端 OpenAiCompatibleModelProviderHealthProbeTest 覆盖，真实 Provider
 *    链路归 M9b-Q02。
 */

test.describe.configure({ timeout: 60_000 })

// ---------------------------------------------------------------------------
// 1. IME composition: the palette must not treat candidate-confirmation Enter as submit.
// ---------------------------------------------------------------------------

test('IME 组合中的 Enter 不会提交命令面板搜索', async ({ page }) => {
  await mockApi(page)
  await page.goto(`/today?team=${ids.team}`)
  await expect(page.locator('.app-shell')).toBeVisible()

  await page.getByRole('button', { name: '打开命令面板，搜索工作、成员或 Agent' }).click()
  const dialog = page.getByRole('dialog', { name: '命令面板' })
  await expect(dialog).toBeVisible()
  const urlBefore = page.url()

  // Composition session: type pinyin, the Enter that confirms the candidate carries
  // isComposing — dispatch it synthetically because a real IME cannot run headless.
  const query = dialog.getByRole('searchbox')
  await query.focus()
  await query.type('gongzuo', { delay: 5 })
  await query.dispatchEvent('compositionstart')
  await query.dispatchEvent('keydown', { key: 'Enter', code: 'Enter', isComposing: true })
  // The palette is still open on the same page: the candidate Enter neither navigated
  // (R20 commands would change the URL) nor closed the dialog.
  await expect(dialog).toBeVisible()
  expect(page.url(), 'IME Enter 不得触发命令提交导航').toBe(urlBefore)

  await query.dispatchEvent('compositionend')
  await page.keyboard.press('Escape')
  await expect(dialog).not.toBeVisible()
})

// ---------------------------------------------------------------------------
// 2. Relative time: the 60s tick must roll the label across the hour boundary.
// ---------------------------------------------------------------------------

const FIXNOW = new Date('2026-08-27T08:30:00Z')

test('相对时间标签跨小时边界随 tick 滚动而不是冻结', async ({ page }) => {
  // pauseAt（而不是 setFixedTime）安装假时钟并暂停：后面的 fastForward 才会驱动
  // RelativeTime 的 60s interval，setFixedTime 只冻结读数、timers 仍走真实节奏。
  await page.clock.pauseAt(FIXNOW)
  await mockActivityApi(page, '2026-08-27T07:31:00Z')
  await page.goto(`/activity?team=${ids.team}&project=${ids.project}`)
  // RelativeTime 的 tick 只在可见 tab 上运行（hidden 时暂停，R32 语义）——先钉住这个前提。
  await page.bringToFront()
  expect(await page.evaluate(() => document.visibilityState)).toBe('visible')
  // 08:30 - 07:31 = 59 minutes: the label starts one tick before the hour boundary.
  await expect(page.getByRole('list', { name: '团队活动列表' })).toContainText('59 分钟前')

  // Two ticks forward = 61 minutes after the event. The label must flip to 小时档,
  // proving the interval recomputes instead of freezing at first render.
  await page.clock.fastForward(120_000)
  await expect.poll(async () =>
    page.getByRole('list', { name: '团队活动列表' }).textContent(), { timeout: 10_000 },
  ).toContain('1 小时前')
})

// ---------------------------------------------------------------------------
// 3. Projection lag: the first list read after a committed create may still be the old
//    projection (read side trails the write side). No ghost entry, no error surface —
//    the retry loop converges on the second read.
// ---------------------------------------------------------------------------

test('创建对话后的第一次列表读取返回旧投影时不产生幽灵条目', async ({ page }) => {
  const world = {
    conversations: [summary('00000000-0000-0000-0000-000000000301', '已有对话', 2)],
    created: null as string | null,
    staleReads: 0,
  }
  await mockConversationApi(page, world)
  await page.goto(`/conversation?team=${ids.team}&project=${ids.project}`)
  await expect(page.getByRole('button', { name: '打开对话 已有对话' })).toBeVisible()

  await page.getByRole('button', { name: '新建对话', exact: true }).first().click()
  const dialog = page.getByRole('dialog', { name: '新建对话' })
  await dialog.getByLabel('标题').fill('投影延迟下的新对话')
  await dialog.getByRole('button', { name: '创建对话' }).click()

  // The command committed (dialog closed); the very next list read still misses it. The
  // page must neither duplicate the existing entry nor surface an error — and once the
  // projection catches up the new conversation is the open one. 「打开对话 …」按钮只在
  // 桌面列表可见（窄屏列表收起），幽灵断言因此是「至多一次」而不是恰好一次。
  await expect(dialog).toBeHidden()
  await expect.poll(async () =>
    await page.getByRole('button', { name: '打开对话 已有对话' }).count(),
  ).toBeLessThanOrEqual(1)
  const createdHeading = page.getByRole('heading', { name: '投影延迟下的新对话', exact: true, level: 2 })
  await expect(createdHeading).toBeVisible()
  await expect.poll(async () =>
    await page.getByRole('button', { name: '打开对话 投影延迟下的新对话' }).count(),
  ).toBeLessThanOrEqual(1)
  expect(new URL(page.url()).searchParams.get('conversation')).toBeTruthy()
  expect(world.staleReads).toBeGreaterThanOrEqual(1)

  function summary(id: string, title: string, lastMessageSequence: number | null) {
    return { id, organizationId: ids.organization, teamId: ids.team, workspaceId: ids.workspace,
      ownerMemberId: 'member-1', ownerPrincipalId: ids.principal, personalAgentPrincipalId: null,
      title, visibility: 'TEAM', status: 'ACTIVE', lastMessageSequence, version: 0,
      createdAt: '2026-08-08T01:00:00Z', updatedAt: '2026-08-08T03:00:00Z' }
  }

  async function mockConversationApi(page: Page, state: typeof world): Promise<void> {
    const creations = new Map<string, { receipt: Record<string, unknown>, result: Record<string, unknown> }>()
    await page.route(/\/api\/v1\//, async route => {
      const request = route.request()
      const path = new URL(request.url()).pathname
      if (request.method() === 'GET' && path === '/api/v1/auth/session') return fulfillJson(route, authenticatedSession(ids.organization, ids.principal, ids.team))
      if (request.method() === 'GET' && path.endsWith('/teams')) return fulfillJson(route, [{
        id: ids.team, organizationId: ids.organization, name: 'Platform Engineering', status: 'ACTIVE',
        initializationStatus: 'READY', ownerMemberId: 'member-1', defaultWorkspaceId: ids.workspace, version: 1,
      }])
      if (request.method() === 'GET' && path.endsWith(`/${ids.team}/work-projects`)) return fulfillJson(route, { items: [{
        id: ids.project, organizationId: ids.organization, teamId: ids.team, workspaceId: ids.workspace,
        key: 'CRW', name: 'CrewScope', status: 'ACTIVE', version: 1,
        createdAt: '2026-08-27T07:00:00Z', createdByPrincipalId: ids.principal,
        updatedAt: '2026-08-27T07:00:00Z', updatedByPrincipalId: ids.principal,
      }], nextCursor: null })
      if (request.method() === 'GET' && path.endsWith('/command-results')) {
        const found = creations.get(request.headers()['idempotency-key'] ?? '')
        if (!found) return notFound(route)
        return fulfillJson(route, found)
      }
      if (request.method() === 'GET' && /\/conversations\/[^/]+$/.test(path)) {
        const found = state.conversations.find(item => item.id === path.split('/').pop())
          ?? (state.created ? summary(state.created, '投影延迟下的新对话', null) : null)
        if (!found) return notFound(route)
        // createAndLocate 校验 resource.conversation.id 与 command-results 坐标一致，
        // 详情响应是聚合包装形态（同 app-shell 的 detail mock）。
        return fulfillJson(route, {
          conversation: found,
          participants: [{
            id: '00000000-0000-0000-0000-000000000901', conversationId: found.id,
            principalId: ids.principal, teamMemberId: 'member-1', displayName: '张凯旋',
            principalType: 'USER', ownerPrincipalId: null, ownerDisplayName: null,
            role: 'OWNER', status: 'ACTIVE', joinedByPrincipalId: ids.principal,
            joinedAt: '2026-08-08T01:00:00Z', leftAt: null, version: 0,
          }],
        })
      }
      if (request.method() === 'GET' && /\/conversations\/[^/]+\/(messages|participants|events)/.test(path)) {
        if (path.endsWith('/events')) return fulfillSse(route, [])
        return fulfillJson(route, path.endsWith('/messages') ? { items: [], nextCursor: null } : [])
      }
      if (request.method() === 'GET' && path.endsWith('/conversations')) {
        const created = state.created ? summary(state.created, '投影延迟下的新对话', null) : null
        // Projection lag: exactly one read after the create still returns the old page.
        if (created && state.staleReads === 0) {
          state.staleReads += 1
          return fulfillJson(route, { items: state.conversations, nextCursor: null })
        }
        return fulfillJson(route, { items: created ? [...state.conversations, created] : state.conversations, nextCursor: null })
      }
      if (request.method() === 'POST' && path.endsWith('/conversations')) {
        const input = request.postDataJSON() as { title: string, visibility: string }
        const id = crypto.randomUUID()
        state.created = id
        creations.set(request.headers()['idempotency-key']!, {
          receipt: { commandId: crypto.randomUUID(), domainEventId: crypto.randomUUID(), committedVersion: 0, correlationId: crypto.randomUUID() },
          result: { organizationId: ids.organization, teamId: ids.team, projectId: ids.project, type: 'CONVERSATION', resourceId: id, committedVersion: 0, stage: 'COMMITTED' },
        })
        return route.fulfill({ status: 202, contentType: 'application/json', body: JSON.stringify({ commandId: crypto.randomUUID(), domainEventId: crypto.randomUUID(), committedVersion: 0, correlationId: crypto.randomUUID() }) })
      }
      return notFound(route)
    })
  }
})

// ---------------------------------------------------------------------------
// 4. Two windows of the same account: saved views share one localStorage scope
//    (last-write-wins, no cross-tab sync) — a view saved in window A shows up in
//    window B after reload, and window B's own save never clobbers it.
// ---------------------------------------------------------------------------

test('同账号双窗口共享已保存视图且互不覆盖', async ({ browser }) => {
  const context = await browser.newContext()
  const world = { projects: [{ id: ids.project, key: 'CRW', name: 'CrewScope' }], deskQueries: [] }
  const windowA = await context.newPage()
  const windowB = await context.newPage()
  for (const page of [windowA, windowB]) await mockDeskApi(page, world)

  await windowA.goto(`/today?team=${ids.team}`)
  await expect(windowA.getByRole('heading', { name: '我的工作台', exact: true })).toBeVisible()
  await windowA.getByRole('button', { name: '视图', exact: true }).click()
  await windowA.getByLabel('视图名称').fill('窗口 A 的执行面')
  await windowA.getByRole('button', { name: '保存当前筛选' }).click()
  await expect(windowA.getByRole('menu', { name: '已保存视图' })).toContainText('窗口 A 的执行面')

  // Window B shares the same account storage: after reload it sees A's view — shared, not
  // forked. It saves its own view next.
  await windowB.goto(`/today?team=${ids.team}`)
  await expect(windowB.getByRole('heading', { name: '我的工作台', exact: true })).toBeVisible()
  await windowB.getByRole('button', { name: '视图', exact: true }).click()
  await expect(windowB.getByRole('menu', { name: '已保存视图' })).toContainText('窗口 A 的执行面')
  await windowB.getByLabel('视图名称').fill('窗口 B 的关注面')
  await windowB.getByRole('button', { name: '保存当前筛选' }).click()
  await expect(windowB.getByRole('menu', { name: '已保存视图' })).toContainText('窗口 B 的关注面')

  // Back in window A: B's save must not have dropped A's entry (scope isolation inside one
  // account's storage, not a single-slot overwrite).
  await windowA.reload()
  await expect(windowA.getByRole('heading', { name: '我的工作台', exact: true })).toBeVisible()
  await windowA.getByRole('button', { name: '视图', exact: true }).click()
  const savedMenu = windowA.getByRole('menu', { name: '已保存视图' })
  await expect(savedMenu).toContainText('窗口 A 的执行面')
  await expect(savedMenu).toContainText('窗口 B 的关注面')
  await context.close()

  async function mockDeskApi(page: Page, state: { projects: Array<{ id: string, key: string, name: string }>, deskQueries: string[] }): Promise<void> {
    await page.route(/\/api\/v1\//, async route => {
      const request = route.request()
      const url = new URL(request.url())
      const path = url.pathname
      const method = request.method()
      if (method === 'GET' && path === '/api/v1/auth/session') return fulfillJson(route, authenticatedSession(ids.organization, ids.principal, ids.team))
      if (method === 'GET' && path.endsWith('/teams')) return fulfillJson(route, [{
        id: ids.team, organizationId: ids.organization, name: 'Platform Engineering', status: 'ACTIVE',
        initializationStatus: 'READY', ownerMemberId: 'member-1', defaultWorkspaceId: ids.workspace, version: 1,
      }])
      if (method === 'GET' && path.endsWith(`/${ids.team}/work-projects`)) {
        return fulfillJson(route, { items: state.projects.map(project => ({
          id: project.id, organizationId: ids.organization, teamId: ids.team, workspaceId: ids.workspace,
          key: project.key, name: project.name, status: 'ACTIVE', version: 1, createdAt: '2026-08-27T07:00:00Z',
          createdByPrincipalId: ids.principal, updatedAt: '2026-08-27T07:00:00Z', updatedByPrincipalId: ids.principal,
        })), nextCursor: null })
      }
      if (method === 'GET' && path.endsWith('/members')) return fulfillJson(route, [])
      if (method === 'GET' && path.endsWith('/work-desk')) {
        state.deskQueries.push(url.search)
        return fulfillJson(route, {
          organizationId: ids.organization, teamId: ids.team, projectId: null, generatedAt: '2026-09-27T08:00:00Z',
          sections: [{ key: 'WORK_ITEM', title: '我的工作项', priority: 1, total: 0, truncated: false, items: [] }],
        })
      }
      if (method === 'GET' && path.endsWith('/work-items')) return fulfillJson(route, { items: [], nextCursor: null })
      return notFound(route)
    })
  }
})

async function mockActivityApi(page: Page, occurredAt: string): Promise<void> {
  await page.route(/\/api\/v1\//, async route => {
    const request = route.request()
    const path = new URL(request.url()).pathname
    if (request.method() !== 'GET') return notFound(route)
    if (path === '/api/v1/auth/session') return fulfillJson(route, authenticatedSession(ids.organization, ids.principal, ids.team))
    if (path.endsWith('/teams')) return fulfillJson(route, [{
      id: ids.team, organizationId: ids.organization, name: 'Platform Engineering', status: 'ACTIVE',
      initializationStatus: 'READY', ownerMemberId: 'member-1', defaultWorkspaceId: ids.workspace, version: 1,
    }])
    if (path.endsWith(`/${ids.team}/work-projects`)) return fulfillJson(route, { items: [{
      id: ids.project, organizationId: ids.organization, teamId: ids.team, workspaceId: ids.workspace,
      key: 'CRW', name: 'CrewScope', status: 'ACTIVE', version: 1,
      createdAt: '2026-08-27T07:00:00Z', createdByPrincipalId: ids.principal,
      updatedAt: '2026-08-27T07:00:00Z', updatedByPrincipalId: ids.principal,
    }], nextCursor: null })
    if (path.endsWith('/activity/snapshot')) {
      return fulfillJson(route, {
        items: [activity('event-midnight', occurredAt)],
        hasMore: false, nextCursor: null, snapshotCursor: 'snapshot-cursor',
      })
    }
    if (path.endsWith('/activity/events')) return fulfillSse(route, [])
    return notFound(route)
  })
}

function activity(eventId: string, occurredAt: string) {
  return {
    eventId, domainEventId: `${eventId}-domain`, teamSequence: 1, eventType: 'TASK_COMPLETED',
    category: 'EXECUTION', visibility: 'TEAM',
    subject: { type: 'TASK', id: 'task-1' },
    actor: { type: 'MEMBER', principalId: ids.principal },
    references: [{ type: 'WORK_ITEM', id: '00000000-0000-0000-0000-000000000601' }],
    occurredAt,
    payload: { schemaName: 'activity-summary', schemaVersion: 1, values: { outcome: 'COMPLETED', evidence: 'SAFE_REFERENCE' } },
  }
}

function fulfillJson(route: Route, value: unknown) {
  return route.fulfill({ status: 200, contentType: 'application/json', headers: { 'Cache-Control': 'no-store' }, body: JSON.stringify(value) })
}

function fulfillSse(route: Route, frames: Array<{ id: string, event: string, data: unknown }>) {
  const body = frames.map(frame => `id:${frame.id}\nevent:${frame.event}\ndata:${JSON.stringify(frame.data)}\n\n`).join('')
  return route.fulfill({ status: 200, contentType: 'text/event-stream', headers: { 'Cache-Control': 'no-store' }, body })
}

function notFound(route: Route) {
  return route.fulfill({ status: 404, contentType: 'application/json', body: JSON.stringify({ code: 'not_found', message: 'Not found', correlationId: 'corr-404', retryable: false, currentVersion: null, details: {} }) })
}
