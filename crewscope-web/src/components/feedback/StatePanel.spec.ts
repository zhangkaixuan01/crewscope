import { mount } from '@vue/test-utils'
import StatePanel from './StatePanel.vue'

describe('StatePanel', () => {
  it('publishes compact recovering feedback as a polite busy status', () => {
    const wrapper = mount(StatePanel, {
      props: { state: 'recovering', compact: true },
    })

    expect(wrapper.classes()).toContain('compact')
    expect(wrapper.attributes('role')).toBe('status')
    expect(wrapper.attributes('aria-busy')).toBe('true')
    expect(wrapper.text()).toContain('执行正在恢复')
  })

  it('publishes failures assertively and offers no retry without a wired action', () => {
    const wrapper = mount(StatePanel, {
      props: { state: 'error', title: '最新事实不可用' },
    })

    expect(wrapper.attributes('role')).toBe('alert')
    expect(wrapper.attributes('aria-live')).toBe('assertive')
    // R25: a retry button nobody listens to is an empty promise — none is rendered.
    expect(wrapper.find('button').exists()).toBe(false)
  })

  it('offers a business-named retry once the caller wires one', async () => {
    const wrapper = mount(StatePanel, {
      props: { state: 'error', retryLabel: '重新加载来源' },
      // Declared emits live in vnode props, which is exactly what the component inspects.
      attrs: { onRetry: () => {} },
    })

    expect(wrapper.get('button').text()).toContain('重新加载来源')
    await wrapper.get('button').trigger('click')
    expect(wrapper.emitted('retry')).toHaveLength(1)
  })

  it('keeps cancelled feedback non-busy', () => {
    const wrapper = mount(StatePanel, { props: { state: 'cancelled' } })

    expect(wrapper.attributes('aria-busy')).toBe('false')
    expect(wrapper.text()).toContain('已取消')
  })
})
