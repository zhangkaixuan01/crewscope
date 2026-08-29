import { mount } from '@vue/test-utils'
import OnboardingWorkspace from './OnboardingWorkspace.vue'

describe('OnboardingWorkspace', () => {
  it('renders native Team semantics and the server-owned creation boundary', () => {
    const wrapper = mount(OnboardingWorkspace, {
      props: { phase: 'required', teamName: '', 'onUpdate:teamName': () => {} },
      attachTo: document.body,
    })

    const input = wrapper.get<HTMLInputElement>('input[name="teamName"]')
    expect(input.attributes('autocomplete')).toBe('organization')
    expect(input.attributes('maxlength')).toBe('200')
    expect(wrapper.get('[aria-label="将要创建的内容"]').text()).toContain('Personal Agent')
    expect(wrapper.text()).toContain('服务端将原子准备')
    wrapper.unmount()
  })

  it('shows a verified Personal Agent before entering Conversation', () => {
    const wrapper = mount(OnboardingWorkspace, {
      props: { phase: 'complete', personalAgentName: '张凯旋的 Personal Agent' },
      attachTo: document.body,
    })

    expect(wrapper.text()).toContain('张凯旋的 Personal Agent')
    expect(wrapper.get('[aria-label="初始化步骤"]').findAll('li.done')).toHaveLength(3)
    expect(wrapper.get('button').text()).toContain('进入团队对话')
    wrapper.unmount()
  })
})
