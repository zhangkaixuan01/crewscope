import { mount } from '@vue/test-utils'
import ResponsibilityChain from './ResponsibilityChain.vue'
import { demoResponsibilities } from '../../domains/demo/fixtures'

describe('CrewScope domain components', () => {
  it('keeps the complete responsibility chain visible', () => {
    const wrapper = mount(ResponsibilityChain, { props: { members: demoResponsibilities } })

    expect(wrapper.get('ol').attributes('aria-label')).toBe('责任链')
    expect(wrapper.findAll('li')).toHaveLength(3)
    expect(wrapper.text()).toContain('Owner')
    expect(wrapper.text()).toContain('Executor')
    expect(wrapper.text()).toContain('Reviewer')
  })
})
