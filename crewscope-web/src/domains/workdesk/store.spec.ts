import { flushPromises } from '@vue/test-utils'
import { createWorkDeskStore } from './store'
import type { WorkDeskGateway } from './gateway'

describe('WorkDesk store', () => {
  it('loads a scope and exposes an empty state when sections contain no items', async () => {
    const gateway: WorkDeskGateway = { get: vi.fn().mockResolvedValue({ organizationId: 'org', teamId: 'team', projectId: null, generatedAt: new Date().toISOString(), sections: [] }) }
    const store = createWorkDeskStore(gateway)
    store.activateScope({ organizationId: 'org', teamId: 'team' })
    await store.load()
    await flushPromises()
    expect(store.state.phase).toBe('empty')
    expect(gateway.get).toHaveBeenCalledTimes(1)
  })

  it('ignores a response from a previous scope', async () => {
    let resolve: ((value: any) => void) | undefined
    const gateway: WorkDeskGateway = { get: vi.fn().mockImplementation(() => new Promise(value => { resolve = value })) }
    const store = createWorkDeskStore(gateway)
    store.activateScope({ organizationId: 'org', teamId: 'old' })
    const pending = store.load()
    store.activateScope({ organizationId: 'org', teamId: 'new' })
    resolve?.({ organizationId: 'org', teamId: 'old', projectId: null, generatedAt: new Date().toISOString(), sections: [] })
    await pending
    expect(store.state.scope?.teamId).toBe('new')
    expect(store.state.summary).toBeNull()
  })
})
