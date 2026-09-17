import { CrewScopeApiClient } from '../../api/client'
import { HttpSetupGateway } from './gateway'

const scope = { organizationId: 'org-1', teamId: 'team-1' }
const capabilities = ['PERSONAL_CONVERSATION', 'TEAM_TASK', 'CODING_REVIEW', 'GITHUB_DRAFT_PR', 'LARK_NOTIFICATIONS', 'TEAM_OBSERVER'].map(capability => ({ capability, required: capability === 'TEAM_TASK', status: 'READY', reasonCode: 'READY', canConfigure: true, responsibleParty: 'Team 管理员', actionKey: null }))
const healthItems = ['AGENT_CONFIGURATION', 'MODEL_CONNECTION', 'CREDENTIAL', 'INTEGRATION'].map(component => ({ component, status: 'READY', reasonCode: 'READY', responsibleParty: 'Team 管理员', actionKey: null }))

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

  it('maps the four-component configuration health projection', async () => {
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(new Response(JSON.stringify({
      organizationId: 'org-1',
      teamId: 'team-1',
      observedAt: '2026-09-01T00:00:00Z',
      overallStatus: 'ACTION_REQUIRED',
      items: healthItems.map((item, index) => (index === 1
        ? { ...item, status: 'ACTION_REQUIRED', reasonCode: 'MODEL_CONNECTION_REQUIRED', actionKey: 'OPEN_MODEL_SETTINGS' }
        : item)),
    }), { status: 200 }))
    const value = await new HttpSetupGateway(new CrewScopeApiClient('/api/v1', fetcher)).getConfigurationHealth(scope)

    expect(value.overallStatus).toBe('ACTION_REQUIRED')
    expect(value.items).toHaveLength(4)
    expect(value.items[1]).toMatchObject({ component: 'MODEL_CONNECTION', actionKey: 'OPEN_MODEL_SETTINGS' })
    expect(fetcher.mock.calls[0]?.[0]).toContain('/organizations/org-1/teams/team-1/configuration-health')
  })

  it('fails closed for an incomplete, unknown or malformed health projection', async () => {
    const cases = [
      healthItems.slice(0, 3),
      [healthItems[0], healthItems[0], healthItems[2], healthItems[3]],
      [healthItems[0], { ...healthItems[1], status: 'FINE' }, healthItems[2], healthItems[3]],
      [healthItems[0], { ...healthItems[1], actionKey: 'open_model_settings' }, healthItems[2], healthItems[3]],
    ]
    for (const items of cases) {
      const fetcher = vi.fn<typeof fetch>().mockResolvedValue(new Response(JSON.stringify({ organizationId: 'org-1', teamId: 'team-1', observedAt: '2026-09-01T00:00:00Z', overallStatus: 'READY', items }), { status: 200 }))
      await expect(new HttpSetupGateway(new CrewScopeApiClient('/api/v1', fetcher)).getConfigurationHealth(scope)).rejects.toThrow(TypeError)
    }
  })

  it('encodes the field search term and maps only metadata', async () => {
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(new Response(JSON.stringify({
      items: [{ profileId: 'profile-1', revision: 3, field: 'modelKey', label: '模型连接', route: '/settings/models?provider=openai' }],
    }), { status: 200 }))
    const hits = await new HttpSetupGateway(new CrewScopeApiClient('/api/v1', fetcher)).searchConfiguration(scope, '  模型 ')

    expect(hits).toEqual([{ profileId: 'profile-1', revision: 3, field: 'modelKey', label: '模型连接', route: '/settings/models?provider=openai' }])
    expect(fetcher.mock.calls[0]?.[0]).toContain('configuration-search?q=%E6%A8%A1%E5%9E%8B')
  })

  it('never sends a query outside the contract range and rejects an unusable hit', async () => {
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(new Response(JSON.stringify({ items: [] }), { status: 200 }))
    const gateway = new HttpSetupGateway(new CrewScopeApiClient('/api/v1', fetcher))
    await expect(gateway.searchConfiguration(scope, '   ')).rejects.toThrow(TypeError)
    await expect(gateway.searchConfiguration(scope, '模'.repeat(101))).rejects.toThrow(TypeError)
    expect(fetcher).not.toHaveBeenCalled()

    for (const item of [
      { profileId: 'profile-1', revision: 0, field: 'modelKey', label: '模型连接', route: '/settings/models' },
      { profileId: 'profile-1', revision: 1, field: 'modelKey', label: '模型连接', route: 'settings/models' },
    ]) {
      const bad = vi.fn<typeof fetch>().mockResolvedValue(new Response(JSON.stringify({ items: [item] }), { status: 200 }))
      await expect(new HttpSetupGateway(new CrewScopeApiClient('/api/v1', bad)).searchConfiguration(scope, 'model')).rejects.toThrow(TypeError)
    }
  })
})
