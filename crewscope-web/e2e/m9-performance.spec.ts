import { expect, test, type Page, type Route } from '@playwright/test'
import { authenticatedSession } from './auth-session'
import { ids, mockApi } from './menu-walk'
import { allowedWorkItemTransitions, type WorkItemStatus } from '../src/domains/workitem/types'

/** The heaviest desk the contract admits: a section may carry at most 500 rows. */
const RESPONSIBILITY_ITEMS = 500

test.describe('M9-Q01 performance budgets', () => {
  test.beforeEach(async ({ page }) => mockApi(page))

  test('keeps the homepage and Setup route interactive within the navigation budget', async ({ page }) => {
    await page.goto(`/today?team=${ids.team}`, { waitUntil: 'domcontentloaded' })
    await expect(page.getByRole('heading', { name: '我的工作台', exact: true })).toBeVisible()
    const homepage = await page.evaluate(() => {
      const navigation = performance.getEntriesByType('navigation')[0] as PerformanceNavigationTiming | undefined
      const paint = performance.getEntriesByType('paint').find(entry => entry.name === 'first-contentful-paint')
      return { domContentLoaded: navigation?.domContentLoadedEventEnd ?? 0, firstContentfulPaint: paint?.startTime ?? 0 }
    })
    expect(homepage.domContentLoaded).toBeGreaterThan(0)
    expect(homepage.domContentLoaded).toBeLessThan(5_000)
    if (homepage.firstContentfulPaint > 0) expect(homepage.firstContentfulPaint).toBeLessThan(5_000)

    const routeStart = Date.now()
    await page.goto(`/setup?team=${ids.team}&project=${ids.project}`, { waitUntil: 'domcontentloaded' })
    await expect(page.getByRole('heading', { name: 'Platform Engineering 的配置中心' })).toBeVisible()
    expect(Date.now() - routeStart).toBeLessThan(5_000)
  })

  /**
   * 500 责任项下的首屏预算。
   *
   * 这是**本地浏览器档**的基线，不是 Q02 的真实环境 P95/500 项实测：它量的是这一页在一条
   * 最大投影上渲染得动还是渲染不动（500 张卡片全部落地、LCP 仍在预算内），用的是本地 vite
   * 与桩响应，因此数字不可外推到生产环境。Q02 的那两行仍待执行。
   */
  test('keeps the homepage within its LCP budget under 500 responsibility items', async ({ page }) => {
    await page.unrouteAll()
    await mockFullDesk(page)

    await page.goto(`/today?team=${ids.team}`, { waitUntil: 'domcontentloaded' })
    await expect(page.locator('.desk-card')).toHaveCount(RESPONSIBILITY_ITEMS)

    const lcp = await page.evaluate(() => new Promise<number>(resolve => {
      let latest = 0
      const observer = new PerformanceObserver(list => {
        const entries = list.getEntries()
        if (entries.length) latest = entries[entries.length - 1]!.startTime
      })
      observer.observe({ type: 'largest-contentful-paint', buffered: true })
      setTimeout(() => { observer.disconnect(); resolve(latest) }, 500)
    }))
    expect(lcp).toBeGreaterThan(0)
    expect(lcp).toBeLessThan(3_000)
  })
})

async function mockFullDesk(page: Page): Promise<void> {
  const statuses: WorkItemStatus[] = ['BACKLOG', 'READY', 'IN_PROGRESS', 'IN_REVIEW', 'BLOCKED', 'DONE']
  await page.route(/\/api\/v1\//, async route => {
    const request = route.request(); const path = new URL(request.url()).pathname
    if (request.method() !== 'GET') return notFound(route)
    if (path === '/api/v1/auth/session') return json(route, authenticatedSession(ids.organization, ids.principal, ids.team))
    if (path.endsWith('/teams')) return json(route, [{
      id: ids.team, organizationId: ids.organization, name: 'Platform Engineering', status: 'ACTIVE',
      initializationStatus: 'READY', ownerMemberId: 'member-1', defaultWorkspaceId: ids.workspace, version: 1,
    }])
    if (path.endsWith(`/${ids.team}/work-projects`)) return json(route, {
      items: [{
        id: ids.project, organizationId: ids.organization, teamId: ids.team, workspaceId: ids.workspace,
        key: 'CRW', name: 'CrewScope', status: 'ACTIVE', version: 1, createdAt: '2026-08-27T07:00:00Z',
        createdByPrincipalId: ids.principal, updatedAt: '2026-08-27T07:00:00Z', updatedByPrincipalId: ids.principal,
      }],
      nextCursor: null,
    })
    if (path.endsWith('/members')) return json(route, [])
    if (path.endsWith('/work-desk')) return json(route, fullDesk(statuses))
    return json(route, /\/(items|events|list|search|history|revisions)$|s$/.test(path) ? { items: [], nextCursor: null } : {})
  })
}

function fullDesk(statuses: readonly WorkItemStatus[]) {
  const items = Array.from({ length: RESPONSIBILITY_ITEMS }, (_, index) => {
    const status = statuses[index % statuses.length]!
    const objectId = `00000000-0000-4000-8000-${String(index + 1).padStart(12, '0')}`
    return {
      objectType: 'WORK_ITEM', objectId, projectId: ids.project,
      title: `责任项 ${index + 1}`, status, updatedAt: '2026-09-13T09:00:00Z',
      responsibilityRole: 'OWNER', needsAction: index % 7 === 0, urgency: 'NORMAL', progress: index % 101,
      availableActions: (allowedWorkItemTransitions[status] ?? []).map(target => ({
        actionId: `${status}-to-${target}`.toLowerCase().replaceAll('_', '-'), targetStatus: target,
        label: `标记${target}`, strength: 'PRIMARY',
        reversible: (allowedWorkItemTransitions[target] ?? []).includes(status),
        enabled: true, reason: null, reasonMessage: null, remedyLabel: null, remedyRoute: null,
      })),
      route: `/work?team=${ids.team}&project=${ids.project}&workItem=${objectId}`,
    }
  })
  // The board draws the WORK_ITEM section only; the other five stay empty so the count below is
  // exactly the number of cards one section can hold.
  return {
    organizationId: ids.organization, teamId: ids.team, projectId: null, generatedAt: '2026-09-13T10:00:00Z',
    sections: [
      { key: 'HUMAN_GATE', title: '等我决策', priority: 1, total: 0, truncated: false, items: [] },
      { key: 'REVIEW', title: '待我 Review', priority: 2, total: 0, truncated: false, items: [] },
      { key: 'BLOCKED', title: '被我阻塞', priority: 3, total: 0, truncated: false, items: [] },
      { key: 'WORK_ITEM', title: '我的工作项', priority: 4, total: items.length, truncated: false, items },
      { key: 'TASK_EXECUTION', title: '进行中的执行', priority: 5, total: 0, truncated: false, items: [] },
      { key: 'INBOX', title: '未读 Inbox', priority: 6, total: 0, truncated: false, items: [] },
    ],
  }
}

function json(route: Route, value: unknown) { return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(value) }) }

function notFound(route: Route) {
  return route.fulfill({
    status: 404, contentType: 'application/json',
    body: JSON.stringify({ code: 'not_found', message: 'Not found', correlationId: 'corr-404', retryable: false, currentVersion: null, details: {} }),
  })
}
