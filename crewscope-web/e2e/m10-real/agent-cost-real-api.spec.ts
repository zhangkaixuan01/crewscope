import { expect, test, type BrowserContext, type Page } from '@playwright/test'
import {
  baseURL,
  currentSession,
  onlyTeam,
  register,
  teamPath,
  type Session,
} from '../real-backend'

/**
 * M10-F03 real-stack contract: the observability page drives the real read API end to end —
 * no Vite server, no HTTP mocks, no fixtures. This covers what the mocked matrix structurally
 * cannot prove: the real serialization of the empty shapes (the strict gateway on the page
 * refuses anything unexpected, so a real empty month rendering is itself a contract check),
 * the real 400 month/cursor/limit validation surface, the cross-team 404 staying one shape
 * across the whole endpoint family, and the rebuild endpoint's operations-plane permission
 * wall rejecting a Team owner before the service is reached.
 *
 * A fresh onboarding Team has no usage facts, so the data lanes (XXX rows, priced amounts,
 * budget alerts) stay with the backend integration tests and the mocked matrix; the optional
 * fact-month test below is env-gated for an augmented stack that has produced real usage.
 */

type MonthsPage = {
  months: Array<{ month: string }>
  nextAfter: string | null
}

type QualityMonth = {
  executionAttempts: {
    total: number
    completed: number
    failed: number
    cancelled: number
    successRate: number | null
  }
  reviewFirstPass: {
    enteredReview: number
    firstPassApproved: number
    firstPassRate: number | null
  }
}

type CostMonthDetail = {
  rows: Array<{
    costStatus: 'PRICED' | 'UNPRICED'
    currencyCode: string
    inputCost: string | null
  }>
}

test.describe.configure({ mode: 'serial' })

let context: BrowserContext
let page: Page
let session: Session
let teamId: string

const observabilityRoot = () => teamPath(session, teamId, 'observability')

/** The current month in the deployment reporting zone — the future-month guard keys off it. */
function shanghaiMonth(): string {
  return new Intl.DateTimeFormat('en-CA', {
    timeZone: 'Asia/Shanghai',
    year: 'numeric',
    month: '2-digit',
  }).format(new Date())
}

function nextMonth(): string {
  const [year, month] = shanghaiMonth().split('-').map(Number) as [number, number]
  return month === 12 ? `${year + 1}-01` : `${year}-${String(month + 1).padStart(2, '0')}`
}

test('the onboarding owner reaches the observability page and reads the real empty state', async ({ browser }, testInfo) => {
  const suffix = `f03-${Date.now()}`.toLowerCase().replace(/[^a-z0-9]+/g, '-')
  const narrow = testInfo.project.name.includes('Narrow')
  context = await browser.newContext({
    baseURL,
    viewport: narrow ? { width: 390, height: 844 } : { width: 1440, height: 960 },
    timezoneId: 'Asia/Shanghai',
    reducedMotion: 'reduce',
  })
  page = await context.newPage()

  await register(page, `owner-${suffix}`.slice(0, 48), `owner-${suffix}@example.test`, 'F03 Owner', 'Correct-Horse-Battery-Staple-47', false)
  await expect(page).toHaveURL(/\/onboarding$/)
  await page.getByRole('textbox', { name: '团队名称' }).fill(`F03 ${suffix}`.slice(0, 96))
  await page.getByRole('button', { name: '创建团队' }).click()
  await expect(page.getByRole('heading', { name: '你的工作入口已经就绪' })).toBeVisible()

  session = await currentSession(page)
  teamId = onlyTeam(session).teamId

  // The strict gateway only renders what matches the frozen DTO shapes — on the real stack the
  // empty months page must serialize exactly {months:[], nextAfter:null} or the page refuses.
  await page.goto(`/observability?team=${teamId}`)
  await expect(page.getByRole('heading', { name: '成本质量', exact: true })).toBeVisible()
  await expect(page.getByText('本月还没有模型用量事实')).toBeVisible()
})

test('the months listing serializes the real empty page and answers no-store', async () => {
  const response = await page.request.get(`${observabilityRoot()}/cost/months`)
  expect(response.ok()).toBe(true)
  expect(response.headers()['cache-control']).toBe('no-store')

  const body = await response.json() as MonthsPage
  expect(body.months).toEqual([])
  expect(body.nextAfter == null).toBe(true)
})

test('a fact-less month reads as the 200 empty structure on both read families', async () => {
  // A fixed past month can never hold facts on a fresh stack — the 200-empty shape is stable.
  const detail = await page.request.get(`${observabilityRoot()}/cost/months/2020-01`)
  expect(detail.ok()).toBe(true)
  expect(((await detail.json()) as CostMonthDetail).rows).toEqual([])

  const quality = await page.request.get(`${observabilityRoot()}/quality/months/2020-01`)
  expect(quality.ok()).toBe(true)
  const body = await quality.json() as QualityMonth
  // Empty denominators read as zero counts with null rates, never as 0%.
  expect(body.executionAttempts).toMatchObject({ total: 0, completed: 0, failed: 0, cancelled: 0, successRate: null })
  expect(body.reviewFirstPass).toMatchObject({ enteredReview: 0, firstPassApproved: 0, firstPassRate: null })
})

test('malformed, future months and bad pagination answer one-field 400s', async () => {
  const cases = [
    { path: '/cost/months/2026-13', field: 'month' },
    { path: `/cost/months/${nextMonth()}`, field: 'month' },
    { path: `/quality/months/${nextMonth()}`, field: 'month' },
    { path: '/cost/months?after=not-a-month', field: 'after' },
    { path: '/cost/months?limit=0', field: 'limit' },
    { path: '/cost/months?limit=25', field: 'limit' },
  ]
  for (const item of cases) {
    const response = await page.request.get(`${observabilityRoot()}${item.path}`)
    expect(response.status(), item.path).toBe(400)
    const envelope = await response.json() as { code: string, details: { field: string } }
    expect(envelope.code, item.path).toBe('invalid_request')
    expect(envelope.details.field, item.path).toBe(item.field)
  }
})

test('an unknown team answers the shared 404 across the whole endpoint family', async () => {
  const unknownTeam = crypto.randomUUID()
  const foreign = `/api/v1/organizations/${session.principal!.organizationId}/teams/${unknownTeam}/observability`
  for (const suffix of ['/cost/months', '/cost/months/2020-01', '/quality/months/2020-01']) {
    const response = await page.request.get(`${foreign}${suffix}`)
    expect(response.status(), suffix).toBe(404)
    expect(((await response.json()) as { code: string }).code, suffix).toBe('aggregate_not_found')
  }
})

test('the rebuild endpoint is an operations-plane wall: a Team owner meets 403', async () => {
  // The onboarding owner is not a platform administrator — the rebuild must be denied before
  // the projection is touched (contract §4: 平台管理员 only).
  const rebuild = await page.request.post(
    `/api/v1/organizations/${session.principal!.organizationId}/operations/model-usage-rollup/rebuilds`,
    { headers: { [session.csrf.headerName]: session.csrf.token } },
  )
  expect(rebuild.status()).toBe(403)
  expect(((await rebuild.json()) as { code: string }).code).toBe('policy_denied')
})

// An augmented stack that has produced real usage facts can export the month to assert the
// data lanes against real aggregation: priced rows carry plain-string amounts while unpriced
// rows keep the XXX sentinel with null costs. Without the export the suite stays honest.
test('a real usage month keeps amount text and the unpriced sentinel row', async () => {
  const month = process.env.CREWSCOPE_M10_COST_FACT_MONTH
  test.skip(!month, 'no usage-fact month exported by this stack')

  const response = await page.request.get(`${observabilityRoot()}/cost/months/${month}`)
  expect(response.ok()).toBe(true)
  const rows = ((await response.json()) as CostMonthDetail).rows
  expect(rows.length).toBeGreaterThan(0)
  for (const row of rows) {
    if (row.costStatus === 'UNPRICED') {
      expect(row.currencyCode).toBe('XXX')
      expect(row.inputCost).toBeNull()
    } else {
      // toPlainString() text — plain decimal, never scientific notation, never converted.
      expect(row.inputCost).toMatch(/^(0|[1-9]\d*)(\.\d+)?$/)
    }
  }
})
