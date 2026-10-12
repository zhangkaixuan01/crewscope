import { expect, test, type Browser, type BrowserContext, type Page } from '@playwright/test'
import { baseURL, currentSession, getJson, onlyTeam, register, teamPath, type Session } from '../real-backend'

/**
 * M11-Q01 real-stack attack face ② (revocation residue) — the full chain the M11-D02
 * document handed over: a real REST suspend command travels the outbox poller, the sweep
 * cuts the member's live connection (4403), presence tears down on the owner's roster,
 * and the cut member cannot re-enter the scope by reconnecting — the denial is the
 * membership's, not the socket's. Everything is observed through the deployed product:
 * two browser sessions, one conversation, one suspend call with honest If-Match/CSRF
 * headers. The sweep cadence on this stack is heartbeat 1s / revalidation 1s, and the
 * outbox poll interval is 1000ms — a 20s observation window holds all three with margin.
 */
test.describe.configure({ mode: 'serial' })

let ownerContext: BrowserContext
let memberContext: BrowserContext
let ownerPage: Page
let memberPage: Page
let ownerSession: Session
let memberSession: Session
let teamId: string
let conversationId: string
let suffix: string

const password = 'Correct-Horse-Battery-Staple-47'

test.beforeAll(async ({ browser }: { browser: Browser }) => {
  suffix = `q01r-${Date.now()}`.toLowerCase().replace(/[^a-z0-9]+/g, '-')
  ownerContext = await browser.newContext({
    baseURL,
    viewport: { width: 1440, height: 960 },
    timezoneId: 'Asia/Shanghai',
    reducedMotion: 'reduce',
  })
  ownerPage = await ownerContext.newPage()
  await register(ownerPage, `owner-${suffix}`.slice(0, 48), `owner-${suffix}@example.test`, 'Olive Q01 Owner', password, false)
  await expect(ownerPage).toHaveURL(/\/onboarding$/)
  await ownerPage.getByRole('textbox', { name: '团队名称' }).fill(`Q01 撤权 ${suffix}`.slice(0, 96))
  await ownerPage.getByRole('button', { name: '创建团队' }).click()
  await expect(ownerPage.getByRole('heading', { name: '你的工作入口已经就绪' })).toBeVisible()
  ownerSession = await currentSession(ownerPage)
  teamId = onlyTeam(ownerSession).teamId

  // The invitation rail: the only way a second account joins this Team on a real stack.
  await ownerPage.goto(`/team/members?team=${teamId}&tab=invitations`)
  await ownerPage.getByRole('button', { name: '创建邀请' }).first().click()
  await ownerPage.locator('input[name="invitationEmail"]').fill(`member-${suffix}@example.test`)
  await ownerPage.locator('select[name="invitationRole"]').selectOption('MEMBER')
  await ownerPage.getByRole('button', { name: '创建邀请链接' }).click()
  const invitationLink = await ownerPage
    .getByRole('textbox', { name: '一次性邀请链接' })
    .inputValue()
  expect(invitationLink).toMatch(/\/invite#token=[A-Za-z0-9_-]{43}$/)

  memberContext = await browser.newContext({
    baseURL,
    viewport: { width: 1440, height: 960 },
    timezoneId: 'Asia/Shanghai',
    reducedMotion: 'reduce',
  })
  memberPage = await memberContext.newPage()
  await memberPage.goto(invitationLink)
  await memberPage.getByRole('button', { name: '创建账号并加入团队' }).click()
  await expect(memberPage).toHaveURL(/\/register$/)
  await expect(memberPage.getByText('已安全载入团队邀请')).toBeVisible()
  await register(memberPage, `member-${suffix}`.slice(0, 48), `member-${suffix}@example.test`, 'Marty Q01 Member', password, true)
  await expect(memberPage).toHaveURL(new RegExp(`/conversation\\?team=${teamId}`))
  memberSession = await currentSession(memberPage)
  expect(onlyTeam(memberSession).teamId).toBe(teamId)

  // A shared conversation both browsers sit in, with the member as a participant.
  const conversationsPath = teamPath(ownerSession, teamId, 'conversations')
  const createConversation = await ownerPage.request.post(conversationsPath, {
    data: { title: `Q01 撤权会话 ${suffix}`.slice(0, 96), visibility: 'TEAM' },
    headers: {
      [ownerSession.csrf.headerName]: ownerSession.csrf.token,
      'Idempotency-Key': crypto.randomUUID(),
    },
  })
  expect(createConversation.status()).toBe(202)
  const conversations = await getJson<{ items: Array<{ id: string, title: string }> }>(ownerPage, `${conversationsPath}?limit=50`)
  conversationId = conversations.items.find(item => item.title.includes(suffix))!.id
  const addParticipant = await ownerPage.request.post(`${conversationsPath}/${conversationId}/participants`, {
    data: { userPrincipalId: memberSession.principal!.principalId },
    headers: {
      [ownerSession.csrf.headerName]: ownerSession.csrf.token,
      'Idempotency-Key': crypto.randomUUID(),
    },
  })
  expect(addParticipant.status()).toBe(202)
})

test.afterAll(async () => {
  await ownerContext.close()
  await memberContext.close()
})

test('a real suspend cuts the member live, tears presence down, and holds on reconnect', async () => {
  const conversationUrl = `/conversation?team=${teamId}&conversation=${conversationId}`
  await ownerPage.goto(conversationUrl)
  await memberPage.goto(conversationUrl)
  await expect(ownerPage.getByLabel('发送消息')).toBeVisible()
  await expect(memberPage.getByLabel('发送消息')).toBeVisible()

  // Both sides live and mutually present — the state the attack starts from.
  await expect(ownerPage.locator('.collab-pill')).toContainText('实时协作在线')
  await expect(memberPage.locator('.collab-pill')).toContainText('实时协作在线')
  await expect(ownerPage.locator('.collaboration-strip .presence-group')).toHaveAttribute('aria-label', /Marty Q01 Member/)

  // The honest command shape: the member row's committed version as a strong If-Match.
  const membersPath = teamPath(ownerSession, teamId, 'members')
  const members = await getJson<Array<{ id: string, userPrincipalId: string, status: string, version: number }>>(
    ownerPage,
    membersPath,
  )
  const memberRow = members.find(row => row.userPrincipalId === memberSession.principal!.principalId)
  expect(memberRow).toBeTruthy()
  expect(memberRow!.status).toBe('ACTIVE')

  const suspend = await ownerPage.request.post(
    `/api/v1/organizations/${ownerSession.principal!.organizationId}/teams/${teamId}/members/${memberRow!.id}/suspend`,
    {
      headers: {
        [ownerSession.csrf.headerName]: ownerSession.csrf.token,
        'Idempotency-Key': crypto.randomUUID(),
        'If-Match': `"${memberRow!.version}"`,
      },
    },
  )
  expect(suspend.status()).toBe(202)

  // The full chain inside one window: outbox poll -> sweep -> 4403 on the member's
  // socket, the member's pill leaves its live state, and the owner's roster lets the
  // member go (presence teardown, not a stale ghost).
  await expect
    .poll(
      async () => (await getJson<Array<{ id: string, status: string }>>(ownerPage, membersPath))
        .find(row => row.id === memberRow!.id)?.status,
      { timeout: 20_000 },
    )
    .toBe('SUSPENDED')
  await expect(memberPage.locator('.collab-pill')).not.toContainText('实时协作在线', { timeout: 20_000 })
  await expect(ownerPage.locator('.collaboration-strip .presence-group'))
    .not.toHaveAttribute('aria-label', /Marty Q01 Member/, { timeout: 20_000 })

  // Residue check: the cut member reconnects on a fresh socket with the same session
  // and re-subscribes the swept scope — the door answers forbidden_scope again.
  const outcome = await memberPage.evaluate(
    ({ organizationId: organization, team }: { organizationId: string, team: string }) => {
      const url = `${location.origin.replace(/^http/, 'ws')}/api/v1/collaboration/ws`
      return new Promise<{ denied: boolean, closeCode: number | null }>(resolve => {
        const socket = new WebSocket(url)
        const timeout = window.setTimeout(() => {
          try { socket.close() } catch { /* never opened */ }
          resolve({ denied: false, closeCode: null })
        }, 15_000)
        socket.onmessage = message => {
          const frame = JSON.parse(String(message.data)) as Record<string, unknown>
          if (frame.type === 'error' && frame.code === 'forbidden_scope') {
            window.clearTimeout(timeout)
            resolve({ denied: true, closeCode: null })
            socket.close(1000, 'spec complete')
          }
        }
        socket.onclose = event => {
          window.clearTimeout(timeout)
          resolve({ denied: false, closeCode: event.code })
        }
        socket.onopen = () => {
          socket.send(JSON.stringify({ type: 'subscribe', scope: { organization, team } }))
        }
        socket.onerror = () => { /* onclose carries the verdict */ }
      })
    },
    { organizationId: memberSession.principal!.organizationId, team: teamId },
  )
  expect(outcome.denied).toBe(true)

  // And the roster does not come back: the revocation residue is zero.
  await expect(ownerPage.locator('.collaboration-strip .presence-group'))
    .not.toHaveAttribute('aria-label', /Marty Q01 Member/)
})
