import { createSetupStore } from './store'
import type { SetupGateway } from './gateway'

const scope = { organizationId: 'org-1', teamId: 'team-1' }
const readiness = { scope, snapshotVersion: 'v1', observedAt: '2026-09-01T00:00:00Z', requiredReady: true, capabilities: [] }

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
})
