import { expect, test, type BrowserContext, type Page } from '@playwright/test'
import {
  baseURL,
  command,
  currentSession,
  getJson,
  onlyTeam,
  teamPath,
  type Session,
} from '../real-backend'

/**
 * M9b-Q02 mobile main path (§9.2 移动端 row) on the real backend at 390×844 with touch enabled:
 * viewing today's work, creating a work item, opening the delegation form, posting a follow-up
 * message, and reaching the review surface — every control tappable, nothing mouse-only, no
 * horizontal overflow. The keyboard row (Tab/Enter through the primary path) rides along: the
 * registration and team creation submits go through Enter, and Tab reaches the primary action.
 * Model-backed assertions (a real reply, a real coding start) are re-verified in real-provider
 * and github-coding-pr; this spec pins what the narrow viewport must prove on any stack.
 */
let contextA: BrowserContext
let pageA: Page
let sessionA: Session
let teamId: string
let workItemId: string

test.describe.configure({ mode: 'serial' })

test('an empty account signs up and builds a team at 390px with touch and Enter submits', async ({ browser }) => {
  const suffix = `mob-${Date.now()}`.toLowerCase().replace(/[^a-z0-9]+/g, '-')
  contextA = await browser.newContext({
    baseURL,
    viewport: { width: 390, height: 844 },
    hasTouch: true,
    isMobile: true,
    timezoneId: 'Asia/Shanghai',
    reducedMotion: 'reduce',
  })
  pageA = await contextA.newPage()
  await pageA.goto('/register')
  await pageA.getByRole('textbox', { name: '用户名' }).fill(`mob-${suffix}`.slice(0, 48))
  await pageA.getByRole('textbox', { name: '邮箱' }).fill(`mob-${suffix}@example.test`)
  await pageA.getByRole('textbox', { name: '展示名' }).fill('Q02 移动用户')
  await pageA.locator('input[name="password"]').fill('Correct-Horse-Battery-Staple-47')
  // The keyboard row: Tab walks the form natively and Enter submits it. The password field's
  // visibility toggle is a real tab stop, so the submit control is the second Tab away.
  await pageA.keyboard.press('Tab')
  await expect(pageA.getByRole('button', { name: '显示密码' })).toBeFocused()
  await pageA.keyboard.press('Tab')
  await expect(pageA.locator('form button[type="submit"]')).toBeFocused()
  await pageA.keyboard.press('Enter')
  await expect(pageA).toHaveURL(/\/onboarding$/)

  await pageA.getByRole('textbox', { name: '团队名称' }).fill(`Q02 移动 ${suffix}`.slice(0, 96))
  await pageA.getByRole('button', { name: '创建团队' }).tap()
  await expect(pageA.getByRole('heading', { name: '你的工作入口已经就绪' })).toBeVisible()
  sessionA = await currentSession(pageA)
  teamId = onlyTeam(sessionA).teamId
})

test('today renders its narrow surface with the mobile menu toggle and no overflow', async () => {
  await pageA.goto(`/today?team=${teamId}`)
  await expect(pageA.getByRole('heading', { name: '今日' })).toBeVisible()
  await expect(pageA.locator('.mobile-menu-toggle')).toBeVisible()
  const overflow = await pageA.evaluate(() =>
    document.documentElement.scrollWidth - document.documentElement.clientWidth)
  expect(overflow).toBeLessThanOrEqual(1)
})

test('a work item is created through the touch dialog at 390px with no key field', async () => {
  const suffix = `mwi-${Date.now()}`.toLowerCase().replace(/[^a-z0-9]+/g, '-')
  const projectsPath = teamPath(sessionA, teamId, 'work-projects')
  await command(pageA, sessionA, projectsPath, { name: `Q02 移动计划 ${suffix}`.slice(0, 96) })
  await expect.poll(async () => {
    const list = await getJson<{ items: Array<{ id: string, name: string }> }>(pageA, `${projectsPath}?limit=50`)
    return list.items.find(item => item.name.includes(suffix))?.id ?? null
  }).not.toBeNull()
  const projectId = (await getJson<{ items: Array<{ id: string, name: string }> }>(
    pageA, `${projectsPath}?limit=50`)).items.find(item => item.name.includes(suffix))!.id
  const workItemsPath = `${projectsPath}/${projectId}/work-items`

  await pageA.goto(`/work?team=${teamId}&project=${projectId}`)
  await pageA.getByRole('button', { name: '新建工作项' }).first().tap()
  const dialog = pageA.getByRole('dialog', { name: '新建工作项' })
  await expect(dialog).toBeVisible()
  const keyishLabels = await dialog.locator('label').evaluateAll(labels =>
    labels.map(label => (label.textContent ?? '').trim()).filter(Boolean))
  expect(keyishLabels.some(label => /[Kk]ey|编号|标识/.test(label))).toBe(false)
  const title = `Q02 移动工作项 ${suffix}`.slice(0, 96)
  await dialog.getByLabel('标题', { exact: false }).tap()
  await dialog.getByLabel('标题', { exact: false }).fill(title)
  await dialog.getByRole('button', { name: '创建' }).tap()
  await expect.poll(async () => {
    const list = await getJson<{ items: Array<{ id: string, title: string }> }>(pageA, `${workItemsPath}?limit=50`)
    return list.items.find(item => item.title === title)?.id ?? null
  }).not.toBeNull()
  workItemId = (await getJson<{ items: Array<{ id: string, title: string }> }>(
    pageA, `${workItemsPath}?limit=50`)).items.find(item => item.title === title)!.id

  const overflow = await pageA.evaluate(() =>
    document.documentElement.scrollWidth - document.documentElement.clientWidth)
  expect(overflow).toBeLessThanOrEqual(1)
})

test('the delegation form opens by touch and surfaces its guided state at 390px', async () => {
  await pageA.goto(`/work?team=${teamId}&workItem=${workItemId}`)
  const drawer = pageA.getByRole('dialog').filter({ hasText: /Q02 移动工作项/ }).first()
  await expect(drawer).toBeVisible()
  await drawer.getByRole('button', { name: '交给 Agent 处理' }).first().tap()
  const delegate = pageA.getByRole('dialog', { name: '交给 Agent 处理' })
  await expect(delegate).toBeVisible()
  await expect(delegate.getByText('这个执行范围没有可用模型 Binding。')).toBeVisible()
  const overflow = await pageA.evaluate(() =>
    document.documentElement.scrollWidth - document.documentElement.clientWidth)
  expect(overflow).toBeLessThanOrEqual(1)
  await delegate.getByRole('button', { name: '取消', exact: true }).tap()
  await expect(pageA.getByRole('dialog', { name: '交给 Agent 处理' })).toHaveCount(0)
})

test('a follow-up message posts from the touch composer at 390px', async () => {
  const suffix = `mmsg-${Date.now()}`.toLowerCase().replace(/[^a-z0-9]+/g, '-')
  const conversationsPath = teamPath(sessionA, teamId, 'conversations')
  await command(pageA, sessionA, conversationsPath, { title: `Q02 移动对话 ${suffix}`.slice(0, 96), visibility: 'TEAM' })
  await expect.poll(async () => {
    const list = await getJson<{ items: Array<{ id: string, title: string }> }>(pageA, `${conversationsPath}?limit=50`)
    return list.items.find(item => item.title.includes(suffix))?.id ?? null
  }).not.toBeNull()
  const conversationId = (await getJson<{ items: Array<{ id: string, title: string }> }>(
    pageA, `${conversationsPath}?limit=50`)).items.find(item => item.title.includes(suffix))!.id

  await pageA.goto(`/conversation?team=${teamId}&conversation=${conversationId}`)
  const composer = pageA.locator('#message-composer-textarea, textarea[name="content"], .conversation-composer textarea').first()
  await expect(composer).toBeVisible()
  await composer.tap()
  const message = `Q02 移动补充 ${suffix}`
  await composer.fill(message)
  await pageA.getByRole('button', { name: '发送' }).tap()
  await expect.poll(async () => {
    const history = await getJson<{ items: Array<{ content: string }> }>(
      pageA, `${conversationsPath}/${conversationId}/messages?limit=50`)
    return history.items.some(item => item.content === message)
  }).toBe(true)
})

test('the review surface shows responsibility and a tappable comment box at 390px', async () => {
  await pageA.goto(`/work?team=${teamId}&workItem=${workItemId}`)
  const drawer = pageA.getByRole('dialog').filter({ hasText: /Q02 移动工作项/ }).first()
  await expect(drawer).toBeVisible()
  // The name also shows up in selects and the timeline; the responsibility chain is the assertion.
  await expect(drawer.getByLabel('责任链').getByText('Q02 移动用户')).toBeVisible()
  const commentBox = drawer.getByLabel('添加评论')
  await expect(commentBox).toBeVisible()
  await commentBox.tap()
  const overflow = await pageA.evaluate(() =>
    document.documentElement.scrollWidth - document.documentElement.clientWidth)
  expect(overflow).toBeLessThanOrEqual(1)
})

test.afterAll(async () => {
  await contextA?.close()
})
