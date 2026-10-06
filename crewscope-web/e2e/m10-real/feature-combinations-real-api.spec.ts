import { expect, test, type Browser, type BrowserContext, type Page } from '@playwright/test'
import { baseURL, currentSession, onlyTeam, register, teamPath, type Session } from '../real-backend'

/**
 * M10-Q01 mixed-combination contract (M10 plan §10.7's hybrid rows) on a real stack whose
 * shape the release gate declares through CREWSCOPE_M10Q01_STACK:
 *
 * - combo-full — every switch open, worker included, no model connection configured: a
 *   published entry's rebuild really enqueues (enqueued:1), the worker picks it up and the
 *   embedding outage fails the job closed — a FAILED row with a visible failure code, never
 *   a zombie — while the retrieval preview degrades explicitly instead of erroring.
 * - combo-index-only — pgvector + index + skill open, retrieval closed: the preview answers
 *   RETRIEVAL_DISABLED while the skill catalog still publishes — the two surfaces do not
 *   depend on each other.
 *
 * Without the declaration every test skips: the gate owns the stack shapes, a bare local
 * run must never guess which combination it is looking at.
 */

type EntryHead = { id: string, entryKey: string, status: string, version: number }
type SkillHead = { id: string, skillKey: string, status: string, version: number, effectiveRevision: number | null }

const stack = process.env.CREWSCOPE_M10Q01_STACK ?? ''

test.skip(!stack, 'the combination shape is declared by the release gate through CREWSCOPE_M10Q01_STACK')

test.describe.configure({ mode: 'serial' })

let context: BrowserContext
let page: Page
let session: Session
let teamId: string

const entriesRoot = () => teamPath(session, teamId, 'knowledge/entries')
const indexRoot = () => teamPath(session, teamId, 'knowledge/index')
const skillsRoot = () => teamPath(session, teamId, 'skills')

async function setup(browser: Browser, tag: string): Promise<void> {
  const suffix = `${tag}-${Date.now()}`.toLowerCase().replace(/[^a-z0-9]+/g, '-')
  context = await browser.newContext({
    baseURL,
    viewport: { width: 1440, height: 960 },
    timezoneId: 'Asia/Shanghai',
    reducedMotion: 'reduce',
  })
  page = await context.newPage()
  await register(page, `owner-${suffix}`.slice(0, 48), `owner-${suffix}@example.test`, `Q01 ${tag}`, 'Correct-Horse-Battery-Staple-47', false)
  await expect(page).toHaveURL(/\/onboarding$/)
  await page.getByRole('textbox', { name: '团队名称' }).fill(`Q01 ${tag} ${suffix}`.slice(0, 96))
  await page.getByRole('button', { name: '创建团队' }).click()
  await expect(page.getByRole('heading', { name: '你的工作入口已经就绪' })).toBeVisible()
  session = await currentSession(page)
  teamId = onlyTeam(session).teamId
}

test('combo-full: a rebuild without a model connection fails the job closed and visibly', async ({ browser }) => {
  test.skip(stack !== 'combo-full', 'combo-full stack only')
  await setup(browser, 'q01full')

  const entryKey = `q01-full-runbook-${Date.now()}`
  const created = await page.request.post(entriesRoot(), {
    data: { entryKey, category: 'RUNBOOK', title: '全开栈部署手册', content: '全开栈上没有模型连接，重建必须显式失败。' },
    headers: { [session.csrf.headerName]: session.csrf.token, 'Idempotency-Key': crypto.randomUUID() },
  })
  expect(created.status(), await created.text()).toBe(202)
  const stored = (await page.request.get(`${entriesRoot()}?limit=50`)).json() as Promise<{ items: EntryHead[] }>
  const draft = (await stored).items.find(item => item.entryKey === entryKey)
  const published = await page.request.post(`${entriesRoot()}/${draft!.id}/publish`, {
    headers: {
      [session.csrf.headerName]: session.csrf.token,
      'Idempotency-Key': crypto.randomUUID(),
      'If-Match': `"${draft!.version}"`,
    },
  })
  expect(published.status()).toBe(202)

  const rebuild = await page.request.post(`${indexRoot()}/rebuilds`, {
    headers: { [session.csrf.headerName]: session.csrf.token, 'Idempotency-Key': crypto.randomUUID() },
  })
  expect(rebuild.status()).toBe(202)
  expect(((await rebuild.json()) as { enqueued: number }).enqueued).toBe(1)

  // The worker owns the job; without a model connection the embedding step fails it closed.
  // Poll until the FAILED row is visible — a zombie QUEUED/PROCESSING row fails this poll.
  let failedRow: { id: string, failureCode: string | null } | undefined
  await expect.poll(async () => {
    const jobs = await page.request.get(`${indexRoot()}/jobs?status=FAILED&limit=10`)
    expect(jobs.ok()).toBe(true)
    const body = await jobs.json() as { items: Array<{ id: string, failureCode: string | null }> }
    failedRow = body.items[0]
    return body.items.length
  }, { timeout: 120_000 }).toBeGreaterThan(0)
  expect(failedRow!.failureCode, 'the FAILED row carries a visible failure code').toBeTruthy()
})

test('combo-full: the preview degrades through the embedding outage, never an error', async () => {
  test.skip(stack !== 'combo-full', 'combo-full stack only')

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
  expect(body.degraded.length, `degraded codes: ${body.degraded.join(', ')}`).toBeGreaterThan(0)

  await context.close()
})

test('combo-index-only: the preview answers RETRIEVAL_DISABLED while the skill catalog still publishes', async ({ browser }) => {
  test.skip(stack !== 'combo-index-only', 'combo-index-only stack only')
  await setup(browser, 'q01idx')

  const preview = await page.request.post(
    teamPath(session, teamId, 'knowledge/knowledge-retrieval:preview'),
    {
      data: { query: '如何部署', sources: ['KNOWLEDGE_ENTRY'], topK: 5 },
      headers: { [session.csrf.headerName]: session.csrf.token },
    },
  )
  expect(preview.status()).toBe(200)
  const previewBody = await preview.json() as { candidates: unknown[], degraded: string[] }
  expect(previewBody.candidates).toEqual([])
  expect(previewBody.degraded).toContain('RETRIEVAL_DISABLED')

  const skillKey = `q01-idx-${Date.now()}`
  const created = await page.request.post(skillsRoot(), {
    data: { skillKey, content: `---\nname: ${skillKey}\ndescription: 索引开检索关时仍可发布\n---\n\n检索关闭不影响 Skill 目录。` },
    headers: { [session.csrf.headerName]: session.csrf.token, 'Idempotency-Key': crypto.randomUUID() },
  })
  expect(created.status()).toBe(202)
  const stored = (await page.request.get(`${skillsRoot()}?limit=50`)).json() as Promise<{ items: SkillHead[] }>
  const draft = (await stored).items.find(item => item.skillKey === skillKey)
  expect(draft).toBeDefined()
  const published = await page.request.post(`${skillsRoot()}/${draft!.id}/publish`, {
    headers: {
      [session.csrf.headerName]: session.csrf.token,
      'Idempotency-Key': crypto.randomUUID(),
      'If-Match': `"${draft!.version}"`,
    },
  })
  expect(published.status()).toBe(202)
  const head = (await page.request.get(`${skillsRoot()}/${draft!.id}`)).json() as Promise<SkillHead>
  expect((await head).status).toBe('PUBLISHED')
  expect((await head).effectiveRevision).toBe(1)

  await context.close()
})
