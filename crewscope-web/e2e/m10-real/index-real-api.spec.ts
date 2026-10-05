import { expect, test, type APIResponse, type BrowserContext, type Page } from '@playwright/test'
import {
  baseURL,
  currentSession,
  onlyTeam,
  register,
  teamPath,
  type Session,
} from '../real-backend'

/**
 * M10-F01b real-stack contract: the index jobs page drives the real I01c control plane end to
 * end — no Vite server, no HTTP mocks, no fixtures. This covers what the mocked matrix
 * structurally cannot prove: the real gate semantics (the augmented stack enables the index
 * gate but keeps the worker off, so rebuilds enqueue real QUEUED rows that stay cancellable),
 * the snapshot swap on cancel, and the 404 of an unknown job id.
 *
 * Lifecycle is serial: each test hands the published entry to the next one.
 */

type EntryHead = {
  id: string
  entryKey: string
  status: string
  version: number
  effectiveRevision: number | null
}

test.describe.configure({ mode: 'serial' })

let context: BrowserContext
let page: Page
let session: Session
let teamId: string

const entriesRoot = () => teamPath(session, teamId, 'knowledge/entries')
const indexRoot = () => teamPath(session, teamId, 'knowledge/index')

test('the onboarding owner reaches the index jobs page and reads the real empty listing', async ({ browser }, testInfo) => {
  const suffix = `m10b-${Date.now()}`.toLowerCase().replace(/[^a-z0-9]+/g, '-')
  const narrow = testInfo.project.name.includes('Narrow')
  context = await browser.newContext({
    baseURL,
    viewport: narrow ? { width: 390, height: 844 } : { width: 1440, height: 960 },
    timezoneId: 'Asia/Shanghai',
    reducedMotion: 'reduce',
  })
  page = await context.newPage()

  await register(page, `owner-${suffix}`.slice(0, 48), `owner-${suffix}@example.test`, 'F01b Owner', 'Correct-Horse-Battery-Staple-47', false)
  await expect(page).toHaveURL(/\/onboarding$/)
  await page.getByRole('textbox', { name: '团队名称' }).fill(`F01b ${suffix}`.slice(0, 96))
  await page.getByRole('button', { name: '创建团队' }).click()
  await expect(page.getByRole('heading', { name: '你的工作入口已经就绪' })).toBeVisible()

  session = await currentSession(page)
  teamId = onlyTeam(session).teamId

  await page.goto(`/knowledge/index?team=${teamId}`)
  await expect(page.getByRole('heading', { name: '索引作业' })).toBeVisible()
  // The reads stay 200 whatever the gate says (contract §7); a fresh Team has no jobs yet.
  await expect(page.getByRole('region', { name: '索引作业列表' }).getByText('暂无索引作业')).toBeVisible()
})

test('a published entry rebuild enqueues a real QUEUED job with the technical block', async () => {
  // Page-outside preparation: a real published entry gives the rebuild something to reindex.
  const entryKey = `f01b-runbook-${Date.now()}`
  const created = await page.request.post(entriesRoot(), {
    data: { entryKey, category: 'RUNBOOK', title: '真实栈重建手册', content: '通过真实 A02 API 创建并发布的条目。' },
    headers: { [session.csrf.headerName]: session.csrf.token, 'Idempotency-Key': crypto.randomUUID() },
  })
  expect(created.status()).toBe(202)
  const stored = (await page.request.get(`${entriesRoot()}?limit=50`)).json() as Promise<{ items: EntryHead[] }>
  const entry = (await stored).items.find(item => item.entryKey === entryKey)
  expect(entry?.status).toBe('DRAFT')
  const published = await page.request.post(`${entriesRoot()}/${entry!.id}/publish`, {
    headers: { [session.csrf.headerName]: session.csrf.token, 'Idempotency-Key': crypto.randomUUID(), 'If-Match': `"${entry!.version}"` },
  })
  expect(published.status()).toBe(202)

  await page.getByRole('button', { name: '重建知识索引' }).click()
  // The augmented stack opens the gate and keeps the worker off: one enqueued, still QUEUED.
  // The banner is scoped by class: the listing's StatePanel also answers role=status while
  // the forced re-read holds it in the loading state.
  await expect(page.locator('p.index-banner')).toContainText('已入队 1 个知识重建作业')

  const listing = page.getByRole('region', { name: '索引作业列表' })
  const queuedRow = listing.locator('li.job-item', { hasText: '排队中' })
  await expect(queuedRow).toBeVisible()
  await expect(queuedRow.getByText('知识条目')).toBeVisible()
  await expect(queuedRow.getByText(`条目 ${entry!.id.slice(0, 8)}`)).toBeVisible()

  // The manager's technical block reads the real snapshot fields (id, creator, no lease yet).
  await queuedRow.getByRole('button', { name: '展开详情' }).click()
  await expect(queuedRow.getByText('作业 ID')).toBeVisible()
  await expect(queuedRow.getByText('持有租约')).toBeVisible()
  await expect(queuedRow.getByText('无（排队中或已结束）')).toBeVisible()
})

test('cancelling swaps the row to the real CANCELLED snapshot; an unknown id answers 404', async () => {
  const listing = page.getByRole('region', { name: '索引作业列表' })
  const queuedRow = listing.locator('li.job-item', { hasText: '排队中' })
  await queuedRow.getByRole('button', { name: '取消作业' }).click()

  await expect(page.locator('p.index-banner')).toContainText('已取消排队中的作业')
  // The row stays expanded from the previous test, so the failure code <dd> carries the
  // same word as the badge — assert the badge itself.
  await expect(queuedRow.locator('.status-badge', { hasText: '已取消' })).toBeVisible()
  await expect(queuedRow.getByRole('button', { name: '取消作业' })).toHaveCount(0)

  // A page-outside cancel of an unknown job id meets the real 404 envelope (contract §6:
  // the job-scoped code, distinct from the binding 404 aggregate_not_found).
  const unknown = await page.request.post(`${indexRoot()}/jobs/${crypto.randomUUID()}/cancel`, {
    headers: { [session.csrf.headerName]: session.csrf.token },
  }) as APIResponse
  expect(unknown.status()).toBe(404)
  const envelope = await unknown.json() as { code: string }
  expect(envelope.code).toBe('knowledge_index_job_not_found')
})
