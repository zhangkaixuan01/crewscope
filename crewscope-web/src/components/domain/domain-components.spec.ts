import { mount } from '@vue/test-utils'
import AgentPresence from './AgentPresence.vue'
import ResponsibilityChain from './ResponsibilityChain.vue'
import { demoAgent, demoResponsibilities } from '../../domains/demo/fixtures'

describe('CrewScope domain components', () => {
  it('keeps the complete responsibility chain visible', () => {
    const wrapper = mount(ResponsibilityChain, { props: { members: demoResponsibilities } })

    expect(wrapper.get('ol').attributes('aria-label')).toBe('责任链')
    expect(wrapper.findAll('li')).toHaveLength(3)
    expect(wrapper.text()).toContain('Owner')
    expect(wrapper.text()).toContain('Executor')
    expect(wrapper.text()).toContain('Reviewer')
  })

  it('identifies an agent and exposes the takeover control', () => {
    const wrapper = mount(AgentPresence, { props: { agent: demoAgent } })

    expect(wrapper.text()).toContain('Specialist Agent')
    expect(wrapper.text()).toContain('运行中')
    expect(wrapper.get('button').text()).toContain('接管')
  })
})
