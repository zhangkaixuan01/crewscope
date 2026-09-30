import { networkInterfaces } from 'node:os'
import { expect, test, type BrowserContext, type Page } from '@playwright/test'
import {
  baseURL,
  command,
  currentSession,
  getJson,
  onlyTeam,
  register,
  teamPath,
  type Session,
} from '../real-backend'

/**
 * M9b-Q02 default deployment, non-loopback HTTP, empty-account first use, and the configuration
 * path — the real-stack tier of the §9.2 rows that the mocked matrix cannot prove:
 *
 * - the four-service Compose stack answers on plain HTTP before any Provider/GitHub exists;
 * - a non-loopback origin (non-secure context, no crypto.randomUUID) still completes the key
 *   submissions — the F01 secureId fix verified on a real deployment instead of a condition mock;
 * - an empty account builds a Team through the pages, reaches Today and conversations with no
 *   Provider configured at all (API-only startup), and a plain team message still works while an
 *   Agent invocation surfaces guidance instead of a crash;
 * - the Setup centre leads goal-first and round-trips back to its registered origin (F02 contract);
 * - a first work item is created through the page dialog with no internal-key field (A01), and a
 *   delegate preflight gap round-trips through settings and reopens the draft.
 */

function nonLoopbackIPv4(): string | null {
  for (const addresses of Object.values(networkInterfaces())) {
    for (const address of addresses ?? []) {
      if (address.family === 'IPv4' && !address.internal) return address.address
    }
  }
  return null
}

const lanAddress = nonLoopbackIPv4()
const lanBaseURL = lanAddress
  ? `http://${lanAddress}:${new URL(baseURL).port}`
  : null

let contextA: BrowserContext
let pageA: Page
let sessionA: Session
let teamId: string

test.describe.configure({ mode: 'serial' })

test('the default deployment answers over loopback HTTP with an anonymous session', async ({ request }) => {
  const response = await request.get('/api/v1/auth/session')
  expect(response.status()).toBe(200)
  const anonymous = await response.json() as { authenticated: boolean, csrf: { headerName: string } }
  expect(anonymous.authenticated).toBe(false)
  expect(anonymous.csrf.headerName).toBeTruthy()
})

test('non-loopback HTTP completes the key submissions (R01 real tier)', async ({ browser }) => {
  test.skip(!lanBaseURL, `no non-loopback IPv4 interface on this host (base ${baseURL})`)
  const context = await browser.newContext({
    baseURL: lanBaseURL!,
    viewport: { width: 1440, height: 960 },
    timezoneId: 'Asia/Shanghai',
    reducedMotion: 'reduce',
  })
  const page = await context.newPage()
  try {
    // Proof the origin really is the non-secure context the R01 defect lived in.
    await page.goto('/register')
    expect(await page.evaluate(() => window.isSecureContext)).toBe(false)
    expect(await page.evaluate(() => typeof crypto.randomUUID)).toBe('undefined')

    const suffix = `lan-${Date.now()}`.toLowerCase().replace(/[^a-z0-9]+/g, '-')
    await register(page, `lan-${suffix}`.slice(0, 48), `lan-${suffix}@example.test`, 'Q02 LAN 用户', 'Correct-Horse-Battery-Staple-47', false)
    await expect(page).toHaveURL(/\/onboarding$/)
    await page.getByRole('textbox', { name: '团队名称' }).fill(`Q02 LAN ${suffix}`.slice(0, 96))
    await page.getByRole('button', { name: '创建团队' }).click()
    await expect(page.getByRole('heading', { name: '你的工作入口已经就绪' })).toBeVisible()
  } finally {
    await context.close()
  }
})

test('an empty account builds a team and stays usable with no provider configured (API-only)', async ({ browser }) => {
  const suffix = `q02-${Date.now()}`.toLowerCase().replace(/[^a-z0-9]+/g, '-')
  // The serial tests share one signed-in page across tests, so it lives in an explicitly owned
  // context — the per-test fixture context is torn down when this test ends.
  contextA = await browser.newContext({
    baseURL,
    viewport: { width: 1440, height: 960 },
    timezoneId: 'Asia/Shanghai',
    reducedMotion: 'reduce',
  })
  pageA = await contextA.newPage()
  await register(pageA, `owner-${suffix}`.slice(0, 48), `owner-${suffix}@example.test`, 'Q02 Owner', 'Correct-Horse-Battery-Staple-47', false)
  await expect(pageA).toHaveURL(/\/onboarding$/)
  await pageA.getByRole('textbox', { name: '团队名称' }).fill(`Q02 ${suffix}`.slice(0, 96))
  await pageA.getByRole('button', { name: '创建团队' }).click()
  await expect(pageA.getByRole('heading', { name: '你的工作入口已经就绪' })).toBeVisible()

  sessionA = await currentSession(pageA)
  teamId = onlyTeam(sessionA).teamId

  // Today and the conversation mode render their empty states — no provider has ever existed.
  await pageA.goto(`/today?team=${teamId}`)
  await expect(pageA.getByRole('heading', { name: '今日' })).toBeVisible()
  await pageA.goto(`/conversation?team=${teamId}`)
  await expect(pageA.getByRole('heading', { name: '团队对话', exact: true })).toBeVisible()
  await expect(pageA.getByRole('heading', { name: '正在加载对话' })).toHaveCount(0)
})

test('a plain team message works without a model while an agent invocation surfaces guidance', async () => {
  const suffix = `q02-msg-${Date.now()}`.toLowerCase().replace(/[^a-z0-9]+/g, '-')
  const conversationsPath = teamPath(sessionA, teamId, 'conversations')
  await command(pageA, sessionA, conversationsPath, { title: `Q02 团队对话 ${suffix}`.slice(0, 96), visibility: 'TEAM' })
  await expect.poll(async () => {
    const list = await getJson<{ items: Array<{ id: string, title: string }> }>(pageA, `${conversationsPath}?limit=50`)
    return list.items.find(item => item.title.includes(suffix))?.id ?? null
  }).not.toBeNull()
  const teamConversationId = (await getJson<{ items: Array<{ id: string, title: string }> }>(
    pageA, `${conversationsPath}?limit=50`)).items.find(item => item.title.includes(suffix))!.id

  // A team-visibility conversation has no personal agent, so posting is a plain message.
  await pageA.goto(`/conversation?team=${teamId}&conversation=${teamConversationId}`)
  const composer = pageA.locator('#message-composer-textarea, textarea[name="content"], .conversation-composer textarea').first()
  await expect(composer).toBeVisible()
  const plainMessage = `Q02 plain team message ${suffix}`
  await composer.fill(plainMessage)
  await pageA.getByRole('button', { name: '发送' }).click()
  await expect.poll(async () => {
    const history = await getJson<{ items: Array<{ content: string }> }>(
      pageA, `${conversationsPath}/${teamConversationId}/messages?limit=50`)
    return history.items.some(item => item.content === plainMessage)
  }).toBe(true)

  // The personal conversation invokes the agent with no binding configured: the page must
  // surface guidance (error copy naming the model/agent) and stay interactive — never a crash
  // loop or a silent dead composer.
  await command(pageA, sessionA, conversationsPath, { title: `Q02 个人对话 ${suffix}`.slice(0, 96), visibility: 'PRIVATE' })
  await expect.poll(async () => {
    const list = await getJson<{ items: Array<{ id: string, title: string }> }>(pageA, `${conversationsPath}?limit=50`)
    return list.items.find(item => item.title.includes('个人对话'))?.id ?? null
  }).not.toBeNull()
  const personalConversationId = (await getJson<{ items: Array<{ id: string, title: string }> }>(
    pageA, `${conversationsPath}?limit=50`)).items.find(item => item.title.includes('个人对话'))!.id
  await pageA.goto(`/conversation?team=${teamId}&conversation=${personalConversationId}`)
  await expect(pageA.locator('.message-history, [data-virtual-key]').first()).toBeVisible()
  const personalComposer = pageA.locator('#message-composer-textarea, textarea[name="content"], .conversation-composer textarea').first()
  await expect(personalComposer).toBeVisible()
  await personalComposer.fill(`Q02 agent attempt ${suffix}`)
  await pageA.getByRole('button', { name: '发送' }).click()
  await expect.poll(() =>
    pageA.getByText(/无法回复|模型|Agent/).first().isVisible(),
  ).toBe(true)
  await expect(personalComposer).toBeVisible()
})

test('the setup centre leads goal-first and round-trips back to its registered origin', async () => {
  await pageA.goto(`/setup?team=${teamId}`)
  await expect(pageA.getByRole('heading', { name: '先选一个目标' })).toBeVisible()
  const goals = pageA.getByRole('region', { name: '先选一个目标' })
  const conversation = goals.getByRole('article').filter({ hasText: '先开始对话' })
  const coding = goals.getByRole('article').filter({ hasText: '先开始 Coding' })
  await expect(conversation.getByText(/还需 \d+ 项/)).toBeVisible()
  await expect(coding.getByText(/还需 \d+ 项/)).toBeVisible()

  // The conversation goal's next step on an empty stack configures the model or the agent;
  // either way the registered origin is the setup centre and the way back is explicit.
  const nextStep = conversation.getByRole('button', { name: /^配置/ }).first()
  await nextStep.click()
  await expect(pageA).toHaveURL(/\/settings\/(agents|models)/)
  const outgoing = new URL(pageA.url())
  expect(outgoing.searchParams.get('from')).toBe('setup')
  expect(outgoing.searchParams.get('team')).toBe(teamId)
  await expect(pageA.getByRole('button', { name: '返回配置中心' })).toBeVisible()
  await pageA.getByRole('button', { name: '返回配置中心' }).click()
  await expect(pageA.getByRole('heading', { name: '先选一个目标' })).toBeVisible()
  const returning = new URL(pageA.url())
  expect(returning.pathname).toBe('/setup')
  expect(returning.searchParams.get('from')).toBeNull()
})

test('a first work item is created through the page dialog with no internal-key field', async () => {
  const suffix = `wi-${Date.now()}`.toLowerCase().replace(/[^a-z0-9]+/g, '-')
  const projectsPath = teamPath(sessionA, teamId, 'work-projects')
  await command(pageA, sessionA, projectsPath, { name: `Q02 计划 ${suffix}`.slice(0, 96) })
  await expect.poll(async () => {
    const list = await getJson<{ items: Array<{ id: string, name: string }> }>(pageA, `${projectsPath}?limit=50`)
    return list.items.find(item => item.name.includes(suffix))?.id ?? null
  }).not.toBeNull()
  const projectId = (await getJson<{ items: Array<{ id: string, name: string }> }>(
    pageA, `${projectsPath}?limit=50`)).items.find(item => item.name.includes(suffix))!.id

  await pageA.goto(`/work?team=${teamId}&project=${projectId}`)
  await pageA.getByRole('button', { name: '新建工作项' }).first().click()
  const dialog = pageA.getByRole('dialog', { name: '新建工作项' })
  await expect(dialog).toBeVisible()
  // A01: the form asks for business fields only — no key/identifier input exists at all.
  const keyishLabels = await dialog.locator('label').evaluateAll(labels =>
    labels.map(label => (label.textContent ?? '').trim()).filter(Boolean))
  expect(keyishLabels.some(label => /[Kk]ey|编号|标识/.test(label))).toBe(false)

  const title = `Q02 首用工作项 ${suffix}`.slice(0, 96)
  await dialog.getByLabel('标题', { exact: false }).fill(title)
  await dialog.getByRole('button', { name: '创建' }).click()
  await expect(pageA.getByText(title).first()).toBeVisible()

  // The created item is addressable through the read side — server-assigned key, never typed.
  const workItemsPath = `${projectsPath}/${projectId}/work-items`
  await expect.poll(async () => {
    const list = await getJson<{ items: Array<{ id: string, key: string, title: string }> }>(pageA, `${workItemsPath}?limit=50`)
    return list.items.find(item => item.title === title) ?? null
  }).not.toBeNull()
  const created = (await getJson<{ items: Array<{ id: string, key: string, title: string }> }>(
    pageA, `${workItemsPath}?limit=50`)).items.find(item => item.title === title)!
  expect(created.key).toMatch(/^[A-Za-z0-9-]+$/)
})

test('a delegate preflight gap round-trips through settings and reopens the draft', async () => {
  const suffix = `dl-${Date.now()}`.toLowerCase().replace(/[^a-z0-9]+/g, '-')
  const projectsPath = teamPath(sessionA, teamId, 'work-projects')
  const projects = await getJson<{ items: Array<{ id: string, name: string }> }>(pageA, `${projectsPath}?limit=50`)
  const project = projects.items.find(item => item.name.includes('Q02 计划'))!
  const workItemsPath = `${projectsPath}/${project.id}/work-items`
  const workItems = await getJson<{ items: Array<{ id: string, key: string, title: string }> }>(pageA, `${workItemsPath}?limit=50`)
  const workItem = workItems.items[0]!

  await pageA.goto(`/work?team=${teamId}&project=${project.id}&workItem=${workItem.id}`)
  const drawer = pageA.getByRole('dialog').filter({ hasText: workItem.title }).first()
  await expect(drawer).toBeVisible()
  await drawer.getByRole('button', { name: '交给 Agent 处理' }).first().click()
  const delegate = pageA.getByRole('dialog', { name: '交给 Agent 处理' })
  await expect(delegate).toBeVisible()

  // No model binding exists on this stack, so the model-side preflight fails with the exact
  // contract copy and a configuration round trip instead of a dead end.
  await expect(delegate.getByText('这个执行范围没有可用模型 Binding。')).toBeVisible()
  const draftText = `Q02 委托草稿 ${suffix}`
  await delegate.getByLabel('执行目标').fill(draftText)

  await delegate.getByRole('button', { name: '去配置模型并返回' }).click()
  await expect(pageA.getByRole('heading', { name: '模型与凭证' })).toBeVisible()
  const outgoing = new URL(pageA.url())
  expect(outgoing.pathname).toBe('/settings/models')
  expect(outgoing.searchParams.get('from')).toBe('work')
  expect(outgoing.searchParams.get('team')).toBe(teamId)
  expect(outgoing.searchParams.get('project')).toBe(project.id)
  expect(outgoing.searchParams.get('workItem')).toBe(workItem.id)
  expect(outgoing.searchParams.get('delegate')).toBe('coding')
  // Credentials and draft text never enter the query string (F02 §4.2).
  expect(pageA.url()).not.toContain(encodeURIComponent(draftText))

  await pageA.getByRole('button', { name: '返回工作项' }).click()
  const reopened = pageA.getByRole('dialog', { name: '交给 Agent 处理' })
  await expect(reopened).toBeVisible()
  await expect(reopened.getByLabel('执行目标')).toHaveValue(draftText)
})
