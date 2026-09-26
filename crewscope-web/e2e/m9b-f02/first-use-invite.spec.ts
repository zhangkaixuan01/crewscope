import { expect, test, type Page } from '@playwright/test'

/**
 * M9b-F02 首用邀请（R29/L12）：接受的提交坐标跨刷新存活（sessionStorage、绑定 principal、
 * 10 分钟窗口、进入后即清）；换账号原地退出、邀请 proof 留内存；会话与提交的成员坐标
 * 不一致时保持 pending，不乱跳。
 */
const ids = {
  organization: '00000000-0000-4000-8000-000000000001',
  principal: '00000000-0000-4000-8000-000000000101',
  memberInvited: '00000000-0000-4000-8000-000000000301',
  memberOther: '00000000-0000-4000-8000-000000000302',
  teamInvited: '00000000-0000-4000-8000-000000000201',
  teamOther: '00000000-0000-4000-8000-000000000202',
  invitation: '00000000-0000-4000-8000-000000000401',
  workspace: '00000000-0000-4000-8000-000000000501',
}
const token = 'F'.repeat(43)

test('restores the committed accept onto the joined Team after a session loss', async ({ page }) => {
  const world = await installInviteWorld(page, { acceptBreaksSession: true })
  await page.goto(`/invite#token=${token}`)

  await page.getByRole('button', { name: '接受邀请并加入团队' }).click()
  expect(world.accepts).toHaveLength(1)
  // The accept committed, but the refreshed session has not shown the Team yet (R29).
  await expect(page.getByRole('heading', { name: '接受已提交，会话待同步' })).toBeFocused()

  // A reload loses the in-memory acceptance; the sessionStorage coordinates — bound to the
  // same principal — restore the entry onto the joined Team, not onto the session's teams[0].
  await page.goto('/invite')
  await expect(page).toHaveURL(new RegExp(`/conversation\\?team=${ids.teamInvited}$`))

  // The record is consumed on entry — a later visit no longer auto-navigates.
  await page.goto('/invite')
  await expect(page.getByRole('heading', { name: '这个邀请无法使用' })).toBeFocused()
  const stored = await page.evaluate(() => sessionStorage.getItem('crewscope:invitation-acceptance:v1'))
  expect(stored).toBeNull()
})

test('switches accounts in place while the invitation proof stays in memory', async ({ page }) => {
  const world = await installInviteWorld(page)
  await page.goto(`/invite#token=${token}`)

  await expect(page.getByText('当前账号：')).toContainText('张凯旋（f02-owner）')
  await page.getByRole('button', { name: '换账号接受' }).click()
  // In-place sign-out: the page stays, only the account path flips to anonymous.
  await expect(page).toHaveURL('/invite')
  await expect(page.getByRole('button', { name: '使用已有账号登录并加入' })).toBeVisible()
  expect(world.logouts).toEqual(['csrf-invite-f02'])

  await page.getByRole('button', { name: '使用已有账号登录并加入' }).click()
  await expect(page).toHaveURL(/\/login\?returnTo=\/invite/)
  await page.getByRole('textbox', { name: '用户名或邮箱' }).fill('alice@example.com')
  await page.locator('input[name="password"]').fill('correct-password-value')
  await page.getByRole('button', { name: '进入 CrewScope' }).click()

  // Back on /invite the proof survived the switch, so the matching account can accept.
  await expect(page).toHaveURL('/invite')
  await page.getByRole('button', { name: '接受邀请并加入团队' }).click()
  await expect(page).toHaveURL(new RegExp(`/conversation\\?team=${ids.teamInvited}$`))
  expect(world.accepts).toHaveLength(1)
})

test('keeps the accept pending when the session disagrees on the membership coordinate', async ({ page }) => {
  await installInviteWorld(page, { joined: true, joinedMemberId: ids.memberOther })
  await page.addInitScript(entry => {
    sessionStorage.setItem('crewscope:invitation-acceptance:v1', JSON.stringify(entry))
  }, {
    organizationId: ids.organization,
    teamId: ids.teamInvited,
    memberId: ids.memberInvited,
    principalId: ids.principal,
    acceptedAt: Date.now(),
  })

  await page.goto('/invite')

  await expect(page.getByRole('heading', { name: '接受已提交，会话待同步' })).toBeFocused()
  await expect(page).toHaveURL('/invite')
})

async function installInviteWorld(
  page: Page,
  options: { joined?: boolean, joinedMemberId?: string, acceptBreaksSession?: boolean } = {},
) {
  let joined = options.joined ?? false
  const joinedMemberId = options.joinedMemberId ?? ids.memberInvited
  let authenticated = true
  // R29 fixture switch: the first session read after the accept comes back anonymous.
  let sessionBroken = Boolean(options.acceptBreaksSession)
  const accepts: Array<{ token: string, csrf?: string }> = []
  const logouts: string[] = []
  await page.route('**/api/v1/**', async route => {
    const request = route.request()
    const path = new URL(request.url()).pathname
    if (path === '/api/v1/auth/session') {
      if (sessionBroken && joined) {
        sessionBroken = false
        return route.fulfill(json({ ...session(), authenticated: false, account: null, principal: null, teams: [], permissions: [] }))
      }
      return route.fulfill(json(session()))
    }
    if (path === '/api/v1/auth/logout') {
      logouts.push(request.headers()['x-xsrf-token'] ?? '')
      authenticated = false
      return route.fulfill({ status: 204 })
    }
    if (path === '/api/v1/auth/login') {
      authenticated = true
      return route.fulfill(json({ authenticated: true, accountId: 'account-1', displayName: 'Alice' }))
    }
    if (path === '/api/v1/invitations/preview') {
      return route.fulfill(json({
        state: 'AVAILABLE', invitationId: ids.invitation, teamName: 'Platform Engineering',
        targetRole: 'MEMBER', expiresAt: '2026-09-08T00:00:00Z', targetRestricted: false,
      }))
    }
    if (path === '/api/v1/invitations/accept') {
      accepts.push({ token: (request.postDataJSON() as { token: string }).token, csrf: request.headers()['x-xsrf-token'] })
      joined = true
      return route.fulfill(json({
        command: { commandId: 'command-accept', domainEventId: 'event-accept', committedVersion: 1, correlationId: 'correlation-accept' },
        acceptance: {
          teamId: ids.teamInvited, memberId: ids.memberInvited, invitationId: ids.invitation,
          membershipDisposition: 'CREATED', roleGrantCreated: true,
        },
        replayed: false,
      }, 202))
    }
    if (path === `/api/v1/organizations/${ids.organization}/teams`) {
      return route.fulfill(json([teamRow(ids.teamOther, 'Existing Team'), teamRow(ids.teamInvited, 'Platform Engineering')]))
    }
    if (path.endsWith('/work-projects')) return route.fulfill(json({ items: [], nextCursor: null }))
    if (path.endsWith('/members')) return route.fulfill(json([]))
    if (path.endsWith('/conversations')) return route.fulfill(json({ items: [], nextCursor: null }))
    return route.fulfill(json([]))
  })
  return {
    accepts, logouts,
    // The joined membership only appears in the session after the accept commits.
  }

  function session() {
    // An anonymous session carries no account, principal or teams — the identity boundary
    // rejects a half-authenticated shape.
    return {
      authenticated,
      registrationMode: 'OPEN' as const,
      csrf: { headerName: 'X-XSRF-TOKEN' as const, parameterName: '_csrf' as const, token: 'csrf-invite-f02' },
      account: authenticated ? {
        accountId: 'account-1', username: 'f02-owner', displayName: '张凯旋',
        platformRole: 'USER' as const, securityVersion: 1, version: 1,
      } : null,
      principal: authenticated ? { principalId: ids.principal, organizationId: ids.organization } : null,
      teams: authenticated ? [
        { teamId: ids.teamOther, name: 'Existing Team', memberId: ids.memberOther, permissions: permissions() },
        ...(joined ? [{ teamId: ids.teamInvited, name: 'Platform Engineering', memberId: joinedMemberId, permissions: permissions() }] : []),
      ] : [],
      permissions: authenticated ? permissions() : [],
    }
  }
}

function teamRow(id: string, name: string) {
  return {
    id, organizationId: ids.organization, name, status: 'ACTIVE',
    initializationStatus: 'READY', ownerMemberId: ids.memberInvited,
    defaultWorkspaceId: ids.workspace, version: 0,
  }
}

function permissions() {
  return ['conversation:use', 'scope:read', 'team:members:read', 'work-projects:read', 'work:read']
}

function json(body: unknown, status = 200) {
  return { status, contentType: 'application/json', body: JSON.stringify(body) }
}
