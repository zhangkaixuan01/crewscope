import { createSearchStore } from './store'
import type { SearchGateway } from './gateway'

describe('createSearchStore', () => {
  it('appends a cursor page to the active query', async () => {
    const item = (id: string) => ({ objectType: 'AGENT' as const, objectId: id, projectId: null, title: id, subtitle: null, status: 'ACTIVE', updatedAt: '2026-09-13T00:00:00Z', route: '/settings/agents', snippet: null })
    const gateway: SearchGateway = { search: vi.fn()
      .mockResolvedValueOnce({ items: [item('first')], nextCursor: 'next' })
      .mockResolvedValueOnce({ items: [item('second')], nextCursor: null }) }
    const store = createSearchStore(gateway)
    store.activateScope({ organizationId: 'org', teamId: 'team' })
    await store.search({ text: 'Agent' })
    await store.search({ text: 'Agent', after: 'next' })
    expect(store.state.result?.items.map(value => value.objectId)).toEqual(['first', 'second'])
  })

  it('appends cursor pages and ignores a response after a scope switch', async () => {
    let resolveFirst!: (value: { items: [], nextCursor: string | null }) => void
    const first = new Promise<{ items: [], nextCursor: string | null }>(resolve => { resolveFirst = resolve })
    const gateway: SearchGateway = { search: vi.fn().mockReturnValueOnce(first).mockResolvedValueOnce({ items: [{ objectType: 'AGENT', objectId: 'a', projectId: null, title: 'Agent', subtitle: null, status: 'ACTIVE', updatedAt: '2026-09-13T00:00:00Z', route: '/settings/agents', snippet: null }], nextCursor: null }) as SearchGateway['search'] }
    const store = createSearchStore(gateway)
    store.activateScope({ organizationId: 'org', teamId: 'team-1' })
    const request = store.search({ text: 'Agent' })
    store.activateScope({ organizationId: 'org', teamId: 'team-2' })
    resolveFirst({ items: [], nextCursor: null })
    await request
    expect(store.state.scope?.teamId).toBe('team-2')
    expect(store.state.result).toBeNull()
  })
})
