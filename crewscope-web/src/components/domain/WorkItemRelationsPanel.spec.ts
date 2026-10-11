import { flushPromises, mount } from '@vue/test-utils'
import WorkItemRelationsPanel from './WorkItemRelationsPanel.vue'
import type { WorkGraphGateway } from '../../domains/workgraph/gateway'
import type { WorkItemRelationEdge } from '../../domains/workgraph/types'
import { createWorkGraphStore, WORK_GRAPH_STORE } from '../../domains/workgraph/store'
import { WORK_ITEM_STORE } from '../../domains/workitem/store'
import type { WorkItemStore } from '../../domains/workitem/store'
import type { WorkItemSummary } from '../../domains/workitem/types'
import { fixtureIds } from '../../test/scopeFixtures'
import { workItemIds } from '../../test/workItemFixtures'

const principalScope = { organizationId: fixtureIds.organization, teamId: fixtureIds.teamPlatform }

const self: WorkItemSummary = {
  id: workItemIds.first, organizationId: fixtureIds.organization, teamId: fixtureIds.teamPlatform,
  workspaceId: 'workspace-1', projectId: fixtureIds.projectCrewScope,
  key: 'CRW-01', title: '当前工作项', description: null, type: 'TASK', status: 'IN_PROGRESS',
  priority: 'HIGH', labels: [], dueAt: null, source: 'MANUAL', sourceReference: null, version: 1,
  createdAt: '2026-10-11T07:00:00Z', createdByPrincipalId: null,
  updatedAt: '2026-10-11T07:00:00Z', updatedByPrincipalId: null, availableActions: [], summary: null,
}
const other: WorkItemSummary = { ...self, id: workItemIds.second, key: 'CRW-02', title: '上游乙', status: 'READY' }
const third: WorkItemSummary = { ...self, id: workItemIds.third, key: 'CRW-03', title: '下游丙', status: 'BACKLOG' }

function incomingEdge(relationId: string, fromId: string, toId: string, crossProject = false): WorkItemRelationEdge {
  return {
    relationId,
    direction: 'INCOMING',
    otherWorkItemId: fromId,
    otherProjectId: fixtureIds.projectCrewScope,
    crossProject,
    views: [{ type: 'BLOCKS', fromWorkItemId: fromId, toWorkItemId: toId }],
    createdAt: '2026-10-11T07:00:00Z',
  }
}

function stubGateway(edges: WorkItemRelationEdge[]) {
  const gateway = {
    listBlockedWorkItems: vi.fn().mockResolvedValue({ items: [], nextCursor: null }),
    listRelations: vi.fn().mockResolvedValue({ anchor: self.id, edges, truncatedByRows: false }),
    listBlockingChain: vi.fn().mockResolvedValue({ anchor: 'a', steps: [], truncatedByDepth: false, truncatedByRows: false }),
    listCriticalPath: vi.fn().mockResolvedValue({ chain: [], truncatedByDepth: false, truncatedByRows: false }),
    createRelation: vi.fn().mockResolvedValue({ commandId: 'c', domainEventId: 'e', committedVersion: 0, correlationId: 'x' }),
    removeRelation: vi.fn().mockResolvedValue({ commandId: 'c', domainEventId: 'e', committedVersion: 0, correlationId: 'x' }),
  }
  return gateway as unknown as WorkGraphGateway & typeof gateway
}

function mountPanel(gateway: ReturnType<typeof stubGateway>, overrides: Record<string, unknown> = {}) {
  const graphStore = createWorkGraphStore(gateway)
  const workItemStore = { state: { items: [self, other, third] } } as unknown as WorkItemStore
  return mount(WorkItemRelationsPanel, {
    props: { scope: principalScope, item: self, canParticipate: true, ...overrides },
    global: { provide: { [WORK_GRAPH_STORE as symbol]: graphStore, [WORK_ITEM_STORE as symbol]: workItemStore } },
  })
}

describe('WorkItemRelationsPanel', () => {
  it('renders its states without any store behind it', () => {
    // Standalone mounts (drawer specs) must not explode: no store means no facts and no actions taken.
    const wrapper = mount(WorkItemRelationsPanel, {
      props: { scope: principalScope, item: self, canParticipate: false },
    })
    expect(wrapper.text()).toContain('阻塞与拆分')
    expect(wrapper.text()).toContain('还没有阻塞或拆分关系')
  })

  it('says each stored edge as one sentence, marking the cross-project fact', async () => {
    const gateway = stubGateway([
      incomingEdge('rel-1', workItemIds.second, self.id),
      { ...incomingEdge('rel-2', 'wi-other-project', self.id, true) },
    ])
    const wrapper = mountPanel(gateway)
    await flushPromises()

    const sentences = wrapper.findAll('.relations-list li')
    expect(sentences).toHaveLength(2)
    expect(sentences[0]!.text()).toContain('「上游乙」阻塞本项')
    expect(sentences[1]!.text()).toContain('（跨项目）')
  })

  it('submits the default sentence as BLOCKS from the chosen item to this one', async () => {
    const gateway = stubGateway([])
    const wrapper = mountPanel(gateway)
    await flushPromises()

    // rel-1's other end (上游乙) is excluded as an existing edge; 下游丙 remains.
    const selects = wrapper.findAll('select')
    await selects[0]!.setValue(workItemIds.third)
    await wrapper.get('form').trigger('submit')

    expect(gateway.createRelation).toHaveBeenCalledWith(
      principalScope,
      { type: 'BLOCKS', fromWorkItemId: workItemIds.third, toWorkItemId: self.id },
      expect.any(String),
    )
  })

  it('submits the outgoing sentence flipped: this item blocks the chosen one', async () => {
    const gateway = stubGateway([])
    const wrapper = mountPanel(gateway)
    await flushPromises()

    const selects = wrapper.findAll('select')
    await selects[0]!.setValue(workItemIds.third)
    await selects[1]!.setValue('outgoingBlocker')
    await wrapper.get('form').trigger('submit')

    expect(gateway.createRelation).toHaveBeenCalledWith(
      principalScope,
      { type: 'BLOCKS', fromWorkItemId: self.id, toWorkItemId: workItemIds.third },
      expect.any(String),
    )
  })

  it('submits split-from toward the chosen source', async () => {
    const gateway = stubGateway([])
    const wrapper = mountPanel(gateway)
    await flushPromises()

    const selects = wrapper.findAll('select')
    await selects[0]!.setValue(workItemIds.third)
    await selects[1]!.setValue('splitFromSource')
    await wrapper.get('form').trigger('submit')

    expect(gateway.createRelation).toHaveBeenCalledWith(
      principalScope,
      { type: 'SPLIT_FROM', fromWorkItemId: self.id, toWorkItemId: workItemIds.third },
      expect.any(String),
    )
  })

  it('short-circuits a second split source instead of walking into the guaranteed 409', async () => {
    const gateway = stubGateway([
      { relationId: 'rel-split', direction: 'OUTGOING', otherWorkItemId: workItemIds.second,
        otherProjectId: fixtureIds.projectCrewScope, crossProject: false,
        views: [
          { type: 'SPLIT_FROM', fromWorkItemId: self.id, toWorkItemId: workItemIds.second },
          { type: 'SPLIT_FROM', fromWorkItemId: workItemIds.second, toWorkItemId: self.id },
        ],
        createdAt: '2026-10-11T07:00:00Z' },
    ])
    const wrapper = mountPanel(gateway)
    await flushPromises()

    const splitOption = wrapper.findAll('option').find(option => option.attributes('value') === 'splitFromSource')
    expect(splitOption!.attributes('disabled')).toBeDefined()
    expect(splitOption!.text()).toContain('本项已有拆分来源')
  })

  it('removes only on the second click of the same row', async () => {
    const gateway = stubGateway([incomingEdge('rel-1', workItemIds.second, self.id)])
    const wrapper = mountPanel(gateway)
    await flushPromises()

    const remove = wrapper.get('.relations-remove')
    expect(remove.text()).toBe('移除')
    await remove.trigger('click')
    expect(gateway.removeRelation).not.toHaveBeenCalled()
    expect(wrapper.get('.relations-remove').text()).toBe('确认移除')

    await wrapper.get('.relations-remove').trigger('click')
    expect(gateway.removeRelation).toHaveBeenCalledWith(principalScope, 'rel-1', expect.any(String))
  })

  it('offers no write actions to a member who may not participate', async () => {
    const gateway = stubGateway([incomingEdge('rel-1', workItemIds.second, self.id)])
    const wrapper = mountPanel(gateway, { canParticipate: false })
    await flushPromises()

    expect(wrapper.find('form').exists()).toBe(false)
    expect(wrapper.find('.relations-remove').exists()).toBe(false)
    expect(wrapper.text()).toContain('「上游乙」阻塞本项')
  })
})
