import { mount } from '@vue/test-utils'
import { fixtureWorkItems } from '../../test/workItemFixtures'
import WorkItemCard from './WorkItemCard.vue'
import type { WorkItemAvailableTransition } from '../../domains/workitem/types'

const action: WorkItemAvailableTransition = {
  actionId: 'complete', targetStatus: 'DONE', label: '标记完成', strength: 'PRIMARY',
  reversible: false, enabled: true, reason: null, reasonMessage: null, remedyLabel: null, remedyRoute: null,
}

const item = { ...fixtureWorkItems[0]!, availableActions: [action] }

describe('WorkItemCard', () => {
  it('shares WorkItem identity and metadata across list and board layouts', async () => {
    const wrapper = mount(WorkItemCard, { props: { item, layout: 'board' } })

    expect(wrapper.classes()).toContain('work-item-card--board')
    expect(wrapper.text()).toContain('CRW-18')
    expect(wrapper.text()).toContain('进行中')
    expect(wrapper.text()).toContain('高')
    expect(wrapper.text()).not.toContain('IN_PROGRESS')
    await wrapper.get('.work-item-card__open').trigger('click')
    expect(wrapper.emitted('select')?.[0]?.[0]).toEqual(item)
  })

  it('exposes pointer drag facts only in board layout', async () => {
    const board = mount(WorkItemCard, { props: { item, layout: 'board' } })
    await board.get('article').trigger('dragstart')
    await board.get('article').trigger('dragend')
    expect(board.emitted('drag-start')?.[0]?.[0]).toEqual(item)
    expect(board.emitted('drag-end')).toHaveLength(1)

    const list = mount(WorkItemCard, { props: { item, layout: 'list' } })
    expect(list.attributes('draggable')).toBe('false')
  })

  it('opens the item from anywhere on the card, not only from its title', async () => {
    // The open affordance is a button, so a click on the surrounding padding would otherwise do
    // nothing — the card would look clickable and not be.
    const wrapper = mount(WorkItemCard, { props: { item, layout: 'board' } })
    await wrapper.get('.work-item-card__metadata').trigger('click')
    expect(wrapper.emitted('select')).toHaveLength(1)
  })

  it('does not open the item when the click was meant for the action trigger', async () => {
    const wrapper = mount(WorkItemCard, { props: { item, layout: 'board' } })
    await wrapper.get('.transition-menu button').trigger('click')

    expect(wrapper.emitted('select')).toBeUndefined()
    expect(wrapper.find('[role="menu"]').exists()).toBe(true)
  })

  it('carries the action up as the action, not as a status of its own choosing', async () => {
    // The card does not translate a status into an action; it forwards exactly what the server
    // offered, so a command the server did not send cannot be reached from here.
    const wrapper = mount(WorkItemCard, { props: { item, layout: 'list' } })
    await wrapper.get('.transition-menu button').trigger('click')
    await wrapper.get('[role="menuitem"]').trigger('click')

    expect(wrapper.emitted('action')?.[0]?.[0]).toEqual(action)
  })

  it('shows an empty action list honestly rather than an unusable menu', async () => {
    const wrapper = mount(WorkItemCard, { props: { item: { ...item, availableActions: [] }, layout: 'list' } })
    await wrapper.get('.transition-menu button').trigger('click')
    expect(wrapper.text()).toContain('当前状态没有可执行的动作')
  })
})
