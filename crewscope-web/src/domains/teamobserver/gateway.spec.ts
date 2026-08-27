import { CrewScopeApiClient } from '../../api/client'
import { evidenceNavigationPath, HttpTeamObserverGateway, safeInternalPath } from './gateway'

const scope = { organizationId: 'org 1', teamId: 'team/1' }

describe('HttpTeamObserverGateway', () => {
  it('sends only the fixed invocation input and validates the SSE identity', async () => {
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(sseResponse([
      event({ invocationId: 'inv-1', sequence: 0, occurredAt: '2026-08-27T08:00:00Z', type: 'STARTED' }),
      event({ invocationId: 'inv-1', sequence: 1, occurredAt: '2026-08-27T08:00:01Z', type: 'SUMMARY_COMPLETED', summary: summary() }),
    ], { 'X-CrewScope-Invocation-Id': 'inv-1' }))
    const gateway = new HttpTeamObserverGateway(new CrewScopeApiClient('/api/v1', fetcher))

    const connection = await gateway.invoke(scope, 'session-1', '总结团队', 10)
    const events = []
    for await (const item of connection.events) events.push(item)

    expect(connection.invocationId).toBe('inv-1')
    expect(events.map(item => item.type)).toEqual(['STARTED', 'SUMMARY_COMPLETED'])
    const [, options] = fetcher.mock.calls[0]!
    expect(JSON.parse(String(options?.body))).toEqual({ instruction: '总结团队', maxItemsPerSection: 10 })
    expect(fetcher.mock.calls[0]![0]).toContain('/organizations/org%201/teams/team%2F1/team-observer/sessions/session-1/invocations')
  })

  it('accepts only approved same-origin evidence routes', () => {
    const authorizedScope = { organizationId: '00000000-0000-4000-8000-000000000001', teamId: '00000000-0000-4000-8000-000000000201' }
    const authorizedPath = `/api/v1/organizations/${authorizedScope.organizationId}/teams/${authorizedScope.teamId}/activity/00000000-0000-4000-8000-000000000802`
    expect(safeInternalPath(authorizedPath)).toBe(true)
    expect(evidenceNavigationPath(authorizedPath, authorizedScope)).toContain('/activity?')
    expect(safeInternalPath('//evil.example/work')).toBe(false)
    expect(safeInternalPath('javascript:alert(1)')).toBe(false)
    expect(evidenceNavigationPath('/api/v1/private-evidence', scope)).toBeNull()
    expect(evidenceNavigationPath('/api/v1/organizations/other/teams/team/1/tasks/00000000-0000-4000-8000-000000000001', scope)).toBeNull()
  })
})

function event(value: object): string { return `event: observer\ndata: ${JSON.stringify(value)}\n\n` }
function sseResponse(chunks: string[], headers: Record<string, string>): Response {
  const encoder = new TextEncoder()
  return new Response(new ReadableStream({ start(controller) { chunks.forEach(chunk => controller.enqueue(encoder.encode(chunk))); controller.close() } }), { headers: { 'Content-Type': 'text/event-stream', ...headers } })
}
function summary() {
  const entry = { section: 'PROGRESS', dataScope: 'TEAM_ACTIVITY', summary: '<img src=x onerror=alert(1)>', evidenceIndex: 0 }
  return { observerProfileId: 'team-observer@1', generatedAt: '2026-08-27T08:00:01Z', progress: [entry], blockers: [], reviewBacklog: [], pendingConfirmations: [], anomalies: [] }
}
