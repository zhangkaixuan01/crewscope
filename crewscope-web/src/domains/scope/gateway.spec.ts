import { CrewScopeApiClient } from '../../api/client'
import { fixtureIds, fixtureProjects, fixtureTeams } from '../../test/scopeFixtures'
import { HttpScopeGateway } from './gateway'

describe('HttpScopeGateway', () => {
  it('maps Team and WorkProject discovery to the reviewed M1 API roots', async () => {
    const fetcher = vi.fn<typeof fetch>()
      .mockResolvedValueOnce(jsonResponse(fixtureTeams))
      .mockResolvedValueOnce(jsonResponse({ items: fixtureProjects[fixtureIds.teamPlatform], nextCursor: null }))
    const gateway = new HttpScopeGateway(new CrewScopeApiClient('/api/v1', fetcher))

    await gateway.listTeams(fixtureIds.organization)
    await gateway.listWorkProjects(fixtureIds.organization, fixtureIds.teamPlatform)

    expect(fetcher.mock.calls[0]?.[0]).toBe(`/api/v1/organizations/${fixtureIds.organization}/teams`)
    expect(fetcher.mock.calls[1]?.[0]).toBe(
      `/api/v1/organizations/${fixtureIds.organization}/teams/${fixtureIds.teamPlatform}/work-projects?limit=100`,
    )
  })

  it('sends member additions as idempotent commands with a server-resolved Principal locator', async () => {
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(jsonResponse({
      commandId: crypto.randomUUID(),
      domainEventId: crypto.randomUUID(),
      committedVersion: 0,
      correlationId: crypto.randomUUID(),
    }))
    const gateway = new HttpScopeGateway(new CrewScopeApiClient('/api/v1', fetcher))

    await gateway.addMember(
      fixtureIds.organization,
      fixtureIds.teamPlatform,
      fixtureIds.secondPrincipal,
      'member-command-1',
    )

    const request = fetcher.mock.calls[0]?.[1]
    const headers = new Headers(request?.headers)
    expect(request?.method).toBe('POST')
    expect(request?.body).toBe(JSON.stringify({ userPrincipalId: fixtureIds.secondPrincipal }))
    expect(headers.get('Idempotency-Key')).toBe('member-command-1')
  })

  it('checks a WorkProject key and creates the project with an idempotency key', async () => {
    const fetcher = vi.fn<typeof fetch>()
      .mockResolvedValueOnce(jsonResponse({ key: 'CREW', available: true }))
      .mockResolvedValueOnce(jsonResponse({
        commandId: crypto.randomUUID(),
        domainEventId: crypto.randomUUID(),
        committedVersion: 0,
        correlationId: crypto.randomUUID(),
      }))
      .mockResolvedValueOnce(jsonResponse({ receipt: { commandId: 'command', domainEventId: 'event', committedVersion: 0, correlationId: 'correlation' },
        result: { type: 'WORK_PROJECT', stage: 'COMMITTED', organizationId: fixtureIds.organization,
          teamId: fixtureIds.teamPlatform, projectId: fixtureIds.projectCrewScope,
          resourceId: fixtureIds.projectCrewScope, committedVersion: 0 } }))
      .mockResolvedValueOnce(jsonResponse({ id: fixtureIds.projectCrewScope }))
    const gateway = new HttpScopeGateway(new CrewScopeApiClient('/api/v1', fetcher))

    expect(await gateway.checkWorkProjectKey(
      fixtureIds.organization,
      fixtureIds.teamPlatform,
      'CREW',
    )).toEqual({ key: 'CREW', available: true })
    await gateway.createWorkProject(
      fixtureIds.organization,
      fixtureIds.teamPlatform,
      { key: 'CREW', name: 'CrewScope Platform' },
      'project-command-1',
    )

    expect(fetcher.mock.calls[0]?.[0]).toBe(
      `/api/v1/organizations/${fixtureIds.organization}/teams/${fixtureIds.teamPlatform}/work-projects/keys/CREW`,
    )
    const request = fetcher.mock.calls[1]?.[1]
    expect(request?.method).toBe('POST')
    expect(request?.body).toBe(JSON.stringify({ key: 'CREW', name: 'CrewScope Platform' }))
    expect(new Headers(request?.headers).get('Idempotency-Key')).toBe('project-command-1')
  })

  it('normalizes every handover response from the jobId/itemId wire shape', async () => {
    const jobId = crypto.randomUUID()
    const wireJob = {
      jobId, status: 'PENDING', role: 'OWNER',
      sourceMemberId: fixtureIds.memberSecond, targetPrincipalId: fixtureIds.principal,
      sourceAuthorizationVersion: 2, version: 0,
      items: [{
        itemId: '00000000-0000-0000-0000-000000000a01', assignmentId: '00000000-0000-0000-0000-000000000b01',
        workItemId: '00000000-0000-0000-0000-000000000901', state: 'PENDING',
        resultAssignmentId: null, errorCode: null,
      }],
    }
    const fetcher = vi.fn<typeof fetch>()
      .mockResolvedValueOnce(jsonResponse({ command: { commandId: 'command', domainEventId: 'event', committedVersion: 0, correlationId: 'correlation' }, job: wireJob }))
      .mockResolvedValueOnce(jsonResponse({ ...wireJob, status: 'COMPLETED', version: 1, items: [{ ...wireJob.items[0], state: 'DONE', resultAssignmentId: '00000000-0000-0000-0000-000000000b02' }] }))
      .mockResolvedValueOnce(jsonResponse({ ...wireJob, status: 'COMPLETED' }))
      .mockResolvedValueOnce(jsonResponse({ ...wireJob, status: 'CANCELLED', version: 1 }))
    const gateway = new HttpScopeGateway(new CrewScopeApiClient('/api/v1', fetcher))

    const created = await gateway.createHandover(fixtureIds.organization, fixtureIds.teamPlatform, {
      sourceMemberId: fixtureIds.memberSecond, targetPrincipalId: fixtureIds.principal, role: 'OWNER',
    }, 'handover-command-1')
    const processed = await gateway.processHandover(fixtureIds.organization, fixtureIds.teamPlatform, jobId, 'handover-command-2')
    await gateway.getHandover(fixtureIds.organization, fixtureIds.teamPlatform, jobId)
    const cancelled = await gateway.cancelHandover(fixtureIds.organization, fixtureIds.teamPlatform, jobId, 'handover-command-3')

    const expectedItems = [{
      id: '00000000-0000-0000-0000-000000000a01', assignmentId: '00000000-0000-0000-0000-000000000b01',
      workItemId: '00000000-0000-0000-0000-000000000901', state: 'PENDING', resultAssignmentId: null, errorCode: null,
    }]
    expect(created.job).toEqual({ id: jobId, status: 'PENDING', role: 'OWNER', sourceMemberId: fixtureIds.memberSecond, targetPrincipalId: fixtureIds.principal, sourceAuthorizationVersion: 2, version: 0, items: expectedItems })
    expect(processed.items[0]).toMatchObject({ id: '00000000-0000-0000-0000-000000000a01', state: 'DONE', resultAssignmentId: '00000000-0000-0000-0000-000000000b02' })
    expect(processed.status).toBe('COMPLETED')
    expect(cancelled.status).toBe('CANCELLED')
    expect(new Headers(fetcher.mock.calls[0]?.[1]?.headers).get('Idempotency-Key')).toBe('handover-command-1')
    expect(fetcher.mock.calls[1]?.[0]).toBe(
      `/api/v1/organizations/${fixtureIds.organization}/teams/${fixtureIds.teamPlatform}/responsibility-handovers/${jobId}/process`,
    )
  })
})

function jsonResponse(body: unknown): Response {
  return new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } })
}
