import { createApp } from 'vue'
import { createSearchStore, installPaletteSearchStore, installSearchStore, PALETTE_SEARCH_STORE, SEARCH_STORE, useSearchStore } from './store'
import type { SearchGateway } from './gateway'

describe('createSearchStore', () => {
  it('clearing input retires a pending response, including its late error', async () => {
    let reject!: (error: Error) => void
    const gateway = { search: vi.fn(() => new Promise<never>((_yes, no) => { reject = no })) }
    const store = createSearchStore(gateway)
    store.activateScope({ organizationId: 'org', teamId: 'A' })
    const old = store.search({ text: 'old' })
    await store.search({ text: '' })
    reject(new Error('late'))
    await old
    expect(store.state.phase).toBe('idle')
    expect(store.state.errorMessage).toBeNull()
  })

  it('rejects the first A result after A → B → A in the same Team', async () => {
    let resolve!: (value: { items: [], nextCursor: string | null }) => void
    const gateway: SearchGateway = { search: vi.fn().mockImplementationOnce(() => new Promise(yes => { resolve = yes }))
      .mockResolvedValue({ items: [], nextCursor: null }) }
    const store = createSearchStore(gateway)
    store.activateScope({ organizationId: 'org', teamId: 'A' })
    const old = store.search({ text: 'A' })
    await store.search({ text: 'B' })
    await store.search({ text: 'A' })
    resolve({ items: [], nextCursor: 'stale' })
    await old
    expect(store.state.result?.nextCursor).toBeNull()
  })
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

  it('keeps the palette session separate: opening and clearing it never touches the page results', async () => {
    const item = { objectType: 'AGENT' as const, objectId: 'agent-1', projectId: null, title: '发布 Agent', subtitle: null, status: 'ACTIVE', updatedAt: '2026-09-26T00:00:00Z', route: '/settings/agents', snippet: null }
    const gateway: SearchGateway = { search: vi.fn().mockResolvedValue({ items: [item], nextCursor: null }) }
    const pageStore = createSearchStore(gateway)
    const paletteStore = createSearchStore(gateway)
    pageStore.activateScope({ organizationId: 'org', teamId: 'team' })
    paletteStore.activateScope({ organizationId: 'org', teamId: 'team' })
    await pageStore.search({ text: '发布' })
    expect(pageStore.state.phase).toBe('ready')
    // The palette opens with a blank query: that clears only its own session.
    await paletteStore.search({ text: '' })
    expect(pageStore.state.result?.items).toHaveLength(1)
    expect(pageStore.state.phase).toBe('ready')
    expect(paletteStore.state.phase).toBe('idle')
    expect(paletteStore.state.result).toBeNull()
  })

  it('installs the page and palette stores under separate injection keys', () => {
    const gateway: SearchGateway = { search: vi.fn() }
    const app = createApp({ render: () => null })
    const pageStore = installSearchStore(app, gateway)
    const paletteStore = installPaletteSearchStore(app, gateway)
    expect(app.runWithContext(() => useSearchStore())).toBe(pageStore)
    expect(app.runWithContext(() => PALETTE_SEARCH_STORE !== SEARCH_STORE)).toBe(true)
    expect(app.runWithContext(() => paletteStore)).toBe(paletteStore)
  })
})
