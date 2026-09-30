import { expect, test, type Browser, type Page, type Route } from '@playwright/test'
import { permissions } from '../src/app/auth'
import { activeRow, createWorld, ids, installMembersApi, viewerPrincipal, type A07World, type Viewer } from './m9b-a07/fixtures'

/**
 * M9b-Q01 revocation matrix (plan §9.1 item 5 + main plan :375): one serial world threads the
 * seven revocation channels through the *frontend* faces only — what the affected member's own
 * browser observes. The server-side checkpoints (per-request re-reads, token authentication
 * boundaries, heartbeat windows) are owned by the m9b-a07 integration tests; every assertion
 * here is something the UI does with the 403/401/session-narrowing facts those checkpoints emit.
 *
 * Channels and their observable faces:
 * - 角色降级: the session projection narrows per-Team permissions after reload — the member
 *   keeps reading the directory but loses the management affordance, and the capability never
 *   leaks into the other Team.
 * - 最后 Owner 保护: the refusing command surfaces verbatim in the dialog and mutates nothing.
 * - Team SSE（Token/旧 SSE 授权失效）: the open Activity stream is cut with 403, the page shows
 *   the forbidden panel and — unlike transport failures — never schedules a reconnect.
 * - 移除成员 + 在途任务失效: the running Task's live stream is refused (实时连接不可用), the
 *   next message submit bounces to access-denied, and the F05 team-scoped storage of the revoked
 *   Team disappears while the other Team's draft survives and still restores.
 * - Team 删除: no Team-deletion API exists (R01 matrix row records the adjudication); the
 *   closest implemented fact is the team disappearing from the session projection, driven here.
 * - Session 过期: 401 authentication_required clears ALL user-scoped storage and lands on
 *   /login with a returnTo — the contrast pair of the per-Team 403 clearing above.
 */
const q01 = {
  conversation: '00000000-0000-4000-8000-000000000901',
  task: '00000000-0000-4000-8000-000000000902',
  workItem: '00000000-0000-4000-8000-000000000903',
}

// The a07 member set plus work:create — the growth-team draft seeding below opens the real
// create dialog, and the f05 draft flow must run through the same permission the UI checks.
const memberBase = ['conversation:use', 'scope:read', 'team:members:read', 'work-projects:read', 'work:read', 'work:create']

interface RevocationWorld {
  world: A07World
  /** Viewers whose session endpoint answers 401 authentication_required (channel: 会话过期). */
  expired: Set<Viewer>
  /** Live-stream routes held open by the fixture; the test decides when authorization lands. */
  held: Map<string, Route>
  /** Every activity-events connection attempt, so the matrix can prove forbidden does not retry. */
  activityConnections: string[]
}

test.describe.configure({ mode: 'serial' })

test.describe('M9b-Q01 revocation matrix', () => {
  let ownerPage: Page
  let memberPage: Page
  let state: RevocationWorld

  test.beforeAll(async ({ browser }: { browser: Browser }) => {
    state = {
      world: createWorld(),
      expired: new Set<Viewer>(),
      held: new Map<string, Route>(),
      activityConnections: [],
    }
    ownerPage = await (await browser.newContext()).newPage()
    memberPage = await (await browser.newContext()).newPage()
    // The a07 interception owns teams/projects/members/lifecycle commands; the Q01 extension is
    // registered afterwards, so its specific patterns win for the endpoints it fulfils and
    // everything else still reaches the a07 handler.
    await installMembersApi(ownerPage, 'owner', state.world)
    await installMembersApi(memberPage, 'member', state.world)
    await installRevocationApi(ownerPage, 'owner', state)
    await installRevocationApi(memberPage, 'member', state)
  })

  test('role downgrade narrows the session projection per Team without expelling the member', async () => {
    await ownerPage.goto(`/team/members?team=${ids.teamPlatform}`)
    const row = ownerPage.getByRole('row').filter({ hasText: '林晨' })
    const roles = row.locator('.member-roles')
    await row.getByRole('button', { name: '更多操作：林晨' }).click()
    await ownerPage.getByRole('menuitem', { name: /授予角色/ }).click()
    const grantDialog = ownerPage.getByRole('dialog', { name: '给 林晨 授予角色' })
    await grantDialog.locator('#lifecycle-role').selectOption('TEAM_LEAD')
    await grantDialog.getByRole('button', { name: '确认授予' }).click()
    await expect(roles).toHaveText('团队负责人')

    // The TEAM_LEAD grant widens the member's own session for THIS Team only.
    await memberPage.goto(`/team/members?team=${ids.teamPlatform}`)
    await expect(memberPage.getByRole('table', { name: '团队成员列表' })).toContainText('张凯旋')
    expect(await memberPage.getByRole('button', { name: '添加成员' }).count()).toBeGreaterThan(0)
    await memberPage.goto(`/team/members?team=${ids.teamGrowth}`)
    await expect(memberPage.getByRole('table', { name: '团队成员列表' })).toContainText('林晨')
    await expect(memberPage.getByRole('button', { name: '添加成员' })).toHaveCount(0)

    await row.getByRole('button', { name: '更多操作：林晨' }).click()
    await ownerPage.getByRole('menuitem', { name: /撤销角色 · 团队负责人/ }).click()
    await ownerPage.getByRole('dialog', { name: '撤销 林晨 的团队负责人角色？' }).getByRole('button', { name: '确认撤销角色' }).click()
    await expect(roles).not.toContainText('团队负责人')

    // Downgrade is not expulsion: the directory stays readable and the capability is gone.
    await memberPage.goto(`/team/members?team=${ids.teamPlatform}`)
    await expect(memberPage.getByRole('table', { name: '团队成员列表' })).toContainText('林晨')
    await expect(memberPage.getByRole('button', { name: '添加成员' })).toHaveCount(0)
    const roleCommands = state.world.commands.filter(command => command.action === 'grantRole' || command.action === 'revokeRole')
    expect(roleCommands.map(command => [command.action, command.ifMatch])).toEqual([['grantRole', '"0"'], ['revokeRole', '"1"']])
    expect(roleCommands[0]?.roleKey).toBe('TEAM_LEAD')
  })

  test('the last Owner protection refuses the self-leave verbatim and mutates nothing', async () => {
    await memberPage.goto(`/team/members?team=${ids.teamGrowth}`)
    const row = memberPage.getByRole('row').filter({ hasText: '林晨' })
    await row.getByRole('button', { name: '更多操作：林晨' }).click()
    await memberPage.getByRole('menuitem', { name: /离开这个 Team/ }).click()
    const dialog = memberPage.getByRole('dialog', { name: '离开这个 Team？' })
    await dialog.getByRole('button', { name: '确认离开' }).click()

    await expect(dialog.getByRole('alert')).toContainText('最后一个 Owner 保护')
    await expect(row).toContainText('活跃')
    expect(state.world.commands.at(-1)).toMatchObject({
      action: 'leave', teamId: ids.teamGrowth, viewer: 'member', error: 'last_owner_protection',
    })
    expect(activeRow(state.world, ids.teamGrowth, ids.principalMember)?.status).toBe('ACTIVE')
  })

  test('revocation cuts the open Team SSE into a terminal forbidden state that never retries', async () => {
    await memberPage.goto(`/activity?team=${ids.teamPlatform}&project=${ids.projectPlatform}`)
    await expect(memberPage.getByRole('heading', { name: '团队 Activity', exact: true })).toBeVisible()

    await ownerPage.goto(`/team/members?team=${ids.teamPlatform}`)
    const row = ownerPage.getByRole('row').filter({ hasText: '林晨' })
    await row.getByRole('button', { name: '更多操作：林晨' }).click()
    await ownerPage.getByRole('menuitem', { name: /停用成员/ }).click()
    await ownerPage.getByRole('dialog', { name: '停用 林晨？' }).getByRole('button', { name: '确认停用' }).click()
    await expect(row).toContainText('已暂停')
    expect(state.world.commands.at(-1)).toMatchObject({ action: 'suspend', teamId: ids.teamPlatform, memberId: ids.memberLin })

    // The live connection the member already holds is answered with the authorization fact.
    await expect.poll(() => state.activityConnections).toEqual([ids.teamPlatform])
    await state.held.get(`activity:${ids.teamPlatform}`)!.fulfill(forbiddenBody())
    await expect(memberPage.getByRole('heading', { name: '无权查看团队活动' })).toBeVisible()
    // Forbidden is terminal: unlike a transport failure no reconnect is scheduled (500ms backoff
    // would have produced a second connection attempt inside this window).
    await memberPage.waitForTimeout(900)
    expect(state.activityConnections).toEqual([ids.teamPlatform])

    // Reactivate so the removal channel below starts from an active membership again.
    await row.getByRole('button', { name: '更多操作：林晨' }).click()
    await ownerPage.getByRole('menuitem', { name: /恢复成员/ }).click()
    await ownerPage.getByRole('dialog', { name: '恢复 林晨？' }).getByRole('button', { name: '确认恢复' }).click()
    await expect(row).toContainText('活跃')
  })

  test('member removal invalidates the in-flight Task stream, bounces the next command and clears only that Team scope', async () => {
    // Seed the OTHER Team's scoped state first, through the real create dialog, so the
    // per-Team clearing below has a survivor to prove selective.
    await memberPage.goto(`/work?team=${ids.teamGrowth}&project=${ids.projectGrowth}`)
    await memberPage.getByRole('button', { name: '新建工作项', exact: true }).first().click()
    await memberPage.getByLabel('标题', { exact: true }).fill('Growth 的撤权幸存草稿')
    await memberPage.getByRole('button', { name: '关闭新建工作项' }).click()

    await memberPage.goto(`/conversation?team=${ids.teamPlatform}&project=${ids.projectPlatform}&conversation=${q01.conversation}`)
    await expect(memberPage.getByRole('heading', { name: '在途任务的对话', exact: true }).first()).toBeVisible()
    // 关联卡默认摘要收起（M9b-Q01）：断言卡内事实前先展开。
    await memberPage.locator('summary', { hasText: '关联任务' }).click()
    const card = memberPage.getByTestId('conversation-task-cards').locator(`[data-task-id="${q01.task}"]`)
    await expect(card).toContainText('撤权时仍在执行的在途任务')
    // 在途任务：the live stream is being connected right now — the fixture holds it open.
    await expect(card.locator('.live-fact')).toHaveText('正在连接')
    await expect.poll(() => state.held.has(`task:${q01.task}`)).toBe(true)
    // The conversation writes platform-scoped reading state on load; the message draft below
    // adds the draft kind. Both are team-scoped F05 records of the Team about to be revoked.
    await memberPage.getByLabel('消息内容').fill('在途任务期间的团队消息草稿')
    expect(await scopedKeyCount(memberPage, 'draft', q01.conversation)).toBe(1)
    expect(await scopedKeyCount(memberPage, 'reading', 'conversation-read-sequences')).toBeGreaterThan(0)

    await ownerPage.goto(`/team/members?team=${ids.teamPlatform}`)
    const row = ownerPage.getByRole('row').filter({ hasText: '林晨' })
    await row.getByRole('button', { name: '更多操作：林晨' }).click()
    await ownerPage.getByRole('menuitem', { name: /移除成员/ }).click()
    await ownerPage.getByRole('dialog', { name: '移除 林晨？' }).getByRole('button', { name: '确认移除' }).click()
    await expect(row).toContainText('已移除')
    expect(state.world.commands.at(-1)).toMatchObject({ action: 'remove', teamId: ids.teamPlatform, memberId: ids.memberLin })

    // 在途任务失效：the Task live stream the browser still holds is refused at its next
    // authorization checkpoint and the card says so — the run never keeps streaming. This first
    // 403 also fires the global onForbidden → clearF05TeamScope(platform).
    await state.held.get(`task:${q01.task}`)!.fulfill(forbiddenBody())
    await expect(card.locator('.live-fact')).toHaveText('实时连接不可用')

    // The member's next command on the revoked Team bounces to the explicit denial page.
    await memberPage.getByLabel('消息内容').fill('撤权后的消息')
    await memberPage.getByRole('button', { name: '发送', exact: true }).click()
    await expect(memberPage).toHaveURL(/\/access-denied\?from=/)

    // 403 cleared exactly the revoked Team's scoped storage (main.ts onForbidden →
    // clearF05TeamScope): the platform reading anchor is gone. The message draft itself is
    // re-written after the failed submit by the draft-preservation behaviour (a failed send
    // restores the draft on purpose), so the anchor — which has no write-back path after the
    // redirect — is the deterministic witness here; the growth draft survives either way.
    expect(await scopedKeyCount(memberPage, 'reading', 'conversation-read-sequences')).toBe(0)
    expect(await scopedKeyCount(memberPage, 'draft', 'create:GRW')).toBe(1)

    // The untouched Team's draft still restores through its own create flow.
    await memberPage.goto(`/work?team=${ids.teamGrowth}&project=${ids.projectGrowth}`)
    await memberPage.getByRole('button', { name: '新建工作项', exact: true }).first().click()
    await expect(memberPage.getByLabel('标题', { exact: true })).toHaveValue('Growth 的撤权幸存草稿')
  })

  test('a Team leaving the session projection revokes its permissions instead of guessing a fallback', async () => {
    // There is no Team-deletion API (R01 matrix row records the adjudication); deleting the
    // Team from the world drives the closest implemented fact: it vanishes from the session.
    // With no Team left the per-Team permissions are gone too, so the router's permission
    // guard fires BEFORE any scope empty panel: the member lands on the explicit denial page
    // rather than being silently re-scoped to some other Team.
    state.world.teams = state.world.teams.filter(team => team.id !== ids.teamGrowth)
    await memberPage.goto(`/conversation?team=${ids.teamGrowth}`)
    await expect(memberPage).toHaveURL(/\/access-denied\?.*from=/)
    await expect(memberPage.getByRole('heading', { name: '当前账号无法访问这个区域' })).toBeVisible()
    await expect(memberPage.getByRole('region', { name: '需要额外的团队权限' })).toContainText('使用对话')
  })

  test('session expiry signs the browser out with all user-scoped state cleared, not just one Team', async () => {
    state.expired.add('member')
    await memberPage.reload()
    await expect(memberPage).toHaveURL(/\/login\?returnTo=/)

    // 401 is the whole-account boundary: the growth draft that survived the Team revocation is
    // gone too (clearSession → clearF05UserData), unlike the per-Team 403 clearing above.
    expect(await scopedKeyCount(memberPage, 'draft', '')).toBe(0)

    // The other browser's session is a separate authority and keeps working.
    await ownerPage.goto(`/team/members?team=${ids.teamPlatform}`)
    await expect(ownerPage.getByRole('table', { name: '团队成员列表' })).toContainText('张凯旋')
  })
})

async function installRevocationApi(page: Page, viewer: Viewer, state: RevocationWorld): Promise<void> {
  const revoked = (teamId: string): boolean => !activeRow(state.world, teamId, viewerPrincipal(viewer))

  await page.route(/\/api\/v1\/auth\/session$/, async route => {
    if (state.expired.has(viewer)) {
      return route.fulfill({
        status: 401,
        contentType: 'application/json',
        body: JSON.stringify({ code: 'authentication_required', message: 'the session is no longer valid', correlationId: 'correlation-q01', retryable: false, currentVersion: null, details: {} }),
      })
    }
    return json(route, sessionFor(state, viewer))
  })

  await page.route(/\/api\/v1\/organizations\/[^/]+\/teams\/([^/]+)\/activity\/snapshot/, async route => {
    if (revoked(teamIdOf(route))) return route.fulfill(forbiddenBody())
    return json(route, { items: [], hasMore: false, nextCursor: null, snapshotCursor: 'q01-activity-snapshot' })
  })
  await page.route(/\/api\/v1\/organizations\/[^/]+\/teams\/([^/]+)\/activity\/events/, async route => {
    const teamId = teamIdOf(route)
    if (revoked(teamId)) return route.fulfill(forbiddenBody())
    // A held route is the mock's stand-in for a live connection: the server keeps it open until
    // an authorization boundary decides otherwise, and the test pulls that trigger.
    state.activityConnections.push(teamId)
    state.held.set(`activity:${teamId}`, route)
    return undefined
  })

  await page.route(/\/api\/v1\/organizations\/[^/]+\/teams\/([^/]+)\/conversations(\?.*)?$/, async route => {
    if (revoked(teamIdOf(route))) return route.fulfill(forbiddenBody())
    return json(route, { items: [conversationRow()], nextCursor: null })
  })
  await page.route(/\/api\/v1\/organizations\/[^/]+\/teams\/[^/]+\/conversations\/[^/]+$/, async route => {
    if (revoked(teamIdOf(route))) return route.fulfill(forbiddenBody())
    return json(route, {
      conversation: conversationRow(),
      participants: [
        participantRow(ids.principalOwner, 'OWNER', '张凯旋'),
        participantRow(ids.principalMember, 'MEMBER', '林晨'),
      ],
    })
  })
  await page.route(/\/api\/v1\/organizations\/[^/]+\/teams\/[^/]+\/conversations\/[^/]+\/messages(\?.*)?$/, async route => {
    if (revoked(teamIdOf(route))) return route.fulfill(forbiddenBody())
    if (route.request().method() === 'POST') {
      return json(route, { commandId: 'command-q01-message', domainEventId: 'event-q01-message', committedVersion: 2, correlationId: 'correlation-q01' }, 202)
    }
    return json(route, {
      items: [{ id: '00000000-0000-4000-8000-000000000911', conversationId: q01.conversation, sequence: 1, type: 'USER_MESSAGE', participantId: '00000000-0000-4000-8000-000000000912', authorPrincipalId: ids.principalMember, content: '任务开始前的最后一条消息', createdAt: '2026-08-08T03:00:00Z' }],
      nextCursor: null,
    })
  })
  await page.route(/\/api\/v1\/organizations\/[^/]+\/teams\/[^/]+\/conversations\/[^/]+\/work-item-links/, async route => {
    if (revoked(teamIdOf(route))) return route.fulfill(forbiddenBody())
    return json(route, { items: [], nextCursor: null })
  })
  await page.route(/\/api\/v1\/organizations\/[^/]+\/teams\/[^/]+\/conversations\/[^/]+\/tasks(\?.*)?$/, async route => {
    if (revoked(teamIdOf(route))) return route.fulfill(forbiddenBody())
    return json(route, { items: [taskAssociation()], nextCursor: null })
  })
  await page.route(/\/api\/v1\/organizations\/[^/]+\/teams\/[^/]+\/conversations\/[^/]+\/events(\?.*)?$/, route => {
    // The conversation durable-events stream stays connected; nothing frontend-observable is
    // defined for its phase (see S2 note), so the held connection is simply never answered.
    state.held.set('conversation', route)
    return undefined
  })
  await page.route(/\/api\/v1\/organizations\/[^/]+\/teams\/[^/]+\/tasks\/[^/]+\/events(\?.*)?$/, async route => {
    if (revoked(teamIdOf(route))) return route.fulfill(forbiddenBody())
    state.held.set(`task:${q01.task}`, route)
    return undefined
  })
  // The a07 catch-all answers bare `[]` for the work-item collection, which the gateway's
  // `array()` rejects — serve the documented page shape so the create-draft flow stays real.
  await page.route(/\/api\/v1\/organizations\/[^/]+\/teams\/[^/]+\/work-projects\/[^/]+\/work-items(\?.*)?$/, async route => {
    if (route.request().method() !== 'GET') return undefined
    if (revoked(teamIdOf(route))) return route.fulfill(forbiddenBody())
    return json(route, { items: [], nextCursor: null })
  })
}

function teamIdOf(route: Route): string {
  const match = /\/teams\/([^/?]+)/.exec(new URL(route.request().url()).pathname)
  return decodeURIComponent(match?.[1] ?? '')
}

function forbiddenBody(): { status: 403, contentType: 'application/json', body: string } {
  return {
    status: 403,
    contentType: 'application/json',
    body: JSON.stringify({ code: 'policy_denied', message: 'membership is not active', correlationId: 'correlation-q01', retryable: false, currentVersion: null, details: {} }),
  }
}

/** The a07 sessionFor, with the Q01 deltas: Team capabilities derive from the live grants and
 *  the Team list is the session projection the server would recompute. */
function sessionFor(state: RevocationWorld, viewer: Viewer) {
  const principalId = viewerPrincipal(viewer)
  const teams = state.world.teams
    .map(team => ({ team, row: activeRow(state.world, team.id, principalId) }))
    .filter(entry => entry.row !== null)
    .map(entry => ({
      teamId: entry.team.id,
      name: entry.team.name,
      memberId: entry.row!.id,
      permissions: viewer === 'owner'
        ? Object.values(permissions)
        : [...memberBase, ...(entry.row!.roles.includes('TEAM_LEAD') ? [permissions.teamMembersManage] : [])],
    }))
  return {
    authenticated: true,
    registrationMode: 'OPEN',
    csrf: { headerName: 'X-XSRF-TOKEN', parameterName: '_csrf', token: `csrf-a07-${viewer}` },
    account: {
      accountId: viewer === 'owner' ? '00000000-0000-4000-8000-000000000011' : '00000000-0000-4000-8000-000000000012',
      username: viewer === 'owner' ? 'a07-owner' : 'a07-member',
      displayName: viewer === 'owner' ? '张凯旋' : '林晨',
      platformRole: 'USER', securityVersion: 1, version: 1,
    },
    principal: { principalId, organizationId: ids.organization },
    // Account-wide capabilities stay empty: every Team capability must come from the selected
    // Team — which is what makes the role-downgrade channel observable per Team.
    teams,
    permissions: [],
  }
}

function conversationRow() {
  return {
    id: q01.conversation, organizationId: ids.organization, teamId: ids.teamPlatform, workspaceId: ids.workspace,
    ownerMemberId: ids.memberOwner, ownerPrincipalId: ids.principalOwner, personalAgentPrincipalId: null,
    title: '在途任务的对话', visibility: 'TEAM', status: 'ACTIVE', lastMessageSequence: 1, version: 1,
    createdAt: '2026-08-08T01:00:00Z', updatedAt: '2026-08-08T03:00:00Z',
  }
}

function participantRow(principalId: string, role: 'OWNER' | 'MEMBER', displayName: string) {
  return {
    id: `00000000-0000-4000-8000-0000000009${role === 'OWNER' ? '21' : '22'}`,
    conversationId: q01.conversation, principalId, teamMemberId: role === 'OWNER' ? ids.memberOwner : ids.memberLin,
    displayName, principalType: 'USER', ownerPrincipalId: null, ownerDisplayName: null,
    role, status: 'ACTIVE', joinedByPrincipalId: ids.principalOwner,
    joinedAt: '2026-08-08T01:00:00Z', leftAt: null, version: 0,
  }
}

function taskAssociation() {
  return {
    origin: 'CONVERSATION_SOURCE',
    associatedAt: '2026-08-08T03:30:00Z',
    task: {
      id: q01.task, workspaceId: ids.workspace, projectId: ids.projectPlatform, workItemId: q01.workItem,
      objective: '撤权时仍在执行的在途任务', acceptanceCriteria: ['执行记录可回放'], status: 'WAITING',
      currentExecutionId: null, currentAttempt: 1, currentExecutionStatus: 'WAITING', currentWaitingReason: 'WAITING_APPROVAL',
      previousExecutionId: null, previousAttempt: 0, executionVersion: 0, ownerPrincipalId: ids.principalMember,
      version: 0, createdAt: '2026-08-08T03:30:00Z', updatedAt: '2026-08-08T03:40:00Z',
      href: `/work?team=${ids.teamPlatform}&project=${ids.projectPlatform}&workItem=${q01.workItem}&task=${q01.task}`,
    },
  }
}

/** Count f05 user-state keys by kind segment and a substring of the decoded key. The kind is
 *  key segment 7 (same shape the m9b-f05 specs assert against); the substring keeps the count
 *  scoped to one object or one project. */
async function scopedKeyCount(page: Page, kind: string, substring: string): Promise<number> {
  return page.evaluate(([expectedKind, expectedSubstring]) =>
    Object.keys(localStorage).filter(key =>
      key.split(':')[6] === expectedKind && decodeURIComponent(key).includes(expectedSubstring)).length, [kind, substring])
}

function json(route: Route, body: unknown, status = 200) {
  return route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) })
}
