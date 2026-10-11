import { mount } from '@vue/test-utils'
import WorkGraphCanvas from './WorkGraphCanvas.vue'
import type { GraphEdge, GraphNode } from '../../domains/workgraph/types'

function node(id: string, overrides: Partial<GraphNode> = {}): GraphNode {
  return {
    id, itemKey: `DEMO-${id}`, title: `工作项${id}`, status: 'IN_PROGRESS', projectId: 'project-1',
    blockedCount: 0, ...overrides,
  }
}

const nodes = [
  node('a', { status: 'DONE' }),
  node('b', { blockedCount: 1 }),
]
const edges: GraphEdge[] = [{ blockerId: 'a', blockedId: 'b' }]

function mountCanvas(overrides: Record<string, unknown> = {}) {
  return mount(WorkGraphCanvas, { props: { nodes, edges, selectedId: null, ...overrides } })
}

describe('WorkGraphCanvas', () => {
  it('renders one focusable node per work item, stating every fact the picture shows', () => {
    const wrapper = mountCanvas()
    const groups = wrapper.findAll('.graph-node')
    expect(groups).toHaveLength(2)
    // The sr-only list is the non-graph equivalent of the canvas: same facts, one line per node.
    const list = wrapper.get('[aria-label="依赖图节点清单"]')
    expect(list.text()).toContain('工作项a,已完成')
    expect(list.text()).toContain('工作项b,进行中,被 1 项阻塞')
    // Nodes are keyboard-operable buttons — Enter selects, the picture never is the only way in.
    expect(groups[0]!.attributes('role')).toBe('button')
    expect(groups[0]!.attributes('aria-label')).toContain('工作项a')
    // Text layout: the title and meta rows keep their left padding, and the count badge rides
    // the meta row's right edge instead of crowding the title row.
    expect(wrapper.get('.graph-node__title').attributes('x')).toBe('14')
    const count = wrapper.get('.graph-node__count')
    expect(count.attributes('y')).toBe('46')
    expect(count.attributes('text-anchor')).toBe('end')
  })

  it('draws one oriented edge per BLOCKS relation', () => {
    const wrapper = mountCanvas()
    expect(wrapper.findAll('.graph-edge')).toHaveLength(1)
    expect(wrapper.get('svg').attributes('aria-label')).toContain('2 个工作项、1 条阻塞关系')
  })

  it('selects by click and by Enter, and only the selected node carries the state', async () => {
    const wrapper = mountCanvas()
    await wrapper.findAll('.graph-node')[1]!.trigger('click')
    expect(wrapper.emitted('select')).toEqual([['b']])

    await wrapper.findAll('.graph-node')[0]!.trigger('keydown', { key: 'Enter' })
    expect(wrapper.emitted('select')).toEqual([['b'], ['a']])

    const marked = mountCanvas({ selectedId: 'b' })
    expect(marked.findAll('.graph-node')[1]!.classes()).toContain('graph-node--selected')
  })

  it('steps zoom by keyboard (+/−/0) and reports the factor as text', async () => {
    const wrapper = mountCanvas()
    expect(wrapper.get('.graph-canvas__zoom').text()).toBe('100%')

    await wrapper.get('.graph-canvas').trigger('keydown', { key: '+' })
    expect(wrapper.get('.graph-canvas__zoom').text()).toBe('110%')

    await wrapper.get('.graph-canvas').trigger('keydown', { key: '-' })
    await wrapper.get('.graph-canvas').trigger('keydown', { key: '-' })
    expect(wrapper.get('.graph-canvas__zoom').text()).toBe('90%')

    await wrapper.get('.graph-canvas').trigger('keydown', { key: '0' })
    expect(wrapper.get('.graph-canvas__zoom').text()).toBe('100%')
  })

  it('keeps the zoom factor clamped to the ADR-033 band', async () => {
    const wrapper = mountCanvas()
    for (let step = 0; step < 30; step += 1) await wrapper.get('.graph-canvas').trigger('keydown', { key: '+' })
    expect(wrapper.get('.graph-canvas__zoom').text()).toBe('250%')
    for (let step = 0; step < 40; step += 1) await wrapper.get('.graph-canvas').trigger('keydown', { key: '-' })
    expect(wrapper.get('.graph-canvas__zoom').text()).toBe('50%')
  })
})
