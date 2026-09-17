import { mount } from '@vue/test-utils'
import TaskRuntimeFactsPanel from './TaskRuntimeFactsPanel.vue'

describe('TaskRuntimeFactsPanel', () => {
  it('keeps runtime loading state isolated from the drawer container', () => {
    const wrapper = mount(TaskRuntimeFactsPanel, {
      props: { phase: 'loading', facts: null, errorMessage: null, displayDate: () => '刚刚', factTone: () => 'info', onRetry: () => undefined },
    })
    expect(wrapper.text()).toContain('正在加载 Attempt Runtime 事实')
  })
})
