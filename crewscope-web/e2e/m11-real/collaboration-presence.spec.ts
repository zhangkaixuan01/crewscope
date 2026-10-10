import { expect, test, type Browser, type BrowserContext, type Page } from '@playwright/test'
import { baseURL, command, currentSession, getJson, onlyTeam, register, teamPath, type Session } from '../real-backend'

/**
 * M11-F01 real-stack presence acceptance: two browser sessions — the onboarding owner
 * and an invited MEMBER — in one Team and one conversation, observed only through the
 * product UI on the deployed Nginx entry point. Four rows of the F01 acceptance: mutual
 * presence through the snapshot/delta dance, typing with the welcome-window self-clear,
 * the remote-change banner that never overwrites a dirty editor (ADR-032's interruption
 * red line), and presence leaving when the peer's page closes.
 */

test.describe.configure({ mode: 'serial' })

let contextA: BrowserContext
let contextB: BrowserContext
let pageA: Page
let pageB: Page
let sessionA: Session
let sessionB: Session
let teamId: string
let conversationId: string
let suffix: string
let conversationPath: string

const password = 'Correct-Horse-Battery-Staple-47'

test.beforeAll(async ({ browser }: { browser: Browser }) => {
  suffix = `f01p-${Date.now()}`.toLowerCase().replace(/[^a-z0-9]+/g, '-')
  contextA = await browser.newContext({
    baseURL,
    viewport: { width: 1440, height: 960 },
    timezoneId: 'Asia/Shanghai',
    reducedMotion: 'reduce',
  })
  pageA = await contextA.newPage()
  await register(pageA, `alice-${suffix}`.slice(0, 48), `alice-${suffix}@example.test`, 'Alice F01', password, false)
  await expect(pageA).toHaveURL(/\/onboarding$/)
  await pageA.getByRole('textbox', { name: '团队名称' }).fill(`F01 在场 ${suffix}`.slice(0, 96))
  await pageA.getByRole('button', { name: '创建团队' }).click()
  await expect(pageA.getByRole('heading', { name: '你的工作入口已经就绪' })).toBeVisible()
  sessionA = await currentSession(pageA)
  teamId = onlyTeam(sessionA).teamId

  // The invitation rail from m7-two-user-real: the link is the only way a second
  // account joins this Team on a real stack.
  await pageA.goto(`/team/members?team=${teamId}&tab=invitations`)
  await pageA.getByRole('button', { name: '创建邀请' }).first().click()
  await pageA.locator('input[name="invitationEmail"]').fill(`bob-${suffix}@example.test`)
  await pageA.locator('select[name="invitationRole"]').selectOption('MEMBER')
  await pageA.getByRole('button', { name: '创建邀请链接' }).click()
  const invitationLink = await pageA
    .getByRole('textbox', { name: '一次性邀请链接' })
    .inputValue()
  expect(invitationLink).toMatch(/\/invite#token=[A-Za-z0-9_-]{43}$/)

  contextB = await browser.newContext({
    baseURL,
    viewport: { width: 1440, height: 960 },
    timezoneId: 'Asia/Shanghai',
    reducedMotion: 'reduce',
  })
  pageB = await contextB.newPage()
  await pageB.goto(invitationLink)
  await pageB.getByRole('button', { name: '创建账号并加入团队' }).click()
  await expect(pageB).toHaveURL(/\/register$/)
  // The banner means the invite token is parsed and the form is in its joined-registration
  // shape — submitting before it risks a plain (team-less) registration.
  await expect(pageB.getByText('已安全载入团队邀请')).toBeVisible()
  await register(pageB, `bob-${suffix}`.slice(0, 48), `bob-${suffix}@example.test`, 'Bob F01', password, true)
  // register() returns on the click, not the landing: the session read must wait for the
  // joined member's redirect, or it races the registration POST (RegisterPage → /conversation).
  await expect(pageB).toHaveURL(new RegExp(`/conversation\\?team=${teamId}`))
  sessionB = await currentSession(pageB)
  expect(onlyTeam(sessionB).teamId).toBe(teamId)

  // The shared conversation both browsers will sit in, with Bob as a participant so
  // the composer and the subscription authorizer both see him.
  const conversationsPath = teamPath(sessionA, teamId, 'conversations')
  await command(pageA, sessionA, conversationsPath, { title: `F01 协作 ${suffix}`.slice(0, 96), visibility: 'TEAM' })
  conversationId = await waitForItem(pageA, conversationsPath, suffix)
  conversationPath = `${conversationsPath}/${conversationId}`
  await command(pageA, sessionA, `${conversationPath}/participants`, { userPrincipalId: sessionB.principal!.principalId })
})

test.afterAll(async () => {
  await contextA.close()
  await contextB.close()
})

test('two browsers on one conversation see each other through the open channel', async () => {
  const url = `/conversation?team=${teamId}&conversation=${conversationId}`
  await pageA.goto(url)
  await pageB.goto(url)
  await expect(pageA.getByLabel('发送消息')).toBeVisible()
  await expect(pageB.getByLabel('发送消息')).toBeVisible()

  // Mutual presence: each side's roster names the other (the snapshot also carries
  // the subscriber, so the assertion matches on the peer, not the full label).
  await expect(pageA.locator('.collaboration-strip .presence-group')).toHaveAttribute('aria-label', /Bob F01/)
  await expect(pageB.locator('.collaboration-strip .presence-group')).toHaveAttribute('aria-label', /Alice F01/)

  // The AppShell pill states the connection plainly (ADR-032 three-state acceptance).
  await expect(pageA.locator('.collab-pill')).toContainText('实时协作在线')
  await expect(pageB.locator('.collab-pill')).toContainText('实时协作在线')
})

test("one side's typing appears on the other and self-clears after the welcome window", async () => {
  const indicator = pageA.locator('.typing-indicator')
  await expect(pageB.getByLabel('消息内容')).toBeEnabled()
  await pageB.getByLabel('消息内容').fill('远端输入信号的自证文本')
  await expect(indicator).toContainText('Bob F01 正在输入…')

  // typingWindowSeconds is 5s on this stack and the client owns the self-clear (§2.3
  // — the server never re-sends stopped). No further keystroke: the row must fall
  // silent on its own.
  await expect(indicator).toHaveCount(0, { timeout: 15_000 })
})

test('a remote work-item change banners the dirty drawer and never overwrites it', async () => {
  // Carrier project + item (the I01c REST recipe).
  const projectsPath = teamPath(sessionA, teamId, 'work-projects')
  await command(pageA, sessionA, projectsPath, { name: `F01 横幅计划 ${suffix}`.slice(0, 96) })
  const projectId = await waitForItem(pageA, projectsPath, suffix)
  const workItemsPath = `${projectsPath}/${projectId}/work-items`
  await command(pageA, sessionA, workItemsPath, {
    type: 'FEATURE',
    title: `F01 横幅载体 ${suffix}`.slice(0, 96),
    description: 'M11-F01 banner carrier',
    priority: 'MEDIUM',
    labels: ['f01-banner'],
    dueAt: null,
  })
  const workItemId = await waitForItem(pageA, workItemsPath, suffix)

  await pageA.goto(`/work?team=${teamId}&project=${projectId}&workItem=${workItemId}`)
  const drawer = pageA.getByRole('dialog').filter({ hasText: /F01 横幅载体/ }).first()
  await expect(drawer).toBeVisible()
  // The drawer's own subscription is live before the remote write lands.
  await expect(pageA.locator('.collab-pill')).toContainText('实时协作在线')

  await drawer.getByRole('button', { name: '编辑内容' }).click()
  const title = drawer.getByLabel('标题', { exact: true })
  const localTitle = `本地未保存标题 ${suffix}`
  await title.fill(localTitle)

  // Bob (MEMBER holds WORK_PARTICIPATE) rewrites the description through the REST
  // plane; the signal channel carries the version bump to Alice's open drawer.
  const before = await getJson<{ workItem: { version: number } }>(pageB, `${workItemsPath}/${workItemId}`)
  const response = await pageB.request.patch(`${workItemsPath}/${workItemId}`, {
    data: { description: `远端修改 ${suffix}` },
    headers: {
      [sessionB.csrf.headerName]: sessionB.csrf.token,
      'Idempotency-Key': crypto.randomUUID(),
      'If-Match': `"${before.workItem.version}"`,
    },
  })
  expect(response.status()).toBe(202)

  // The banner appears; the dirty input is untouched — the interruption red line.
  await expect(drawer.locator('.change-banner')).toContainText('该工作项已被更新')
  await expect(title).toHaveValue(localTitle)

  // The explicit refresh re-reads the item: the hero version moves to the bumped one
  // while the local edit stays in place.
  await drawer.locator('.change-banner').getByRole('button', { name: '点击刷新' }).click()
  await expect(drawer.locator('.change-banner')).toHaveCount(0)
  await expect(drawer.locator('.detail-hero__status .mono')).toHaveText(`v${before.workItem.version + 1}`)
})

test("a peer's closed page announces the leave and drops them from presence", async () => {
  // Back on the conversation: Alice re-subscribes and the snapshot names Bob again.
  await pageA.goto(`/conversation?team=${teamId}&conversation=${conversationId}`)
  await expect(pageA.locator('.collaboration-strip .presence-group')).toHaveAttribute('aria-label', /Bob F01/)

  await pageB.close()

  // The server announces the leave on the socket teardown; Alice's roster lets Bob go.
  await expect(pageA.locator('.collaboration-strip .presence-group'))
    .not.toHaveAttribute('aria-label', /Bob F01/, { timeout: 20_000 })
})

async function waitForItem(page: Page, listPath: string, marker: string): Promise<string> {
  const deadline = Date.now() + 30_000
  for (;;) {
    const list = await getJson<{ items: Array<{ id: string, name?: string, title?: string }> }>(page, `${listPath}?limit=50`)
    const found = (list.items ?? []).find(item => (item.name ?? item.title ?? '').includes(marker))?.id
    if (found) return found
    if (Date.now() > deadline) throw new Error(`item with marker ${marker} never appeared`)
    await page.waitForTimeout(1000)
  }
}
