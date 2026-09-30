import { expect, test } from '@playwright/test'
import {
  baseURL,
  command,
  currentSession,
  expireBrowserSessions,
  getJson,
  login,
  logout,
  onlyTeam,
  register,
  restartApi,
  sessionCookie,
  teamPath,
  type AgentPage,
} from './real-backend'

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

    // 成员页是 tab 结构：邀请管理在 `tab=invitations`（TeamInvitationManager），成员目录
    // （table 团队成员列表）在默认 tab——M9b 重排后这里曾用默认 tab 找「创建邀请」挂满
    // 整个 test timeout，这个直落 invitations tab 的深链接就是那次回归的固定回归。
    await pageA.goto(`/team/members?team=${teamA.teamId}&tab=invitations`)
    // 空团队起步时 TeamInvitationManager 同时渲染工具条与空态 StatePanel 两个「创建邀请」
    // （mock 档预置了邀请所以只见一个）——取第一个（工具条主入口）避免 strict violation。
    await expect(pageA.getByRole('button', { name: '创建邀请' }).first()).toBeVisible()
    await pageA.getByRole('button', { name: '创建邀请' }).first().click()
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
