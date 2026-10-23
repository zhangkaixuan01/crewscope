import { flushPromises, mount } from '@vue/test-utils'
import WorkGraphWorkspace from './WorkGraphWorkspace.vue'
import type { WorkGraphGateway } from '../../domains/workgraph/gateway'
import type { BlockedWorkItemRow } from '../../domains/workgraph/types'
import { createWorkGraphStore, WORK_GRAPH_STORE } from '../../domains/workgraph/store'
import { fixtureIds } from '../../test/scopeFixtures'
import { workItemIds } from '../../test/workItemFixtures'

const graphScope = { organizationId: fixtureIds.organization, teamId: fixtureIds.teamPlatform, projectId: fixtureIds.projectCrewScope }

function blockedRow(id: string, blockerCount = 1): BlockedWorkItemRow {
  return {
    workItemId: id,
    itemKey: `CRW-${id.slice(-2)}`,
    title: `工作项 ${id.slice(-2)}`,
    status: 'IN_PROGRESS',
    projectId: fixtureIds.projectCrewScope,
    blockedSince: '2026-10-11T07:00:00Z',
    blockerCount,
    crossesProject: false,
    blockers: [{
      workItemId: workItemIds.second,
      title: '上游甲',
      status: 'READY',
      projectId: fixtureIds.projectCrewScope,
    }],
  }
}

function stubGateway(overrides: Partial<WorkGraphGateway> = {}): WorkGraphGateway {
  return {
    listBlockedWorkItems: vi.fn().mockResolvedValue({ items: [], nextCursor: null }),
    listRelations: vi.fn().mockResolvedValue({ anchor: 'a', edges: [], truncatedByRows: false }),
    listBlockingChain: vi.fn().mockResolvedValue({ anchor: 'a', steps: [], truncatedByDepth: false, truncatedByRows: false }),
    listCriticalPath: vi.fn().mockResolvedValue({ chain: [], truncatedByDepth: false, truncatedByRows: false }),
    createRelation: vi.fn(),
    removeRelation: vi.fn(),
    ...overrides,
  } as WorkGraphGateway
}

function mountWorkspace(store: ReturnType<typeof createWorkGraphStore>, props: Record<string, unknown> = {}) {
  // jsdom has no matchMedia, which the shell reads as the wide form — the phone form is an e2e fact.
  return mount(WorkGraphWorkspace, {
    props: { teamName: '平台组', projectKey: 'CRW', scope: graphScope, ...props },
    global: { provide: { [WORK_GRAPH_STORE as symbol]: store } },
  })
}

describe('WorkGraphWorkspace', () => {
  it('loads the aggregate on mount and paints the interactive graph when it fits', async () => {
    const gateway = stubGateway({
      listBlockedWorkItems: vi.fn().mockResolvedValue({
        items: [blockedRow(workItemIds.first), blockedRow(workItemIds.third)],
        nextCursor: null,
      }),
    })
    const store = createWorkGraphStore(gateway)
    const wrapper = mountWorkspace(store)

    expect(wrapper.text()).toContain('正在加载阻塞关系')
    await flushPromises()

    // Two blocked items + their shared blocker = 3 nodes, 2 edges, picture form.
    expect(gateway.listBlockedWorkItems).toHaveBeenCalledWith(
      { organizationId: graphScope.organizationId, teamId: graphScope.teamId },
      graphScope.projectId,
      undefined,
      100,
      expect.anything(),
    )
    expect(wrapper.find('.graph-canvas').exists()).toBe(true)
    expect(wrapper.text()).toContain('3 个工作项 · 2 条阻塞关系')
    expect(wrapper.find('.graph-hierarchy').exists()).toBe(false)

    // Entering the view force-reloads: the scope is already settled here, so a second mount that
    // still re-reads is the observable proof of the force flag.
    const again = mountWorkspace(store)
    await flushPromises()
    expect(gateway.listBlockedWorkItems).toHaveBeenCalledTimes(2)
    again.unmount()
  })

  it('keeps the collection exits: the header switcher still leaves the graph', async () => {
    const gateway = stubGateway()
    const store = createWorkGraphStore(gateway)
    const wrapper = mountWorkspace(store)
    await flushPromises()

    const switcher = wrapper.get('[aria-label="工作项视图"]')
    await switcher.findAll('button')[0]!.trigger('click')
    expect(wrapper.emitted('switch-view')).toEqual([['list']])
  })

  it('degrades to the hierarchy list beyond 500 nodes and focuses a subgraph from a row', async () => {
    const many: BlockedWorkItemRow[] = Array.from({ length: 501 }, (_, index) =>
      blockedRow(`wi-${String(index).padStart(4, '0')}-000000000000`))
    const gateway = stubGateway({
      listBlockedWorkItems: vi.fn().mockResolvedValue({ items: many, nextCursor: null }),
    })
    const store = createWorkGraphStore(gateway)
    const wrapper = mountWorkspace(store)
    await flushPromises()

    // 501 blocked rows + 1 blocker = 502 nodes > 500: the layered list, with the way back to a graph.
    expect(wrapper.find('.graph-canvas').exists()).toBe(false)
    expect(wrapper.find('.graph-hierarchy').exists()).toBe(true)
    expect(wrapper.text()).toContain('图超过 500 个工作项')

    await wrapper.get('.graph-hierarchy button').trigger('click')
    expect(wrapper.find('.graph-canvas').exists()).toBe(true)
    expect(wrapper.text()).toContain('返回全图')
  })

  it('says out loud when the aggregate was capped by the hard page stop', async () => {
    const gateway = stubGateway({
      listBlockedWorkItems: vi.fn().mockImplementation(async () => ({
        items: [blockedRow(workItemIds.first)],
        nextCursor: 'always-more',
      })),
    })
    const store = createWorkGraphStore(gateway)
    const wrapper = mountWorkspace(store)
    await flushPromises()

    expect(wrapper.text()).toContain('图规模已达读取上限')
  })

  it('opens the selected node\'s facts and hands the item to the page', async () => {
    const gateway = stubGateway({
      listBlockedWorkItems: vi.fn().mockResolvedValue({
        items: [blockedRow(workItemIds.first)],
        nextCursor: null,
      }),
    })
    const store = createWorkGraphStore(gateway)
    const wrapper = mountWorkspace(store)
    await flushPromises()

    await wrapper.get('.graph-node').trigger('click')
    const detail = wrapper.get('.graph-workspace__detail')
    expect(detail.text()).toContain('被 1 项阻塞')
    expect(detail.text()).toContain('上游甲')

    await detail.get('button').trigger('click')
    expect(wrapper.emitted('open-item')).toEqual([[workItemIds.first]])
  })

  it('surfaces the aggregate error with a retry instead of a blank pane', async () => {
    const gateway = stubGateway({
      listBlockedWorkItems: vi.fn()
        .mockRejectedValueOnce(new Error('network down'))
        .mockResolvedValue({ items: [blockedRow(workItemIds.first)], nextCursor: null }),
    })
    const store = createWorkGraphStore(gateway)
    const wrapper = mountWorkspace(store)
    await flushPromises()

    expect(wrapper.text()).toContain('暂时无法加载阻塞关系')
    const retry = wrapper.findAll('button').find(button => button.text() === '刷新事实')
    expect(retry).toBeDefined()
    await retry!.trigger('click')
    await flushPromises()
    expect(wrapper.find('.graph-canvas').exists()).toBe(true)
  })

  // M13b-F05（R34）: the collection views' blocked badge deep-links in with ?focus=<id> — the
  // workspace lands focused on that node's neighbourhood, and exiting focus reports back so the
  // URL never claims a focus the graph no longer has.
  it('lands focused on the focusId deep link and reports focus exits (R34)', async () => {
    const gateway = stubGateway({
      listBlockedWorkItems: vi.fn().mockResolvedValue({
        items: [blockedRow(workItemIds.first), blockedRow(workItemIds.third)],
        nextCursor: null,
      }),
    })
    const store = createWorkGraphStore(gateway)
    const wrapper = mountWorkspace(store, { focusId: workItemIds.first })
    await flushPromises()

    expect(wrapper.text()).toContain('返回全图')
    expect(wrapper.emitted('update:focus')).toBeUndefined()

    const exitFocus = wrapper.findAll('button').find(button => button.text() === '返回全图')
    expect(exitFocus).toBeDefined()
    await exitFocus!.trigger('click')
    expect(wrapper.emitted('update:focus')).toEqual([[null]])
  })
})
