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
 * M10-F01a real-stack contract: the knowledge management page drives the real A02 API end to
 * end — no Vite server, no HTTP mocks, no fixtures. This is the tier the delivery contract
 * insists on ("F01a 必须依赖 A02 真实 API"), covering what the mocked matrix structurally
 * cannot prove: the real URL shapes (the distillation collection sits beside /entries), the
 * real ETag/version discipline, the 409 currentVersion envelope, and the DELETED tombstone
 * that the real listing filter keeps visible.
 *
 * The lifecycle is serial: each test hands its entry head to the next one.
 */

type EntryHead = {
  id: string
  entryKey: string
  status: string
  effectiveRevision: number | null
  latestRevision: number
  version: number
  draft: { title: string, content: string } | null
}

type VersionRow = {
  revision: number
  title: string
  content: string
  indexStatus: string
}

test.describe.configure({ mode: 'serial' })

let context: BrowserContext
let page: Page
let session: Session
let teamId: string
let entryId: string

const knowledgeRoot = () => teamPath(session, teamId, 'knowledge/entries')

async function head(): Promise<EntryHead> {
  const response = await page.request.get(`${knowledgeRoot()}/${entryId}`)
  expect(response.ok()).toBe(true)
  return response.json() as Promise<EntryHead>
}

/** A member-side write outside the page under test: moves the real head so the page's next
 *  command carries a stale If-Match — the concurrent-editor shape of contract §7. */
async function concurrentPatch(title: string, ifMatch: string): Promise<APIResponse> {
  return page.request.patch(`${knowledgeRoot()}/${entryId}`, {
    data: { title, content: '另一名成员在页外保存的内容。' },
    headers: {
      [session.csrf.headerName]: session.csrf.token,
      'Idempotency-Key': crypto.randomUUID(),
      'If-Match': ifMatch,
    },
  })
}

test('the onboarding owner reaches the knowledge page and reads the real empty listing', async ({ browser }, testInfo) => {
  const suffix = `m10-${Date.now()}`.toLowerCase().replace(/[^a-z0-9]+/g, '-')
  const narrow = testInfo.project.name.includes('Narrow')
  context = await browser.newContext({
    baseURL,
    viewport: narrow ? { width: 390, height: 844 } : { width: 1440, height: 960 },
    timezoneId: 'Asia/Shanghai',
    reducedMotion: 'reduce',
  })
  page = await context.newPage()

  await register(page, `owner-${suffix}`.slice(0, 48), `owner-${suffix}@example.test`, 'F01a Owner', 'Correct-Horse-Battery-Staple-47', false)
  await expect(page).toHaveURL(/\/onboarding$/)
  await page.getByRole('textbox', { name: '团队名称' }).fill(`F01a ${suffix}`.slice(0, 96))
  await page.getByRole('button', { name: '创建团队' }).click()
  await expect(page.getByRole('heading', { name: '你的工作入口已经就绪' })).toBeVisible()

  session = await currentSession(page)
  teamId = onlyTeam(session).teamId

  await page.goto(`/knowledge?team=${teamId}`)
  await expect(page.getByRole('heading', { name: '知识库' })).toBeVisible()
  await expect(page.getByRole('region', { name: '知识条目列表' }).getByText('暂无知识条目')).toBeVisible()
})

test('the create dialog lands a real DRAFT head and the listing row opens the detail', async () => {
  const entryKey = `f01a-runbook-${Date.now()}`
  await page.getByRole('button', { name: '创建条目' }).first().click()
  const dialog = page.getByRole('dialog', { name: '创建知识条目' })
  await dialog.getByLabel('条目 Key').fill(entryKey)
  await dialog.getByLabel('标题').fill('真实栈创建的部署手册')
  await dialog.getByLabel('正文').fill('通过页面创建、由真实 A02 API 提交的第一条知识。')
  await dialog.getByRole('button', { name: '创建条目' }).click()
  await expect(dialog).toHaveCount(0)

  const listing = page.getByRole('region', { name: '知识条目列表' })
  await expect(listing.getByRole('button', { name: new RegExp(entryKey) })).toBeVisible()

  const stored = (await page.request.get(`${knowledgeRoot()}?limit=50`)).json() as Promise<{ items: EntryHead[] }>
  const created = (await stored).items.find(item => item.entryKey === entryKey)
  expect(created?.status).toBe('DRAFT')
  expect(created?.draft).toMatchObject({ title: '真实栈创建的部署手册', content: '通过页面创建、由真实 A02 API 提交的第一条知识。' })
  entryId = created!.id

  await listing.getByRole('button', { name: new RegExp(entryKey) }).click()
  await expect(page.getByRole('complementary', { name: '知识条目详情' })).toBeVisible()
  expect(new URL(page.url()).searchParams.get('entry')).toBe(entryId)
})

test('saving the draft through the page advances the real head version', async () => {
  const before = await head()
  const detail = page.getByRole('complementary', { name: '知识条目详情' })
  await detail.getByLabel('标题').fill('真实栈修订后的部署手册')
  await detail.getByLabel('正文').fill('真实栈保存后等待发布的修订正文。')
  await detail.getByRole('button', { name: '保存草稿' }).click()
  await expect(detail.getByText(/命令已确认/)).toBeVisible()

  const after = await head()
  expect(after.version).toBe(before.version + 1)
  expect(after.draft?.title).toBe('真实栈修订后的部署手册')
  expect(after.draft?.content).toBe('真实栈保存后等待发布的修订正文。')
})

test('a stale If-Match save meets the real 409 and the editor keeps the local draft', async () => {
  const detail = page.getByRole('complementary', { name: '知识条目详情' })

  // A page-outside write moves the head; the page still holds the pre-write ETag.
  const moved = await head()
  const outside = await concurrentPatch('页外并发修订', `"${moved.version}"`)
  expect(outside.status()).toBe(202)
  const serverHead = await head()
  expect(serverHead.version).toBe(moved.version + 1)

  await detail.getByLabel('标题').fill('冲突期间保留的本地修订')
  await detail.getByRole('button', { name: '保存草稿' }).click()

  await expect(detail.getByText('条目已被其他成员更新')).toBeVisible()
  await expect(detail.getByText(new RegExp(`服务端当前版本 v${serverHead.version}`))).toBeVisible()
  await expect(detail.getByLabel('标题')).toHaveValue('冲突期间保留的本地修订')

  // Recover through the offered reload so the next lifecycle step starts from the server head.
  await detail.getByRole('button', { name: '载入服务端内容' }).click()
  const discard = page.getByRole('alertdialog', { name: '放弃未保存的草稿修改？' })
  await discard.getByRole('button', { name: '放弃修改' }).click()
  await expect(detail.getByLabel('标题')).toHaveValue('页外并发修订')
})

test('publishing promotes the draft to effective revision 1 with a real version row', async () => {
  const detail = page.getByRole('complementary', { name: '知识条目详情' })
  await detail.getByRole('button', { name: '发布当前草稿' }).click()
  await expect(detail.getByText('已发布', { exact: true })).toBeVisible()
  await expect(detail.getByText('当前没有未保存的草稿')).toBeVisible()

  const published = await head()
  expect(published.status).toBe('PUBLISHED')
  expect(published.effectiveRevision).toBe(1)
  expect(published.draft).toBeNull()

  const versions = await page.request.get(`${knowledgeRoot()}/${entryId}/versions`)
  const rows = await versions.json() as { items: VersionRow[] }
  expect(rows.items).toHaveLength(1)
  expect(rows.items[0]).toMatchObject({ revision: 1, title: '页外并发修订' })

  const effective = await page.request.get(`${knowledgeRoot()}/${entryId}/effective-version`)
  expect(effective.ok()).toBe(true)
  expect(new Headers(effective.headers()).get('ETag')).toContain('"')

  await detail.getByRole('tab', { name: '版本' }).click()
  // The effective card renders revision, time and content only — the title lives in the
  // version content region, not on the card (mocked-matrix contract).
  await expect(detail.getByRole('region', { name: '生效版本' })).toContainText('另一名成员在页外保存的内容。')
})

test('retiring then deleting leaves the real tombstone on the listing', async () => {
  const detail = page.getByRole('complementary', { name: '知识条目详情' })
  await detail.getByRole('button', { name: '废弃条目' }).click()
  await expect(detail.getByText('已废弃', { exact: true })).toBeVisible()
  expect((await head()).status).toBe('RETIRED')

  await detail.getByRole('button', { name: '删除条目' }).click()
  const confirm = page.getByRole('alertdialog', { name: '删除知识条目' })
  await confirm.getByRole('checkbox').check()
  await confirm.getByRole('button', { name: '确认删除' }).click()

  await expect(detail.getByText('此条目已删除')).toBeVisible()
  expect((await head()).status).toBe('DELETED')

  // The real listing keeps the tombstone row visible for every member (§7 read side).
  const listing = page.getByRole('region', { name: '知识条目列表' })
  await expect(listing.getByRole('button', { name: /已删除/ })).toBeVisible()
  await expect(detail.getByRole('button', { name: '发布当前草稿' })).toHaveCount(0)
})

// Real distillation needs a completed Task execution plus a configured LLM provider; the gate
// script exports CREWSCOPE_M10_DISTILL_KEY only when both exist. The two receipt shapes stay
// covered by the mocked matrix, so the skip is honest (never a fake pass).
test('distillation through the page creates a real DRAFT entry', async () => {
  test.skip(!process.env.CREWSCOPE_M10_DISTILL_KEY, 'no completed task execution + LLM provider on this stack')

  const execution = process.env.CREWSCOPE_M10_DISTILL_EXECUTION
  test.skip(!execution, 'CREWSCOPE_M10_DISTILL_EXECUTION (a completed task execution id) is not set')

  const entryKey = `f01a-distill-${Date.now()}`
  await page.getByRole('button', { name: '从执行蒸馏' }).first().click()
  const dialog = page.getByRole('dialog', { name: '从执行蒸馏知识' })
  await dialog.getByLabel('任务执行 ID').fill(execution!)
  await dialog.getByLabel('目标条目 Key').fill(entryKey)
  await dialog.getByRole('button', { name: '开始蒸馏' }).click()

  const receipt = dialog.getByRole('region', { name: '蒸馏回执' })
  await expect(receipt.getByText(entryKey)).toBeVisible()
  await expect(receipt.getByText(/第 \d+ 次尝试/)).toBeVisible()

  const stored = (await page.request.get(`${knowledgeRoot()}?limit=50`)).json() as Promise<{ items: EntryHead[] }>
  const distilled = (await stored).items.find(item => item.entryKey === entryKey)
  expect(distilled?.status).toBe('DRAFT')
})
