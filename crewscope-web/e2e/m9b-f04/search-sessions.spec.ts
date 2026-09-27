import { expect, test, type Page, type Route } from '@playwright/test'
import { authenticatedSession } from '../auth-session'

const ids = {
  organization: '00000000-0000-4000-8000-000000000001',
  team: '00000000-0000-4000-8000-000000000201',
  project: '00000000-0000-4000-8000-000000000401',
  workspace: '00000000-0000-4000-8000-000000000501',
  principal: '00000000-0000-4000-8000-000000000101',
}

test.beforeEach(async ({ page }) => {
  await page.clock.setFixedTime(new Date('2026-09-26T08:30:00Z'))
})

test('keeps the search page session when the palette opens, previews and closes', async ({ page }) => {
  const api = await mockSearchApi(page)
  await page.goto(`/search?team=${ids.team}&q=发布`)
  await expect(page.getByText('找到 1 条结果')).toBeVisible()
  await expect(page.getByRole('heading', { name: '工作项' })).toBeVisible()

  await page.keyboard.press('Meta+k')
  await expect(page.getByRole('dialog', { name: '命令面板' })).toBeVisible()
  await page.getByLabel('搜索动作或对象').fill('部署')
  await expect(page.getByRole('dialog', { name: '命令面板' }).getByText('部署 Agent')).toBeVisible()
  // The palette preview never replaces the search page's committed result underneath it.
  await expect(page.getByText('找到 1 条结果')).toBeVisible()

  await page.keyboard.press('Escape')
  await expect(page.getByRole('dialog', { name: '命令面板' })).toHaveCount(0)
  await expect(page.getByText('找到 1 条结果')).toBeVisible()
  await expect(page.getByRole('heading', { name: '工作项' })).toBeVisible()
})

test('submits the palette text to the search page only through 搜索全部', async ({ page }) => {
  await mockSearchApi(page)
  await page.goto(`/today?team=${ids.team}`)
  // The narrow shell hides the toolbar search button until S9, so both viewports open through
  // the keyboard shortcut once the app shell is mounted.
  await expect(page.getByRole('banner')).toBeVisible()

  await page.keyboard.press('Meta+k')
  await expect(page.getByRole('dialog', { name: '命令面板' })).toBeVisible()
  await page.getByLabel('搜索动作或对象').fill('发布')
  await page.getByRole('button', { name: '搜索全部' }).click()

  await expect(page).toHaveURL(/\/search\?/)
  expect(new URL(page.url()).searchParams.get('q')).toBe('发布')
  await expect(page.getByText('找到 1 条结果')).toBeVisible()
})

test('keeps the last committed result beside a failed refresh and retries the committed conditions', async ({ page }) => {
  const api = await mockSearchApi(page)
  await page.goto(`/search?team=${ids.team}&q=发布`)
  await expect(page.getByText('找到 1 条结果')).toBeVisible()

  api.failNextSearches = 1
  await page.getByLabel('搜索内容').fill('部署')
  await page.getByRole('button', { name: '搜索', exact: true }).click()
  await expect(page.getByText('搜索服务暂时不可用')).toBeVisible()
  await expect(page.getByText('仍显示上次结果')).toBeVisible()
  // The alpha rows stay beside the error, they are not blanked by the failure.
  await expect(page.getByText('发布清单', { exact: true })).toBeVisible()

  // A draft never rides along with the retry: only the committed query is resubmitted.
  await page.getByLabel('搜索内容').fill('永远不提交的草稿')
  await page.getByRole('button', { name: '刷新事实' }).click()
  await expect(page.getByText('找到 1 条结果')).toBeVisible()
  expect(api.searchTexts.at(-1)).toBe('部署')
})

test('treats a failed cursor page as local and retries only that page', async ({ page }) => {
  const api = await mockSearchApi(page)
  await page.goto(`/search?team=${ids.team}&q=发布`)
  await expect(page.getByText('找到 1 条结果')).toBeVisible()

  api.failNextSearches = 1
  await page.getByRole('button', { name: '加载更多' }).click()
  await expect(page.getByRole('button', { name: '重试本页' })).toBeVisible()
  await expect(page.getByText('本页加载失败，以上结果已保留。')).toBeVisible()
  await expect(page.getByText('仍显示上次结果')).toHaveCount(0)
  await expect(page.getByText('发布清单', { exact: true })).toBeVisible()

  await page.getByRole('button', { name: '重试本页' }).click()
  await expect(page.getByText('找到 2 条结果')).toBeVisible()
  expect(api.searchTexts).toEqual(['发布', '发布', '发布'])
  expect(api.afterCursors.at(-1)).toBe('release-cursor')
})

interface MockApi {
  searchTexts: string[]
  afterCursors: Array<string | null>
  failNextSearches: number
}

/**
 * A mutable search fixture: the committed query, its cursor page and a counter of
 * deliberately failing responses, so each case can fail exactly one request.
 */
async function mockSearchApi(page: Page): Promise<MockApi> {
  const api: MockApi = { searchTexts: [], afterCursors: [], failNextSearches: 0 }
  const items = [
    { objectType: 'WORK_ITEM', objectId: '00000000-0000-4000-8000-000000000701', projectId: ids.project, title: '发布清单', subtitle: null, status: 'IN_PROGRESS', updatedAt: '2026-09-26T08:00:00Z', route: `/work?team=${ids.team}&project=${ids.project}&workItem=00000000-0000-4000-8000-000000000701`, snippet: null },
    { objectType: 'WORK_ITEM', objectId: '00000000-0000-4000-8000-000000000702', projectId: ids.project, title: '发布回滚', subtitle: null, status: 'OPEN', updatedAt: '2026-09-25T08:00:00Z', route: `/work?team=${ids.team}&project=${ids.project}&workItem=00000000-0000-4000-8000-000000000702`, snippet: null },
    { objectType: 'AGENT', objectId: '00000000-0000-4000-8000-000000000801', projectId: null, title: '部署 Agent', subtitle: null, status: 'ACTIVE', updatedAt: '2026-09-24T08:00:00Z', route: `/settings/agents?team=${ids.team}`, snippet: null },
  ]

  await page.route(/\/api\/v1\//, async route => {
    const request = route.request()
    const url = new URL(request.url())
    const path = url.pathname
    if (request.method() === 'GET' && path === '/api/v1/auth/session') return fulfillJson(route, authenticatedSession(ids.organization, ids.principal, ids.team))
    if (request.method() === 'GET' && path.endsWith('/teams')) {
      return fulfillJson(route, [{
        id: ids.team, organizationId: ids.organization, name: 'Platform Engineering', status: 'ACTIVE',
        initializationStatus: 'READY', ownerMemberId: 'member-1', defaultWorkspaceId: ids.workspace, version: 1,
      }])
    }
    if (request.method() === 'GET' && path.endsWith(`/${ids.team}/work-projects`)) {
      // No projects: selectedProjectId stays null, so the page watch fires exactly once per
      // committed query and the request-count assertions stay deterministic.
      return fulfillJson(route, { items: [], nextCursor: null })
    }
    if (request.method() === 'GET' && path.endsWith('/search')) {
      const text = url.searchParams.get('q') ?? ''
      const after = url.searchParams.get('after')
      api.searchTexts.push(text)
      api.afterCursors.push(after)
      if (api.failNextSearches > 0) {
        api.failNextSearches -= 1
        return route.fulfill({ status: 503, contentType: 'application/json', body: JSON.stringify(errorEnvelope('service_unavailable')) })
      }
      // The page's committed query returns a work-item page with a cursor; other
      // texts (the palette preview) return the single matching object.
      const matched = items.filter(item => item.title.includes(text) || text.length === 0)
      if (after) return fulfillJson(route, { items: matched.slice(1), nextCursor: null })
      return fulfillJson(route, { items: matched.slice(0, 1), nextCursor: matched.length > 1 ? 'release-cursor' : null })
    }
    return route.fulfill({ status: 404, contentType: 'application/json', body: JSON.stringify(errorEnvelope('not_found')) })
  })
  return api
}

function fulfillJson(route: Route, value: unknown) {
  return route.fulfill({ status: 200, contentType: 'application/json', headers: { 'Cache-Control': 'no-store' }, body: JSON.stringify(value) })
}

function errorEnvelope(code: string) {
  return { code, message: code === 'service_unavailable' ? '搜索服务暂时不可用，请稍后重试。' : code, correlationId: crypto.randomUUID(), retryable: false, currentVersion: null, details: {} }
}
