import { mount } from '@vue/test-utils'
import TaskAttemptHistoryPanel from './TaskAttemptHistoryPanel.vue'
import type { TaskExecution } from '../../domains/task/types'

const attempt: TaskExecution = {
  id: 'execution-1', attempt: 1, maxAttempts: 3, parentExecutionId: null, priority: 3,
  notBefore: '2026-08-24T01:00:00Z', status: 'WAITING', waiting: { reason: 'RUNTIME', waitingSince: '2026-08-24T01:00:00Z' },
  controlRequest: null, terminal: null, executorPrincipalId: null, currentPlanVersionId: null, version: 1,
  audit: { createdByPrincipalId: null, createdAt: '2026-08-24T01:00:00Z', updatedByPrincipalId: null, updatedAt: '2026-08-24T01:00:00Z' },
}

describe('TaskAttemptHistoryPanel', () => {
  it('renders attempt facts and delegates selection', async () => {
    const wrapper = mount(TaskAttemptHistoryPanel, {
      props: {
        attempts: [attempt], selectedExecutionId: attempt.id, currentExecutionId: attempt.id,
        principalName: () => '未命名成员', displayDate: () => '刚刚', factTone: () => 'info',
      },
    })
    expect(wrapper.text()).toContain('Attempt 历史')
    expect(wrapper.text()).toContain('等待认领')
    await wrapper.get('button').trigger('click')
    expect(wrapper.emitted('selectAttempt')).toEqual([[attempt.id]])
  })
})
