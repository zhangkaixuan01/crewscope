import AxeBuilder from '@axe-core/playwright'
import { expect, test, type Page, type Request } from '@playwright/test'

const ids = {
  organization: '00000000-0000-4000-8000-000000000001',
  team: '00000000-0000-4000-8000-000000000201',
  principal: '00000000-0000-4000-8000-000000000101',
  member: '00000000-0000-4000-8000-000000000301',
  invitation: '00000000-0000-4000-8000-000000000401',
}
const token = 'D'.repeat(43)

test('creates, copies and lists privacy-bounded Team invitations', async ({ page, context }) => {
  const fixture = await installInvitationApi(page, { authenticated: true })
  await context.grantPermissions(['clipboard-read', 'clipboard-write'])
  await page.goto(`/team/members?team=${ids.team}`)

  await expect(page.getByRole('heading', { name: '团队邀请' })).toBeVisible()
  await expect(page.getByRole('list', { name: '团队邀请列表' })).toContainText('已接受')
  await expect(page.getByRole('list', { name: '团队邀请列表' })).toContainText('已过期')
  await page.getByRole('button', { name: '创建邀请' }).click()
  await expect(page.locator('input[name="invitationEmail"]')).toBeFocused()
  await page.locator('input[name="invitationEmail"]').fill('new@example.com')
  await page.locator('select[name="invitationRole"]').selectOption('TEAM_LEAD')
  await page.getByRole('button', { name: '创建邀请链接' }).click()

  const link = page.getByRole('textbox', { name: '一次性邀请链接' })
  await expect(link).toBeFocused()
  await expect(link).toHaveValue(new RegExp(`/invite#token=${token}$`))
  await page.getByRole('button', { name: '复制链接' }).click()
  await expect(page.getByRole('button', { name: '已复制' })).toBeVisible()
  expect(fixture.creates).toEqual([{
    body: { targetEmail: 'new@example.com', targetRole: 'TEAM_LEAD', expiresInMinutes: 10_080 },
    csrf: 'csrf-invitation-e2e', idempotencyKey: expect.any(String),
  }])
  expect(await page.evaluate(() => ({ local: localStorage.length, session: sessionStorage.length }))).toEqual({ local: 0, session: 0 })
  expect((await new AxeBuilder({ page }).analyze()).violations).toEqual([])
  expect(await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth)).toBe(0)
})

test('confirms and revokes a pending invitation with deterministic focus', async ({ page }) => {
  await installInvitationApi(page, { authenticated: true })
  await page.goto(`/team/members?team=${ids.team}`)
  const row = page.getByRole('listitem').filter({ hasText: 'pending@example.com' })
  await row.getByRole('button', { name: '撤销' }).click()
  const dialog = page.getByRole('dialog', { name: '撤销这个邀请？' })
  await expect(dialog.getByRole('button', { name: '取消' })).toBeFocused()
  await dialog.getByRole('button', { name: '确认撤销' }).click()

  await expect(row).toContainText('已撤销')
  await expect(page.getByRole('heading', { name: '团队邀请' })).toBeFocused()
})

test('keeps an invitation proof in memory across existing-account login and accepts it', async ({ page }) => {
  const fixture = await installInvitationApi(page)
  await page.goto(`/invite#token=${token}`)

  await expect(page).toHaveURL('/invite')
  await expect(page.getByRole('heading', { name: '加入团队，一起推进工作' })).toBeFocused()
  await page.getByRole('button', { name: '使用已有账号登录并加入' }).click()
  await expect(page).toHaveURL(/\/login\?returnTo=\/invite/)
  expect(page.url()).not.toContain(token)
  await page.getByRole('textbox', { name: '用户名或邮箱' }).fill('alice@example.com')
  await page.locator('input[name="password"]').fill('correct-password-value')
  await page.getByRole('button', { name: '进入 CrewScope' }).click()

  await expect(page).toHaveURL('/invite')
  await page.getByRole('button', { name: '接受邀请并加入团队' }).click()
  await expect(page).toHaveURL(new RegExp(`/conversation\\?team=${ids.team}`))
  expect(fixture.accepts).toEqual([{ token, csrf: 'csrf-invitation-e2e', idempotencyKey: expect.any(String) }])
})

test('hands a valid proof to atomic invitation registration without putting it back in the URL', async ({ page }) => {
  const fixture = await installInvitationApi(page, { registrationMode: 'INVITE_ONLY' })
  await page.goto(`/invite#token=${token}`)
  await page.getByRole('button', { name: '创建账号并加入团队' }).click()

  await expect(page).toHaveURL('/register')
  await expect(page.getByText('已安全载入团队邀请')).toBeVisible()
  await page.getByRole('textbox', { name: '用户名' }).fill('new-member')
  await page.getByRole('textbox', { name: '邮箱' }).fill('new@example.com')
  await page.getByRole('textbox', { name: '展示名' }).fill('New Member')
  await page.locator('input[name="password"]').fill('correct horse battery staple')
  await page.getByRole('button', { name: '创建账号并加入团队' }).click()

  await expect(page).toHaveURL(new RegExp('/conversation'))
  expect(fixture.registrations).toEqual([expect.objectContaining({ invitationToken: token, email: 'new@example.com' })])
  expect(page.url()).not.toContain(token)
})

test('distinguishes expiry while keeping unavailable invitation details private', async ({ page }) => {
  await installInvitationApi(page, { previewState: 'EXPIRED' })
  await page.goto(`/invite#token=${token}`)
  await expect(page).toHaveURL('/invite')
  await expect(page.getByRole('heading', { name: '这个邀请已经过期' })).toBeFocused()
  await expect(page.locator('body')).not.toContainText(/target@example|principal|private/i)
})

test('keeps an email-mismatch failure on the invitation page with non-identifying copy', async ({ page }) => {
  await installInvitationApi(page, { authenticated: true, rejectAccept: true })
  await page.goto(`/invite#token=${token}`)
  await page.getByRole('button', { name: '接受邀请并加入团队' }).click()

  const alert = page.getByRole('alert')
  await expect(alert).toBeFocused()
  await expect(alert).toContainText('邀请可能已失效、已经使用，或与当前账号不匹配')
  await expect(page.locator('body')).not.toContainText('private target email mismatch')
  await expect(page).toHaveURL('/invite')
})

test('does not expose invitation management to a Team member without MEMBER_MANAGE', async ({ page }) => {
  const fixture = await installInvitationApi(page, { authenticated: true, canManage: false })
  await page.goto(`/team/members?team=${ids.team}`)

  await expect(page.getByRole('table', { name: '团队成员列表' })).toBeVisible()
  await expect(page.getByRole('heading', { name: '团队邀请' })).toHaveCount(0)
  expect(fixture.managementReads).toBe(0)
})

async function installInvitationApi(page: Page, options: {
  authenticated?: boolean
  canManage?: boolean
  registrationMode?: 'OPEN' | 'INVITE_ONLY' | 'DISABLED'
  previewState?: 'AVAILABLE' | 'EXPIRED' | 'UNAVAILABLE'
  rejectAccept?: boolean
} = {}) {
  let authenticated = options.authenticated ?? false
  let joined = authenticated
  const canManage = options.canManage ?? true
  const creates: Array<{ body: Record<string, unknown>, csrf?: string, idempotencyKey?: string }> = []
  const accepts: Array<{ token: string, csrf?: string, idempotencyKey?: string }> = []
  const registrations: Record<string, unknown>[] = []
  let managementReads = 0
  let invitations: Record<string, unknown>[] = invitationList()
  await page.route('**/api/v1/**', async route => {
    const request = route.request()
    const path = new URL(request.url()).pathname
    if (path === '/api/v1/auth/session') return route.fulfill(json(session(authenticated, joined, canManage, options.registrationMode)))
    if (path === '/api/v1/auth/login') {
      authenticated = true
      return route.fulfill(json({ authenticated: true, accountId: 'account-1', displayName: 'Alice' }))
    }
    if (path === '/api/v1/auth/register') {
      const body = request.postDataJSON() as Record<string, unknown>
      registrations.push(body)
      authenticated = true
      joined = true
      return route.fulfill(json({
        accountId: 'account-1', principalId: ids.principal, organizationId: ids.organization,
        teamId: ids.team, memberId: ids.member, onboardingRequired: false,
        commandId: 'command-register', domainEventId: 'event-register', committedVersion: 1,
        correlationId: 'correlation-register', replayed: false,
      }, 201))
    }
    if (path === '/api/v1/invitations/preview') {
      const state = options.previewState ?? 'AVAILABLE'
      return route.fulfill(json(state === 'AVAILABLE' ? {
        state, invitationId: ids.invitation, teamName: 'Platform Engineering', targetRole: 'MEMBER',
        expiresAt: '2026-09-08T00:00:00Z', targetRestricted: true,
      } : { state, invitationId: null, teamName: null, targetRole: null, expiresAt: null, targetRestricted: false }))
    }
    if (path === '/api/v1/invitations/accept') {
      const body = request.postDataJSON() as { token: string }
      if (options.rejectAccept) return route.fulfill(json(error('invitation_invalid', 'private target email mismatch'), 422))
      accepts.push({ token: body.token, csrf: header(request, 'x-xsrf-token'), idempotencyKey: header(request, 'idempotency-key') })
      joined = true
      return route.fulfill(json(receipt('accept'), 202))
    }
    if (path === `/api/v1/organizations/${ids.organization}/teams/${ids.team}/invitations`) {
      if (request.method() === 'GET') {
        managementReads += 1
        return route.fulfill(json({ items: invitations, nextCursor: null }))
      }
      const body = request.postDataJSON() as Record<string, unknown>
      creates.push({ body, csrf: header(request, 'x-xsrf-token'), idempotencyKey: header(request, 'idempotency-key') })
      const issued = invitation({ id: '00000000-0000-4000-8000-000000000499', targetEmail: body.targetEmail as string, targetRole: body.targetRole as string })
      invitations = [issued, ...invitations]
      return route.fulfill(json({ command: receipt('create'), invitation: issued, token }, 202))
    }
    if (path.endsWith(`/invitations/${ids.invitation}/revoke`)) {
      invitations = invitations.map(item => item.id === ids.invitation ? { ...item, status: 'REVOKED', resolvedAt: '2026-08-29T12:00:00Z' } : item)
      return route.fulfill(json(receipt('revoke'), 202))
    }
    if (path === `/api/v1/organizations/${ids.organization}/teams`) return route.fulfill(json([team()]))
    if (path.endsWith('/work-projects')) return route.fulfill(json({ items: [], nextCursor: null }))
    if (path.endsWith('/members')) return route.fulfill(json([{
      id: ids.member, userPrincipalId: ids.principal, status: 'ACTIVE', joinMethod: 'CREATOR',
      joinedAt: '2026-08-01T00:00:00Z', version: 0,
    }]))
    if (path.endsWith('/conversations')) return route.fulfill(json({ items: [], nextCursor: null }))
    return route.fulfill(json([]))
  })
  return {
    creates, accepts, registrations,
    get managementReads() { return managementReads },
  }
}

function session(authenticated: boolean, joined: boolean, canManage: boolean, registrationMode = 'OPEN') {
  const permissions = [
    'conversation:use', 'scope:read', 'team:members:read', 'work-projects:read', 'work:read',
    ...(canManage ? ['team:members:manage'] : []),
  ]
  return {
    authenticated, registrationMode,
    csrf: { headerName: 'X-XSRF-TOKEN', parameterName: '_csrf', token: 'csrf-invitation-e2e' },
    account: authenticated ? { accountId: 'account-1', username: 'alice', displayName: 'Alice', platformRole: 'USER', securityVersion: 1, version: 1 } : null,
    principal: authenticated ? { principalId: ids.principal, organizationId: ids.organization } : null,
    teams: authenticated && joined ? [{ teamId: ids.team, name: 'Platform Engineering', memberId: ids.member, permissions }] : [],
    permissions: authenticated ? permissions : [],
  }
}

function team() {
  return { id: ids.team, organizationId: ids.organization, name: 'Platform Engineering', status: 'ACTIVE', initializationStatus: 'READY', ownerMemberId: ids.member, defaultWorkspaceId: 'workspace-1', version: 1 }
}

function invitation(overrides: Record<string, unknown> = {}) {
  return {
    id: ids.invitation, organizationId: ids.organization, teamId: ids.team, invitedByPrincipalId: ids.principal,
    targetEmail: 'pending@example.com', targetRole: 'MEMBER', status: 'PENDING', expiresAt: '2026-09-08T00:00:00Z',
    acceptedMemberId: null, resolvedAt: null, version: 0,
    createdAt: '2026-08-29T00:00:00Z', updatedAt: '2026-08-29T00:00:00Z', ...overrides,
  }
}

function invitationList() {
  return [
    invitation(),
    invitation({ id: '00000000-0000-4000-8000-000000000402', targetEmail: 'accepted@example.com', status: 'ACCEPTED', acceptedMemberId: 'member-accepted', resolvedAt: '2026-08-28T00:00:00Z' }),
    invitation({ id: '00000000-0000-4000-8000-000000000403', targetEmail: null, status: 'EXPIRED', resolvedAt: '2026-08-27T00:00:00Z' }),
  ]
}

function receipt(prefix: string) {
  return { commandId: `command-${prefix}`, domainEventId: `event-${prefix}`, committedVersion: 1, correlationId: `correlation-${prefix}` }
}

function header(request: Request, name: string): string | undefined {
  return request.headers()[name]
}

function error(code: string, message: string) {
  return { code, message, correlationId: 'correlation-invitation', retryable: false, currentVersion: null, details: {} }
}

function json(body: unknown, status = 200) {
  return { status, contentType: 'application/json', body: JSON.stringify(body) }
}
