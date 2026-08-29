import AxeBuilder from '@axe-core/playwright'
import { execFileSync } from 'node:child_process'
import { expect, test, type Browser, type Page } from '@playwright/test'

type RegistrationMode = 'OPEN' | 'INVITE_ONLY' | 'DISABLED'

type Session = {
  authenticated: boolean
  registrationMode: RegistrationMode
  csrf: { headerName: string, token: string }
  account: { accountId: string, username: string } | null
  principal: { principalId: string, organizationId: string } | null
  teams: Array<{ teamId: string, memberId: string }>
}

const repositoryRoot = process.env.CREWSCOPE_Q04_REPOSITORY_ROOT
const demoScript = process.env.CREWSCOPE_Q04_DEMO_SCRIPT
const baseURL = process.env.CREWSCOPE_Q04_BASE_URL ?? 'http://127.0.0.1:18081'

test('OPEN, INVITE_ONLY and DISABLED preserve their production registration contracts', async ({
  browser,
  page: owner,
}) => {
  const suffix = `${Date.now()}-${test.info().workerIndex}`
  const password = 'Correct-Horse-Battery-Staple-47'
  const ownerName = `q04-owner-${suffix}`.slice(0, 48)
  const memberName = `q04-member-${suffix}`.slice(0, 48)
  const blockedName = `q04-blocked-${suffix}`.slice(0, 48)
  const ownerEmail = `${ownerName}@example.test`
  const memberEmail = `${memberName}@example.test`

  await assertFormalLogin(owner, 'OPEN')
  await register(owner, ownerName, ownerEmail, 'Q04 Owner', password)
  await expect(owner).toHaveURL(/\/onboarding$/)
  await owner.getByRole('textbox', { name: '团队名称' }).fill(`Q04 Profile ${suffix}`)
  await owner.getByRole('button', { name: '创建团队' }).click()
  await expect(owner.getByRole('heading', { name: '你的工作入口已经就绪' })).toBeVisible()
  const ownerSession = await currentSession(owner)
  expect(ownerSession.registrationMode).toBe('OPEN')
  const invitation = await createInvitation(owner, ownerSession, memberEmail)

  switchMode('INVITE_ONLY')
  await expect.poll(async () => (await anonymousSession(browser)).registrationMode).toBe('INVITE_ONLY')
  const inviteOnlyContext = await browser.newContext({ baseURL })
  const inviteOnly = await inviteOnlyContext.newPage()
  try {
    await inviteOnly.goto('/register')
    await expect(inviteOnly.getByRole('heading', { name: '通过团队邀请加入 CrewScope' })).toBeVisible()
    await assertAxe(inviteOnly)
    await assertRegistrationRejected(
      inviteOnly,
      blockedName,
      `${blockedName}@example.test`,
      password,
      422,
    )

    await inviteOnly.goto(`/invite#token=${invitation}`)
    await inviteOnly.getByRole('button', { name: '创建账号并加入团队' }).click()
    await expect(inviteOnly).toHaveURL(/\/register$/)
    await expect(inviteOnly.getByText('已安全载入团队邀请')).toBeVisible()
    await register(inviteOnly, memberName, memberEmail, 'Q04 Member', password, true)
    await expect(inviteOnly).toHaveURL(/\/conversation/)
    const memberSession = await currentSession(inviteOnly)
    expect(memberSession.registrationMode).toBe('INVITE_ONLY')
    expect(memberSession.teams[0]?.teamId).toBe(ownerSession.teams[0]?.teamId)
  } finally {
    await inviteOnlyContext.close()
  }

  const disabledInvitation = await createInvitation(
    owner,
    await currentSession(owner),
    `${blockedName}@example.test`,
  )
  switchMode('DISABLED')
  await expect.poll(async () => (await anonymousSession(browser)).registrationMode).toBe('DISABLED')

  const disabledContext = await browser.newContext({ baseURL })
  const disabled = await disabledContext.newPage()
  try {
    await disabled.goto(`/register#token=${disabledInvitation}`)
    await expect(disabled.getByRole('heading', { name: '当前部署未开放新账号' })).toBeVisible()
    await assertAxe(disabled)
    await assertRegistrationRejected(
      disabled,
      blockedName,
      `${blockedName}@example.test`,
      password,
      403,
      disabledInvitation,
    )
    await assertFormalLogin(disabled, 'DISABLED')
    await login(disabled, ownerEmail, password)
    const recoveredOwner = await currentSession(disabled)
    expect(recoveredOwner.account?.accountId).toBe(ownerSession.account?.accountId)
    expect(recoveredOwner.teams[0]?.teamId).toBe(ownerSession.teams[0]?.teamId)
  } finally {
    await disabledContext.close()
  }
})

async function assertFormalLogin(page: Page, mode: RegistrationMode): Promise<void> {
  const document = await page.request.get('/login')
  expect(document.status()).toBe(200)
  expect(document.headers()['www-authenticate']).toBeUndefined()
  await page.goto('/login')
  await expect(page.getByRole('heading', { name: '继续你的团队工作' })).toBeVisible()
  await expect(page.getByText(mode === 'OPEN'
    ? '当前部署支持自行创建账号'
    : mode === 'INVITE_ONLY'
      ? '新成员通过团队邀请加入'
      : '当前部署未开放新账号注册')).toBeVisible()
  await assertAxe(page)
  const sessionResponse = await page.request.get('/api/v1/auth/session')
  expect(sessionResponse.status()).toBe(200)
  expect(sessionResponse.headers()['www-authenticate']).toBeUndefined()
}

async function register(
  page: Page,
  username: string,
  email: string,
  displayName: string,
  password: string,
  invited = false,
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
  await page.getByRole('textbox', { name: '用户名或邮箱' }).fill(identifier)
  await page.locator('input[name="password"]').fill(password)
  await page.getByRole('button', { name: '进入 CrewScope' }).click()
  await expect(page).not.toHaveURL(/\/login/)
}

async function currentSession(page: Page): Promise<Session> {
  const response = await page.request.get('/api/v1/auth/session')
  expect(response.status()).toBe(200)
  expect(response.headers()['www-authenticate']).toBeUndefined()
  return response.json() as Promise<Session>
}

async function anonymousSession(browser: Browser): Promise<Session> {
  const context = await browser.newContext()
  try {
    const response = await context.request.get('/api/v1/auth/session')
    expect(response.status()).toBe(200)
    return response.json() as Promise<Session>
  } finally {
    await context.close()
  }
}

async function createInvitation(page: Page, session: Session, email: string): Promise<string> {
  const team = session.teams[0]
  if (!session.principal || !team) throw new Error('Owner Team identity is missing')
  const response = await page.request.post(
    `/api/v1/organizations/${session.principal.organizationId}/teams/${team.teamId}/invitations`,
    {
      data: { targetEmail: email, targetRole: 'MEMBER', expiresInMinutes: 60 },
      headers: {
        [session.csrf.headerName]: session.csrf.token,
        'Idempotency-Key': crypto.randomUUID(),
      },
    },
  )
  expect(response.status(), await response.text()).toBe(202)
  const body = await response.json() as { token: string }
  expect(body.token).toMatch(/^[A-Za-z0-9_-]{43}$/)
  return body.token
}

async function assertRegistrationRejected(
  page: Page,
  username: string,
  email: string,
  password: string,
  expectedStatus: 403 | 422,
  invitationToken?: string,
): Promise<void> {
  const session = await currentSession(page)
  const response = await page.request.post('/api/v1/auth/register', {
    data: {
      username,
      email,
      displayName: 'Q04 Blocked',
      password,
      ...(invitationToken ? { invitationToken } : {}),
    },
    headers: {
      [session.csrf.headerName]: session.csrf.token,
      'Idempotency-Key': crypto.randomUUID(),
    },
  })
  expect(response.status()).toBe(expectedStatus)
  expect(response.headers()['www-authenticate']).toBeUndefined()
  expect((await response.json()).code).toBe('registration_unavailable')
}

async function assertAxe(page: Page): Promise<void> {
  const result = await new AxeBuilder({ page }).analyze()
  expect(result.violations).toEqual([])
}

function switchMode(mode: RegistrationMode): void {
  if (!repositoryRoot || !demoScript) throw new Error('Q04 mode-switch coordinates are missing')
  execFileSync(demoScript, ['set-registration-mode', mode], {
    cwd: repositoryRoot,
    env: process.env,
    stdio: 'inherit',
    timeout: 120_000,
  })
}
