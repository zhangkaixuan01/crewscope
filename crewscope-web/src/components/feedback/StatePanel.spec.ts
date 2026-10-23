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

  // M13b-F11（C2）：error 面板要么给恢复动作、要么显式承认没有——两条路都不走的调用点
  // 由 check-state-panel-recovery 门禁拦截，这里锁住组件本身的两端行为。
  it('states explicitly that no automatic recovery exists when retryable is false', () => {
    const wrapper = mount(StatePanel, { props: { state: 'error', retryable: false } })

    expect(wrapper.find('button').exists()).toBe(false)
    expect(wrapper.text()).toContain('此面板不提供自动恢复动作')
  })

  it('offers the retry button to offline panels once the caller wires one', () => {
    const wrapper = mount(StatePanel, {
      props: { state: 'offline', retryLabel: '重新连接' },
      attrs: { onRetry: () => {} },
    })

    expect(wrapper.get('button').text()).toContain('重新连接')
  })

  it('keeps offline panels without a wired retry free of the error stance note', () => {
    // offline 的恢复在网络层且 description 必然说明——不该叠一行 error 语境的「无法自动恢复」。
    const wrapper = mount(StatePanel, { props: { state: 'offline' } })

    expect(wrapper.text()).not.toContain('此面板不提供自动恢复动作')
  })
})
