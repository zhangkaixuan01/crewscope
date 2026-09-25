import type { Page, Request, Route } from '@playwright/test'
import { permissions } from '../../src/app/auth'

/**
 * M9b-A07 browser fixtures: every API is answered by interception against the Vite dev server.
 * One `A07World` can be shared by several browser contexts, so a lifecycle command issued by
 * the owner's page immediately changes what the affected member's own session may see —
 * the same revocation semantics the server enforces across its seven channels.
 */
export const ids = {
  organization: '00000000-0000-4000-8000-000000000001',
  principalOwner: '00000000-0000-4000-8000-000000000101',
  principalMember: '00000000-0000-4000-8000-000000000102',
  teamPlatform: '00000000-0000-4000-8000-000000000201',
  teamGrowth: '00000000-0000-4000-8000-000000000202',
  memberOwner: '00000000-0000-4000-8000-000000000301',
  memberLin: '00000000-0000-4000-8000-000000000302',
  memberLinInGrowth: '00000000-0000-4000-8000-000000000303',
  projectPlatform: '00000000-0000-4000-8000-000000000401',
  projectGrowth: '00000000-0000-4000-8000-000000000402',
  workspace: '00000000-0000-4000-8000-000000000501',
}

const ownerPermissions = Object.values(permissions)
const memberPermissions = ['conversation:use', 'scope:read', 'team:members:read', 'work-projects:read', 'work:read']

export type Viewer = 'owner' | 'member'

export interface MemberFixtureRow {
  id: string
  userPrincipalId: string
  displayName: string
  status: 'ACTIVE' | 'SUSPENDED' | 'REMOVED' | 'LEFT'
  joinMethod: string
  joinedAt: string
  roles: string[]
  grants: Array<{ id: string, roleKey: string }>
  authorizationVersion: number
  version: number
}

export interface ResponsibilityFixtureRow {
  assignmentId: string
  workItemId: string
  role: 'OWNER' | 'EXECUTOR' | 'REVIEWER'
  version: number
}

interface HandoverJobFixture {
  jobId: string
  status: 'PENDING' | 'RUNNING' | 'COMPLETED' | 'CANCELLED'
  role: string
  sourceMemberId: string
  targetPrincipalId: string
  sourceAuthorizationVersion: number
  version: number
  items: Array<{
    itemId: string
    assignmentId: string
    workItemId: string
    state: 'PENDING' | 'DONE' | 'CONFLICT' | 'DENIED'
    resultAssignmentId: string | null
    errorCode: string | null
  }>
}

export interface LifecycleCommandLog {
  action: string
  teamId: string
  memberId: string | null
  viewer: Viewer
  ifMatch: string | undefined
  idempotencyKey: string | undefined
  csrf: string | undefined
  roleKey?: string
  grantId?: string
  targetPrincipalId?: string
  error?: string
}

export interface A07World {
  teams: Array<{
    id: string
    organizationId: string
    name: string
    status: string
    initializationStatus: string
    ownerMemberId: string
    defaultWorkspaceId: string
    version: number
  }>
  members: Record<string, MemberFixtureRow[]>
  /** Active assignments per member id; the preview and the created job both read this. */
  responsibilities: Record<string, ResponsibilityFixtureRow[]>
  /** Work items whose handover item ends as CONFLICT instead of DONE when the job is processed. */
  conflictWorkItemIds: Set<string>
  commands: LifecycleCommandLog[]
  jobs: Record<string, HandoverJobFixture>
}

export function createWorld(): A07World {
  return {
    teams: [
      {
        id: ids.teamPlatform, organizationId: ids.organization, name: 'Platform Engineering',
        status: 'ACTIVE', initializationStatus: 'READY', ownerMemberId: ids.memberOwner,
        defaultWorkspaceId: ids.workspace, version: 1,
      },
      {
        id: ids.teamGrowth, organizationId: ids.organization, name: 'Growth Engineering',
        status: 'ACTIVE', initializationStatus: 'READY', ownerMemberId: ids.memberLinInGrowth,
        defaultWorkspaceId: ids.workspace, version: 1,
      },
    ],
    members: {
      [ids.teamPlatform]: [
        memberRow(ids.memberOwner, ids.principalOwner, '张凯旋', 'CREATED_WITH_TEAM'),
        memberRow(ids.memberLin, ids.principalMember, '林晨', 'ADDED_BY_MEMBER'),
      ],
      [ids.teamGrowth]: [memberRow(ids.memberLinInGrowth, ids.principalMember, '林晨', 'CREATED_WITH_TEAM')],
    },
    responsibilities: {},
    conflictWorkItemIds: new Set<string>(),
    commands: [],
    jobs: {},
  }
}

export function viewerPrincipal(viewer: Viewer): string {
  return viewer === 'owner' ? ids.principalOwner : ids.principalMember
}

export function activeRow(world: A07World, teamId: string, principalId: string): MemberFixtureRow | null {
  const row = (world.members[teamId] ?? []).find(candidate => candidate.userPrincipalId === principalId)
  return row && row.status === 'ACTIVE' ? row : null
}

export async function installMembersApi(page: Page, viewer: Viewer, world: A07World = createWorld()): Promise<A07World> {
  // Spring's CookieServerCsrfTokenRepository pair: the api client reads X-XSRF-TOKEN from this cookie.
  await page.context().addCookies([{ name: 'XSRF-TOKEN', value: `csrf-a07-${viewer}`, url: page.url().startsWith('http') ? new URL(page.url()).origin : 'http://127.0.0.1:4173' }])
  await page.route('**/api/v1/**', async route => {
    const request = route.request()
    const url = new URL(request.url())
    const path = url.pathname
    const method = request.method()
    if (method === 'GET' && path === '/api/v1/auth/session') return json(route, sessionFor(world, viewer))
    const teamMatch = /\/organizations\/[^/]+\/teams\/([^/]+)/.exec(path)
    const teamId = teamMatch ? decodeURIComponent(teamMatch[1]!) : null
    if (teamId && method === 'GET' && !activeRow(world, teamId, viewerPrincipal(viewer))) {
      return json(route, envelope('policy_denied', 'fixture membership is not active'), 403)
    }
    if (method === 'GET' && path === `/api/v1/organizations/${ids.organization}/teams`) {
      return json(route, world.teams.filter(team => activeRow(world, team.id, viewerPrincipal(viewer))))
    }
    const projectsMatch = /\/teams\/([^/]+)\/work-projects$/.exec(path)
    if (projectsMatch && method === 'GET') return json(route, { items: [projectRow(decodeURIComponent(projectsMatch[1]!))], nextCursor: null })
    if (method === 'GET' && /\/teams\/[^/]+\/members$/.test(path)) return json(route, world.members[teamId!] ?? [])
    if (method === 'GET' && /\/teams\/[^/]+\/invitations$/.test(path)) return json(route, { items: [], nextCursor: null })
    if (method === 'GET' && /\/teams\/[^/]+\/conversations$/.test(path)) return json(route, { items: [], nextCursor: null })

    const leaveMatch = /\/teams\/([^/]+)\/members\/me\/leave$/.exec(path)
    if (leaveMatch && method === 'POST') {
      const self = (world.members[teamId!] ?? []).find(row => row.userPrincipalId === viewerPrincipal(viewer))
      if (!self) return json(route, envelope('not_found', 'fixture member is missing'), 404)
      return lifecycleCommand(route, world, viewer, teamId!, self.id, 'leave')
    }
    const transitionMatch = /\/teams\/([^/]+)\/members\/([^/]+)\/(suspend|activate|remove)$/.exec(path)
    if (transitionMatch && method === 'POST') {
      return lifecycleCommand(route, world, viewer, teamId!, decodeURIComponent(transitionMatch[2]!), transitionMatch[3]! as 'suspend' | 'activate' | 'remove')
    }
    const revokeMatch = /\/teams\/([^/]+)\/members\/([^/]+)\/roles\/([^/]+)\/revoke$/.exec(path)
    if (revokeMatch && method === 'POST') {
      return lifecycleCommand(route, world, viewer, teamId!, decodeURIComponent(revokeMatch[2]!), 'revokeRole', decodeURIComponent(revokeMatch[3]!))
    }
    const grantMatch = /\/teams\/([^/]+)\/members\/([^/]+)\/roles$/.exec(path)
    if (grantMatch && method === 'POST') {
      return lifecycleCommand(route, world, viewer, teamId!, decodeURIComponent(grantMatch[2]!), 'grantRole')
    }
    const transferMatch = /\/teams\/([^/]+)\/transfer-ownership$/.exec(path)
    if (transferMatch && method === 'POST') {
      const body = request.postDataJSON() as { targetMemberId: string }
      return lifecycleCommand(route, world, viewer, teamId!, body.targetMemberId, 'transferOwnership')
    }

    const responsibilitiesMatch = /\/teams\/([^/]+)\/members\/([^/]+)\/responsibilities$/.exec(path)
    if (responsibilitiesMatch && method === 'GET') {
      const memberId = decodeURIComponent(responsibilitiesMatch[2]!)
      const role = url.searchParams.get('role')
      const items = (world.responsibilities[memberId] ?? []).filter(item => !role || item.role === role)
      return json(route, items.map(({ assignmentId, workItemId, role: itemRole, version }) => ({ assignmentId, workItemId, role: itemRole, version })))
    }
    const handoverCreateMatch = /\/teams\/([^/]+)\/responsibility-handovers$/.exec(path)
    if (handoverCreateMatch && method === 'POST') {
      const body = request.postDataJSON() as { sourceMemberId: string, targetPrincipalId: string, role: string }
      const log: LifecycleCommandLog = {
        action: 'createHandover', teamId: teamId!, memberId: body.sourceMemberId, viewer,
        ifMatch: undefined, idempotencyKey: header(request, 'idempotency-key'), csrf: header(request, 'x-xsrf-token'),
        targetPrincipalId: body.targetPrincipalId, roleKey: body.role,
      }
      world.commands.push(log)
      const source = (world.members[teamId!] ?? []).find(row => row.id === body.sourceMemberId)
      const job: HandoverJobFixture = {
        jobId: crypto.randomUUID(),
        status: 'PENDING',
        role: body.role,
        sourceMemberId: body.sourceMemberId,
        targetPrincipalId: body.targetPrincipalId,
        sourceAuthorizationVersion: source?.authorizationVersion ?? 1,
        version: 0,
        items: (world.responsibilities[body.sourceMemberId] ?? [])
          .filter(item => item.role === body.role)
          .map(item => ({
            itemId: crypto.randomUUID(), assignmentId: item.assignmentId, workItemId: item.workItemId,
            state: 'PENDING' as const, resultAssignmentId: null, errorCode: null,
          })),
      }
      world.jobs[job.jobId] = job
      return json(route, { command: receipt('handover-create'), job }, 202)
    }
    const handoverActionMatch = /\/teams\/([^/]+)\/responsibility-handovers\/([^/]+)\/(process|cancel)$/.exec(path)
    if (handoverActionMatch && method === 'POST') {
      const job = world.jobs[decodeURIComponent(handoverActionMatch[2]!)]
      if (!job) return json(route, envelope('not_found', 'fixture job is missing'), 404)
      world.commands.push({
        action: handoverActionMatch[3] === 'process' ? 'processHandover' : 'cancelHandover',
        teamId: teamId!, memberId: job.sourceMemberId, viewer,
        ifMatch: undefined, idempotencyKey: header(request, 'idempotency-key'), csrf: header(request, 'x-xsrf-token'),
      })
      if (handoverActionMatch[3] === 'process') {
        job.items = job.items.map(item => item.state !== 'PENDING' ? item : world.conflictWorkItemIds.has(item.workItemId)
          ? { ...item, state: 'CONFLICT' }
          : { ...item, state: 'DONE', resultAssignmentId: `${item.assignmentId}-next` })
        job.status = 'COMPLETED'
      } else {
        job.status = 'CANCELLED'
      }
      job.version += 1
      return json(route, job)
    }
    return json(route, [])
  })
  return world
}

function lifecycleCommand(
  route: Route,
  world: A07World,
  viewer: Viewer,
  teamId: string,
  memberId: string,
  action: 'suspend' | 'activate' | 'remove' | 'leave' | 'grantRole' | 'revokeRole' | 'transferOwnership',
  grantId?: string,
) {
  const request = route.request()
  const log: LifecycleCommandLog = {
    action, teamId, memberId, viewer,
    ifMatch: header(request, 'if-match'),
    idempotencyKey: header(request, 'idempotency-key'),
    csrf: header(request, 'x-xsrf-token'),
    grantId,
  }
  world.commands.push(log)
  const list = world.members[teamId] ?? []
  const index = list.findIndex(candidate => candidate.id === memberId)
  if (index < 0) {
    log.error = 'not_found'
    return json(route, envelope('not_found', 'fixture member is missing'), 404)
  }
  const row = list[index]!
  if (log.ifMatch !== `"${row.version}"`) {
    log.error = 'optimistic_lock_conflict'
    return json(route, envelope('optimistic_lock_conflict', 'fixture version mismatch', String(row.version)), 409)
  }
  const team = world.teams.find(candidate => candidate.id === teamId)!
  if ((action === 'suspend' || action === 'remove' || action === 'leave') && team.ownerMemberId === memberId) {
    log.error = 'last_owner_protection'
    return json(route, envelope('last_owner_protection', 'fixture refuses to drop the last Owner'), 409)
  }
  const bump = (target: MemberFixtureRow, patch: Partial<MemberFixtureRow>): MemberFixtureRow => ({
    ...target, ...patch, version: target.version + 1, authorizationVersion: target.authorizationVersion + 1,
  })
  // The server revokes every active grant at suspension/removal/leave, and activation restores
  // only the default MEMBER role (TeamMemberLifecycleApplicationService: revokeActiveGrants +
  // ensureDefaultMemberRole) — custom roles are never resurrected.
  if (action === 'suspend') {
    list[index] = bump(row, { status: 'SUSPENDED', roles: [], grants: [] })
  } else if (action === 'activate') {
    list[index] = bump(row, { status: 'ACTIVE', roles: ['MEMBER'], grants: [{ id: crypto.randomUUID(), roleKey: 'MEMBER' }] })
  } else if (action === 'remove' || action === 'leave') {
    list[index] = bump(row, { status: action === 'remove' ? 'REMOVED' : 'LEFT', roles: [], grants: [] })
  } else if (action === 'grantRole') {
    const roleKey = (request.postDataJSON() as { roleKey: string }).roleKey
    log.roleKey = roleKey
    list[index] = bump(row, { grants: [...row.grants, { id: crypto.randomUUID(), roleKey }], roles: [...row.roles, roleKey] })
  } else if (action === 'revokeRole') {
    const grant = row.grants.find(candidate => candidate.id === log.grantId)
    list[index] = bump(row, {
      grants: row.grants.filter(candidate => candidate.id !== log.grantId),
      roles: row.roles.filter(role => role !== grant?.roleKey),
    })
  } else {
    // Ownership transfer: the target gains ownership while the previous Owner loses every grant.
    const previousOwnerId = team.ownerMemberId
    team.ownerMemberId = memberId
    list[index] = bump(row, {})
    const previous = list.findIndex(candidate => candidate.id === previousOwnerId)
    if (previous >= 0) {
      const previousRow = list[previous]!
      list[previous] = { ...previousRow, version: previousRow.version + 1, authorizationVersion: previousRow.authorizationVersion + 1, roles: [], grants: [] }
    }
  }
  return json(route, receipt(action), 202)
}

function sessionFor(world: A07World, viewer: Viewer) {
  const principalId = viewerPrincipal(viewer)
  const viewerPermissions = viewer === 'owner' ? ownerPermissions : memberPermissions
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
    teams: world.teams
      .map(team => ({ team, row: activeRow(world, team.id, principalId) }))
      .filter(entry => entry.row !== null)
      .map(entry => ({ teamId: entry.team.id, name: entry.team.name, memberId: entry.row!.id, permissions: viewerPermissions })),
    // Account-wide capabilities stay empty: every Team capability must come from the selected Team.
    permissions: [],
  }
}

function memberRow(id: string, principalId: string, displayName: string, joinMethod: string): MemberFixtureRow {
  return {
    id, userPrincipalId: principalId, displayName, status: 'ACTIVE', joinMethod,
    joinedAt: '2026-08-01T00:00:00Z', roles: [], grants: [], authorizationVersion: 1, version: 0,
  }
}

function projectRow(teamId: string) {
  const onPlatform = teamId === ids.teamPlatform
  return {
    id: onPlatform ? ids.projectPlatform : ids.projectGrowth,
    organizationId: ids.organization, teamId, workspaceId: ids.workspace,
    key: onPlatform ? 'CRW' : 'GRW', name: onPlatform ? 'CrewScope' : 'Growth',
    status: 'ACTIVE', version: 1,
    createdAt: '2026-08-27T07:00:00Z', createdByPrincipalId: ids.principalOwner,
    updatedAt: '2026-08-27T07:00:00Z', updatedByPrincipalId: ids.principalOwner,
  }
}

let commandSequence = 0

function receipt(action: string) {
  commandSequence += 1
  return {
    commandId: `command-${action}-${commandSequence}`,
    domainEventId: `event-${action}-${commandSequence}`,
    committedVersion: 1,
    correlationId: `correlation-${action}-${commandSequence}`,
  }
}

function envelope(code: string, message: string, currentVersion: string | null = null) {
  return { code, message, correlationId: 'correlation-a07-fixture', retryable: false, currentVersion, details: {} }
}

function header(request: Request, name: string): string | undefined {
  return request.headers()[name]
}

function json(route: Route, body: unknown, status = 200) {
  return route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) })
}
