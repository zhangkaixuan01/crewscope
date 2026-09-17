import { CrewScopeApiError } from '../../api/client'
import { createSetupStore } from './store'
import type { SetupGateway } from './gateway'
import type { ConfigurationSearchHit } from './types'

const scope = { organizationId: 'org-1', teamId: 'team-1' }
const readiness = { scope, snapshotVersion: 'v1', observedAt: '2026-09-01T00:00:00Z', requiredReady: true, capabilities: [] }
const health = {
  scope,
  observedAt: '2026-09-01T00:00:00Z',
  overallStatus: 'READY' as const,
  items: [],
}

function apiError(status: number, message = 'Unavailable'): CrewScopeApiError {
  return new CrewScopeApiError(status, { code: 'error', message, correlationId: 'c-1', retryable: false, currentVersion: null, details: {} })
}

function hit(profileId: string): ConfigurationSearchHit {
  return { profileId, revision: 1, field: 'modelKey', label: '模型连接', route: '/settings/models' }
}

describe('SetupStore', () => {
  it('clears a previous Team snapshot before loading the new scope', async () => {
    const gateway: SetupGateway = { getReadiness: vi.fn(async active => ({ ...readiness, scope: active })) }
    const store = createSetupStore(gateway)
    store.activateScope(scope)
    await store.load()
    expect(store.state.readiness?.scope.teamId).toBe('team-1')
    store.activateScope({ ...scope, teamId: 'team-2' })
    expect(store.state.readiness).toBeNull()
    await store.load()
    expect(store.state.readiness?.scope.teamId).toBe('team-2')
  })

  it('reports a deployment without the health projection instead of an empty result', async () => {
    const gateway: SetupGateway = { getReadiness: vi.fn(async active => ({ ...readiness, scope: active })) }
    const store = createSetupStore(gateway)
    store.activateScope(scope)
    await store.loadHealth()
    expect(store.state.healthPhase).toBe('unavailable')
    expect(store.state.health).toBeNull()
  })

  it('loads health independently of readiness and separates offline from failure', async () => {
    const getConfigurationHealth = vi.fn(async () => health)
    const gateway: SetupGateway = { getReadiness: vi.fn(async active => ({ ...readiness, scope: active })), getConfigurationHealth }
    const store = createSetupStore(gateway)
    store.activateScope(scope)
    // One call while the first request is in flight; the refresh path re-issues it.
    await Promise.all([store.loadHealth(), store.loadHealth()])
    expect(getConfigurationHealth).toHaveBeenCalledTimes(1)
    expect(store.state.healthPhase).toBe('ready')
    await store.loadHealth(true)
    expect(getConfigurationHealth).toHaveBeenCalledTimes(2)

    getConfigurationHealth.mockRejectedValueOnce(apiError(0, 'Network down'))
    await store.loadHealth(true)
    expect(store.state.healthPhase).toBe('offline')
    expect(store.state.healthErrorMessage).toBe('Network down')

    getConfigurationHealth.mockRejectedValueOnce(new Error('boom'))
    await store.loadHealth(true)
    expect(store.state.healthPhase).toBe('error')
    expect(store.state.health).toBeNull()
  })

  it('switching Team drops the previous Team health and search state', async () => {
    const gateway: SetupGateway = {
      getReadiness: vi.fn(async active => ({ ...readiness, scope: active })),
      getConfigurationHealth: vi.fn(async () => health),
      searchConfiguration: vi.fn(async () => []),
    }
    const store = createSetupStore(gateway)
    store.activateScope(scope)
    await store.loadHealth()
    await store.search('model')
    expect(store.state.searchQuery).toBe('model')

    store.activateScope({ ...scope, teamId: 'team-2' })
    expect(store.state.health).toBeNull()
    expect(store.state.healthPhase).toBe('idle')
    expect(store.state.searchResults).toEqual([])
    expect(store.state.searchQuery).toBe('')

    store.reset()
    expect(store.state.scope).toBeNull()
    expect(store.state.healthPhase).toBe('idle')
    expect(store.state.searchPhase).toBe('idle')
  })

  it('maps search failures to their own phases and never sends an empty query', async () => {
    const searchConfiguration = vi.fn(async () => [{ profileId: 'p-1', revision: 1, field: 'modelKey', label: '模型连接', route: '/settings/models' }])
    const gateway: SetupGateway = { getReadiness: vi.fn(async active => ({ ...readiness, scope: active })), searchConfiguration }
    const store = createSetupStore(gateway)
    store.activateScope(scope)

    await store.search('   ')
    expect(searchConfiguration).not.toHaveBeenCalled()
    expect(store.state.searchPhase).toBe('idle')

    await store.search(' model ')
    expect(searchConfiguration).toHaveBeenCalledWith(scope, 'model')
    expect(store.state.searchQuery).toBe('model')
    expect(store.state.searchResults).toHaveLength(1)

    searchConfiguration.mockRejectedValueOnce(apiError(403, '需要 Team 成员权限'))
    await store.search('model')
    expect(store.state.searchPhase).toBe('forbidden')
    expect(store.state.searchResults).toEqual([])

    searchConfiguration.mockRejectedValueOnce(apiError(0, 'Network down'))
    await store.search('model')
    expect(store.state.searchPhase).toBe('offline')
  })

  it('keeps a slow answer from an abandoned query or a switched Team out of the state', async () => {
    const pending: Array<(value: ConfigurationSearchHit[]) => void> = []
    const searchConfiguration = vi.fn(() => new Promise<ConfigurationSearchHit[]>(resolve => { pending.push(resolve) }))
    const gateway: SetupGateway = { getReadiness: vi.fn(async active => ({ ...readiness, scope: active })), searchConfiguration }
    const store = createSetupStore(gateway)
    store.activateScope(scope)

    // Two queries in flight: only the answer to the one the member is waiting for may land.
    const first = store.search('first')
    const second = store.search('second')
    pending[0]?.([hit('p-first')])
    pending[1]?.([hit('p-second')])
    await Promise.all([first, second])
    expect(store.state.searchQuery).toBe('second')
    expect(store.state.searchResults).toEqual([hit('p-second')])

    // A response that arrives after the Team switched describes the previous Team and is dropped.
    const stale = store.search('stale')
    store.activateScope({ ...scope, teamId: 'team-2' })
    pending[2]?.([hit('p-stale')])
    await stale
    expect(store.state.searchResults).toEqual([])
    expect(store.state.searchQuery).toBe('')
    expect(store.state.searchPhase).toBe('idle')
  })
})
