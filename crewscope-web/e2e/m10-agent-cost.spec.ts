import { expect, test, type Route } from '@playwright/test'
import { authenticatedSession } from './auth-session'

/**
 * M10-F03 cost and quality observability (contract docs/api/M10-成本与质量观测API契约.md §5/§8).
 * The mock e2e freezes the read surface the acceptance hinges on: amounts stay per-currency
 * plain strings, unpriced tokens never read as zero, both quality cards name their denominators,
 * and the month keyset page walks through `nextAfter`.
 */

const ids = {
  organization: '00000000-0000-0000-0000-000000000001',
  principal: '00000000-0000-0000-0000-000000000101',
  team: '00000000-0000-0000-0000-000000000201',
  project: '00000000-0000-0000-0000-000000000401',
  workspace: '00000000-0000-0000-0000-000000000501',
}

const PAGE_SIZE = 2
const months = [
  monthSummary('2026-09', 300_000),
  monthSummary('2026-08', 0),
  monthSummary('2026-07', 0),
]
let listingQueries: Array<URLSearchParams>
let detailMonths: string[]
let qualityMonths: string[]
let emptyMonths: boolean

test.beforeEach(async ({ page }) => {
  await page.clock.setFixedTime(new Date('2026-10-05T04:00:00Z'))
  listingQueries = []
  detailMonths = []
  qualityMonths = []
  emptyMonths = false

  await page.route(/\/api\/v1\//, async route => {
    const request = route.request()
    const url = new URL(request.url())
    const path = url.pathname
    if (request.method() !== 'GET') return notFound(route)
    if (path === '/api/v1/auth/session') return json(route, authenticatedSession(ids.organization, ids.principal, ids.team))
    if (path.endsWith('/teams')) return json(route, [team()])
    if (path.endsWith(`/${ids.team}/work-projects`)) return json(route, { items: [project()], nextCursor: null })
    if (path.endsWith('/observability/cost/months')) {
      listingQueries.push(url.searchParams)
      const after = url.searchParams.get('after')
      const live = emptyMonths ? [] : months.filter(item => !after || item.month < after)
      // The mock pages tighter than the requested 12 so the cursor walk is observable.
      const page = live.slice(0, PAGE_SIZE)
      return json(route, {
        months: page,
        nextAfter: live.length > PAGE_SIZE ? (page.at(-1)?.month ?? null) : null,
      })
    }
    const detailMatch = path.match(/\/observability\/cost\/months\/(\d{4}-\d{2})$/)
    if (detailMatch) {
      detailMonths.push(detailMatch[1]!)
      return json(route, detail(detailMatch[1]!))
    }
    const qualityMatch = path.match(/\/observability\/quality\/months\/(\d{4}-\d{2})$/)
    if (qualityMatch) {
      qualityMonths.push(qualityMatch[1]!)
      return json(route, quality(qualityMatch[1]!))
    }
    return json(route, {})
  })
})

test('renders the source lanes, per-currency amounts, price revisions and the unpriced badge', async ({ page }) => {
  await page.goto(`/observability?team=${ids.team}`)

  await expect(page.getByRole('heading', { name: '2026-09 成本概览' })).toBeVisible()
  // The three source lanes each name themselves with tokens plus call count.
  await expect(page.getByText('1,260,000 token / 40 次调用')).toBeVisible()
  await expect(page.getByText('100,000 token / 2 次调用')).toBeVisible()
  // Amounts keep the plain-string shape per currency — never converted, never merged.
  const currencyTable = page.getByRole('table', { name: '2026-09 分币种费用' })
  await expect(currencyTable.getByRole('row').filter({ hasText: 'USD' })).toContainText('0.528000000000')
  await expect(currencyTable.getByRole('row').filter({ hasText: 'CNY' })).toContainText('0.050000000000')
  // The unpriced lane is visible and is never worded as a zero cost.
  await expect(page.getByText('存在未能解析价格的用量：按 token 计量，不计为 0 成本（300,000 token，XXX 行）')).toBeVisible()
  // The detail table pins the price revision pair and the unreported count.
  await expect(page.getByText('目录 2 · 价格 3')).toBeVisible()
  await expect(page.getByText('（未回显 2）')).toBeVisible()
  await expect(page.getByText('未计价')).toBeVisible()
})

test('pages earlier months through the keyset cursor', async ({ page }) => {
  await page.goto(`/observability?team=${ids.team}`)
  await expect(page.getByRole('heading', { name: '2026-09 成本概览' })).toBeVisible()
  expect(listingQueries[0]!.get('limit')).toBe('12')

  await page.getByRole('button', { name: '加载更早月份' }).click()
  await expect(page.getByRole('button', { name: '2026-07' })).toBeVisible()

  // The cursor is the last month of the previous page; each page keeps the descending order.
  expect(listingQueries.at(-1)!.get('after')).toBe('2026-08')
  // The paging control disappears once the cursor is exhausted.
  await expect(page.getByRole('button', { name: '加载更早月份' })).toHaveCount(0)
})


test('deep-links a month and names both quality denominators', async ({ page }) => {
  await page.goto(`/observability?team=${ids.team}&month=2026-08`)

  await expect(page.getByRole('heading', { name: '2026-08 成本概览' })).toBeVisible()
  expect(detailMonths[0]).toBe('2026-08')
  expect(qualityMonths[0]).toBe('2026-08')
  // The cards print their own denominators — an execution rate and a review rate are different
  // populations (contract §5), and the empty review month reads 暂无样本, not 0%.
  await expect(page.getByText('分母：20 次执行尝试')).toBeVisible()
  await expect(page.getByText('75.0%')).toBeVisible()
  await expect(page.getByText('分母：0 个进入 Review 的请求')).toBeVisible()
  await expect(page.getByText('暂无样本')).toBeVisible()
})

test('filters detail rows by role through the URL', async ({ page }) => {
  await page.goto(`/observability?team=${ids.team}`)

  await page.getByLabel('角色筛选').selectOption('EMBEDDING')
  await expect(page).toHaveURL(/role=EMBEDDING/)
  // The filter is client-side over the loaded month — the XXX embedding row stays, the chat row goes.
  await expect(page.getByText('text-embedding-v4')).toBeVisible()
  await expect(page.getByText('deepseek-v3')).toHaveCount(0)
})

test('renders the empty state when the team has no usage months yet', async ({ page }) => {
  emptyMonths = true
  await page.goto(`/observability?team=${ids.team}`)

  await expect(page.getByText('本月还没有模型用量事实')).toBeVisible()
  await expect(page.getByText('执行与对话')).toHaveCount(0)
})

function team() {
  return {
    id: ids.team, organizationId: ids.organization, name: 'Platform Engineering', status: 'ACTIVE',
    initializationStatus: 'READY', ownerMemberId: 'member-1', defaultWorkspaceId: ids.workspace, version: 1,
  }
}

function project() {
  return {
    id: ids.project, organizationId: ids.organization, teamId: ids.team, workspaceId: ids.workspace,
    key: 'CRW', name: 'CrewScope', status: 'ACTIVE', version: 1,
    createdAt: '2026-08-27T07:00:00Z', createdByPrincipalId: ids.principal,
    updatedAt: '2026-08-27T07:00:00Z', updatedByPrincipalId: ids.principal,
  }
}

function monthSummary(month: string, unpricedTokens: number) {
  return {
    month,
    roles: {
      EXECUTION: { inputTokens: 1_200_000, outputTokens: 60_000, cachedTokens: 40_000, factCount: 40 },
      EMBEDDING: { inputTokens: 100_000, outputTokens: 0, cachedTokens: 0, factCount: 2 },
    },
    currencies: [
      { currency: 'CNY', inputCost: '0.050000000000', outputCost: '0', cachedInputCost: '0' },
      { currency: 'USD', inputCost: '0.528000000000', outputCost: '0.105600000000', cachedInputCost: '0' },
    ],
    unpricedTokens,
    totalFactCount: 42,
  }
}

function detail(month: string) {
  return {
    month,
    rows: [
      {
        role: 'CHAT_PRIMARY', providerKey: 'dashscope', modelId: 'deepseek-v3', currencyCode: 'USD',
        catalogRevision: 2, priceRevision: 3, attempt: 1,
        inputTokens: 1_000_000, outputTokens: 50_000, cachedTokens: 10_000,
        inputCost: '0.528000000000', outputCost: '0.066000000000', cachedInputCost: '0',
        factCount: 30, unreportedFactCount: 2, costStatus: 'PRICED',
      },
      {
        role: 'EMBEDDING', providerKey: 'dashscope', modelId: 'text-embedding-v4', currencyCode: 'XXX',
        catalogRevision: null, priceRevision: null, attempt: 2,
        inputTokens: 300_000, outputTokens: 0, cachedTokens: 0,
        inputCost: null, outputCost: null, cachedInputCost: null,
        factCount: 4, unreportedFactCount: 0, costStatus: 'UNPRICED',
      },
    ],
  }
}

function quality(month: string) {
  return {
    month,
    executionAttempts: { total: 20, completed: 15, failed: 4, cancelled: 1, successRate: 0.75 },
    reviewFirstPass: { enteredReview: 0, firstPassApproved: 0, firstPassRate: null },
  }
}

function json(route: Route, value: unknown) {
  return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(value) })
}

function notFound(route: Route) {
  return route.fulfill({
    status: 404, contentType: 'application/json',
    body: JSON.stringify({ code: 'not_found', message: 'Not found', correlationId: 'corr-404', retryable: false, currentVersion: null, details: {} }),
  })
}
