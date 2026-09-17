import { mount } from '@vue/test-utils'
import { nextTick } from 'vue'
import StatusTransitionMenu from './StatusTransitionMenu.vue'
import type { WorkItemAvailableTransition } from '../../domains/workitem/types'

/**
 * The keyboard contract of the status badge as an action entry.
 *
 * Every assertion here is about something the previous implementation could not do: it opened on
 * hover, could not be closed, never said whether it was open, and moved focus to the first action
 * rather than the first *executable* one.
 */
const actions: WorkItemAvailableTransition[] = [
  { actionId: 'request-review', targetStatus: 'IN_REVIEW', label: '提交评审', strength: 'PRIMARY', reversible: true, enabled: true, reason: null, reasonMessage: null, remedyLabel: null, remedyRoute: null },
  { actionId: 'complete', targetStatus: 'DONE', label: '标记完成', strength: 'PRIMARY', reversible: false, enabled: false, reason: 'GATE_NOT_PASSED', reasonMessage: '前置 Gate 未通过', remedyLabel: '指派 Reviewer', remedyRoute: '/work?focus=CRW-18' },
  { actionId: 'cancel', targetStatus: 'CANCELLED', label: '取消工作项', strength: 'DANGER', reversible: true, enabled: true, reason: null, reasonMessage: null, remedyLabel: null, remedyRoute: null },
]

const irreversible: WorkItemAvailableTransition = {
  actionId: 'cancel', targetStatus: 'CANCELLED', label: '取消工作项', strength: 'DANGER',
  reversible: false, enabled: true, reason: null, reasonMessage: null, remedyLabel: null, remedyRoute: null,
}

/**
 * Mounted into the document, not detached: focus assertions are the point of this spec, and a
 * detached tree never receives them.
 */
const mounted: ReturnType<typeof mount>[] = []

function mountMenu(overrides: Partial<InstanceType<typeof StatusTransitionMenu>['$props']> = {}) {
  const wrapper = mount(StatusTransitionMenu, {
    props: { actions, ...overrides },
    slots: { default: '进行中' },
    attachTo: document.body,
    global: { stubs: { RouterLink: { template: '<a><slot /></a>' } } },
  })
  mounted.push(wrapper)
  return wrapper
}

afterEach(() => {
  mounted.splice(0).forEach(wrapper => wrapper.unmount())
})

describe('StatusTransitionMenu', () => {
  it('renders the badge closed and reports that it is closed', () => {
    const wrapper = mountMenu()
    const trigger = wrapper.get('button')

    expect(trigger.text()).toContain('进行中')
    expect(trigger.attributes('aria-haspopup')).toBe('menu')
    expect(trigger.attributes('aria-expanded')).toBe('false')
    expect(wrapper.find('[role="menu"]').exists()).toBe(false)
  })

  it('opens on the trigger and focuses the first executable action', async () => {
    const wrapper = mountMenu()
    await wrapper.get('button').trigger('click')
    await nextTick()

    expect(wrapper.get('button').attributes('aria-expanded')).toBe('true')
    const items = wrapper.findAll('[role="menuitem"]')
    expect(items.map(item => item.text())).toEqual(['提交评审', '标记完成', '取消工作项'])
  })

  it('moves focus with the arrow keys and skips an action the server blocked', async () => {
    const wrapper = mountMenu()
    await wrapper.get('button').trigger('click')
    await nextTick()
    const menu = wrapper.get('[role="menu"]')

    await menu.trigger('keydown', { key: 'ArrowDown' })
    await menu.trigger('keydown', { key: 'ArrowDown' })
    // The blocked entry is not reachable: focus wraps to the first action instead of landing on a
    // control that cannot be executed.
    expect(document.activeElement?.textContent).toContain('提交评审')

    await menu.trigger('keydown', { key: 'End' })
    expect(document.activeElement?.textContent).toContain('取消工作项')
  })

  it('shows a blocked action with the server reason and its remedy', async () => {
    const wrapper = mountMenu()
    await wrapper.get('button').trigger('click')
    await nextTick()

    const blocked = wrapper.findAll('[role="menuitem"]')[1]!
    expect(blocked.attributes('aria-disabled')).toBe('true')
    expect(blocked.attributes('disabled')).toBeUndefined()
    expect(wrapper.text()).toContain('前置 Gate 未通过')
    expect(wrapper.get('[role="menu"] a').text()).toBe('指派 Reviewer')
  })

  it('ignores a click on a blocked action', async () => {
    const wrapper = mountMenu()
    await wrapper.get('button').trigger('click')
    await nextTick()

    await wrapper.findAll('[role="menuitem"]')[1]!.trigger('click')
    expect(wrapper.emitted('select')).toBeUndefined()
  })

  it('closes on Escape and returns focus to the badge', async () => {
    const wrapper = mountMenu()
    const trigger = wrapper.get('button')
    await trigger.trigger('click')
    await nextTick()

    await wrapper.get('[role="menu"]').trigger('keydown', { key: 'Escape' })
    await nextTick()

    expect(wrapper.get('button').attributes('aria-expanded')).toBe('false')
    expect(document.activeElement).toBe(trigger.element)
    expect(wrapper.emitted('dismissed')).toHaveLength(1)
  })

  it('emits the whole action so the caller can execute it and confirm it', async () => {
    const wrapper = mountMenu()
    await wrapper.get('button').trigger('click')
    await nextTick()

    await wrapper.findAll('[role="menuitem"]')[0]!.trigger('click')
    expect(wrapper.emitted('select')?.[0]).toEqual([actions[0]])
  })

  it('keeps an irreversible action open for its second click', async () => {
    const wrapper = mountMenu({ confirmingTarget: 'DONE' })
    await wrapper.get('button').trigger('click')
    await nextTick()

    const complete = wrapper.findAll('[role="menuitem"]')[1]!
    expect(complete.text()).toBe('再次点击确认标记完成')
  })

  it('stays open after an irreversible action until the confirmation is resolved', async () => {
    // The label the second click needs lives inside the menu, so closing on the first click would
    // remove the very control that asked for confirmation.
    const wrapper = mountMenu({ actions: [irreversible] })
    await wrapper.get('button').trigger('click')
    await nextTick()
    await wrapper.get('[role="menuitem"]').trigger('click')

    expect(wrapper.emitted('select')).toHaveLength(1)
    await expect(wrapper.find('[role="menu"]').exists()).toBe(true)

    await wrapper.setProps({ confirmingTarget: 'CANCELLED' })
    expect(wrapper.get('[role="menuitem"]').text()).toContain('再次点击确认取消工作项')

    // The caller clears the confirmation once the command ran; only then does the menu go away.
    await wrapper.setProps({ confirmingTarget: null })
    await nextTick()
    expect(wrapper.find('[role="menu"]').exists()).toBe(false)
  })

  it('closes straight away for a reversible action, which the undo window covers', async () => {
    const wrapper = mountMenu()
    await wrapper.get('button').trigger('click')
    await nextTick()
    await wrapper.findAll('[role="menuitem"]')[0]!.trigger('click')

    expect(wrapper.emitted('select')).toHaveLength(1)
    expect(wrapper.find('[role="menu"]').exists()).toBe(false)
  })

  it('says so when the server offers nothing, instead of showing an empty box', async () => {
    const wrapper = mountMenu({ actions: [] })
    await wrapper.get('button').trigger('click')
    await nextTick()

    expect(wrapper.find('[role="menu"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('当前状态没有可执行的动作')
  })

  it('cannot be opened while a command is in flight', async () => {
    const wrapper = mountMenu({ busy: true })
    const trigger = wrapper.get('button')
    expect(trigger.attributes('disabled')).toBeDefined()

    await trigger.trigger('click')
    expect(wrapper.find('[role="menu"]').exists()).toBe(false)
  })
})
