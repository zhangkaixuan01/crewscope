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
 * M9b-Q02 real-stack collaboration spine: the §9.2 rows that need two live identities against
 * a production backend — responsibility assignment reaching the member Inbox, reversible Inbox
 * dispositions through the page, member suspension cutting the old session off at the next
 * request (others unaffected, reactivation without resurrecting stale authority), self-service
 * notification preferences, the dedicated unknown-import code from M9b-Q01 S4, and private
 * conversation isolation. The mocked matrix already pins the front-end behaviour of each of
 * these; this spec proves the same contracts survive the real authorization checkpoints.
 */

let contextA: BrowserContext
let contextB: BrowserContext
let pageA: Page
let pageB: Page
let sessionA: Session
let sessionB: Session
let teamId: string
let workItemsPath: string
let teamConversationPath: string

test.describe.configure({ mode: 'serial' })

test('two people share a team and a responsibility assignment reaches the member inbox', async ({ browser }) => {
  const suffix = `s2-${Date.now()}`.toLowerCase().replace(/[^a-z0-9]+/g, '-')
  const password = 'Correct-Horse-Battery-Staple-47'
  // Serial tests share one signed-in page, so both people live in explicitly owned contexts —
  // the per-test fixture context would be torn down after this first test ends.
  contextA = await browser.newContext({
    baseURL,
    viewport: { width: 1440, height: 960 },
    timezoneId: 'Asia/Shanghai',
    reducedMotion: 'reduce',
  })
  pageA = await contextA.newPage()
  await register(pageA, `owner-${suffix}`.slice(0, 48), `owner-${suffix}@example.test`, 'S2 Owner', password, false)
  await expect(pageA).toHaveURL(/\/onboarding$/)
  await pageA.getByRole('textbox', { name: '团队名称' }).fill(`S2 ${suffix}`.slice(0, 96))
  await pageA.getByRole('button', { name: '创建团队' }).click()
  await expect(pageA.getByRole('heading', { name: '你的工作入口已经就绪' })).toBeVisible()

  sessionA = await currentSession(pageA)
  teamId = onlyTeam(sessionA).teamId

  await pageA.goto(`/team/members?team=${teamId}&tab=invitations`)
  await pageA.getByRole('button', { name: '创建邀请' }).first().click()
  await pageA.locator('input[name="invitationEmail"]').fill(`member-${suffix}@example.test`)
  await pageA.locator('select[name="invitationRole"]').selectOption('MEMBER')
  await pageA.getByRole('button', { name: '创建邀请链接' }).click()
  const invitationLink = await pageA.getByRole('textbox', { name: '一次性邀请链接' }).inputValue()
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
  await register(pageB, `member-${suffix}`.slice(0, 48), `member-${suffix}@example.test`, 'S2 Member', password, true)
  await expect(pageB).toHaveURL(new RegExp(`/conversation(?:\\?team=${teamId})?$`))

  sessionB = await currentSession(pageB)
  expect(onlyTeam(sessionB).teamId).toBe(teamId)

  // A shared team conversation the suspended-member scenario can post into later.
  const conversationsPath = teamPath(sessionA, teamId, 'conversations')
  await command(pageA, sessionA, conversationsPath, { title: `S2 团队对话 ${suffix}`.slice(0, 96), visibility: 'TEAM' })
  await expect.poll(async () => {
    const list = await getJson<{ items: Array<{ id: string, title: string }> }>(pageA, `${conversationsPath}?limit=50`)
    return list.items.find(item => item.title.includes(suffix))?.id ?? null
  }).not.toBeNull()
  const teamConversationId = (await getJson<{ items: Array<{ id: string, title: string }> }>(
    pageA, `${conversationsPath}?limit=50`)).items.find(item => item.title.includes(suffix))!.id
  teamConversationPath = `${conversationsPath}/${teamConversationId}`
  await command(pageA, sessionA, `${teamConversationPath}/participants`, { userPrincipalId: sessionB.principal?.principalId })

  // A project, a work item, and the owner responsibility handed to B through the real command.
  const projectsPath = teamPath(sessionA, teamId, 'work-projects')
  await command(pageA, sessionA, projectsPath, { name: `S2 计划 ${suffix}`.slice(0, 96) })
  await expect.poll(async () => {
    const list = await getJson<{ items: Array<{ id: string, name: string }> }>(pageA, `${projectsPath}?limit=50`)
    return list.items.find(item => item.name.includes(suffix))?.id ?? null
  }).not.toBeNull()
  const projectId = (await getJson<{ items: Array<{ id: string, name: string }> }>(
    pageA, `${projectsPath}?limit=50`)).items.find(item => item.name.includes(suffix))!.id
  workItemsPath = `${projectsPath}/${projectId}/work-items`
  await command(pageA, sessionA, workItemsPath, {
    type: 'FEATURE',
    title: `S2 责任工作项 ${suffix}`.slice(0, 96),
    description: 'M9b-Q02 collab spine work item',
    priority: 'MEDIUM',
    labels: ['q02'],
    dueAt: null,
  })
  await expect.poll(async () => {
    const list = await getJson<{ items: Array<{ id: string }> }>(pageA, `${workItemsPath}?limit=50`)
    return list.items[0]?.id ?? null
  }).not.toBeNull()
  const workItemId = (await getJson<{ items: Array<{ id: string }> }>(pageA, `${workItemsPath}?limit=50`)).items[0]!.id

  // Handing ownership over to B is a real optimistic-version replace: creation already made A
  // the owner, so the command must carry the live assignment identity and version it replaces.
  await expect.poll(async () => {
    const assignments = await getJson<Array<{ id: string, role: string, version: number }>>(
      pageA, `${workItemsPath}/${workItemId}/responsibilities`)
    return assignments.find(row => row.role === 'OWNER')?.id ?? null
  }).not.toBeNull()
  const ownerAssignment = (await getJson<Array<{ id: string, role: string, version: number }>>(
    pageA, `${workItemsPath}/${workItemId}/responsibilities`)).find(row => row.role === 'OWNER')!
  await command(pageA, sessionA, `${workItemsPath}/${workItemId}/responsibilities/owner`, {
    actorPrincipalId: sessionB.principal?.principalId,
    expectedAssignmentId: ownerAssignment.id,
    expectedVersion: ownerAssignment.version,
  })

  // RESPONSIBILITY_ASSIGNED projects into B's inbox — the read side carries readable context.
  await expect.poll(async () => {
    const inbox = await getJson<{ items: Array<{ itemType: string, source: { type: string } }> }>(
      pageB, teamPath(sessionB, teamId, 'inbox?limit=50'))
    return inbox.items.some(item => item.source.type === 'RESPONSIBILITY_ASSIGNMENT') ? 1 : 0
  }).toBe(1)
})

test('inbox dispositions round-trip through the page on the real backend', async () => {
  const inboxPath = teamPath(sessionB, teamId, 'inbox')
  await expect.poll(async () => {
    const inbox = await getJson<{ items: Array<{ inboxItemId: string, dispositionStatus: string }> }>(
      pageB, `${inboxPath}?limit=50`)
    return inbox.items.length
  }).toBeGreaterThan(0)
  const itemId = (await getJson<{ items: Array<{ inboxItemId: string, dispositionStatus: string }> }>(
    pageB, `${inboxPath}?limit=50`)).items[0]!.inboxItemId

  await pageB.goto(`/inbox?team=${teamId}&inboxItem=${itemId}`)
  await expect(pageB.getByText(/未读，处置状态由当前成员独立维护/)).toBeVisible()

  // The fresh row is UNREAD, so the offered action is marking it read; the unmark action only
  // exists from READ (contract §5.1 transition matrix).
  await pageB.getByRole('button', { name: '标记已读' }).click()
  await expect.poll(async () => {
    const detail = await getJson<{ dispositionStatus: string }>(pageB, `${inboxPath}/${itemId}`)
    return detail.dispositionStatus
  }).toBe('READ')

  // Unmark: a persisted UNREAD row, not a synthetic zero (contract §5.1).
  await pageB.getByRole('button', { name: '标为未读' }).click()
  await expect.poll(async () => {
    const detail = await getJson<{ dispositionStatus: string, dispositionVersion: number }>(
      pageB, `${inboxPath}/${itemId}`)
    return detail.dispositionStatus === 'UNREAD' && detail.dispositionVersion > 0 ? 1 : 0
  }).toBe(1)

  // Archiving asks once; the row leaves the open totals but stays browsable through the filter.
  await pageB.getByRole('button', { name: '归档', exact: true }).click()
  await expect(pageB.getByRole('alertdialog')).toContainText('从「归档」筛选恢复')
  await pageB.getByRole('alertdialog').getByRole('button', { name: '归档', exact: true }).click()
  await expect.poll(async () => {
    const detail = await getJson<{ dispositionStatus: string }>(pageB, `${inboxPath}/${itemId}`)
    return detail.dispositionStatus
  }).toBe('ARCHIVED')

  await pageB.goto(`/inbox?team=${teamId}`)
  await pageB.getByLabel('我的处置').selectOption('ARCHIVED')
  await expect(pageB).toHaveURL(/disposition=ARCHIVED/)
  await expect(pageB.getByRole('button', { name: /查看 .* 详情/ })).toHaveCount(1)

  // Restore is the single step out of the archive and lands on READ.
  await pageB.goto(`/inbox?team=${teamId}&inboxItem=${itemId}`)
  await pageB.getByRole('button', { name: '恢复（回到已读）' }).click()
  await expect.poll(async () => {
    const detail = await getJson<{ dispositionStatus: string }>(pageB, `${inboxPath}/${itemId}`)
    return detail.dispositionStatus
  }).toBe('READ')
})

test('a suspended member is cut off at the next request while others stay signed in', async () => {
  // Park B on the shared team conversation so the suspension lands on a live, interactive page.
  const conversationId = teamConversationPath.split('/').at(-1)!
  await pageB.goto(`/conversation?team=${teamId}&conversation=${conversationId}`)
  const composer = pageB.locator('#message-composer-textarea, textarea[name="content"], .conversation-composer textarea').first()
  await expect(composer).toBeVisible()

  // Member lifecycle commands are optimistic-version writes: read B's live version first and
  // carry it as If-Match (the plain command helper only sends CSRF + Idempotency-Key).
  const memberVersion = async (): Promise<string> => {
    const members = await getJson<Array<{ id: string, version: number }>>(
      pageA, teamPath(sessionA, teamId, 'members'))
    return String(members.find(member => member.id === onlyTeam(sessionB).memberId)!.version)
  }
  const lifecycle = async (action: 'suspend' | 'activate'): Promise<void> => {
    const response = await pageA.request.post(
      teamPath(sessionA, teamId, `members/${onlyTeam(sessionB).memberId}/${action}`),
      {
        data: {},
        headers: {
          [sessionA.csrf.headerName]: sessionA.csrf.token,
          'Idempotency-Key': crypto.randomUUID(),
          'If-Match': `"${await memberVersion()}"`,
        },
      })
    expect(response.status(), `${action} command failed: ${await response.text()}`).toBe(202)
  }

  await lifecycle('suspend')
  await composer.fill('a message that must be rejected after suspension')
  await pageB.getByRole('button', { name: '发送' }).click()
  await expect(pageB).toHaveURL(/\/access-denied\?from=/)

  // The owner's session is untouched by the other member's suspension.
  const ownerSession = await currentSession(pageA)
  expect(ownerSession.authenticated).toBe(true)

  // Reactivation restores access on the next request — nothing was deleted, only gated.
  await lifecycle('activate')
  await pageB.goto(`/inbox?team=${teamId}`)
  await expect(pageB.getByText(/未读，处置状态由当前成员独立维护/)).toBeVisible()
})

test('notification preferences are self-service only', async () => {
  const preferencePath = teamPath(sessionB, teamId, 'members/me/notification-preference')
  const before = await getJson<{ enabled: boolean, enabledItemTypes: string[], version: number }>(pageB, preferencePath)
  expect(before.enabledItemTypes.length).toBeGreaterThan(0)

  // The member rewrites their own preference; the optimistic-version command is honored.
  const response = await pageB.request.put(preferencePath, {
    data: {
      enabled: before.enabled,
      enabledItemTypes: [before.enabledItemTypes[0]!],
      mutedUntil: null,
    },
    headers: {
      [sessionB.csrf.headerName]: sessionB.csrf.token,
      'Idempotency-Key': crypto.randomUUID(),
      'If-Match': `"${before.version}"`,
    },
  })
  expect(response.status()).toBe(202)
  const after = await getJson<{ enabledItemTypes: string[], version: number }>(pageB, preferencePath)
  expect(after.version).toBeGreaterThan(before.version)
  expect(after.enabledItemTypes).toEqual([before.enabledItemTypes[0]!])

  // The literal `me` segment only ever resolves to the caller: another member's id in its
  // place is not a management route, it simply does not exist.
  const forged = await pageB.request.get(teamPath(sessionB, teamId, `members/${onlyTeam(sessionA).memberId}/notification-preference`))
  expect(forged.status()).toBe(404)
})

test('an unknown import job reads back with its dedicated code', async () => {
  const projectsPath = teamPath(sessionA, teamId, 'work-projects')
  const projects = await getJson<{ items: Array<{ id: string }> }>(pageA, `${projectsPath}?limit=50`)
  const projectId = projects.items[0]!.id
  const unknownJob = crypto.randomUUID()
  const response = await pageA.request.get(teamPath(sessionA, teamId, `work-projects/${projectId}/github-imports/${unknownJob}`))
  expect(response.status()).toBe(404)
  const envelope = await response.json() as { code: string, details: { jobId?: string } }
  expect(envelope.code).toBe('github_import_job_not_found')
  expect(envelope.details.jobId).toBe(unknownJob)
})

test('a private conversation stays invisible to the other member', async () => {
  const suffix = `pv-${Date.now()}`.toLowerCase().replace(/[^a-z0-9]+/g, '-')
  const conversationsPath = teamPath(sessionB, teamId, 'conversations')
  await command(pageB, sessionB, conversationsPath, { title: `S2 私有 ${suffix}`.slice(0, 96), visibility: 'PRIVATE' })
  await expect.poll(async () => {
    const own = await getJson<{ items: Array<{ title: string }> }>(pageB, `${conversationsPath}?limit=50`)
    return own.items.some(item => item.title.includes(suffix)) ? 1 : 0
  }).toBe(1)

  const other = await getJson<{ items: Array<{ title: string }> }>(pageA, `${conversationsPath}?limit=50`)
  expect(other.items.some(item => item.title.includes(suffix))).toBe(false)
})

test.afterAll(async () => {
  await contextA?.close()
  await contextB?.close()
})
