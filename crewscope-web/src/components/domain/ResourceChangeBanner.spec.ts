import { mount } from '@vue/test-utils'
import ResourceChangeBanner from './ResourceChangeBanner.vue'

describe('ResourceChangeBanner', () => {
  it('shows the frozen ADR-032 wording with a refresh action and alert semantics', async () => {
    const wrapper = mount(ResourceChangeBanner, { props: { visible: true } })

    expect(wrapper.attributes('role')).toBe('alert')
    expect(wrapper.text()).toContain('该工作项已被更新')

    await wrapper.get('button').trigger('click')
    expect(wrapper.emitted('refresh')).toHaveLength(1)
  })

  it('renders nothing until a remote change the viewer has not caused arrives', () => {
    const wrapper = mount(ResourceChangeBanner, { props: { visible: false } })

    expect(wrapper.find('section').exists()).toBe(false)
  })
})
