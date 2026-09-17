import { mount } from '@vue/test-utils'
import AgentConfigurationLifecycleSection from './AgentConfigurationLifecycleSection.vue'

describe('AgentConfigurationLifecycleSection', () => {
  it('exposes reversible and terminal actions for a regular active agent', async () => {
    const wrapper = mount(AgentConfigurationLifecycleSection, {
      props: { visible: true, platformManaged: false, status: 'ACTIVE', defaultProfile: false, pending: false, confirmation: null },
    })
    expect(wrapper.text()).toContain('禁用')
    expect(wrapper.text()).toContain('归档')
    await wrapper.get('button').trigger('click')
    expect(wrapper.emitted('transition')).toEqual([['disable']])
  })

  it('renders managed lifecycle copy without mutation controls', () => {
    const wrapper = mount(AgentConfigurationLifecycleSection, {
      props: { visible: true, platformManaged: true, status: 'ACTIVE', defaultProfile: false, pending: false, confirmation: null },
    })
    expect(wrapper.text()).toContain('平台托管生命周期')
    expect(wrapper.findAll('button')).toHaveLength(0)
  })
})
