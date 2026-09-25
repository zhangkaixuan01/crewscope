import { readFile } from 'node:fs/promises'
import { extname, resolve, sep } from 'node:path'
import type { Page, Route } from '@playwright/test'
import { permissions } from '../../src/app/auth'

/**
 * M9b-F05 browser fixtures: the production build is served from a reserved non-loopback
 * origin (so Chromium must not grant localhost's secure-context exception) with every API
 * answered by interception. No application/Compose service is involved.
 */
export const origin = 'http://crewscope-f05.test'
const dist = resolve('dist')

export const ids = {
  organization: '00000000-0000-0000-0000-000000000001',
  accountA: '00000000-0000-0000-0000-0000000000a1',
  accountB: '00000000-0000-0000-0000-0000000000b1',
  principalA: '00000000-0000-0000-0000-000000000101',
  principalB: '00000000-0000-0000-0000-000000000102',
  memberA: '00000000-0000-0000-0000-000000000301',
  memberB: '00000000-0000-0000-0000-000000000302',
  teamA: '00000000-0000-0000-0000-000000000201',
  teamB: '00000000-0000-0000-0000-000000000202',
  projectA: '00000000-0000-0000-0000-000000000401',
  projectB: '00000000-0000-0000-0000-000000000402',
  workspace: '00000000-0000-0000-0000-000000000501',
  workItem: '00000000-0000-0000-0000-000000000601',
  createdItem: '00000000-0000-0000-0000-000000000651',
  conversation: '00000000-0000-0000-0000-000000001101',
  personalAgent: '00000000-0000-0000-0000-000000000105',
}

export function workUrl(teamId: string = ids.teamA): string {
  const projectId = teamId === ids.teamB ? ids.projectB : ids.projectA
  return `${origin}/work?team=${teamId}&project=${projectId}`
}

/** Both fixture accounts belong to both Teams; the isolating dimension is the account itself. */
export function sessionFor(account: 'A' | 'B') {
  const granted = Object.values(permissions)
  const accountId = account === 'A' ? ids.accountA : ids.accountB
  const principalId = account === 'A' ? ids.principalA : ids.principalB
  const memberId = account === 'A' ? ids.memberA : ids.memberB
  return {
    authenticated: true,
    registrationMode: 'OPEN',
    csrf: { headerName: 'X-XSRF-TOKEN', parameterName: '_csrf', token: `csrf-f05-${account}` },
    account: {
      accountId, username: account === 'A' ? 'f05-member-a' : 'f05-member-b',
      displayName: account === 'A' ? 'fixture 成员甲' : 'fixture 成员乙',
      platformRole: 'USER', securityVersion: 1, version: 1,
    },
    principal: { principalId, organizationId: ids.organization },
    teams: [ids.teamA, ids.teamB].map(teamId => ({
      teamId, name: teamId === ids.teamA ? 'Platform Engineering' : 'Growth Engineering',
      memberId: `${memberId}-${teamId.slice(-3)}`, permissions: granted,
    })),
    permissions: granted,
  }
}

function teamRow(teamId: string) {
  return {
    id: teamId, organizationId: ids.organization,
    name: teamId === ids.teamA ? 'Platform Engineering' : 'Growth Engineering',
    status: 'ACTIVE', initializationStatus: 'READY',
    ownerMemberId: `${ids.memberA}-${teamId.slice(-3)}`, defaultWorkspaceId: ids.workspace, version: 1,
  }
}

function projectRow(teamId: string) {
  const projectId = teamId === ids.teamB ? ids.projectB : ids.projectA
  return {
    id: projectId, organizationId: ids.organization, teamId, workspaceId: ids.workspace,
    key: teamId === ids.teamB ? 'GRW' : 'CRW', name: teamId === ids.teamB ? 'Growth' : 'CrewScope',
    status: 'ACTIVE', version: 1, createdAt: '2026-08-27T07:00:00Z', createdByPrincipalId: ids.principalA,
    updatedAt: '2026-08-27T07:00:00Z', updatedByPrincipalId: ids.principalA,
  }
}

function workItemRow(id: string, key: string, version: number, teamId: string) {
  const projectId = teamId === ids.teamB ? ids.projectB : ids.projectA
  const principalId = teamId === ids.teamB ? ids.principalB : ids.principalA
  return {
    id, organizationId: ids.organization, teamId, workspaceId: ids.workspace, projectId,
    key, type: 'FEATURE', title: 'Fix login hint', description: 'Fixture description',
    status: 'IN_PROGRESS', priority: 'HIGH', labels: [], dueAt: null, source: 'CREWSCOPE',
    sourceReference: null, version, createdAt: '2026-08-08T01:00:00Z', createdByPrincipalId: principalId,
    updatedAt: '2026-08-08T02:00:00Z', updatedByPrincipalId: principalId,
  }
}

export interface F05World {
  session: ReturnType<typeof sessionFor>
  /** The device has signed out; GET auth/session answers with an anonymous body from then on. */
  signedOut: boolean
  /** POST /work-items answers 422 while true; the dialog must keep the browser draft. */
  createFail: boolean
  /** POST .../comments answers 503 while true; the drawer must keep the browser draft. */
  commentFail: boolean
  /** Teams whose every API answer becomes 403 (revocation scenario). */
  forbiddenTeams: Set<string>
  workItemVersion: number
  comments: Array<Record<string, unknown>>
  commandReceipts: Map<string, { resourceId: string }>
  unexpected: string[]
}

export async function mockF05App(page: Page): Promise<F05World> {
  const world: F05World = {
    session: sessionFor('A'),
    signedOut: false,
    createFail: false,
    commentFail: false,
    forbiddenTeams: new Set(),
    workItemVersion: 0,
    comments: [],
    commandReceipts: new Map(),
    unexpected: [],
  }
  await page.route('**/*', async route => {
    const request = route.request()
    const url = new URL(request.url())
    if (url.origin !== origin) { world.unexpected.push(url.origin); return route.abort() }
    if (url.pathname.startsWith('/api/')) return handleApi(route, world, url)
    return serveAsset(route, url.pathname)
  })
  return world
}

async function handleApi(route: Route, world: F05World, url: URL): Promise<void> {
  const request = route.request()
  const path = url.pathname
  const method = request.method()
  const teamOf = (): string | null => {
    const match = /\/teams\/([^/]+)/.exec(path)
    return match ? decodeURIComponent(match[1]!) : null
  }
  if (world.forbiddenTeams.has(teamOf() ?? '') && !path.endsWith('/auth/session')) {
    return json(route, {
      code: 'policy_denied', message: 'fixture membership revoked', correlationId: 'f05-fixture',
      retryable: false, currentVersion: null, details: {},
    }, 403)
  }
  // The real backend answers an anonymous body once the device signed out; a forever-authenticated
  // fixture would bounce the login page straight back into the workspace.
  if (method === 'GET' && path === '/api/v1/auth/session') {
    return json(route, world.signedOut ? {
      authenticated: false, registrationMode: 'OPEN',
      csrf: { headerName: 'X-XSRF-TOKEN', parameterName: '_csrf', token: 'csrf-f05-anonymous' },
      account: null, principal: null, teams: [], permissions: [],
    } : world.session)
  }
  if (method === 'POST' && path === '/api/v1/auth/logout') {
    world.signedOut = true
    return route.fulfill({ status: 204, body: '' })
  }

  const workItemsMatch = /\/teams\/([^/]+)\/work-projects\/([^/]+)\/work-items(?:\/([^/]+)(\/comments|\/transitions(?:\/availability)?|\/responsibilities|\/timeline|\/conversations)?)?$/.exec(path)
  if (workItemsMatch) {
    const [, teamId, , itemId, suffix] = workItemsMatch
    if (!itemId && !suffix && method === 'GET') return json(route, { items: [workItemRow(ids.workItem, teamId === ids.teamB ? 'GRW-1' : 'CRW-1', world.workItemVersion, teamId!)], nextCursor: null })
    if (!itemId && !suffix && method === 'POST') {
      if (world.createFail) {
        // A definitive 4xx rejection — unlike a 503, this must not enter the A04 result-recovery
        // polling; the dialog stays editable and keeps the browser draft for retry.
        return json(route, {
          code: 'validation_failed', message: 'fixture rejected this command', correlationId: 'f05-fixture',
          retryable: false, currentVersion: null, details: {},
        }, 422)
      }
      const key = request.headers()['idempotency-key'] ?? ''
      world.commandReceipts.set(key, { resourceId: ids.createdItem })
      return json(route, { commandId: `cmd-${key.slice(0, 8)}`, domainEventId: 'evt-f05', committedVersion: 1, correlationId: 'corr-f05' }, 202)
    }
    if (itemId && !suffix && method === 'GET') {
      // The created item reports itself once committed; the seeded row reflects the mutable version.
      const row = itemId === ids.createdItem
        ? workItemRow(ids.createdItem, teamId === ids.teamB ? 'GRW-2' : 'CRW-2', 0, teamId!)
        : workItemRow(itemId!, teamId === ids.teamB ? 'GRW-1' : 'CRW-1', world.workItemVersion, teamId!)
      return json(route, { workItem: row, comments: world.comments, resourceLinks: [] })
    }
    if (itemId && suffix === '/comments' && method === 'POST') {
      if (world.commentFail) {
        return json(route, {
          code: 'service_unavailable', message: 'fixture upstream unavailable', correlationId: 'f05-fixture',
          retryable: true, currentVersion: null, details: {},
        }, 503)
      }
      world.workItemVersion += 1
      world.comments = [...world.comments, {
        id: `comment-${world.comments.length + 1}`, workItemId: itemId,
        authorPrincipalId: ids.principalA, content: (request.postDataJSON() as { content: string }).content,
        source: 'CREWSCOPE', externalId: null, createdAt: '2026-09-24T01:00:00Z',
      }]
      return json(route, { commandId: 'cmd-comment', domainEventId: 'evt-comment', committedVersion: world.workItemVersion, correlationId: 'corr-comment' }, 202)
    }
    if (itemId && (suffix === '/transitions/availability' || suffix === '/transitions') && method === 'GET') return json(route, { transitions: [] })
    if (itemId && suffix === '/responsibilities' && method === 'GET') return json(route, [])
    if (itemId && suffix === '/timeline' && method === 'GET') return json(route, { items: [], nextCursor: null })
    if (itemId && suffix === '/conversations' && method === 'GET') return json(route, [])
  }

  if (method === 'GET' && path === `/api/v1/organizations/${ids.organization}/command-results`) {
    // The client contract carries the idempotency key as a header on every command read.
    const key = request.headers()['idempotency-key'] ?? ''
    const found = world.commandReceipts.get(key)
    if (!found) return json(route, envelope404(), 404)
    return json(route, {
      receipt: { commandId: `cmd-${key.slice(0, 8)}`, domainEventId: 'evt-f05', committedVersion: 1, correlationId: 'corr-f05' },
      result: {
        organizationId: ids.organization, teamId: ids.teamA, projectId: ids.projectA,
        type: 'WORK_ITEM', resourceId: found.resourceId, committedVersion: 1, stage: 'COMMITTED',
      },
    })
  }
  if (method === 'GET' && path.endsWith('/teams')) {
    return json(route, [teamRow(ids.teamA), teamRow(ids.teamB)])
  }
  const projectsMatch = /\/teams\/([^/]+)\/work-projects$/.exec(path)
  if (projectsMatch && method === 'GET') {
    return json(route, { items: [projectRow(decodeURIComponent(projectsMatch[1]!))], nextCursor: null })
  }
  if (method === 'GET' && path.endsWith('/members')) return json(route, [])

  // Conversation surface (scenario: the composer offers no fake entries).
  if (method === 'GET' && /\/teams\/[^/]+\/conversations$/.test(path)) {
    return json(route, {
      items: [{
        id: ids.conversation, organizationId: ids.organization, teamId: ids.teamA, workspaceId: ids.workspace,
        ownerMemberId: ids.memberA, ownerPrincipalId: ids.principalA, personalAgentPrincipalId: ids.personalAgent,
        title: '规划 F05 验收', visibility: 'TEAM', status: 'ACTIVE', lastMessageSequence: 1, version: 0,
        createdAt: '2026-08-08T01:00:00Z', updatedAt: '2026-08-08T03:00:00Z',
      }],
      nextCursor: null,
    })
  }
  const conversationMatch = /\/conversations\/([^/]+)$/.exec(path)
  if (conversationMatch && method === 'GET') {
    return json(route, {
      conversation: {
        id: ids.conversation, organizationId: ids.organization, teamId: ids.teamA, workspaceId: ids.workspace,
        ownerMemberId: ids.memberA, ownerPrincipalId: ids.principalA, personalAgentPrincipalId: ids.personalAgent,
        title: '规划 F05 验收', visibility: 'TEAM', status: 'ACTIVE', lastMessageSequence: 1, version: 0,
        createdAt: '2026-08-08T01:00:00Z', updatedAt: '2026-08-08T03:00:00Z',
      },
      participants: [{
        id: 'participant-1', conversationId: ids.conversation, principalId: ids.principalA, teamMemberId: ids.memberA,
        displayName: 'fixture 成员甲', principalType: 'USER', ownerPrincipalId: null, ownerDisplayName: null,
        role: 'OWNER', status: 'ACTIVE', joinedByPrincipalId: ids.principalA,
        joinedAt: '2026-08-08T01:00:00Z', leftAt: null, version: 0,
      }],
    })
  }
  if (method === 'GET' && /\/conversations\/[^/]+\/messages$/.test(path)) {
    return json(route, {
      items: [{
        id: 'message-1', conversationId: ids.conversation, sequence: 1, type: 'USER_MESSAGE',
        participantId: 'participant-1', authorPrincipalId: ids.principalA,
        content: '开始规划输入与本地内容可信。', createdAt: '2026-08-08T01:10:00Z',
      }],
      nextCursor: null,
    })
  }
  if (method === 'GET' && /\/conversations\/[^/]+\/(work-items|tasks|task-intents)/.test(path)) {
    return json(route, /tasks$/.test(path) ? { items: [], nextCursor: null } : [])
  }

  // Unknown endpoints fail closed like the server would; the suite never depends on them.
  return json(route, envelope404(), 404)
}

function envelope404() {
  return {
    code: 'not_found', message: 'fixture has no such resource', correlationId: 'f05-fixture',
    retryable: false, currentVersion: null, details: {},
  }
}

function json(route: Route, body: unknown, status = 200) {
  return route.fulfill({ status, json: body })
}

async function serveAsset(route: Route, pathname: string): Promise<void> {
  const mime: Record<string, string> = {
    '.html': 'text/html', '.js': 'application/javascript', '.css': 'text/css',
    '.svg': 'image/svg+xml', '.woff2': 'font/woff2', '.png': 'image/png', '.ico': 'image/x-icon',
  }
  // SPA routes (/work, /conversation, /login) fall back to the single built document.
  for (const file of [resolve(dist, `.${pathname}`), resolve(dist, 'index.html')]) {
    if (!file.startsWith(`${dist}${sep}`)) continue
    try {
      return route.fulfill({ body: await readFile(file), contentType: mime[extname(file)] ?? 'application/octet-stream' })
    } catch { /* try the next candidate */ }
  }
  return route.abort()
}

/** Scoped user keys currently living in localStorage (the whole `cs.user.v1:` namespace). */
export async function f05UserKeys(page: Page): Promise<string[]> {
  return page.evaluate(() => Object.keys(localStorage).filter(key => key.startsWith('cs.user.v1:')))
}

/** Occupies the per-account draft budget with valid records, as a browser full of old drafts would. */
export async function seedDrafts(page: Page, count: number): Promise<void> {
  const epoch = await page.evaluate(() =>
    (JSON.parse(localStorage.getItem('cs.user.epoch.v1') ?? 'null') as { epoch: number } | null)?.epoch)
  if (typeof epoch !== 'number') throw new Error('The scoped namespace is not active yet')
  const now = Date.now()
  const entries = Array.from({ length: count }, (_, index) => {
    const objectId = `occupy-${index}`
    const key = ['cs.user.v1', ids.accountA, ids.principalA, ids.organization, ids.teamA, 'none', 'draft', objectId, '0']
      .map(segment => encodeURIComponent(segment)).join(':')
    const record = {
      schemaVersion: 1, kind: 'draft', epoch,
      scope: { accountId: ids.accountA, principalId: ids.principalA, organizationId: ids.organization, teamId: ids.teamA, projectId: null, objectId, revision: null },
      createdAt: now, updatedAt: now, expiresAt: now + 7 * 24 * 60 * 60 * 1000,
      value: { title: 'occupied' },
    }
    return [key, JSON.stringify(record)] as const
  })
  await page.evaluate(pairs => {
    for (const [key, value] of pairs) localStorage.setItem(key, value)
  }, entries)
}
