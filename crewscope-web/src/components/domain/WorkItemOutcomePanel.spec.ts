import { mount } from '@vue/test-utils'
import type { WorkItemExecutionSummary } from '../../domains/workitem/types'
import WorkItemOutcomePanel from './WorkItemOutcomePanel.vue'

/**
 * M9b-F03 WorkItem-level outcome panel: the A06 line is shown verbatim, the evidence reference
 * only becomes a navigation when the Task is certain, the four states never merge into one
 * green completion, and waiting facts name the human they wait on.
 */
const currentTask = '00000000-0000-0000-0000-000000000021'
const currentExecution = '00000000-0000-0000-0000-000000000031'

function summary(overrides: Partial<WorkItemExecutionSummary> = {}): WorkItemExecutionSummary {
  return {
    workItemId: '00000000-0000-0000-0000-000000000011',
    workItemVersion: 3,
    workStatus: 'IN_PROGRESS',
    taskCount: 1,
    activeTaskCount: 1,
    pendingReviewCount: 0,
    currentTaskId: currentTask,
    currentExecutionId: currentExecution,
    executionStatus: 'RUNNING',
    selectionRequired: false,
    blockedReasons: [],
    resultSummary: null,
    resultSourceReference: null,
    projectionVersion: 1,
    observedAt: '2026-09-01T00:00:00Z',
    ...overrides,
  }
}

const principalName = (id: string) => (id === '00000000-0000-0000-0000-000000000001' ? '张凯旋' : id.slice(0, 8))

function mountPanel(summaryValue: WorkItemExecutionSummary | null, onOpenReference = vi.fn()) {
  return mount(WorkItemOutcomePanel, {
    props: { summary: summaryValue, principalName, onOpenReference },
  })
}

describe('WorkItemOutcomePanel', () => {
  it('shows the A06 delivery line verbatim with a working evidence jump for the current execution', async () => {
    const onOpenReference = vi.fn()
    const wrapper = mountPanel(summary({
      resultSummary: '已交付 8 个文件变更（+120/−30），测试通过',
      resultSourceReference: `coding-attempt:${currentExecution}@${'f'.repeat(64)}`,
      activeTaskCount: 0,
    }), onOpenReference)

    expect(wrapper.get('[data-testid="work-item-outcome"]').text()).toContain('已交付 8 个文件变更（+120/−30），测试通过')
    const jump = wrapper.get('.reference-line button')
    expect(jump.text()).toContain('查看证据锚')
    await jump.trigger('click')
    expect(onOpenReference).toHaveBeenCalledWith(
      { kind: 'coding-attempt', executionId: currentExecution, finalHash: 'f'.repeat(64) },
      currentTask,
    )
  })

  it('keeps a failed-test delivery warning-toned instead of green', () => {
    const wrapper = mountPanel(summary({
      resultSummary: '已交付 3 个文件变更（+9/−1），测试未通过',
      resultSourceReference: `coding-attempt:${currentExecution}@x`,
      activeTaskCount: 0,
    }))
    expect(wrapper.get('.delivered-line').classes()).toContain('tests-failed')
    expect(wrapper.text()).not.toContain('执行进行中')
  })

  it('keeps an unresolvable reference raw with a copy affordance — no guessed jump', () => {
    const onOpenReference = vi.fn()
    const staleExecution = '00000000-0000-0000-0000-000000000099'
    const wrapper = mountPanel(summary({
      taskCount: 2,
      resultSummary: '已交付 1 个文件变更（+2/−0）',
      resultSourceReference: `coding-attempt:${staleExecution}@y`,
      activeTaskCount: 0,
    }), onOpenReference)

    expect(wrapper.get('.reference-line .mono').text()).toBe(`coding-attempt:${staleExecution}@y`)
    expect(wrapper.text()).toContain('复制引用')
    expect(wrapper.findAll('button').some(button => button.text().includes('查看证据锚'))).toBe(false)
  })

  it('phrases the decision, waiting and empty states separately with their honest facts', () => {
    const waiting = mountPanel(summary({
      pendingReviewCount: 2,
      blockedReasons: [{ code: 'REVIEW', taskId: currentTask, executionId: currentExecution, since: '2026-09-01T00:00:00Z', waitingOnPrincipalId: '00000000-0000-0000-0000-000000000001' }],
    }))
    expect(waiting.text()).toContain('2 个评审等待成员决定')
    expect(waiting.text()).toContain('等待评审 · 张凯旋')

    const running = mountPanel(summary())
    expect(running.text()).toContain('1 个 Task 进行中 · 执行中')

    const notStarted = mountPanel(summary({ taskCount: 0, activeTaskCount: 0, currentTaskId: null, currentExecutionId: null, executionStatus: null }))
    expect(notStarted.text()).toContain('这个工作项还没有启动 Task')

    const finished = mountPanel(summary({ activeTaskCount: 0, executionStatus: 'COMPLETED' }))
    expect(finished.text()).toContain('执行已结束，服务端没有可展示的成果摘要')
  })

  it('hides entirely while the summary read is still in flight', () => {
    const wrapper = mountPanel(null)
    expect(wrapper.find('[data-testid="work-item-outcome"]').exists()).toBe(false)
  })
})
