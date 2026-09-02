import { CrewScopeApiClient } from '../../api/client'
import { HttpSetupGateway } from './gateway'

const scope = { organizationId: 'org-1', teamId: 'team-1' }
const capabilities = ['PERSONAL_CONVERSATION', 'TEAM_TASK', 'CODING_REVIEW', 'GITHUB_DRAFT_PR', 'LARK_NOTIFICATIONS', 'TEAM_OBSERVER'].map(capability => ({ capability, required: capability === 'TEAM_TASK', status: 'READY', reasonCode: 'READY', canConfigure: true, responsibleParty: 'Team 管理员', actionKey: null }))

describe('HttpSetupGateway', () => {
  it('maps the readiness contract and keeps the active scope boundary', async () => {
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(new Response(JSON.stringify({ organizationId: 'org-1', teamId: 'team-1', snapshotVersion: 'v1', observedAt: '2026-09-01T00:00:00Z', capabilities, requiredReady: true }), { status: 200 }))
    const value = await new HttpSetupGateway(new CrewScopeApiClient('/api/v1', fetcher)).getReadiness(scope)
    expect(value.scope).toEqual(scope)
    expect(value.capabilities).toHaveLength(6)
    expect(fetcher.mock.calls[0]?.[0]).toContain('/organizations/org-1/teams/team-1/setup-readiness')
  })

  it('rejects an incomplete capability set', async () => {
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(new Response(JSON.stringify({ organizationId: 'org-1', teamId: 'team-1', snapshotVersion: 'v1', observedAt: '2026-09-01T00:00:00Z', capabilities: capabilities.slice(0, 2), requiredReady: false }), { status: 200 }))
    await expect(new HttpSetupGateway(new CrewScopeApiClient('/api/v1', fetcher)).getReadiness(scope)).rejects.toThrow('capability set')
  })
})
