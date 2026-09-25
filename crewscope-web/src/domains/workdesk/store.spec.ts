import { flushPromises } from '@vue/test-utils'
import { createWorkDeskStore } from './store'
import type { WorkDeskGateway } from './gateway'
import type { WorkDeskItem, WorkDeskSummary } from './types'

function deskItem(objectId: string): WorkDeskItem {
  return {
    objectType: 'WORK_ITEM', objectId, projectId: 'project-1', title: `工作 ${objectId}`, status: 'IN_PROGRESS',
    updatedAt: '2026-09-17T09:00:00Z', responsibilityRole: 'OWNER', needsAction: false, urgency: 'NORMAL', progress: 40,
    availableActions: [], route: `/work?item=${objectId}`,
    workItemId: null, workItemTitle: null, rowSummary: null, waitingOn: null,
  }
}
function deskSummary(items: string[], nextCursor: string | null, total = items.length): WorkDeskSummary {
  return {
    organizationId: 'org', teamId: 'team', projectId: null, generatedAt: '2026-09-17T10:00:00Z',
    sections: [
      { key: 'HUMAN_GATE', title: 'HUMAN_GATE', priority: 1, total: 0, truncated: false, items: [], nextCursor: null },
      { key: 'REVIEW', title: 'REVIEW', priority: 2, total: 0, truncated: false, items: [], nextCursor: null },
      { key: 'BLOCKED', title: 'BLOCKED', priority: 3, total: 0, truncated: false, items: [], nextCursor: null },
      { key: 'WORK_ITEM', title: 'WORK_ITEM', priority: 4, total, truncated: false, items: items.map(deskItem), nextCursor },
      { key: 'TASK_EXECUTION', title: 'TASK_EXECUTION', priority: 5, total: 0, truncated: false, items: [], nextCursor: null },
      { key: 'INBOX', title: 'INBOX', priority: 6, total: 0, truncated: false, items: [], nextCursor: null },
    ],
  }
}

describe('WorkDesk store', () => {
  it('loads a scope and exposes an empty state when sections contain no items', async () => {
    const gateway: WorkDeskGateway = { get: vi.fn().mockResolvedValue({ organizationId: 'org', teamId: 'team', projectId: null, generatedAt: new Date().toISOString(), sections: [] }), getSection: vi.fn() }
    const store = createWorkDeskStore(gateway)
    store.activateScope({ organizationId: 'org', teamId: 'team' })
    await store.load()
    await flushPromises()
    expect(store.state.phase).toBe('empty')
    expect(gateway.get).toHaveBeenCalledTimes(1)
  })

  it('ignores a response from a previous scope', async () => {
    let resolve: ((value: any) => void) | undefined
    const gateway: WorkDeskGateway = { get: vi.fn().mockImplementation(() => new Promise(value => { resolve = value })), getSection: vi.fn() }
    const store = createWorkDeskStore(gateway)
    store.activateScope({ organizationId: 'org', teamId: 'old' })
    const pending = store.load()
    store.activateScope({ organizationId: 'org', teamId: 'new' })
    resolve?.({ organizationId: 'org', teamId: 'old', projectId: null, generatedAt: new Date().toISOString(), sections: [] })
    await pending
    expect(store.state.scope?.teamId).toBe('new')
    expect(store.state.summary).toBeNull()
  })

  it('sends the explicit first-screen page size on every load', async () => {
    const get = vi.fn().mockResolvedValue(deskSummary(['item-1'], null))
    const store = createWorkDeskStore({ get, getSection: vi.fn() })
    store.activateScope({ organizationId: 'org', teamId: 'team' })
    await store.load({ projectId: 'project-1' })
    expect(get).toHaveBeenCalledWith(
      { organizationId: 'org', teamId: 'team' },
      { projectId: 'project-1' },
      50,
    )
  })

  it('continues a section from its cursor and merges rows by id', async () => {
    const getSection = vi.fn().mockResolvedValue(
      { key: 'WORK_ITEM', title: 'WORK_ITEM', priority: 4, total: 3, truncated: false, items: [deskItem('item-2'), deskItem('item-1')], nextCursor: null })
    const store = createWorkDeskStore({ get: vi.fn().mockResolvedValue(deskSummary(['item-1'], 'cursor-1', 3)), getSection })
    store.activateScope({ organizationId: 'org', teamId: 'team' })
    await store.load({ projectId: 'project-1' })
    await store.loadMore('WORK_ITEM')
    await flushPromises()

    expect(getSection).toHaveBeenCalledWith(
      { organizationId: 'org', teamId: 'team' },
      'WORK_ITEM',
      { projectId: 'project-1' },
      'cursor-1',
      50,
    )
    const section = store.state.summary?.sections.find(candidate => candidate.key === 'WORK_ITEM')
    // item-1 came back again on the continuation; it must not appear twice.
    expect(section?.items.map(item => item.objectId)).toEqual(['item-1', 'item-2'])
    expect(section?.nextCursor).toBeNull()
    expect(section?.total).toBe(3)
    expect(store.state.phase).toBe('ready')
  })

  it('does not ask for a section whose page already ended', async () => {
    const getSection = vi.fn()
    const store = createWorkDeskStore({ get: vi.fn().mockResolvedValue(deskSummary(['item-1'], null)), getSection })
    store.activateScope({ organizationId: 'org', teamId: 'team' })
    await store.load()
    await store.loadMore('WORK_ITEM')
    expect(getSection).not.toHaveBeenCalled()
    expect(store.state.sectionErrorMessage).toBeNull()
  })

  it('keeps the loaded page and names the section when a continuation fails', async () => {
    const store = createWorkDeskStore({
      get: vi.fn().mockResolvedValue(deskSummary(['item-1'], 'cursor-1')),
      getSection: vi.fn().mockRejectedValue(new Error('section unavailable')),
    })
    store.activateScope({ organizationId: 'org', teamId: 'team' })
    await store.load()
    await store.loadMore('WORK_ITEM')
    await flushPromises()

    expect(store.state.phase).toBe('ready')
    expect(store.state.summary?.sections.find(candidate => candidate.key === 'WORK_ITEM')?.items).toHaveLength(1)
    expect(store.state.sectionErrorMessage).toContain('暂时无法加载该分组的更多内容')
  })
})
