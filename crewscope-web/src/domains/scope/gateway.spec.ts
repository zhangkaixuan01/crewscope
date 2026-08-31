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
})

function jsonResponse(body: unknown): Response {
  return new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } })
}
