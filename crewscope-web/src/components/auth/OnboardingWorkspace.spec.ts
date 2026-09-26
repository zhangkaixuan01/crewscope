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

  it('splits the completion entry by goal and only warns about a missing model setup', async () => {
    const wrapper = mount(OnboardingWorkspace, {
      props: { phase: 'complete', conversationSetupReady: false },
      attachTo: document.body,
    })

    const entry = wrapper.findAll('button')
    await entry.find(button => button.text().includes('先完成 Coding 配置'))!.trigger('click')
    expect(wrapper.emitted('configure')).toHaveLength(1)
    // 决策 7：缺口提示不改变进入对话的主动作。
    expect(wrapper.text()).toContain('Personal Agent 尚未完成模型配置')
    expect(wrapper.text()).toContain('进入团队对话')
    wrapper.unmount()
  })

  it.each([
    ['submitting', 'team', 1],
    ['verifying', 'workspace', 2],
    ['verifying', 'agent', 3],
  ] as const)('lists only confirmed or in-flight steps during %s at %s', (phase, stage, expected) => {
    const wrapper = mount(OnboardingWorkspace, {
      props: { phase, currentStage: stage },
      attachTo: document.body,
    })

    // L10：进度只陈述已经确认与正在确认的步骤。
    expect(wrapper.get('[aria-label="团队初始化进度"]').findAll('li')).toHaveLength(expected)
    wrapper.unmount()
  })
})
