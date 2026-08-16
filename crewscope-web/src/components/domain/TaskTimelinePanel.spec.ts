import { mount } from '@vue/test-utils'
import type { TaskEventItem } from '../../domains/task/types'
import TaskTimelinePanel from './TaskTimelinePanel.vue'

describe('TaskTimelinePanel', () => {
  it('shows merged progress, recovery and newest durable event first', () => {
    const wrapper = mount(TaskTimelinePanel, { props: props({
      page: page([
        event('started', 'WORKER_TASK_START_ACCEPTED', { operation: 'START' }, '2026-08-15T12:02:00Z'),
        event('progress', 'AGENT_RUN_EVENT_RECORDED', { eventKind: 'PROGRESS', safeText: '正在验证', progressPercent: 65 }, '2026-08-15T12:01:00Z'),
      ]),
      live: { phase: 'reconnecting', errorMessage: '连接已轮换', projectionGap: true },
      continuityGap: true,
    }) })

    expect(wrapper.text()).toContain('65%')
    expect(wrapper.text()).toContain('正在验证')
    expect(wrapper.text()).toContain('执行正在恢复')
    expect(wrapper.text()).toContain('重新连接')
    const facts = wrapper.findAll('.timeline-list article')
    expect(facts[0]?.text()).toContain('Agent 进度已更新')
    expect(facts[1]?.text()).toContain('执行已开始')
    wrapper.unmount()
  })

  it('throttles polite announcements and skips the initial history', async () => {
    vi.useFakeTimers()
    const initial = page([event('started', 'WORKER_TASK_START_ACCEPTED', { operation: 'START' })])
    const wrapper = mount(TaskTimelinePanel, { props: props({ page: initial }) })
    expect(wrapper.get('[aria-live="polite"]').text()).toBe('')

    await wrapper.setProps({ page: page([...initial.items, event('progress', 'WORKER_TASK_PROGRESS_ACCEPTED', { operation: 'PROGRESS', safeSummary: '第一阶段', progressPercent: 20 })]) })
    await wrapper.setProps({ page: page([...initial.items, event('progress-2', 'WORKER_TASK_PROGRESS_ACCEPTED', { operation: 'PROGRESS', safeSummary: '第二阶段', progressPercent: 45 })]) })
    await vi.advanceTimersByTimeAsync(899)
    expect(wrapper.get('[aria-live="polite"]').text()).toBe('')
    await vi.advanceTimersByTimeAsync(1)
    expect(wrapper.get('[aria-live="polite"]').text()).toContain('第二阶段')

    wrapper.unmount()
    vi.useRealTimers()
  })
})

function props(overrides: Record<string, unknown> = {}) {
  return {
    phase: 'ready' as const, page: page([]), errorMessage: null,
    live: { phase: 'connected' as const, errorMessage: null, projectionGap: false },
    executionId: 'execution-current', executionStatus: 'RUNNING' as const, continuityGap: false,
    onLoadMore: vi.fn(), onRetry: vi.fn(), ...overrides,
  }
}

function page(items: TaskEventItem[]) {
  return { items, hasMore: false, taskTerminal: false, nextCursor: items.at(-1)?.cursor ?? null }
}

function event(eventId: string, eventType: string, payload: Record<string, unknown>, occurredAt = '2026-08-15T12:00:00Z'): TaskEventItem {
  return {
    cursor: `cursor-${eventId}`, projectionGap: false,
    context: { taskId: 'task', taskExecutionId: 'execution-current', stepExecutionId: null, agentRunId: null, executionLeaseId: null },
    event: {
      eventId, domainEventId: `domain-${eventId}`, streamType: 'TASK', eventType, schemaVersion: '1',
      aggregateType: 'Task', aggregateId: 'task', aggregateVersion: 1, correlationId: 'correlation',
      causationId: null, occurredAt, payload,
    },
  }
}
