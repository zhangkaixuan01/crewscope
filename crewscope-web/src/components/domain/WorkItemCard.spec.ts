import { mount } from '@vue/test-utils'
import { fixtureWorkItems } from '../../test/workItemFixtures'
import WorkItemCard from './WorkItemCard.vue'

describe('WorkItemCard', () => {
  it('shares WorkItem identity and metadata across list and board layouts', async () => {
    const wrapper = mount(WorkItemCard, { props: { item: fixtureWorkItems[0]!, layout: 'board' } })

    expect(wrapper.classes()).toContain('work-item-card--board')
    expect(wrapper.text()).toContain('CRW-18')
    expect(wrapper.text()).toContain('IN_PROGRESS')
    expect(wrapper.text()).toContain('HIGH')
    await wrapper.get('button').trigger('click')
    expect(wrapper.emitted('select')?.[0]?.[0]).toEqual(fixtureWorkItems[0])
  })

  it('exposes pointer drag facts only in board layout', async () => {
    const board = mount(WorkItemCard, { props: { item: fixtureWorkItems[0]!, layout: 'board' } })
    await board.get('article').trigger('dragstart')
    await board.get('article').trigger('dragend')
    expect(board.emitted('drag-start')?.[0]?.[0]).toEqual(fixtureWorkItems[0])
    expect(board.emitted('drag-end')).toHaveLength(1)

    const list = mount(WorkItemCard, { props: { item: fixtureWorkItems[0]!, layout: 'list' } })
    expect(list.attributes('draggable')).toBe('false')
  })
})
