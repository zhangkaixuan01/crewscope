// @vitest-environment node
import { CrewScopeApiError } from '../../api/client'
import { fixtureIds } from '../../test/scopeFixtures'
import { workItemIds } from '../../test/workItemFixtures'
import type { WorkGraphGateway } from './gateway'
import type { BlockedWorkItemRow } from './types'
import { createWorkGraphStore } from './store'

const commandScope = { organizationId: fixtureIds.organization, teamId: fixtureIds.teamPlatform }
const queryScope = { ...commandScope, projectId: fixtureIds.projectCrewScope }

describe('createWorkGraphStore', () => {
  it('accumulates cursor pages into nodes and blocker→blocked edges', async () => {
    const gateway = stubGateway({
      listBlockedWorkItems: vi.fn()
        .mockResolvedValueOnce(page([blockedRow(workItemIds.first, 1, [workItemIds.second])], 'cursor-2'))
        .mockResolvedValueOnce(page([blockedRow(workItemIds.third, 2, [workItemIds.first, workItemIds.second])], null)),
    })
    const store = createWorkGraphStore(gateway)

    await store.loadBlockedGraph(commandScope)

    expect(store.state.blocked.phase).toBe('ready')
    expect(store.state.blocked.pagesLoaded).toBe(2)
    expect(store.state.blocked.capped).toBe(false)
    expect(gateway.listBlockedWorkItems).toHaveBeenCalledTimes(2)
    expect(store.state.nodes.size).toBe(3)
    expect(store.blockedCountOf(workItemIds.third)).toBe(2)
    expect(store.blockedCountOf(workItemIds.second)).toBe(0)
    expect([...store.state.edges.values()]).toEqual([
      { blockerId: workItemIds.second, blockedId: workItemIds.first },
      { blockerId: workItemIds.first, blockedId: workItemIds.third },
      { blockerId: workItemIds.second, blockedId: workItemIds.third },
    ])
  })

  it('stops on the hard page cap and reports capped instead of chasing cursors forever', async () => {
    const gateway = stubGateway({
      listBlockedWorkItems: vi.fn().mockImplementation(async () => page([blockedRow(workItemIds.first)], 'always-more')),
    })
    const store = createWorkGraphStore(gateway)

    await store.loadBlockedGraph(commandScope)

    expect(gateway.listBlockedWorkItems).toHaveBeenCalledTimes(10)
    expect(store.state.blocked.pagesLoaded).toBe(10)
    expect(store.state.blocked.capped).toBe(true)
  })

  it('reports a truncated blocker window rather than showing it as the whole truth', async () => {
    const gateway = stubGateway({
      listBlockedWorkItems: vi.fn()
        .mockResolvedValue(page([blockedRow(workItemIds.first, 3, [workItemIds.second])], null)),
    })
    const store = createWorkGraphStore(gateway)

    await store.loadBlockedGraph(commandScope)

    // blockerCount 3 > window 1: the badge still says 3, the window fact is flagged.
    expect(store.state.blocked.windowTruncated).toBe(true)
    expect(store.blockedCountOf(workItemIds.first)).toBe(3)
  })

  it('skips a settled scope and force re-reads on demand', async () => {
    const gateway = stubGateway({
      listBlockedWorkItems: vi.fn().mockResolvedValue(page([blockedRow(workItemIds.first)], null)),
    })
    const store = createWorkGraphStore(gateway)

    await store.loadBlockedGraph(commandScope)
    await store.loadBlockedGraph(commandScope)
    expect(gateway.listBlockedWorkItems).toHaveBeenCalledTimes(1)
    await store.loadBlockedGraph(commandScope, undefined, true)
    expect(gateway.listBlockedWorkItems).toHaveBeenCalledTimes(2)
  })

  it('maps a 409 duplicate onto actionable copy in the command state', async () => {
    const gateway = stubGateway({
      createRelation: vi.fn().mockRejectedValue(apiError(409, 'work_item_relation_duplicate', 'raw server message')),
      listBlockedWorkItems: vi.fn().mockResolvedValue(page([], null)),
    })
    const store = createWorkGraphStore(gateway)

    const receipt = await store.createRelation(
      commandScope,
      { type: 'BLOCKS', fromWorkItemId: workItemIds.first, toWorkItemId: workItemIds.second },
      'idem-1',
    )

    expect(receipt).toBeNull()
    expect(store.state.command.errorMessage).toBe('两个工作项之间已存在同方向的阻塞关系。')
    expect(store.state.command.errorStatus).toBe(409)
  })

  it('re-reads both the anchor facts and the blocked aggregate after an accepted command', async () => {
    const receipt = { commandId: 'c', domainEventId: 'e', committedVersion: 0, correlationId: 'x' }
    const gateway = stubGateway({
      createRelation: vi.fn().mockResolvedValue(receipt),
      listBlockedWorkItems: vi.fn().mockResolvedValue(page([blockedRow(workItemIds.first)], null)),
      listRelations: vi.fn().mockResolvedValue({ anchor: workItemIds.first, edges: [], truncatedByRows: false }),
    })
    const store = createWorkGraphStore(gateway)
    await store.loadBlockedGraph(commandScope)
    await store.loadRelations(queryScope, workItemIds.first)
    vi.mocked(gateway.listBlockedWorkItems).mockClear()
    vi.mocked(gateway.listRelations).mockClear()

    await store.createRelation(
      commandScope,
      { type: 'BLOCKS', fromWorkItemId: workItemIds.second, toWorkItemId: workItemIds.first },
      'idem-2',
    )

    expect(gateway.listBlockedWorkItems).toHaveBeenCalledWith(commandScope, undefined, undefined, 100, expect.anything())
    expect(gateway.listRelations).toHaveBeenCalledWith(queryScope, workItemIds.first, expect.anything())
  })

  it('resets everything and clears command intents', async () => {
    const gateway = stubGateway({
      listBlockedWorkItems: vi.fn().mockResolvedValue(page([blockedRow(workItemIds.first)], null)),
    })
    const store = createWorkGraphStore(gateway)
    await store.loadBlockedGraph(commandScope)

    store.reset()

    expect(store.state.blocked.phase).toBe('idle')
    expect(store.state.nodes.size).toBe(0)
    expect(store.state.edges.size).toBe(0)
    expect(store.blockedCountOf(workItemIds.first)).toBe(0)
    expect(store.exceedsNodeLimit()).toBe(false)
    await store.loadBlockedGraph(commandScope)
    expect(gateway.listBlockedWorkItems).toHaveBeenCalledTimes(2)
  })
})

function stubGateway(overrides: Partial<WorkGraphGateway>): WorkGraphGateway {
  return {
    listBlockedWorkItems: vi.fn().mockResolvedValue(page([], null)),
    listRelations: vi.fn().mockResolvedValue({ anchor: 'a', edges: [], truncatedByRows: false }),
    listBlockingChain: vi.fn().mockResolvedValue({ anchor: 'a', steps: [], truncatedByDepth: false, truncatedByRows: false }),
    listCriticalPath: vi.fn().mockResolvedValue({ chain: [], truncatedByDepth: false, truncatedByRows: false }),
    createRelation: vi.fn().mockResolvedValue({ commandId: 'c', domainEventId: 'e', committedVersion: 0, correlationId: 'x' }),
    removeRelation: vi.fn().mockResolvedValue({ commandId: 'c', domainEventId: 'e', committedVersion: 0, correlationId: 'x' }),
    ...overrides,
  } as WorkGraphGateway
}

function blockedRow(id: string, blockerCount = 1, blockerIds: string[] = [workItemIds.second]): BlockedWorkItemRow {
  return {
    workItemId: id,
    itemKey: `CRW-${id.slice(-2)}`,
    title: `工作项 ${id.slice(-2)}`,
    status: 'IN_PROGRESS',
    projectId: fixtureIds.projectCrewScope,
    blockedSince: '2026-10-11T07:00:00Z',
    blockerCount,
    crossesProject: false,
    blockers: blockerIds.map(blockerId => ({
      workItemId: blockerId,
      title: `工作项 ${blockerId.slice(-2)}`,
      status: 'READY',
      projectId: fixtureIds.projectCrewScope,
    })),
  }
}

function page(items: BlockedWorkItemRow[], nextCursor: string | null) {
  return { items, nextCursor }
}

function apiError(status: number, code: string, message: string) {
  return new CrewScopeApiError(status, {
    code, message, correlationId: 'x', retryable: false, currentVersion: null, details: {},
  })
}