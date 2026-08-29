import { execFileSync } from 'node:child_process'
import { expect, test, type BrowserContext, type Page } from '@playwright/test'

type Session = {
  authenticated: boolean
  csrf: { headerName: string, token: string }
  account: { accountId: string, username: string } | null
  principal: { principalId: string, organizationId: string } | null
  teams: Array<{ teamId: string, memberId: string, permissions: string[] }>
}

type AgentPage = {
  items: Array<{ id: string, principalId: string, ownerMemberId: string | null, defaultProfile: boolean }>
}

const baseURL = process.env.CREWSCOPE_Q03_BASE_URL ?? 'http://127.0.0.1:18080'
const apiContainer = process.env.CREWSCOPE_Q03_API_CONTAINER ?? 'crewscope-m7-q03-api-1'
const redisContainer = process.env.CREWSCOPE_Q03_REDIS_CONTAINER ?? 'crewscope-m7-q03-redis-1'

test('two people join one Team and retain distinct identities through restart and expiry', async ({
  browser,
  page: pageA,
}, testInfo) => {
  const suffix = `${testInfo.project.name}-${Date.now()}-${testInfo.workerIndex}`
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
  const password = 'Correct-Horse-Battery-Staple-47'
  const userA = `owner-${suffix}`.slice(0, 48)
  const userB = `member-${suffix}`.slice(0, 48)
  const emailA = `${userA}@example.test`
  const emailB = `${userB}@example.test`
  const teamName = `Q03 ${suffix}`.slice(0, 96)
  const message = `Q03 collaboration proof ${suffix}`
  const viewport = pageA.viewportSize() ?? { width: 1440, height: 960 }
  const contextB = await browser.newContext({
    baseURL,
    viewport,
    timezoneId: 'Asia/Shanghai',
    reducedMotion: 'reduce',
  })
  const pageB = await contextB.newPage()

  try {
    await register(pageA, userA, emailA, 'Q03 Owner', password, false)
    await expect(pageA).toHaveURL(/\/onboarding$/)
    await pageA.getByRole('textbox', { name: '团队名称' }).fill(teamName)
    await pageA.getByRole('button', { name: '创建团队' }).click()
    await expect(pageA.getByRole('heading', { name: '你的工作入口已经就绪' })).toBeVisible()
    await expect(pageA.getByLabel('已完成的初始化')).toContainText('Personal Agent')

    const sessionA = await currentSession(pageA)
    const teamA = onlyTeam(sessionA)
    expect(sessionA.account).not.toBeNull()
    expect(sessionA.principal).not.toBeNull()
    expect(teamA.permissions).toContain('team:members:manage')
    expect(teamA.permissions).toContain('audit:read')

    await pageA.goto(`/team/members?team=${teamA.teamId}`)
    await expect(pageA.getByRole('table', { name: '团队成员列表' })).toBeVisible()
    await pageA.getByRole('button', { name: '创建邀请' }).click()
    await pageA.locator('input[name="invitationEmail"]').fill(emailB)
    await pageA.locator('select[name="invitationRole"]').selectOption('MEMBER')
    await pageA.getByRole('button', { name: '创建邀请链接' }).click()
    const invitationLink = await pageA
      .getByRole('textbox', { name: '一次性邀请链接' })
      .inputValue()
    expect(invitationLink).toMatch(/\/invite#token=[A-Za-z0-9_-]{43}$/)

    await pageB.goto(invitationLink)
    await pageB.getByRole('button', { name: '创建账号并加入团队' }).click()
    await expect(pageB).toHaveURL(/\/register$/)
    await expect(pageB.getByText('已安全载入团队邀请')).toBeVisible()
    await register(pageB, userB, emailB, 'Q03 Member', password, true)
    await expect(pageB).toHaveURL(new RegExp(`/conversation(?:\\?team=${teamA.teamId})?$`))

    const sessionB = await currentSession(pageB)
    const teamB = onlyTeam(sessionB)
    expect(sessionB.account?.accountId).not.toBe(sessionA.account?.accountId)
    expect(sessionB.principal?.principalId).not.toBe(sessionA.principal?.principalId)
    expect(teamB.memberId).not.toBe(teamA.memberId)
    expect(teamB.teamId).toBe(teamA.teamId)
    expect(teamB.permissions).not.toContain('team:members:manage')
    expect(teamB.permissions).not.toContain('audit:read')

    const cookieA = await sessionCookie(pageA.context())
    const cookieB = await sessionCookie(contextB)
    expect(cookieA).not.toBe(cookieB)

    const agentPath = teamPath(sessionA, teamA.teamId, 'agent-profiles')
    const agentsA = await getJson<AgentPage>(pageA, agentPath)
    const agentsB = await getJson<AgentPage>(pageB, agentPath)
    const personalA = agentsA.items.find(agent =>
      agent.defaultProfile && agent.ownerMemberId === teamA.memberId)
    const personalB = agentsB.items.find(agent =>
      agent.defaultProfile && agent.ownerMemberId === teamB.memberId)
    expect(personalA).toBeTruthy()
    expect(personalB).toBeTruthy()
    expect(personalA?.id).not.toBe(personalB?.id)
    expect(personalA?.principalId).not.toBe(personalB?.principalId)

    const membersPath = teamPath(sessionA, teamA.teamId, 'members')
    const membersA = await getJson<Array<{ id: string }>>(pageA, membersPath)
    const membersB = await getJson<Array<{ id: string }>>(pageB, membersPath)
    expect(new Set(membersA.map(member => member.id))).toEqual(
      new Set([teamA.memberId, teamB.memberId]),
    )
    expect(new Set(membersB.map(member => member.id))).toEqual(
      new Set([teamA.memberId, teamB.memberId]),
    )

    const conversationsPath = teamPath(sessionA, teamA.teamId, 'conversations')
    await command(pageA, sessionA, conversationsPath, { title: teamName, visibility: 'TEAM' })
    await expect.poll(async () => {
      const value = await getJson<{ items: Array<{ id: string, title: string }> }>(
        pageA,
        `${conversationsPath}?limit=50`,
      )
      return value.items.find(item => item.title === teamName)?.id ?? null
    }).not.toBeNull()
    const conversationPage = await getJson<{ items: Array<{ id: string, title: string }> }>(
      pageA,
      `${conversationsPath}?limit=50`,
    )
    const conversationId = conversationPage.items.find(item => item.title === teamName)?.id
    expect(conversationId).toBeTruthy()
    await command(
      pageA,
      sessionA,
      `${conversationsPath}/${conversationId}/participants`,
      { userPrincipalId: sessionB.principal?.principalId },
    )
    await command(
      pageB,
      sessionB,
      `${conversationsPath}/${conversationId}/messages`,
      { content: message },
    )
    await expect.poll(async () => {
      const value = await getJson<{ items: Array<{ content: string, authorPrincipalId: string }> }>(
        pageA,
        `${conversationsPath}/${conversationId}/messages?limit=50`,
      )
      return value.items.some(item =>
        item.content === message && item.authorPrincipalId === sessionB.principal?.principalId)
    }).toBe(true)

    await pageA.reload()
    await pageB.reload()
    expect((await currentSession(pageA)).account?.accountId).toBe(sessionA.account?.accountId)
    expect((await currentSession(pageB)).account?.accountId).toBe(sessionB.account?.accountId)

    restartApi()
    await expect.poll(async () => (await pageA.request.get('/api/v1/auth/session')).status(), {
      timeout: 90_000,
    }).toBe(200)
    expect((await currentSession(pageA)).account?.accountId).toBe(sessionA.account?.accountId)
    expect((await currentSession(pageB)).account?.accountId).toBe(sessionB.account?.accountId)

    const auditPath = teamPath(sessionA, teamA.teamId, 'audit-events?limit=100')
    await expect.poll(async () => {
      const audit = await getJson<{
        items: Array<{ identity: { actorId: string | null } }>
      }>(pageA, auditPath)
      const actors = new Set(audit.items.map(item => item.identity.actorId))
      return actors.has(sessionA.principal?.principalId ?? '')
        && actors.has(sessionB.principal?.principalId ?? '')
    }, { timeout: 60_000 }).toBe(true)

    expireBrowserSessions()
    await pageA.waitForTimeout(2_500)
    expect((await currentSession(pageA)).authenticated).toBe(false)
    expect((await currentSession(pageB)).authenticated).toBe(false)
    await pageA.goto(`/conversation?team=${teamA.teamId}`)
    await pageB.goto(`/conversation?team=${teamA.teamId}`)
    await expect(pageA).toHaveURL(/\/login\?returnTo=/)
    await expect(pageB).toHaveURL(/\/login\?returnTo=/)

    await login(pageA, emailA, password)
    await login(pageB, emailB, password)
    const recoveredA = await currentSession(pageA)
    const recoveredB = await currentSession(pageB)
    expect(recoveredA.account?.accountId).toBe(sessionA.account?.accountId)
    expect(recoveredB.account?.accountId).toBe(sessionB.account?.accountId)
    expect(onlyTeam(recoveredA).teamId).toBe(teamA.teamId)
    expect(onlyTeam(recoveredB).teamId).toBe(teamA.teamId)

    await logout(pageA, recoveredA)
    await logout(pageB, recoveredB)
    expect((await currentSession(pageA)).authenticated).toBe(false)
    expect((await currentSession(pageB)).authenticated).toBe(false)
  } finally {
    await contextB.close()
  }
})

async function register(
  page: Page,
  username: string,
  email: string,
  displayName: string,
  password: string,
  invited: boolean,
): Promise<void> {
  if (!invited) await page.goto('/register')
  await page.getByRole('textbox', { name: '用户名' }).fill(username)
  await page.getByRole('textbox', { name: '邮箱' }).fill(email)
  await page.getByRole('textbox', { name: '展示名' }).fill(displayName)
  await page.locator('input[name="password"]').fill(password)
  await page.getByRole('button', {
    name: invited ? '创建账号并加入团队' : '创建账号',
    exact: true,
  }).click()
}

async function login(page: Page, identifier: string, password: string): Promise<void> {
  await expect(page.getByRole('textbox', { name: '用户名或邮箱' })).toBeVisible()
  await page.getByRole('textbox', { name: '用户名或邮箱' }).fill(identifier)
  await page.locator('input[name="password"]').fill(password)
  await page.getByRole('button', { name: '进入 CrewScope' }).click()
  await expect(page).not.toHaveURL(/\/login/)
}

async function currentSession(page: Page): Promise<Session> {
  return getJson<Session>(page, '/api/v1/auth/session')
}

function onlyTeam(session: Session): Session['teams'][number] {
  expect(session.authenticated).toBe(true)
  expect(session.teams).toHaveLength(1)
  return session.teams[0]!
}

function teamPath(session: Session, teamId: string, suffix: string): string {
  const organizationId = session.principal?.organizationId
  if (!organizationId) throw new Error('Authenticated Organization is missing')
  return `/api/v1/organizations/${organizationId}/teams/${teamId}/${suffix}`
}

async function getJson<T>(page: Page, path: string): Promise<T> {
  const response = await page.request.get(path)
  expect(response.ok(), `${path} returned ${response.status()}`).toBe(true)
  return response.json() as Promise<T>
}

async function command(
  page: Page,
  session: Session,
  path: string,
  body: Record<string, unknown>,
): Promise<void> {
  const response = await page.request.post(path, {
    data: body,
    headers: {
      [session.csrf.headerName]: session.csrf.token,
      'Idempotency-Key': crypto.randomUUID(),
    },
  })
  expect(response.status(), `${path} command failed: ${await response.text()}`).toBe(202)
}

async function sessionCookie(context: BrowserContext): Promise<string> {
  const cookie = (await context.cookies(baseURL)).find(value => value.name === 'CREWSCOPE_SESSION')
  expect(cookie).toBeTruthy()
  return cookie!.value
}

async function logout(page: Page, session: Session): Promise<void> {
  const response = await page.request.post('/api/v1/auth/logout', {
    headers: { [session.csrf.headerName]: session.csrf.token },
  })
  expect(response.status()).toBe(204)
}

function restartApi(): void {
  execFileSync('docker', ['restart', apiContainer], { stdio: 'ignore', timeout: 30_000 })
}

function expireBrowserSessions(): void {
  const script = String.raw`
set -eu
password=$(sed -n 's/^user default on >\([^ ]*\).*/\1/p' /tmp/redis_acl)
test -n "$password"
export REDISCLI_AUTH="$password"
redis-cli --user default --scan --pattern 'crewscope:session:sessions:*' | while IFS= read -r key; do
  redis-cli --user default expire "$key" 1 >/dev/null
done
`
  execFileSync('docker', ['exec', redisContainer, 'sh', '-ec', script], {
    stdio: 'ignore',
    timeout: 30_000,
  })
}
