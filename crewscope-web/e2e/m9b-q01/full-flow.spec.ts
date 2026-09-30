import { expect, test } from '@playwright/test'
import {
  baseURL,
  command,
  currentSession,
  getJson,
  onlyTeam,
  register,
  teamPath,
} from '../real-backend'

/**
 * M9b-Q01 real-backend full-flow proof. Like the M7-Q03 spec this runs against a production
 * Compose stack (no Vite server, no HTTP mocks, no seeded data): two isolated people join one
 * Team through the real pages, then the M9b collaboration spine — project, work item, model
 * connection, conversation, message, comment — is created through the real API with CSRF and
 * idempotency headers. The model connection is created inside the test on purpose: the
 * release contract forbids pre-seeding the very binding a gate claims to verify.
 *
 * Real Provider inference (a task actually running on a live model) stays out of scope here —
 * that belongs to the M9b-Q02 provider gate; the connection record itself is this spec's
 * boundary.
 */

type ProjectPage = { items: Array<{ id: string, name: string, key: string }> }
type WorkItemPage = { items: Array<{ id: string, key: string, title: string }> }
type ProviderPage = { items: Array<{ key: string, availableRegions: string[] }> }
type ConnectionPage = { items: Array<{ id: string, status: string }> }
type MessagePage = { items: Array<{ id: string, content: string, authorPrincipalId: string }> }
type CommentEnvelope = { comments: Array<{ id: string, content: string, authorPrincipalId: string }> }

test('two people build the M9b collaboration spine on a real backend', async ({
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
  const teamName = `Q01 ${suffix}`.slice(0, 96)
  const projectName = `Q01 计划 ${suffix}`.slice(0, 96)
  const workItemTitle = `Q01 工作项 ${suffix}`.slice(0, 96)
  const message = `Q01 message proof ${suffix}`
  const comment = `Q01 comment proof ${suffix}`
  test.setTimeout(240_000)

  const contextB = await browser.newContext({
    baseURL,
    viewport: { width: 1440, height: 960 },
    timezoneId: 'Asia/Shanghai',
    reducedMotion: 'reduce',
  })
  const pageB = await contextB.newPage()

  try {
    // --- Identity spine through the real pages (same contract as M7-Q03). ---
    await register(pageA, userA, emailA, 'Q01 Owner', password, false)
    await expect(pageA).toHaveURL(/\/onboarding$/)
    await pageA.getByRole('textbox', { name: '团队名称' }).fill(teamName)
    await pageA.getByRole('button', { name: '创建团队' }).click()
    await expect(pageA.getByRole('heading', { name: '你的工作入口已经就绪' })).toBeVisible()

    const sessionA = await currentSession(pageA)
    const teamA = onlyTeam(sessionA)
    expect(teamA.permissions).toContain('team:members:manage')

    // Invitation management lives behind the `tab=invitations` deep link (same regression the
    // M7-Q03 spec now pins): the members tab has no 创建邀请 button.
    await pageA.goto(`/team/members?team=${teamA.teamId}&tab=invitations`)
    // Empty-team start renders the toolbar and the empty-state panel's 创建邀请 side by side;
    // the toolbar entry comes first in DOM order (same strict-mode guard as the M7-Q03 spec).
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
    await register(pageB, userB, emailB, 'Q01 Member', password, true)
    await expect(pageB).toHaveURL(new RegExp(`/conversation(?:\\?team=${teamA.teamId})?$`))

    const sessionB = await currentSession(pageB)
    const teamB = onlyTeam(sessionB)
    expect(teamB.teamId).toBe(teamA.teamId)
    expect(teamB.memberId).not.toBe(teamA.memberId)

    // --- Collaboration spine through the real API. ---
    const projectsPath = teamPath(sessionA, teamA.teamId, 'work-projects')
    await command(pageA, sessionA, projectsPath, { name: projectName })
    await expect.poll(async () => {
      const page = await getJson<ProjectPage>(pageA, `${projectsPath}?limit=50`)
      return page.items.find(item => item.name === projectName)?.id ?? null
    }).not.toBeNull()
    const project = (await getJson<ProjectPage>(pageA, `${projectsPath}?limit=50`))
      .items.find(item => item.name === projectName)
    expect(project).toBeTruthy()

    const workItemsPath = `${projectsPath}/${project!.id}/work-items`
    await command(pageA, sessionA, workItemsPath, {
      type: 'FEATURE',
      title: workItemTitle,
      description: 'M9b-Q01 real-backend full-flow work item',
      priority: 'MEDIUM',
      labels: ['q01'],
      dueAt: null,
    })
    await expect.poll(async () => {
      const page = await getJson<WorkItemPage>(pageA, `${workItemsPath}?limit=50`)
      return page.items.find(item => item.title === workItemTitle)?.id ?? null
    }).not.toBeNull()
    const workItem = (await getJson<WorkItemPage>(pageA, `${workItemsPath}?limit=50`))
      .items.find(item => item.title === workItemTitle)
    expect(workItem).toBeTruthy()

    // The member sees the same project and work item facts as the owner.
    const memberProjectView = await getJson<ProjectPage>(pageB, `${projectsPath}?limit=50`)
    expect(memberProjectView.items.find(item => item.id === project!.id)).toBeTruthy()
    const memberWorkItemView = await getJson<WorkItemPage>(pageB, `${workItemsPath}?limit=50`)
    expect(memberWorkItemView.items.find(item => item.id === workItem!.id)).toBeTruthy()

    // --- Model connection created inside the test (never pre-seeded). ---
    const organizationId = sessionA.principal!.organizationId
    const providers = await getJson<ProviderPage>(pageA, `/api/v1/organizations/${organizationId}/model-providers?limit=50`)
    const provider = providers.items[0]
    expect(provider, 'at least one model provider must be registered').toBeTruthy()
    const connectionsPath = `/api/v1/organizations/${organizationId}/model-connections`
    // A placeholder credential: the record and its lifecycle are under test, not a live model.
    await command(pageA, sessionA, connectionsPath, {
      providerKey: provider.key,
      ownerType: 'USER',
      teamId: null,
      region: provider.availableRegions[0] ?? '',
      apiKey: `q01-placeholder-${suffix}`,
      credentialExpiresAt: null,
    })
    await expect.poll(async () => (await getJson<ConnectionPage>(
      pageA,
      `${connectionsPath}?ownerType=USER&limit=50`,
    )).items.length).toBeGreaterThan(0)

    // --- Conversation, cross-member message, and a work-item comment. ---
    const conversationsPath = teamPath(sessionA, teamA.teamId, 'conversations')
    await command(pageA, sessionA, conversationsPath, { title: teamName, visibility: 'TEAM' })
    await expect.poll(async () => {
      const page = await getJson<{ items: Array<{ id: string, title: string }> }>(
        pageA,
        `${conversationsPath}?limit=50`,
      )
      return page.items.find(item => item.title === teamName)?.id ?? null
    }).not.toBeNull()
    const conversationList = await getJson<{ items: Array<{ id: string, title: string }> }>(
      pageA,
      `${conversationsPath}?limit=50`,
    )
    const conversationId = conversationList.items.find(item => item.title === teamName)!.id

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
      const page = await getJson<MessagePage>(
        pageA,
        `${conversationsPath}/${conversationId}/messages?limit=50`,
      )
      return page.items.some(item =>
        item.content === message && item.authorPrincipalId === sessionB.principal?.principalId)
    }).toBe(true)

    await command(pageA, sessionA, `${workItemsPath}/${workItem!.id}/comments`, { content: comment })
    const details = await getJson<CommentEnvelope>(pageA, `${workItemsPath}/${workItem!.id}`)
    expect(details.comments.some(item => item.content === comment)).toBe(true)
    const memberDetails = await getJson<CommentEnvelope>(pageB, `${workItemsPath}/${workItem!.id}`)
    expect(memberDetails.comments.some(item => item.content === comment)).toBe(true)

    // --- The audit spine records both actors (same contract as M7-Q03). ---
    const auditPath = teamPath(sessionA, teamA.teamId, 'audit-events?limit=100')
    await expect.poll(async () => {
      const audit = await getJson<{ items: Array<{ identity: { actorId: string | null } }> }>(pageA, auditPath)
      const actors = new Set(audit.items.map(item => item.identity.actorId))
      return actors.has(sessionA.principal?.principalId ?? '')
        && actors.has(sessionB.principal?.principalId ?? '')
    }, { timeout: 60_000 }).toBe(true)
  } finally {
    await contextB.close()
  }
})
