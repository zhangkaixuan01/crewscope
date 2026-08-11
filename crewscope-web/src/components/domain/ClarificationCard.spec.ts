import { mount } from '@vue/test-utils'
import ClarificationCard from './ClarificationCard.vue'

const request = {
  schemaVersion: '1' as const,
  summary: '需要确定代码仓库与目标分支。',
  questions: [
    { fieldKey: 'repository', question: '使用哪个仓库？', context: '请选择当前 Team 可访问的仓库', required: true, choices: ['crewscope-java', 'agentscope-java'] },
    { fieldKey: 'branch', question: '使用哪个分支？', context: null, required: true, choices: [] },
  ],
}

describe('ClarificationCard', () => {
  it('collects only declared field-keyed answers with native keyboard controls', async () => {
    const wrapper = mount(ClarificationCard, { props: { request } })
    await wrapper.get('input[value="crewscope-java"]').setValue(true)
    await wrapper.get('textarea').setValue('main')
    await wrapper.get('form').trigger('submit')

    expect(wrapper.emitted('submit')?.[0]?.[0]).toEqual({ repository: 'crewscope-java', branch: 'main' })
    expect(wrapper.text()).not.toContain('toolCallId')
  })

  it('announces a missing required answer without submitting', async () => {
    const wrapper = mount(ClarificationCard, { props: { request } })
    await wrapper.get('form').trigger('submit')

    expect(wrapper.get('[role="alert"]').text()).toContain('使用哪个仓库')
    expect(wrapper.emitted('submit')).toBeUndefined()
  })
})
