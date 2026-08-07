import { mount } from '@vue/test-utils'
import BaseButton from './BaseButton.vue'
import StatusBadge from './StatusBadge.vue'
import StatePanel from '../feedback/StatePanel.vue'

describe('foundation components', () => {
  it('exposes loading state and prevents duplicate button commands', () => {
    const wrapper = mount(BaseButton, { props: { loading: true }, slots: { default: '保存' } })

    expect(wrapper.get('button').attributes('aria-busy')).toBe('true')
    expect(wrapper.get('button').attributes()).toHaveProperty('disabled')
    expect(wrapper.text()).toContain('保存')
  })

  it('renders semantic status with text rather than color alone', () => {
    const wrapper = mount(StatusBadge, { props: { tone: 'warning', dot: true }, slots: { default: '待 Review' } })

    expect(wrapper.text()).toBe('待 Review')
    expect(wrapper.classes()).toContain('status-badge--warning')
  })

  it('announces errors and offers a retry action', async () => {
    const wrapper = mount(StatePanel, { props: { state: 'error' } })

    expect(wrapper.attributes('role')).toBe('alert')
    await wrapper.get('button').trigger('click')
    expect(wrapper.emitted('retry')).toHaveLength(1)
  })
})
