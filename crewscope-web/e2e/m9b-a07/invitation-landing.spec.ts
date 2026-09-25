import { expect, test, type Page, type Request, type Route } from '@playwright/test'
import { permissions } from '../../src/app/auth'

/**
 * A07 invitation landing: the 202 acceptance coordinates — not session diffing or Team-name
 * guessing — decide where an accepted invitation lands. The fixture names both Teams alike so
 * any fallback heuristics would land on the wrong one.
 */
const ids = {
  organization: '00000000-0000-4000-8000-000000000001',
  principalJoiner: '00000000-0000-4000-8000-000000000103',
  teamJoined: '00000000-0000-4000-8000-000000000201',
  teamNamesake: '00000000-0000-4000-8000-000000000202',
  memberNamesake: '00000000-0000-4000-8000-000000000304',
  memberJoined: '00000000-0000-4000-8000-000000000305',
  projectJoined: '00000000-0000-4000-8000-000000000401',
  projectNamesake: '00000000-0000-4000-8000-000000000402',
  workspace: '00000000-0000-4000-8000-000000000501',
  invitation: '00000000-0000-4000-8000-000000000441',
}
const token = 'F'.repeat(43)

test('lands on the committed Team even when another Team shares the invitation name', async ({ page }) => {
  const fixture = await installLandingApi(page, {})
  await page.goto(`/invite#token=${token}`)
  await page.getByRole('button', { name: '接受邀请并加入团队' }).click()

  await expect(page).toHaveURL(new RegExp(`/conversation\\?team=${ids.teamJoined}(&|$)`))
  expect(fixture.accepts).toHaveLength(1)
  expect(fixture.accepts[0]).toMatchObject({ token, csrf: 'csrf-a07-landing', idempotencyKey: expect.any(String) })
})

test('a replayed accept with stored coordinates lands identically without re-accepting', async ({ page }) => {
  const fixture = await installLandingApi(page, { replayed: true })
  await page.goto(`/invite#token=${token}`)
  await page.getByRole('button', { name: '接受邀请并加入团队' }).click()

  await expect(page).toHaveURL(new RegExp(`/conversation\\?team=${ids.teamJoined}(&|$)`))
  expect(fixture.accepts).toHaveLength(1)
})

test('keeps the acceptance pending while the session lags and resync never re-accepts', async ({ page }) => {
  const fixture = await installLandingApi(page, { neverExposeJoinedTeam: true })
  await page.goto(`/invite#token=${token}`)
  await page.getByRole('button', { name: '接受邀请并加入团队' }).click()

  await expect(page.getByRole('heading', { name: '接受已提交，会话待同步' })).toBeVisible()
  await expect(page).toHaveURL('/invite')
  expect(fixture.accepts).toHaveLength(1)

  fixture.exposeJoinedTeam = true
  await page.getByRole('button', { name: '重新同步会话' }).click()
  await expect(page).toHaveURL(new RegExp(`/conversation\\?team=${ids.teamJoined}(&|$)`))
  expect(fixture.accepts).toHaveLength(1)
})

interface LandingFixture {
  accepts: Array<{ token: string, csrf: string | undefined, idempotencyKey: string | undefined }>
  exposeJoinedTeam: boolean
}

async function installLandingApi(page: Page, options: { replayed?: boolean, neverExposeJoinedTeam?: boolean }): Promise<LandingFixture> {
  let accepted = false
  let exposeJoinedTeam = !options.neverExposeJoinedTeam
  const accepts: LandingFixture['accepts'] = []
  const granted = Object.values(permissions)
  const teams = () => [
    teamRow(ids.teamNamesake, ids.memberNamesake),
    ...(accepted && exposeJoinedTeam ? [teamRow(ids.teamJoined, ids.memberJoined)] : []),
  ]
  await page.route('**/api/v1/**', async route => {
    const request = route.request()
    const path = new URL(request.url()).pathname
    const method = request.method()
    if (method === 'GET' && path === '/api/v1/auth/session') {
      return json(route, {
        authenticated: true,
        registrationMode: 'OPEN',
        csrf: { headerName: 'X-XSRF-TOKEN', parameterName: '_csrf', token: 'csrf-a07-landing' },
        account: {
          accountId: '00000000-0000-4000-8000-000000000013', username: 'a07-joiner', displayName: '王新',
          platformRole: 'USER', securityVersion: 1, version: 1,
        },
        principal: { principalId: ids.principalJoiner, organizationId: ids.organization },
        teams: teams().map(team => ({ teamId: team.id, name: team.name, memberId: team.ownerMemberId, permissions: granted })),
        permissions: [],
      })
    }
    if (path === '/api/v1/invitations/preview') {
      return json(route, {
        state: 'AVAILABLE', invitationId: ids.invitation, teamName: 'Platform Engineering',
        targetRole: 'MEMBER', expiresAt: '2026-10-08T00:00:00Z', targetRestricted: false,
      })
    }
    if (path === '/api/v1/invitations/accept') {
      const body = request.postDataJSON() as { token: string }
      accepts.push({ token: body.token, csrf: header(request, 'x-xsrf-token'), idempotencyKey: header(request, 'idempotency-key') })
      accepted = true
      return route.fulfill({
        status: 202,
        contentType: 'application/json',
        headers: options.replayed ? { 'Idempotency-Replayed': 'true' } : {},
        body: JSON.stringify({
          command: { commandId: 'command-accept', domainEventId: 'event-accept', committedVersion: 1, correlationId: 'correlation-accept' },
          // A replay backfills only the durable coordinate; disposition facts stay null (controller).
          acceptance: options.replayed
            ? { teamId: ids.teamJoined, memberId: ids.memberJoined, invitationId: null, membershipDisposition: null, roleGrantCreated: null }
            : {
                teamId: ids.teamJoined, memberId: ids.memberJoined, invitationId: ids.invitation,
                membershipDisposition: 'CREATED', roleGrantCreated: true,
              },
        }),
      })
    }
    if (method === 'GET' && path === `/api/v1/organizations/${ids.organization}/teams`) return json(route, teams())
    if (method === 'GET' && /\/teams\/([^/]+)\/work-projects$/.test(path)) {
      const teamId = new URL(request.url()).pathname.split('/teams/')[1]!.split('/')[0]
      return json(route, {
        items: [{
          id: teamId === ids.teamJoined ? ids.projectJoined : ids.projectNamesake,
          organizationId: ids.organization, teamId, workspaceId: ids.workspace,
          key: teamId === ids.teamJoined ? 'CRW' : 'GRW', name: 'CrewScope',
          status: 'ACTIVE', version: 1, createdAt: '2026-08-27T07:00:00Z', createdByPrincipalId: ids.principalJoiner,
          updatedAt: '2026-08-27T07:00:00Z', updatedByPrincipalId: ids.principalJoiner,
        }],
        nextCursor: null,
      })
    }
    if (method === 'GET' && /\/teams\/[^/]+\/members$/.test(path)) {
      const teamId = new URL(request.url()).pathname.split('/teams/')[1]!.split('/')[0]
      return json(route, [memberRow(teamId === ids.teamJoined ? ids.memberJoined : ids.memberNamesake)])
    }
    if (method === 'GET' && /\/teams\/[^/]+\/conversations$/.test(path)) return json(route, { items: [], nextCursor: null })
    return json(route, [])
  })
  return {
    accepts,
    get exposeJoinedTeam() { return exposeJoinedTeam },
    set exposeJoinedTeam(value: boolean) { exposeJoinedTeam = value },
  }
}

function teamRow(id: string, ownerMemberId: string) {
  return {
    id, organizationId: ids.organization, name: 'Platform Engineering',
    status: 'ACTIVE', initializationStatus: 'READY', ownerMemberId,
    defaultWorkspaceId: ids.workspace, version: 1,
  }
}

function memberRow(id: string) {
  return {
    id, userPrincipalId: ids.principalJoiner, displayName: '王新', status: 'ACTIVE',
    joinMethod: 'INVITATION', joinedAt: '2026-09-25T00:00:00Z',
    roles: ['MEMBER'], grants: [], authorizationVersion: 1, version: 0,
  }
}

function header(request: Request, name: string): string | undefined {
  return request.headers()[name]
}

function json(route: Route, body: unknown, status = 200) {
  return route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) })
}
