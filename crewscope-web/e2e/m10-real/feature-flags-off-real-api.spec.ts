import { expect, test, type BrowserContext, type Page } from '@playwright/test'
import { baseURL, currentSession, onlyTeam, register, teamPath, type Session } from '../real-backend'

/**
 * M10-Q01 all-off combination contract (M10 plan §10.7's「全部关闭」row): a plain PostgreSQL
 * stack with every M10 switch closed keeps its defined behaviour on each surface — knowledge
 * management stays fully writable, the index trigger answers 202 enqueued:0 while the read
 * plane stays 200, retrieval preview degrades through RETRIEVAL_DISABLED (200, never an
 * error), the skill catalog reads 200 and answers 422 skill_disabled before permissions,
 * assistant memory stays viewable and clearable, and the observability planes keep their
 * honest empty shapes. No degraded surface may turn into an implicit error, and no write
 * gate may turn into a silent pass.
 */

type EntryHead = { id: string, entryKey: string, status: string, version: number, effectiveRevision: number | null }
type ProfileSummary = { id: string, ownershipType: string }

test.describe.configure({ mode: 'serial' })

let context: BrowserContext
let page: Page
let session: Session
let teamId: string
let entryId: string

const entriesRoot = () => teamPath(session, teamId, 'knowledge/entries')
const indexRoot = () => teamPath(session, teamId, 'knowledge/index')
const skillsRoot = () => teamPath(session, teamId, 'skills')
const profilesRoot = () => teamPath(session, teamId, 'agent-profiles')
const observabilityRoot = () => teamPath(session, teamId, 'observability')

test('the all-off stack onboards and knowledge management stays fully writable', async ({ browser }) => {
  const suffix = `q01off-${Date.now()}`.toLowerCase().replace(/[^a-z0-9]+/g, '-')
  context = await browser.newContext({
    baseURL,
    viewport: { width: 1440, height: 960 },
    timezoneId: 'Asia/Shanghai',
    reducedMotion: 'reduce',
  })
  page = await context.newPage()
  await register(page, `owner-${suffix}`.slice(0, 48), `owner-${suffix}@example.test`, 'Q01 AllOff', 'Correct-Horse-Battery-Staple-47', false)
  await expect(page).toHaveURL(/\/onboarding$/)
  await page.getByRole('textbox', { name: '团队名称' }).fill(`Q01 Off ${suffix}`.slice(0, 96))
  await page.getByRole('button', { name: '创建团队' }).click()
  await expect(page.getByRole('heading', { name: '你的工作入口已经就绪' })).toBeVisible()
  session = await currentSession(page)
  teamId = onlyTeam(session).teamId

  const entryKey = `q01-off-runbook-${Date.now()}`
  const created = await page.request.post(entriesRoot(), {
    data: { entryKey, category: 'RUNBOOK', title: '全关栈部署手册', content: '开关全部关闭时知识管理照常可用。' },
    headers: { [session.csrf.headerName]: session.csrf.token, 'Idempotency-Key': crypto.randomUUID() },
  })
  expect(created.status(), await created.text()).toBe(202)
  const stored = (await page.request.get(`${entriesRoot()}?limit=50`)).json() as Promise<{ items: EntryHead[] }>
  const draft = (await stored).items.find(item => item.entryKey === entryKey)
  expect(draft?.status).toBe('DRAFT')

  const published = await page.request.post(`${entriesRoot()}/${draft!.id}/publish`, {
    headers: {
      [session.csrf.headerName]: session.csrf.token,
      'Idempotency-Key': crypto.randomUUID(),
      'If-Match': `"${draft!.version}"`,
    },
  })
  expect(published.status()).toBe(202)
  const head = (await page.request.get(`${entriesRoot()}/${draft!.id}`)).json() as Promise<EntryHead>
  expect((await head).status).toBe('PUBLISHED')
  expect((await head).effectiveRevision).toBe(1)
  entryId = (await head).id
})

test('a rebuild answers 202 enqueued:0 while the index gate is closed, and the read plane stays 200', async () => {
  const rebuild = await page.request.post(`${indexRoot()}/rebuilds`, {
    headers: { [session.csrf.headerName]: session.csrf.token, 'Idempotency-Key': crypto.randomUUID() },
  })
  expect(rebuild.status()).toBe(202)
  expect(((await rebuild.json()) as { enqueued: number }).enqueued).toBe(0)

  const jobs = await page.request.get(`${indexRoot()}/jobs?limit=10`)
  expect(jobs.ok()).toBe(true)
  expect(((await jobs.json()) as { items: unknown[] }).items).toEqual([])
})

test('retrieval preview degrades through RETRIEVAL_DISABLED, never an error', async () => {
  const preview = await page.request.post(
    teamPath(session, teamId, 'knowledge/knowledge-retrieval:preview'),
    {
      data: { query: '如何部署', sources: ['KNOWLEDGE_ENTRY'], topK: 5 },
      headers: { [session.csrf.headerName]: session.csrf.token },
    },
  )
  expect(preview.status()).toBe(200)
  expect(preview.headers()['cache-control']).toBe('no-store')
  const body = await preview.json() as { candidates: unknown[], degraded: string[] }
  expect(body.candidates).toEqual([])
  expect(body.degraded).toContain('RETRIEVAL_DISABLED')
})

test('the skill catalog reads 200 and answers 422 skill_disabled on writes before permissions', async () => {
  const listing = await page.request.get(`${skillsRoot()}?limit=50`)
  expect(listing.ok()).toBe(true)
  expect(((await listing.json()) as { items: unknown[] }).items).toEqual([])

  const create = await page.request.post(skillsRoot(), {
    data: { skillKey: 'q01-off-probe', content: '---\nname: q01-off-probe\ndescription: 关闸探测\n---\n\n正文。' },
    headers: { [session.csrf.headerName]: session.csrf.token, 'Idempotency-Key': crypto.randomUUID() },
  })
  expect(create.status()).toBe(422)
  expect(((await create.json()) as { code: string }).code).toBe('skill_disabled')

  // The gate answers before permissions: even an unknown id never reaches not-found.
  const patch = await page.request.patch(`${skillsRoot()}/00000000-0000-1000-8000-000000009901`, {
    data: { content: '---\nname: q01-off-probe\ndescription: 关闸探测\n---\n\n正文。' },
    headers: {
      [session.csrf.headerName]: session.csrf.token,
      'Idempotency-Key': crypto.randomUUID(),
      'If-Match': '"1"',
    },
  })
  expect(patch.status()).toBe(422)
  expect(((await patch.json()) as { code: string }).code).toBe('skill_disabled')
})

test('assistant memory stays viewable and clearable with the switch off', async () => {
  const profiles = (await page.request.get(profilesRoot())).json() as Promise<{ items: ProfileSummary[] }>
  const profileId = (await profiles).items.find(item => item.ownershipType === 'USER')!.id

  const view = await page.request.get(`${profilesRoot()}/${profileId}/memory`)
  expect(view.status(), await view.text()).toBe(200)
  const viewBody = await view.json() as { entryCount: number, clearanceGeneration: number }
  expect(viewBody.entryCount).toBe(0)

  const clear = await page.request.delete(`${profilesRoot()}/${profileId}/memory`, {
    headers: { [session.csrf.headerName]: session.csrf.token },
  })
  expect(clear.status(), await clear.text()).toBe(200)
  expect(await clear.json()).toEqual({ clearedCount: 0, clearanceGeneration: 1 })
})

test('injection references keep the 404 shape and observability keeps its empty structures', async () => {
  const references = await page.request.get(
    teamPath(session, teamId, `tasks/${crypto.randomUUID()}/attempts/${crypto.randomUUID()}/injection-references`))
  expect(references.status()).toBe(404)
  expect(((await references.json()) as { code: string }).code).toBe('aggregate_not_found')

  const months = await page.request.get(`${observabilityRoot()}/cost/months`)
  expect(months.ok()).toBe(true)
  expect(months.headers()['cache-control']).toBe('no-store')
  expect(((await months.json()) as { months: unknown[], nextAfter: string | null }).months).toEqual([])

  const quality = await page.request.get(`${observabilityRoot()}/quality/months/2020-01`)
  expect(quality.ok()).toBe(true)
  expect(((await quality.json()) as { executionAttempts: { total: number, successRate: number | null } })
    .executionAttempts).toMatchObject({ total: 0, successRate: null })

  // The published entry stays readable through the whole run — degradation never eats CRUD.
  const head = await page.request.get(`${entriesRoot()}/${entryId}`)
  expect(head.ok()).toBe(true)
  await context.close()
})
