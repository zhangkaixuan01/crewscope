import { CrewScopeApiClient } from '../../api/client'
import { HttpSearchGateway } from './gateway'

describe('HttpSearchGateway', () => {
  it('encodes scope, filters and cursor without exposing arbitrary routes', async () => {
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(new Response(JSON.stringify({
      items: [{ objectType: 'WORK_ITEM', objectId: 'item-1', projectId: null, title: '修复登录', subtitle: 'CREW-1', status: 'OPEN', updatedAt: '2026-09-13T00:00:00Z', route: '/work?team=team-1', snippet: '修复登录' }],
      nextCursor: 'cursor_1',
    }), { status: 200 }))
    const gateway = new HttpSearchGateway(new CrewScopeApiClient('/api/v1', fetcher))

    const page = await gateway.search(
      { organizationId: 'org/1', teamId: 'team-1' },
      { text: '登录', projectId: 'project-1', types: ['WORK_ITEM'], after: 'cursor_1', limit: 10 },
    )

    expect(page.items[0]?.title).toBe('修复登录')
    expect(fetcher.mock.calls[0]?.[0]).toContain('/organizations/org%2F1/teams/team-1/search?')
    expect(fetcher.mock.calls[0]?.[0]).toContain('types=WORK_ITEM')
    expect(fetcher.mock.calls[0]?.[0]).toContain('after=cursor_1')
  })

  it('rejects unsafe server-provided navigation routes', async () => {
    const client = new CrewScopeApiClient('/api/v1', vi.fn<typeof fetch>().mockResolvedValue(new Response(JSON.stringify({
      items: [{ objectType: 'AGENT', objectId: 'agent-1', projectId: null, title: 'Agent', subtitle: 'TEAM', status: 'ACTIVE', updatedAt: '2026-09-13T00:00:00Z', route: 'https://evil.example', snippet: null }], nextCursor: null,
    }), { status: 200 })))
    await expect(new HttpSearchGateway(client).search({ organizationId: 'org', teamId: 'team' }, { text: 'Agent' })).rejects.toThrow('Unsafe Search route')
  })
})
