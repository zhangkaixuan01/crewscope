import { mount } from '@vue/test-utils'
import type { ClarificationRequest } from '../../domains/conversation/types'
import ConversationAgentActionRegion from './ConversationAgentActionRegion.vue'

const clarification: ClarificationRequest = {
  schemaVersion: '1',
  summary: '继续前需要确认目标仓库。',
  questions: [{
    fieldKey: 'repository',
    question: '使用哪个仓库？',
    context: null,
    required: true,
    choices: ['crewscope-java'],
  }],
}

describe('ConversationAgentActionRegion', () => {
  it('keeps an invocation failure in the bottom action region and exposes safe retry', async () => {
    const wrapper = mount(ConversationAgentActionRegion, { props: {
      phase: 'error', statusText: '服务暂时无法完成请求', invocationId: 'invocation-1',
      online: true, retryable: true, clarification: null,
    } })

    expect(wrapper.get('.conversation-agent-action-region').text()).toContain('服务暂时无法完成请求')
    expect(wrapper.get('.agent-live-status').attributes('role')).toBe('alert')
    await wrapper.get('button').trigger('click')
    expect(wrapper.emitted('retry')).toHaveLength(1)
  })

  it('renders the Agent status and HITL form together in one bottom action region', () => {
    const wrapper = mount(ConversationAgentActionRegion, { props: {
      phase: 'interrupted', statusText: 'Personal Agent 需要补充信息', invocationId: 'invocation-2',
      online: true, retryable: false, clarification,
    } })
    const region = wrapper.get('.conversation-agent-action-region')

    expect(region.get('.agent-live-status').text()).toContain('Personal Agent 需要补充信息')
    expect(region.get('.clarification-card').text()).toContain('继续前需要确认目标仓库')
    expect(region.element.lastElementChild?.classList.contains('clarification-card')).toBe(true)
  })
})
