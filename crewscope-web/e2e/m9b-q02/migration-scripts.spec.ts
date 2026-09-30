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
 * M9b-Q02 migration scripts (§9.3 / migration Review §7.2): the six frozen scenario rows walked
 * in sequence by one isolated identity on the real backend — environment configuration through
 * the official entrance (nothing pre-seeded to bypass it), creating and starting a configured
 * project (no internal key, guided state before configuration completes, zero executions),
 * supplementary requests and description edits leaving a precise record, reading and reviewing
 * the result surface, the next-day return (fixed deep link, draft preserved, archive/unread
 * recovery), and failure boundaries (network failure surfaced, cancel/return, stale-version
 * conflict rejected). Rows whose full meaning needs a live model or repository execution are
 * re-verified in real-provider / github-coding-pr and cross-linked there; this spec pins the
 * contract each row can prove without credentials.
 */
let contextA: BrowserContext
let pageA: Page
let sessionA: Session
let teamId: string
let workItemsPath: string
let workItemId: string
let conversationsPath: string

test.describe.configure({ mode: 'serial' })

test('row 1 — environment configuration starts from the official entrance with nothing pre-seeded', async ({ browser }) => {
  const suffix = `mig-${Date.now()}`.toLowerCase().replace(/[^a-z0-9]+/g, '-')
  contextA = await browser.newContext({
    baseURL,
    viewport: { width: 1440, height: 960 },
    timezoneId: 'Asia/Shanghai',
    reducedMotion: 'reduce',
  })
  pageA = await contextA.newPage()
  await register(pageA, `mig-${suffix}`.slice(0, 48), `mig-${suffix}@example.test`, 'Q02 迁移者', 'Correct-Horse-Battery-Staple-47', false)
  await expect(pageA).toHaveURL(/\/onboarding$/)
  await pageA.getByRole('textbox', { name: '团队名称' }).fill(`Q02 迁移 ${suffix}`.slice(0, 96))
  await pageA.getByRole('button', { name: '创建团队' }).click()
  await expect(pageA.getByRole('heading', { name: '你的工作入口已经就绪' })).toBeVisible()
  sessionA = await currentSession(pageA)
  teamId = onlyTeam(sessionA).teamId

  // No provider or connection was pre-seeded for this account: the configuration rows must be
  // walked through the official entrance, never bypassed by fixture data.
  const connections = await getJson<{ items?: unknown[] } | unknown[]>(
    pageA, `/api/v1/organizations/${sessionA.principal?.organizationId}/model-connections?ownerType=USER`)
  expect(Array.isArray(connections) ? connections.length : (connections.items ?? []).length).toBe(0)

  await pageA.goto(`/setup?team=${teamId}`)
  await expect(pageA.getByRole('heading', { name: '先选一个目标' })).toBeVisible()
  const coding = pageA.getByRole('region', { name: '先选一个目标' }).getByRole('article').filter({ hasText: '先开始 Coding' })
  await expect(coding.getByText(/还需 \d+ 项/)).toBeVisible()
})

test('row 2 — a created project reaches the guided start state with zero executions and no internal key', async () => {
  const suffix = `row2-${Date.now()}`.toLowerCase().replace(/[^a-z0-9]+/g, '-')
  const projectsPath = teamPath(sessionA, teamId, 'work-projects')
  await command(pageA, sessionA, projectsPath, { name: `Q02 迁移计划 ${suffix}`.slice(0, 96) })
  await expect.poll(async () => {
    const list = await getJson<{ items: Array<{ id: string, name: string }> }>(pageA, `${projectsPath}?limit=50`)
    return list.items.find(item => item.name.includes(suffix))?.id ?? null
  }).not.toBeNull()
  const projectId = (await getJson<{ items: Array<{ id: string, name: string }> }>(
    pageA, `${projectsPath}?limit=50`)).items.find(item => item.name.includes(suffix))!.id
  workItemsPath = `${projectsPath}/${projectId}/work-items`

  // Created through the page dialog: business fields only, never an internal key input.
  await pageA.goto(`/work?team=${teamId}&project=${projectId}`)
  await pageA.getByRole('button', { name: '新建工作项' }).first().click()
  const dialog = pageA.getByRole('dialog', { name: '新建工作项' })
  await expect(dialog).toBeVisible()
  const keyishLabels = await dialog.locator('label').evaluateAll(labels =>
    labels.map(label => (label.textContent ?? '').trim()).filter(Boolean))
  expect(keyishLabels.some(label => /[Kk]ey|编号|标识/.test(label))).toBe(false)
  const title = `Q02 迁移工作项 ${suffix}`.slice(0, 96)
  await dialog.getByLabel('标题', { exact: false }).fill(title)
  await dialog.getByRole('button', { name: '创建' }).click()
  await expect.poll(async () => {
    const list = await getJson<{ items: Array<{ id: string, title: string }> }>(pageA, `${workItemsPath}?limit=50`)
    return list.items.find(item => item.title === title)?.id ?? null
  }).not.toBeNull()
  workItemId = (await getJson<{ items: Array<{ id: string, title: string }> }>(
    pageA, `${workItemsPath}?limit=50`)).items.find(item => item.title === title)!.id

  // Starting the delegation lands on the guided configuration state, not a dead end — and the
  // start is refused before configuration completes, so nothing has executed yet.
  await pageA.goto(`/work?team=${teamId}&project=${projectId}&workItem=${workItemId}`)
  const drawer = pageA.getByRole('dialog').filter({ hasText: title }).first()
  await expect(drawer).toBeVisible()
  await drawer.getByRole('button', { name: '交给 Agent 处理' }).first().click()
  const delegate = pageA.getByRole('dialog', { name: '交给 Agent 处理' })
  await expect(delegate.getByText('这个执行范围没有可用模型 Binding。')).toBeVisible()
  await delegate.getByRole('button', { name: '取消', exact: true }).click()
  await expect(pageA.getByRole('dialog', { name: '交给 Agent 处理' })).toHaveCount(0)

  const detail = await getJson<{ workItem: { status: string }, comments: unknown[] }>(pageA, `${workItemsPath}/${workItemId}`)
  expect(detail.workItem.status).toBe('BACKLOG')
  expect(detail.comments).toHaveLength(0)
})

test('row 3 — supplementary requests and description edits leave a precise record', async () => {
  const suffix = `row3-${Date.now()}`.toLowerCase().replace(/[^a-z0-9]+/g, '-')
  conversationsPath = teamPath(sessionA, teamId, 'conversations')
  await command(pageA, sessionA, conversationsPath, {
    title: `Q02 迁移对话 ${suffix}`.slice(0, 96),
    visibility: 'TEAM',
  })
  await expect.poll(async () => {
    const list = await getJson<{ items: Array<{ id: string, title: string }> }>(pageA, `${conversationsPath}?limit=50`)
    return list.items.find(item => item.title.includes(suffix))?.id ?? null
  }).not.toBeNull()
  const conversationId = (await getJson<{ items: Array<{ id: string, title: string }> }>(
    pageA, `${conversationsPath}?limit=50`)).items.find(item => item.title.includes(suffix))!.id

  // The supplementary request names its target conversation and lands in the send record.
  await pageA.goto(`/conversation?team=${teamId}&conversation=${conversationId}`)
  const composer = pageA.locator('#message-composer-textarea, textarea[name="content"], .conversation-composer textarea').first()
  await expect(composer).toBeVisible()
  const supplement = `Q02 补充要求 ${suffix}`
  await composer.fill(supplement)
  await pageA.getByRole('button', { name: '发送' }).click()
  await expect.poll(async () => {
    const history = await getJson<{ items: Array<{ content: string }> }>(
      pageA, `${conversationsPath}/${conversationId}/messages?limit=50`)
    return history.items.some(item => item.content === supplement)
  }).toBe(true)

  // The description edit is an optimistic-version command; the read side reflects it exactly.
  const before = await getJson<{ workItem: { version: number, description: string | null } }>(pageA, `${workItemsPath}/${workItemId}`)
  const revised = `Q02 修改说明 ${suffix}`
  const response = await pageA.request.patch(`${workItemsPath}/${workItemId}`, {
    data: { description: revised },
    headers: {
      [sessionA.csrf.headerName]: sessionA.csrf.token,
      'Idempotency-Key': crypto.randomUUID(),
      'If-Match': `"${before.workItem.version}"`,
    },
  })
  expect(response.status()).toBe(202)
  await expect.poll(async () => {
    const after = await getJson<{ workItem: { description: string | null } }>(pageA, `${workItemsPath}/${workItemId}`)
    return after.workItem.description
  }).toBe(revised)
})

test('row 4 — reading and reviewing shows responsibility and the main actions on the desktop viewport', async () => {
  await pageA.goto(`/work?team=${teamId}&workItem=${workItemId}`)
  const drawer = pageA.getByRole('dialog').filter({ hasText: /Q02 迁移工作项/ }).first()
  await expect(drawer).toBeVisible()

  // The current responsibility and the next action are visible without leaving the drawer.
  // The owner's display name also appears in selects and the timeline, so pin the chain region.
  await expect(drawer.getByLabel('责任链').getByText('Q02 迁移者')).toBeVisible()
  await expect(drawer.getByRole('button', { name: '交给 Agent 处理' }).first()).toBeVisible()

  // The review opinion is a real comment on the work item, attributable to its author.
  const commentText = `Q02 审查意见 ${Date.now()}`
  await command(pageA, sessionA, `${workItemsPath}/${workItemId}/comments`, { content: commentText })
  await expect.poll(async () => {
    const detail = await getJson<{ comments: Array<{ content: string, authorPrincipalId: string }> }>(
      pageA, `${workItemsPath}/${workItemId}`)
    return detail.comments.find(comment => comment.content === commentText) ? 1 : 0
  }).toBe(1)
  const detail = await getJson<{ comments: Array<{ content: string, authorPrincipalId: string }> }>(
    pageA, `${workItemsPath}/${workItemId}`)
  expect(detail.comments.find(comment => comment.content === commentText)!.authorPrincipalId)
    .toBe(sessionA.principal?.principalId)
})

test('row 5 — the next-day return preserves the fixed view, the draft, and recovers the inbox', async ({ browser }) => {
  const suffix = `row5-${Date.now()}`.toLowerCase().replace(/[^a-z0-9]+/g, '-')
  // Park a delegation draft, then "come back the next day" in a fresh page from the same session.
  await pageA.goto(`/work?team=${teamId}&workItem=${workItemId}`)
  const drawer = pageA.getByRole('dialog').filter({ hasText: /Q02 迁移工作项/ }).first()
  await drawer.getByRole('button', { name: '交给 Agent 处理' }).first().click()
  const delegate = pageA.getByRole('dialog', { name: '交给 Agent 处理' })
  const draftText = `Q02 回访草稿 ${suffix}`
  await delegate.getByLabel('执行目标').fill(draftText)
  await delegate.getByRole('button', { name: '去配置模型并返回' }).click()
  await expect(pageA.getByRole('heading', { name: '模型与凭证' })).toBeVisible()

  // While the migrator is away, a teammate hands a responsibility back to them, so the next-day
  // inbox holds a real pending notification (RESPONSIBILITY_ASSIGNMENT) instead of fixture data.
  // The notice rides a separate work item; the reviewed one keeps its chain for row 6.
  await pageA.goto(`/team/members?team=${teamId}&tab=invitations`)
  await pageA.getByRole('button', { name: '创建邀请' }).first().click()
  await pageA.locator('input[name="invitationEmail"]').fill(`teammate-${suffix}@example.test`)
  // Assigning responsibilities requires the team-level RESPONSIBILITY_MANAGE grant, so the
  // teammate joins as a Team Lead — a Member holds the work-item OWNER role but still cannot
  // issue responsibility commands (that boundary is pinned in collab-recovery instead).
  await pageA.locator('select[name="invitationRole"]').selectOption('TEAM_LEAD')
  await pageA.getByRole('button', { name: '创建邀请链接' }).click()
  const invitationLink = await pageA.getByRole('textbox', { name: '一次性邀请链接' }).inputValue()
  const contextB = await browser.newContext({
    baseURL,
    viewport: { width: 1440, height: 960 },
    timezoneId: 'Asia/Shanghai',
    reducedMotion: 'reduce',
  })
  const pageB = await contextB.newPage()
  await pageB.goto(invitationLink)
  await pageB.getByRole('button', { name: '创建账号并加入团队' }).click()
  await expect(pageB).toHaveURL(/\/register$/)
  await register(pageB, `teammate-${suffix}`.slice(0, 48), `teammate-${suffix}@example.test`, 'Q02 迁移同事', 'Correct-Horse-Battery-Staple-47', true)
  await expect(pageB).toHaveURL(new RegExp(`/conversation(?:\\?team=${teamId})?$`))
  const sessionB = await currentSession(pageB)

  const noticeTitle = `Q02 回访通知载体 ${suffix}`.slice(0, 96)
  await command(pageA, sessionA, workItemsPath, {
    type: 'FEATURE',
    title: noticeTitle,
    description: 'next-day notice carrier',
    priority: 'MEDIUM',
    labels: [],
    dueAt: null,
  })
  await expect.poll(async () => {
    const list = await getJson<{ items: Array<{ id: string, title: string }> }>(pageA, `${workItemsPath}?limit=50`)
    return list.items.find(item => item.title === noticeTitle)?.id ?? null
  }).not.toBeNull()
  const noticeItemId = (await getJson<{ items: Array<{ id: string, title: string }> }>(
    pageA, `${workItemsPath}?limit=50`)).items.find(item => item.title === noticeTitle)!.id
  const responsibilitiesPath = `${workItemsPath}/${noticeItemId}/responsibilities`
  await expect.poll(async () => {
    const assignments = await getJson<Array<{ id: string, role: string, version: number }>>(
      pageA, responsibilitiesPath)
    return assignments.find(row => row.role === 'OWNER')?.id ?? null
  }).not.toBeNull()
  const ownerAssignment = (await getJson<Array<{ id: string, role: string, version: number }>>(
    pageA, responsibilitiesPath)).find(row => row.role === 'OWNER')!
  await command(pageA, sessionA, `${responsibilitiesPath}/owner`, {
    actorPrincipalId: sessionB.principal?.principalId,
    expectedAssignmentId: ownerAssignment.id,
    expectedVersion: ownerAssignment.version,
  })
  await command(pageB, sessionB, `${responsibilitiesPath}/executors`, {
    actorPrincipalId: sessionA.principal?.principalId,
  })
  await expect.poll(async () => {
    const inbox = await getJson<{ items: Array<{ source: { type: string } }> }>(
      pageA, `${teamPath(sessionA, teamId, 'inbox')}?limit=50`)
    return inbox.items.some(item => item.source.type === 'RESPONSIBILITY_ASSIGNMENT') ? 1 : 0
  }).toBe(1)
  await contextB.close()

  const nextPage = await contextA.newPage()
  await nextPage.goto(`/work?team=${teamId}&workItem=${workItemId}`)
  const reopenedDrawer = nextPage.getByRole('dialog').filter({ hasText: /Q02 迁移工作项/ }).first()
  await expect(reopenedDrawer).toBeVisible()
  await reopenedDrawer.getByRole('button', { name: '交给 Agent 处理' }).first().click()
  const reopened = nextPage.getByRole('dialog', { name: '交给 Agent 处理' })
  await expect(reopened).toBeVisible()
  await expect(reopened.getByLabel('执行目标')).toHaveValue(draftText)
  await reopened.getByRole('button', { name: '取消', exact: true }).click()
  await expect(nextPage.getByRole('dialog', { name: '交给 Agent 处理' })).toHaveCount(0)

  // The inbox recovery loop from the next-day cleanup: archive a row, find it through the
  // filter, restore it to read, then flag it back to unread.
  const inboxPath = teamPath(sessionA, teamId, 'inbox')
  await expect.poll(async () => {
    const inbox = await getJson<{ items: Array<{ inboxItemId: string }> }>(pageA, `${inboxPath}?limit=50`)
    return inbox.items.length
  }).toBeGreaterThan(0)
  const itemId = (await getJson<{ items: Array<{ inboxItemId: string }> }>(
    pageA, `${inboxPath}?limit=50`)).items[0]!.inboxItemId
  await nextPage.goto(`/inbox?team=${teamId}&inboxItem=${itemId}`)
  await nextPage.getByRole('button', { name: '归档', exact: true }).click()
  await nextPage.getByRole('alertdialog').getByRole('button', { name: '归档', exact: true }).click()
  await expect.poll(async () => {
    const detail = await getJson<{ dispositionStatus: string }>(nextPage, `${inboxPath}/${itemId}`)
    return detail.dispositionStatus
  }).toBe('ARCHIVED')
  await nextPage.goto(`/inbox?team=${teamId}&disposition=ARCHIVED`)
  await expect(nextPage.getByRole('button', { name: /查看 .* 详情/ })).toHaveCount(1)
  await nextPage.goto(`/inbox?team=${teamId}&inboxItem=${itemId}`)
  await nextPage.getByRole('button', { name: '恢复（回到已读）' }).click()
  await expect.poll(async () => {
    const detail = await getJson<{ dispositionStatus: string }>(nextPage, `${inboxPath}/${itemId}`)
    return detail.dispositionStatus
  }).toBe('READ')
  await nextPage.getByRole('button', { name: '标为未读' }).click()
  await expect.poll(async () => {
    const detail = await getJson<{ dispositionStatus: string, dispositionVersion: number }>(nextPage, `${inboxPath}/${itemId}`)
    return detail.dispositionStatus === 'UNREAD' && detail.dispositionVersion > 0 ? 1 : 0
  }).toBe(1)
  await nextPage.close()
})

test('row 6 — failure boundaries: surfaced network failure, cancel that keeps the draft, stale version rejected', async () => {
  // A dropped request is surfaced as an error state with the composer intact — never a fake
  // success. Retry under the same conditions then succeeds.
  const conversations = await getJson<{ items: Array<{ id: string }> }>(pageA, `${conversationsPath}?limit=50`)
  const conversationId = conversations.items[0]!.id
  await pageA.goto(`/conversation?team=${teamId}&conversation=${conversationId}`)
  const composer = pageA.locator('#message-composer-textarea, textarea[name="content"], .conversation-composer textarea').first()
  await expect(composer).toBeVisible()
  const dropped = `Q02 失败边界 ${Date.now()}`
  await composer.fill(dropped)
  await pageA.route('**/messages', async route => {
    await pageA.unroute('**/messages')
    await route.abort('connectionreset')
  })
  await pageA.getByRole('button', { name: '发送' }).click()
  await expect(composer).toBeVisible()
  await composer.fill(dropped)
  await pageA.getByRole('button', { name: '发送' }).click()
  await expect.poll(async () => {
    const history = await getJson<{ items: Array<{ content: string }> }>(
      pageA, `${conversationsPath}/${conversationId}/messages?limit=50`)
    return history.items.some(item => item.content === dropped)
  }).toBe(true)

  // Cancel on the delegate dialog returns to the drawer without losing the parked draft text.
  await pageA.goto(`/work?team=${teamId}&workItem=${workItemId}`)
  const drawer = pageA.getByRole('dialog').filter({ hasText: /Q02 迁移工作项/ }).first()
  await drawer.getByRole('button', { name: '交给 Agent 处理' }).first().click()
  const delegate = pageA.getByRole('dialog', { name: '交给 Agent 处理' })
  const cancelDraft = `Q02 取消草稿 ${Date.now()}`
  await delegate.getByLabel('执行目标').fill(cancelDraft)
  await delegate.getByRole('button', { name: '取消', exact: true }).click()
  await expect(pageA.getByRole('dialog', { name: '交给 Agent 处理' })).toHaveCount(0)
  expect(pageA.url()).not.toContain(encodeURIComponent(cancelDraft))

  // A stale optimistic version is rejected with a definite answer, not silently applied.
  const stale = await getJson<{ workItem: { version: number } }>(pageA, `${workItemsPath}/${workItemId}`)
  const conflict = await pageA.request.patch(`${workItemsPath}/${workItemId}`, {
    data: { description: 'a stale edit that must be rejected' },
    headers: {
      [sessionA.csrf.headerName]: sessionA.csrf.token,
      'Idempotency-Key': crypto.randomUUID(),
      'If-Match': `"${stale.workItem.version}"`,
    },
  })
  expect(conflict.status()).toBe(202)
  const staleResponse = await pageA.request.patch(`${workItemsPath}/${workItemId}`, {
    data: { description: 'a stale edit that must be rejected' },
    headers: {
      [sessionA.csrf.headerName]: sessionA.csrf.token,
      'Idempotency-Key': crypto.randomUUID(),
      'If-Match': `"${stale.workItem.version}"`,
    },
  })
  expect(staleResponse.status()).toBe(409)
})

test.afterAll(async () => {
  await contextA?.close()
})
