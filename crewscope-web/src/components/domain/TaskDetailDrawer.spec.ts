import { flushPromises, mount } from '@vue/test-utils'
import { fixtureIds } from '../../test/scopeFixtures'
import {
  details,
  execution,
  fixtureTasks,
  previousExecution,
  runtimeFacts,
  taskIds,
} from '../../test/taskFixtures'
import type { RuntimeFleetSummary } from '../../domains/task/types'
import TaskDetailDrawer from './TaskDetailDrawer.vue'

describe('TaskDetailDrawer', () => {
  it('shows responsibility, current plan, step progress and only member-safe Runtime facts', () => {
    const wrapper = mount(TaskDetailDrawer, { props: props() })
    const text = wrapper.text()

    expect(text).toContain('完成 Task Gateway')
    expect(text).toContain('责任快照')
    expect(text).toContain('Owner')
    expect(text).toContain('Revision 2')
    expect(text).toContain('步骤进度 1/2')
    expect(text).toContain('WAITING_RUNTIME')
    expect(text).toContain('1 个 Worker 失联')
    expect(text).toContain('WORKER_LOST')
    expect(text).toContain('State Snapshot')
    expect(text).not.toContain('must-not-render')
    expect(text).not.toContain('secret-token-value')
    wrapper.unmount()
  })

  it('switches PlanVersion and delegates historical attempt selection without mixing facts', async () => {
    const onSelectAttempt = vi.fn()
    const wrapper = mount(TaskDetailDrawer, { props: props({ onSelectAttempt }) })

    await wrapper.get<HTMLSelectElement>('.plan-selector select').setValue(taskIds.previousPlan)
    expect(wrapper.text()).toContain('先建立公开契约，再实现详情视图。')
    expect(wrapper.text()).not.toContain('展示 Task、责任、Plan、Step、AgentRun 与 Lease 的安全事实。')

    const historical = wrapper.findAll<HTMLButtonElement>('.attempt-list button').find(button => button.text().includes('Attempt 1'))
    expect(historical).toBeDefined()
    await historical!.trigger('click')
    expect(onSelectAttempt).toHaveBeenCalledWith(taskIds.previousExecution)
    wrapper.unmount()
  })

  it('keeps narrow-screen reading order in the semantic DOM and exposes fleet degradation', () => {
    const wrapper = mount(TaskDetailDrawer, { props: props() })
    const ordered = [
      '.task-hero', '.control-card', '.timeline-card', '.associations-card', '.responsibility-card', '.attempt-card', '.fleet-card',
      '.plan-card', '.steps-card', '.runs-card', '.lease-card',
    ].map(selector => wrapper.get(selector).element)

    for (let index = 1; index < ordered.length; index += 1) {
      expect(ordered[index - 1]!.compareDocumentPosition(ordered[index]!) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    }
    expect(wrapper.get('.fleet-card').text()).toContain('DEGRADED')
    expect(wrapper.get('.fleet-card').text()).toContain('CAPACITY · 1')
    wrapper.unmount()
  })

  it('opens only member-visible associated Conversations from the durable association projection', async () => {
    const wrapper = mount(TaskDetailDrawer, { props: props() })

    await wrapper.get('.task-conversation-links button').trigger('click')

    expect(wrapper.emitted('openConversation')).toEqual([[taskIds.conversation]])
    expect(wrapper.text()).toContain('M3 前端协作')
    wrapper.unmount()
  })

  it('focuses the close action, closes with Escape and keeps the WorkItem handoff explicit', async () => {
    const wrapper = mount(TaskDetailDrawer, { attachTo: document.body, props: props() })
    await flushPromises()

    expect(document.activeElement?.getAttribute('aria-label')).toBe('关闭 Task 详情')
    await wrapper.get('.task-detail-footer button').trigger('click')
    expect(wrapper.emitted('openWorkItem')).toBeTruthy()
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }))
    expect(wrapper.emitted('close')).toBeTruthy()
    expect(document.body.style.overflow).toBe('hidden')

    wrapper.unmount()
    expect(document.body.style.overflow).toBe('')
  })

  it('renders isolated detail, Runtime and fleet loading and failure states', async () => {
    const loading = mount(TaskDetailDrawer, { props: props({ phase: 'loading', details: null }) })
    expect(loading.text()).toContain('正在加载 Task 详情')
    loading.unmount()

    const onRetryRuntime = vi.fn()
    const onRetryFleet = vi.fn()
    const failed = mount(TaskDetailDrawer, {
      props: props({
        runtimePhase: 'error', runtimeFacts: null, runtimeErrorMessage: 'Runtime 读取失败',
        fleetPhase: 'error', fleet: null, fleetErrorMessage: 'Fleet 读取失败',
        onRetryRuntime, onRetryFleet,
      }),
    })
    expect(failed.text()).toContain('Runtime 读取失败')
    expect(failed.text()).toContain('Fleet 读取失败')
    const retryButtons = failed.findAll('button').filter(button => button.text().includes('刷新事实'))
    await retryButtons[0]!.trigger('click')
    await retryButtons[1]!.trigger('click')
    expect(onRetryFleet).toHaveBeenCalled()
    expect(onRetryRuntime).toHaveBeenCalled()
    failed.unmount()
  })

  it('retains stale details while refreshing and makes a cancelled terminal state explicit', async () => {
    const refreshing = mount(TaskDetailDrawer, {
      props: props({ phase: 'loading' }),
    })
    expect(refreshing.text()).toContain('正在刷新 Task 事实')
    expect(refreshing.text()).toContain('完成 Task Gateway')
    refreshing.unmount()

    const onRetry = vi.fn()
    const staleError = mount(TaskDetailDrawer, {
      props: props({ phase: 'error', errorMessage: '最新版本读取失败', onRetry }),
    })
    await staleError.get('.detail-sync-state button').trigger('click')
    expect(staleError.text()).toContain('完成 Task Gateway')
    expect(onRetry).toHaveBeenCalled()
    staleError.unmount()

    const cancelledProps = props()
    const cancelledDetails = structuredClone(cancelledProps.details)
    const cancelledAttempts = structuredClone(cancelledProps.attempts)
    cancelledDetails.status = 'CANCELLED'
    cancelledAttempts[0]!.status = 'CANCELLED'
    const cancelled = mount(TaskDetailDrawer, {
      props: { ...cancelledProps, details: cancelledDetails, attempts: cancelledAttempts },
    })
    expect(cancelled.get('.task-lifecycle-state').text()).toContain('Task 已取消')
    expect(cancelled.get('.task-lifecycle-state').attributes('role')).toBe('status')
    cancelled.unmount()
  })
})

function props(overrides: Record<string, unknown> = {}) {
  const task = structuredClone(fixtureTasks[fixtureIds.teamPlatform]![0]!)
  const taskDetails = details(task)
  const current = execution()
  current.attempt = 2
  current.status = 'WAITING'
  current.waiting = { reason: 'WAITING_RUNTIME', waitingSince: '2026-08-15T12:00:00Z' }
  const historical = previousExecution()
  taskDetails.currentExecutionId = current.id
  taskDetails.attempts = [current, historical]
  taskDetails.responsibilitySnapshot = [
    { assignmentId: 'owner', assignmentVersion: 1, role: 'OWNER', principalId: fixtureIds.principal, principalType: 'USER', memberId: fixtureIds.memberOwner, assignedAt: task.createdAt, acceptedAt: task.createdAt },
    { assignmentId: 'executor', assignmentVersion: 1, role: 'EXECUTOR', principalId: '00000000-0000-0000-0000-000000000105', principalType: 'PERSONAL_AGENT', memberId: null, assignedAt: task.createdAt, acceptedAt: task.createdAt },
  ]
  const facts = runtimeFacts()
  ;(facts as unknown as Record<string, unknown>).claimToken = 'must-not-render'
  ;(facts.execution as unknown as Record<string, unknown>).credential = 'secret-token-value'
  return {
    phase: 'ready' as const,
    details: taskDetails,
    attempts: [current, historical],
    selectedExecutionId: current.id,
    errorMessage: null,
    runtimePhase: 'ready' as const,
    runtimeFacts: facts,
    runtimeErrorMessage: null,
    codingPhase: 'empty' as const,
    codingAttempt: null,
    codingErrorMessage: null,
    codingCommandsPhase: 'empty' as const,
    codingCommands: null,
    codingCommandsErrorMessage: null,
    codingTestsPhase: 'empty' as const,
    codingTests: null,
    codingTestsErrorMessage: null,
    codingCommandLog: vi.fn(() => null),
    codingTestReport: vi.fn(() => null),
    codingPatchPhase: 'idle' as const,
    codingPatch: null,
    codingPatchErrorMessage: null,
    fleetPhase: 'ready' as const,
    fleet: fleet(),
    fleetErrorMessage: null,
    associationPhase: 'ready' as const,
    associations: {
      task: { id: task.id, projectId: task.projectId, workItemId: task.workItemId, status: task.status, objective: task.objective, href: `/work?task=${task.id}` },
      workItem: { id: task.workItemId, projectId: task.projectId, key: 'CRW-18', title: 'Task 前端', status: 'IN_PROGRESS', href: `/work?workItem=${task.workItemId}` },
      conversations: { items: [{ id: taskIds.conversation, title: 'M3 前端协作', visibility: 'PRIVATE', status: 'ACTIVE', origin: 'CONVERSATION_SOURCE', associatedAt: task.createdAt, href: `/conversation?conversation=${taskIds.conversation}` }], nextCursor: null },
    },
    associationErrorMessage: null,
    eventPhase: 'ready' as const,
    eventPage: {
      items: [{
        cursor: 'cursor-1', projectionGap: false,
        context: { taskId: task.id, taskExecutionId: current.id, stepExecutionId: null, agentRunId: taskIds.agentRun, executionLeaseId: null },
        event: {
          eventId: 'event-progress-1', domainEventId: 'domain-progress-1', streamType: 'TASK',
          eventType: 'AGENT_RUN_EVENT_RECORDED', schemaVersion: '1', aggregateType: 'AgentRun',
          aggregateId: taskIds.agentRun, aggregateVersion: 2, correlationId: 'correlation', causationId: null,
          occurredAt: task.updatedAt, payload: { eventKind: 'PROGRESS', safeText: '正在核验验收标准', progressPercent: 60 },
        },
      }],
      hasMore: false, taskTerminal: false, nextCursor: 'cursor-1',
    },
    eventErrorMessage: null,
    liveState: { phase: 'connected' as const, errorMessage: null, projectionGap: false },
    reviewListPhase: 'empty' as const,
    reviews: null,
    selectedReviewRequestId: null,
    reviewDetailPhase: 'idle' as const,
    review: null,
    reviewListErrorMessage: null,
    reviewDetailErrorMessage: null,
    reviewCommand: {
      phase: 'idle' as const, operation: null, reviewRequestId: null,
      receiptCorrelationId: null, execution: null, errorMessage: null,
      errorStatus: null, errorCode: null, errorDetails: {}, retryable: false,
    },
    canGateReview: false,
    canConfirmDelivery: false,
    principals: [{ principalId: fixtureIds.principal, displayName: '张凯旋' }],
    canControl: true,
    online: true,
    commandPending: null,
    commandErrorMessage: null,
    commandRetryable: false,
    commandVersionConflict: null,
    onSelectAttempt: vi.fn(),
    onRetry: vi.fn(),
    onRetryRuntime: vi.fn(),
    onRetryCoding: vi.fn(),
    onLoadCodingPatch: vi.fn(),
    onLoadCodingCommandsMore: vi.fn(),
    onLoadCodingTestsMore: vi.fn(),
    onLoadCodingCommandLog: vi.fn(),
    onLoadCodingTestReport: vi.fn(),
    onRetryFleet: vi.fn(),
    onRetryAssociations: vi.fn(),
    onLoadEventsMore: vi.fn(),
    onRetryEvents: vi.fn(),
    onSelectReview: vi.fn(),
    onRetryReviews: vi.fn(),
    onRetryReviewDetail: vi.fn(),
    onExecuteReviewer: vi.fn().mockResolvedValue(true),
    onDecideReview: vi.fn().mockResolvedValue(true),
    onRequestReviewChanges: vi.fn().mockResolvedValue(true),
    onRetryReviewCommand: vi.fn().mockResolvedValue(true),
    onClearReviewCommand: vi.fn(),
    onCommand: vi.fn().mockResolvedValue(undefined),
    onRetryCommand: vi.fn().mockResolvedValue(undefined),
    onClearCommand: vi.fn(),
    ...overrides,
  }
}

function fleet(): RuntimeFleetSummary {
  return {
    environment: 'production', observedAt: '2026-08-15T12:01:00Z', health: 'DEGRADED',
    runtimeCount: 2, workerCount: 3, activeWorkerCount: 2, staleWorkerCount: 1,
    drainingWorkerCount: 0, capacity: { maximum: 6, active: 4, available: 2 },
    waitingRuntimeExecutions: 1, waitingCauses: [{ cause: 'CAPACITY', count: 1 }],
  }
}
