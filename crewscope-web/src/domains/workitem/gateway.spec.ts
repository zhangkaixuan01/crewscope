import { CrewScopeApiClient } from '../../api/client'
import { fixtureIds } from '../../test/scopeFixtures'
import { fixtureResponsibilities, fixtureTimeline, fixtureWorkItemDetails, fixtureWorkItems, responsibilityIds, workItemIds } from '../../test/workItemFixtures'
import { HttpWorkItemGateway } from './gateway'

describe('HttpWorkItemGateway', () => {
  it('encodes status, opaque Cursor and page size on the reviewed query route', async () => {
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(jsonResponse({ items: fixtureWorkItems, nextCursor: null }))
    const gateway = new HttpWorkItemGateway(new CrewScopeApiClient('/api/v1', fetcher))

    await gateway.listWorkItems({
      organizationId: fixtureIds.organization,
      teamId: fixtureIds.teamPlatform,
      projectId: fixtureIds.projectCrewScope,
      status: 'IN_PROGRESS',
      after: 'opaque+/cursor',
      limit: 25,
    })

    const url = new URL(String(fetcher.mock.calls[0]?.[0]), 'http://crewscope.test')
    expect(url.pathname).toBe(`/api/v1/organizations/${fixtureIds.organization}/teams/${fixtureIds.teamPlatform}/work-projects/${fixtureIds.projectCrewScope}/work-items`)
    expect(Object.fromEntries(url.searchParams)).toEqual({ status: 'IN_PROGRESS', after: 'opaque+/cursor', limit: '25' })
  })

  it('creates a native WorkItem with an Idempotency-Key and no client actor', async () => {
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(jsonResponse({ commandId: 'command', domainEventId: 'event', committedVersion: 0, correlationId: 'correlation' }, 202))
    const gateway = new HttpWorkItemGateway(new CrewScopeApiClient('/api/v1', fetcher))
    const input = { key: 'CRW-21', type: 'TASK' as const, title: '准备发布', description: null, priority: 'MEDIUM' as const, labels: ['release'], dueAt: null }

    await gateway.createWorkItem({ organizationId: fixtureIds.organization, teamId: fixtureIds.teamPlatform, projectId: fixtureIds.projectCrewScope }, input, 'work-command-1')

    const request = fetcher.mock.calls[0]?.[1]
    expect(request?.method).toBe('POST')
    expect(request?.body).toBe(JSON.stringify(input))
    expect(new Headers(request?.headers).get('Idempotency-Key')).toBe('work-command-1')
    expect(request?.body).not.toContain('principal')
  })

  it('loads the consistent detail snapshot and sends a strong version precondition on transition', async () => {
    const fetcher = vi.fn<typeof fetch>()
      .mockResolvedValueOnce(jsonResponse(fixtureWorkItemDetails))
      .mockResolvedValueOnce(jsonResponse({ commandId: 'command', domainEventId: 'event', committedVersion: 4, correlationId: 'correlation' }, 202))
    const gateway = new HttpWorkItemGateway(new CrewScopeApiClient('/api/v1', fetcher))
    const scope = { organizationId: fixtureIds.organization, teamId: fixtureIds.teamPlatform, projectId: fixtureIds.projectCrewScope }

    await gateway.getWorkItem(scope, workItemIds.first)
    await gateway.transitionWorkItem(scope, workItemIds.first, 'IN_REVIEW', 3, 'transition-command')

    expect(fetcher.mock.calls[0]?.[0]).toBe(`/api/v1/organizations/${fixtureIds.organization}/teams/${fixtureIds.teamPlatform}/work-projects/${fixtureIds.projectCrewScope}/work-items/${workItemIds.first}`)
    const transition = fetcher.mock.calls[1]?.[1]
    const headers = new Headers(transition?.headers)
    expect(transition?.body).toBe(JSON.stringify({ targetStatus: 'IN_REVIEW' }))
    expect(headers.get('If-Match')).toBe('"3"')
    expect(headers.get('Idempotency-Key')).toBe('transition-command')
  })

  it('maps comments and ResourceLinks to separate idempotent collaboration routes', async () => {
    const fetcher = vi.fn<typeof fetch>()
      .mockImplementation(async () => jsonResponse({ commandId: 'command', domainEventId: 'event', committedVersion: 0, correlationId: 'correlation' }, 202))
    const gateway = new HttpWorkItemGateway(new CrewScopeApiClient('/api/v1', fetcher))
    const scope = { organizationId: fixtureIds.organization, teamId: fixtureIds.teamPlatform, projectId: fixtureIds.projectCrewScope }

    await gateway.addComment(scope, workItemIds.first, { content: '准备 Review' }, 'comment-command')
    await gateway.linkResource(scope, workItemIds.first, { resourceType: 'EXTERNAL_URL', resourceReference: 'https://example.com/evidence', label: '验证证据' }, 'resource-command')

    expect(fetcher.mock.calls[0]?.[0]).toMatch(/\/comments$/)
    expect(fetcher.mock.calls[1]?.[0]).toMatch(/\/resource-links$/)
    expect(new Headers(fetcher.mock.calls[0]?.[1]?.headers).get('Idempotency-Key')).toBe('comment-command')
    expect(fetcher.mock.calls[1]?.[1]?.body).toContain('https://example.com/evidence')
  })

  it('maps responsibility queries and commands to the policy-specific A06 routes', async () => {
    const fetcher = vi.fn<typeof fetch>()
      .mockResolvedValueOnce(jsonResponse(fixtureResponsibilities))
      .mockImplementation(async () => jsonResponse({ commandId: 'command', domainEventId: 'event', committedVersion: 0, correlationId: 'correlation' }, 202))
    const gateway = new HttpWorkItemGateway(new CrewScopeApiClient('/api/v1', fetcher))
    const scope = { organizationId: fixtureIds.organization, teamId: fixtureIds.teamPlatform, projectId: fixtureIds.projectCrewScope }

    await gateway.listResponsibilities(scope, workItemIds.first)
    await gateway.replaceOwner(scope, workItemIds.first, { actorPrincipalId: fixtureIds.principal, expectedAssignmentId: responsibilityIds.owner, expectedVersion: 3 }, 'owner-command')
    await gateway.assignExecutor(scope, workItemIds.first, { actorPrincipalId: fixtureIds.principal }, 'executor-command')
    await gateway.assignGateReviewer(scope, workItemIds.first, { actorPrincipalId: fixtureIds.principal }, 'gate-command')
    await gateway.assignAdvisoryReviewer(scope, workItemIds.first, { actorPrincipalId: fixtureIds.principal }, 'advisory-command')

    expect(fetcher.mock.calls.map(call => String(call[0]).split('/').at(-1))).toEqual([
      'responsibilities', 'owner', 'executors', 'gate-reviewers', 'advisory-reviewers',
    ])
    expect(fetcher.mock.calls[1]?.[1]?.body).toBe(JSON.stringify({ actorPrincipalId: fixtureIds.principal, expectedAssignmentId: responsibilityIds.owner, expectedVersion: 3 }))
    expect(new Headers(fetcher.mock.calls[1]?.[1]?.headers).get('Idempotency-Key')).toBe('owner-command')
  })

  it('releases a non-Owner responsibility with its strong version precondition', async () => {
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(jsonResponse({ commandId: 'command', domainEventId: 'event', committedVersion: 1, correlationId: 'correlation' }, 202))
    const gateway = new HttpWorkItemGateway(new CrewScopeApiClient('/api/v1', fetcher))
    const scope = { organizationId: fixtureIds.organization, teamId: fixtureIds.teamPlatform, projectId: fixtureIds.projectCrewScope }

    await gateway.releaseResponsibility(scope, workItemIds.first, responsibilityIds.executor, 4, 'release-command')

    expect(fetcher.mock.calls[0]?.[0]).toMatch(new RegExp(`/responsibilities/${responsibilityIds.executor}/releases$`))
    const request = fetcher.mock.calls[0]?.[1]
    expect(new Headers(request?.headers).get('If-Match')).toBe('"4"')
    expect(new Headers(request?.headers).get('Idempotency-Key')).toBe('release-command')
    expect(request?.body).toBeUndefined()
  })

  it('keeps the timeline Cursor opaque and separate from collection pagination', async () => {
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(jsonResponse({ items: fixtureTimeline, nextCursor: null }))
    const gateway = new HttpWorkItemGateway(new CrewScopeApiClient('/api/v1', fetcher))
    const scope = { organizationId: fixtureIds.organization, teamId: fixtureIds.teamPlatform, projectId: fixtureIds.projectCrewScope }

    await gateway.listTimeline(scope, workItemIds.first, 'timeline+/cursor', 25)

    const url = new URL(String(fetcher.mock.calls[0]?.[0]), 'http://crewscope.test')
    expect(url.pathname).toMatch(new RegExp(`/work-items/${workItemIds.first}/timeline$`))
    expect(Object.fromEntries(url.searchParams)).toEqual({ limit: '25', after: 'timeline+/cursor' })
  })
})

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}
