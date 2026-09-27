import { expect, test, type Page, type Route } from '@playwright/test'
import { authenticatedSession } from '../auth-session'
import type { WorkItemStatus } from '../../src/domains/workitem/types'

/**
 * The work list against a server that owns its ordering (M9b-A06 → F04): what the browser sends is
 * a sort name and filters, and what these cases hold it to is that the pages it stitches together
 * read as one server-ordered list — stable across pages, restarted when the filter changes, and
 * reachable on a phone.
 */
const ids = {
  organization: '00000000-0000-0000-0000-000000000001', team: '00000000-0000-4000-8000-000000000201',
  project: '00000000-0000-4000-8000-000000000401', workspace: '00000000-0000-4000-8000-000000000501',
  principal: '00000000-0000-0000-0000-000000000101',
  first: '00000000-0000-4000-8000-000000000601', second: '00000000-0000-4000-8000-000000000602',
  third: '00000000-0000-4000-8000-000000000603', fourth: '00000000-0000-4000-8000-000000000604',
}

interface Row {
  id: string
  key: string
  title: string
  status: WorkItemStatus
  priority: 'LOW' | 'MEDIUM' | 'HIGH' | 'URGENT'
  updatedAt: string
}

interface World {
  rows: Row[]
  pageSize: number
  /** Every list request's query, in arrival order — the assertions read what the browser sent. */
  queries: string[]
}

test('keeps one server ordering stable across pages', async ({ page }) => {
  const state: World = {
    pageSize: 2,
    queries: [],
    rows: [
      row({ id: ids.first, title: '旧更新低优先级', priority: 'LOW', updatedAt: '2026-09-10T09:00:00Z' }),
      row({ id: ids.second, title: '新更新中优先级', priority: 'MEDIUM', updatedAt: '2026-09-13T09:00:00Z' }),
      row({ id: ids.third, title: '紧急事项', priority: 'URGENT', updatedAt: '2026-09-11T09:00:00Z' }),
      row({ id: ids.fourth, title: '高优先级事项', priority: 'HIGH', updatedAt: '2026-09-12T09:00:00Z' }),
    ],
  }
  await mockApi(page, state)

  await page.goto(workUrl())
  // 默认按更新时间：新→旧。
  await expect(listTitles(page)).toHaveText(['新更新中优先级', '高优先级事项'])

  await page.getByRole('button', { name: /^按优先级排序/ }).click()

  // 第一页是优先级前两名；「加载更多」续上第三、四名，拼起来是同一份服务端排序。
  await expect(listTitles(page)).toHaveText(['紧急事项', '高优先级事项'])
  await page.getByRole('button', { name: '加载更多工作项' }).click()
  await expect(listTitles(page)).toHaveText(['紧急事项', '高优先级事项', '新更新中优先级', '旧更新低优先级'])

  // 排序与续页都是服务端的：priority 的首个请求从第一页开始（无 cursor），「加载更多」
  // 之后才有带 after 的 priority 请求，且 cursor 指向优先级排序里的下一行。页面初始化
  // 会重复发出同参请求，这里断言的是请求形状而不是精确次数。
  const updatedAtQueries = state.queries.filter(query => query.includes('sort=updatedAt'))
  const priorityQueries = state.queries.filter(query => query.includes('sort=priority'))
  expect(updatedAtQueries.length).toBeGreaterThan(0)
  expect(updatedAtQueries.every(query => !query.includes('after='))).toBe(true)
  expect(priorityQueries.length).toBeGreaterThan(1)
  expect(priorityQueries[0]).not.toContain('after=')
  expect(priorityQueries.filter(query => query.includes('after=')).length).toBeGreaterThan(0)
})

test('restarts the list from its first page when a filter changes', async ({ page }) => {
  const state: World = {
    pageSize: 2,
    queries: [],
    rows: [
      row({ id: ids.first, title: '紧急事项', priority: 'URGENT', updatedAt: '2026-09-13T09:00:00Z' }),
      row({ id: ids.second, title: '高优先级事项', priority: 'HIGH', updatedAt: '2026-09-12T09:00:00Z' }),
      row({ id: ids.third, title: '中优先级事项', priority: 'MEDIUM', updatedAt: '2026-09-11T09:00:00Z' }),
    ],
  }
  await mockApi(page, state)

  await page.goto(workUrl())
  await page.getByRole('button', { name: '加载更多工作项' }).click()
  await expect(listTitles(page)).toHaveCount(3)

  const filterToggle = page.getByRole('button', { name: /展开筛选/ })
  if (await filterToggle.isVisible()) await filterToggle.click()
  await page.locator('.filters select').nth(2).selectOption('URGENT')

  // 筛选变化后列表从第一页重新开始：只剩服务端过滤后的行，已加载的旧页被替换而不是拼接。
  await expect(listTitles(page)).toHaveText(['紧急事项'])
  await expect(page.getByRole('button', { name: '加载更多工作项' })).toHaveCount(0)
  // 新的筛选请求不带旧 cursor——cursor 只对铸造它的筛选有效。
  const lastQuery = new URLSearchParams(state.queries.at(-1)!)
  expect(lastQuery.get('priority')).toBe('URGENT')
  expect(lastQuery.get('after')).toBeNull()
  expect(state.queries.filter(query => query.includes('after=')).length).toBe(1)
})

test('keeps the primary actions reachable on a phone while filters fold', async ({ page }, testInfo) => {
  // 折叠只存在于手机布局；桌面不渲染这个开关。
  test.skip(testInfo.project.name !== 'narrow-chromium', 'the folded filter row exists only on the phone layout')
  const state: World = {
    pageSize: 50,
    queries: [],
    rows: [row({ id: ids.first, title: '紧急事项', priority: 'URGENT', updatedAt: '2026-09-13T09:00:00Z' })],
  }
  await mockApi(page, state)

  await page.goto(workUrl())

  // 主操作不被筛选行挤走：新建入口与视图切换都保持可见可点（R16）。
  await expect(page.getByRole('button', { name: /新建工作项/ })).toBeVisible()
  await expect(page.getByRole('button', { name: '看板视图' })).toBeVisible()

  // 折叠的筛选行不显示控件，但已应用的数量一直在开关上；展开后控件可达。
  const toggle = page.locator(".filters-toggle")
  await expect(toggle).toBeVisible()
  await expect(page.locator('.filters select').first()).toBeHidden()
  await toggle.click()
  await expect(toggle).toHaveAttribute('aria-expanded', 'true')
  await expect(page.locator('.filters select').first()).toBeVisible()
  await page.locator('.filters select').nth(2).selectOption('URGENT')

  // 应用筛选是 URL 更新，不是页面重建：折叠区保持展开，已应用数量随之上来。
  await expect(toggle).toContainText('已应用 1 项')
  await expect(toggle).toHaveAttribute('aria-expanded', 'true')
  await expect(page.locator('.filters select').first()).toBeVisible()
})

function workUrl(): string {
  return `/work?team=${ids.team}&project=${ids.project}`
}

function listTitles(page: Page) {
  return page.getByLabel('工作项列表').locator('.work-item-card__open h3')
}

function row(overrides: Partial<Row> = {}): Row {
  return { id: ids.first, key: 'CRW-18', title: '工作项', status: 'IN_PROGRESS', priority: 'HIGH', updatedAt: '2026-09-12T09:00:00Z', ...overrides }
}

async function mockApi(page: Page, state: World): Promise<void> {
  await page.route(/\/api\/v1\//, async route => {
    const request = route.request()
    const path = new URL(request.url()).pathname
    const method = request.method()

    if (method === 'GET' && path === '/api/v1/auth/session') return json(route, authenticatedSession(ids.organization, ids.principal, ids.team))
    if (method === 'GET' && path.endsWith('/teams')) return json(route, [{
      id: ids.team, organizationId: ids.organization, name: 'Platform Engineering', status: 'ACTIVE',
      initializationStatus: 'READY', ownerMemberId: 'member-1', defaultWorkspaceId: ids.workspace, version: 1,
    }])
    if (method === 'GET' && path.endsWith(`/${ids.team}/work-projects`)) return json(route, {
      items: [{
        id: ids.project, organizationId: ids.organization, teamId: ids.team, workspaceId: ids.workspace,
        key: 'CRW', name: 'CrewScope', status: 'ACTIVE', version: 1, createdAt: '2026-08-27T07:00:00Z',
        createdByPrincipalId: ids.principal, updatedAt: '2026-08-27T07:00:00Z', updatedByPrincipalId: ids.principal,
      }],
      nextCursor: null,
    })
    if (method === 'GET' && path.endsWith(`/${ids.team}/members`)) return json(route, [])

    if (method === 'GET' && path.endsWith('/work-items')) {
      // A server that owns its ordering: it filters, sorts and paginates by what the request says.
      const search = new URL(request.url()).searchParams
      state.queries.push(search.toString())
      const priority = search.get('priority')?.split(',').filter(Boolean) ?? []
      const sort = search.get('sort') ?? 'updatedAt'
      const byPriority = ['LOW', 'MEDIUM', 'HIGH', 'URGENT']
      const filtered = state.rows.filter(candidate => !priority.length || priority.includes(candidate.priority))
      const ordered = [...filtered].sort((left, right) =>
        sort === 'priority'
          ? byPriority.indexOf(right.priority) - byPriority.indexOf(left.priority)
          : right.updatedAt.localeCompare(left.updatedAt))
      const after = search.get('after')
      const start = after ? ordered.findIndex(item => item.id === after) + 1 : 0
      const items = ordered.slice(start, start + state.pageSize)
      const hasMore = start + state.pageSize < ordered.length
      return json(route, { items: items.map(summaryOf), nextCursor: hasMore ? items.at(-1)!.id : null })
    }

    if (method === 'GET' && /\/work-items\/[^/]+$/.test(path)) {
      const target = state.rows.find(candidate => path.endsWith(`/${candidate.id}`))
      return json(route, target ? { workItem: summaryOf(target), comments: [], resourceLinks: [] } : {})
    }
    if (/\/work-items\/[^/]+\/(transitions|responsibilities|timeline)/.test(path)) {
      if (path.endsWith('/transitions/availability')) return json(route, { transitions: [] })
      return json(route, { items: [], nextCursor: null })
    }
    return json(route, {})
  })
}

function summaryOf(item: Row) {
  return {
    id: item.id, organizationId: ids.organization, teamId: ids.team, workspaceId: ids.workspace,
    projectId: ids.project, key: item.key, type: 'TASK', title: item.title,
    description: null, status: item.status, priority: item.priority,
    labels: [], dueAt: null, source: 'CREWSCOPE', sourceReference: null, version: 0,
    createdAt: '2026-09-01T01:00:00Z', createdByPrincipalId: ids.principal,
    updatedAt: item.updatedAt, updatedByPrincipalId: ids.principal, availableActions: [],
  }
}

function json(route: Route, value: unknown) {
  return route.fulfill({ status: 200, contentType: 'application/json', headers: { 'Cache-Control': 'no-store' }, body: JSON.stringify(value) })
}
