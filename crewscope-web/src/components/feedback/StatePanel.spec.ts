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

  it('publishes failures assertively and delegates an explicit fact refresh', async () => {
    const wrapper = mount(StatePanel, {
      props: { state: 'error', title: '最新事实不可用' },
    })

    expect(wrapper.attributes('role')).toBe('alert')
    expect(wrapper.attributes('aria-live')).toBe('assertive')
    await wrapper.get('button').trigger('click')
    expect(wrapper.emitted('retry')).toHaveLength(1)
  })

  it('keeps cancelled feedback non-busy', () => {
    const wrapper = mount(StatePanel, { props: { state: 'cancelled' } })

    expect(wrapper.attributes('aria-busy')).toBe('false')
    expect(wrapper.text()).toContain('已取消')
  })
})
