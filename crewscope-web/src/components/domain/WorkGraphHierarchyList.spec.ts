import { mount } from '@vue/test-utils'
import WorkGraphHierarchyList from './WorkGraphHierarchyList.vue'
import type { GraphNode } from '../../domains/workgraph/types'
import type { HierarchyGroup } from '../../domains/workgraph/layout'

function node(id: string, overrides: Partial<GraphNode> = {}): GraphNode {
  return {
    id, itemKey: `DEMO-${id}`, title: `工作项${id}`, status: 'READY', projectId: 'project-1',
    blockedCount: 0, ...overrides,
  }
}

const groups: HierarchyGroup[] = [
  { roots: ['上游甲'], nodes: [node('a'), node('b', { blockedCount: 2, status: 'IN_PROGRESS' })] },
  { roots: [], nodes: [node('c')] },
]

describe('WorkGraphHierarchyList', () => {
  it('names each group by its root blockers and says how many items hang there', () => {
    const wrapper = mount(WorkGraphHierarchyList, { props: { groups, selectedId: null } })
    const headings = wrapper.findAll('h4')
    expect(headings).toHaveLength(2)
    expect(headings[0]!.text()).toContain('阻塞链:上游甲')
    expect(headings[0]!.text()).toContain('2')
    expect(headings[1]!.text()).toContain('独立工作项')
  })

  it('states the blocked fact on the row, not just the picture colour', () => {
    const wrapper = mount(WorkGraphHierarchyList, { props: { groups, selectedId: null } })
    const rows = wrapper.findAll('button')
    expect(rows[1]!.text()).toContain('被 2 项阻塞')
    expect(rows[0]!.text()).not.toContain('被')
    expect(rows[1]!.text()).toContain('进行中')
  })

  it('marks the selected row and focuses it on activation', async () => {
    const wrapper = mount(WorkGraphHierarchyList, { props: { groups, selectedId: 'b' } })
    const selected = wrapper.findAll('button')[1]!
    expect(selected.attributes('aria-pressed')).toBe('true')

    await wrapper.findAll('button')[0]!.trigger('click')
    expect(wrapper.emitted('focus')).toEqual([['a']])
  })
})
