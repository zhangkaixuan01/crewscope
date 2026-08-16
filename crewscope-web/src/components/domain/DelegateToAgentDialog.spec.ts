import { flushPromises, mount } from '@vue/test-utils'
import { fixtureResponsibilities, fixtureWorkItemDetails } from '../../test/workItemFixtures'
import DelegateToAgentDialog from './DelegateToAgentDialog.vue'

describe('DelegateToAgentDialog', () => {
  it('previews responsibility and submits the selected server-authored AgentProfile identity', async () => {
    const responsibilities = structuredClone(fixtureResponsibilities)
    responsibilities[1] = {
      ...responsibilities[1]!,
      actorPrincipalId: '00000000-0000-0000-0000-000000000201',
      actorType: 'PERSONAL_AGENT',
      actorMemberId: null,
      actorDisplayName: '张凯旋的 Personal Agent',
      actorAgentProfileId: '00000000-0000-0000-0000-000000000301',
    }
    const onSubmit = vi.fn().mockResolvedValue(undefined)
    const conversationSource = { conversationId: crypto.randomUUID(), messageId: crypto.randomUUID() }
    const wrapper = mount(DelegateToAgentDialog, { props: props({ responsibilities, onSubmit, conversationSource }) })

    expect(wrapper.text()).toContain('Owner · 张凯旋')
    expect(wrapper.text()).toContain('Executor · 张凯旋的 Personal Agent')
    expect(wrapper.text()).toContain('来源保留为当前 Conversation 消息')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(onSubmit).toHaveBeenCalledWith(expect.objectContaining({
      objective: fixtureWorkItemDetails.workItem.title,
      executorAgentProfileId: '00000000-0000-0000-0000-000000000301',
      providerBindingIds: [],
      conversationSource,
    }))
  })

  it('fails closed without an Agent Executor and offers exact-request retry after failure', async () => {
    const unavailable = mount(DelegateToAgentDialog, { props: props() })
    expect(unavailable.text()).toContain('请先在责任链中分配 Agent')
    expect(unavailable.get('button[type="submit"]').attributes('disabled')).toBeDefined()

    const onRetry = vi.fn().mockResolvedValue(undefined)
    const retry = mount(DelegateToAgentDialog, { props: props({ retryable: true, errorMessage: '网络中断', onRetry }) })
    const retryButton = retry.findAll('button').find(button => button.text().includes('使用原请求重试'))!
    await retryButton.trigger('click')
    expect(retry.text()).toContain('网络中断')
    expect(onRetry).toHaveBeenCalled()
  })

  it('keeps keyboard focus inside the topmost delegation Modal and closes only that layer', async () => {
    const responsibilities = structuredClone(fixtureResponsibilities)
    responsibilities[1] = {
      ...responsibilities[1]!,
      actorType: 'PERSONAL_AGENT',
      actorMemberId: null,
      actorAgentProfileId: crypto.randomUUID(),
    }
    const wrapper = mount(DelegateToAgentDialog, {
      attachTo: document.body,
      props: props({ responsibilities }),
    })
    await flushPromises()

    expect(document.activeElement).toBe(wrapper.get('input').element)
    const controls = wrapper.get('form').findAll('button:not(:disabled), input:not(:disabled), textarea:not(:disabled)')
    const first = controls[0]!.element as HTMLElement
    const last = controls.at(-1)!.element as HTMLElement
    last.focus()
    await wrapper.get('form').trigger('keydown', { key: 'Tab' })
    expect(document.activeElement).toBe(first)
    await wrapper.get('form').trigger('keydown', { key: 'Tab', shiftKey: true })
    expect(document.activeElement).toBe(last)

    await wrapper.get('form').trigger('keydown', { key: 'Escape' })
    expect(wrapper.emitted('close')).toHaveLength(1)
    wrapper.unmount()
  })

  it('focuses the Modal itself when delegation has no eligible Agent Executor', async () => {
    const wrapper = mount(DelegateToAgentDialog, { attachTo: document.body, props: props() })
    await flushPromises()

    expect(document.activeElement).toBe(wrapper.get('[role="dialog"]').element)
    wrapper.unmount()
  })
})

function props(overrides: Record<string, unknown> = {}) {
  return {
    workItem: structuredClone(fixtureWorkItemDetails.workItem),
    responsibilities: structuredClone(fixtureResponsibilities),
    submitting: false,
    retryable: false,
    errorMessage: null,
    onSubmit: vi.fn().mockResolvedValue(undefined),
    onRetry: vi.fn().mockResolvedValue(undefined),
    ...overrides,
  }
}
