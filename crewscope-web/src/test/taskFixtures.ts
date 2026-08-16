import type { TaskEventConnection, TaskGateway } from '../domains/task/gateway'
import type {
  CreateTaskCommand,
  MemberTaskCommand,
  TaskAssociationPage,
  TaskAssociations,
  TaskDetails,
  TaskEventPage,
  TaskExecution,
  TaskListQuery,
  TaskPage,
  RuntimeFleetSummary,
  TaskRuntimeFacts,
  TaskScope,
  TaskCommandReceipt,
  TaskSummary,
} from '../domains/task/types'
import { fixtureIds } from './scopeFixtures'

export const taskIds = {
  first: '00000000-0000-0000-0000-000000003101',
  second: '00000000-0000-0000-0000-000000003102',
  security: '00000000-0000-0000-0000-000000003103',
  execution: '00000000-0000-0000-0000-000000003201',
  previousExecution: '00000000-0000-0000-0000-000000003202',
  plan: '00000000-0000-0000-0000-000000003211',
  previousPlan: '00000000-0000-0000-0000-000000003212',
  stepRunning: '00000000-0000-0000-0000-000000003221',
  stepWaiting: '00000000-0000-0000-0000-000000003222',
  session: '00000000-0000-0000-0000-000000003231',
  agentRun: '00000000-0000-0000-0000-000000003241',
  lease: '00000000-0000-0000-0000-000000003251',
  workItem: '00000000-0000-0000-0000-000000003301',
  conversation: '00000000-0000-0000-0000-000000003401',
} as const

export const fixtureTasks: Record<string, TaskSummary[]> = {
  [fixtureIds.teamPlatform]: [
    taskSummary(taskIds.first, fixtureIds.projectCrewScope, '完成 Task Gateway'),
    taskSummary(taskIds.second, fixtureIds.projectCrewScope, '验证 Cursor 隔离'),
  ],
  [fixtureIds.teamSecurity]: [
    taskSummary(taskIds.security, fixtureIds.projectRuntime, '检查 Runtime 安全'),
  ],
}

export class FixtureTaskGateway implements TaskGateway {
  listCalls: TaskListQuery[] = []
  eventCalls: Array<{ taskId: string, after?: string }> = []
  associationCalls: string[] = []
  tasks = structuredClone(fixtureTasks)
  pageSize = 50
  createCalls: Array<{ command: CreateTaskCommand, idempotencyKey: string }> = []
  commandCalls: Array<{ command: MemberTaskCommand, idempotencyKey: string }> = []
  runtimeCalls: string[] = []

  async createTask(command: CreateTaskCommand, idempotencyKey: string): Promise<TaskCommandReceipt> {
    this.createCalls.push({ command: structuredClone(command), idempotencyKey })
    const id = `00000000-0000-0000-0000-${String(3900 + this.createCalls.length).padStart(12, '0')}`
    const created = taskSummary(id, command.projectId, command.input.objective)
    created.workItemId = command.workItemId
    this.tasks[command.scope.teamId] = [created, ...(this.tasks[command.scope.teamId] ?? [])]
    return {
      commandId: crypto.randomUUID(),
      domainEventId: crypto.randomUUID(),
      committedVersion: 0,
      correlationId: crypto.randomUUID(),
    }
  }

  async commandTask(command: MemberTaskCommand, idempotencyKey: string): Promise<TaskCommandReceipt> {
    this.commandCalls.push({ command: structuredClone(command), idempotencyKey })
    const selected = this.tasks[command.scope.teamId]?.find(item => item.id === command.taskId)
    if (!selected || selected.currentExecutionId !== command.executionId) throw new Error('Task attempt not found')
    if (command.operation === 'PAUSE') selected.currentExecutionStatus = 'PAUSE_REQUESTED'
    if (command.operation === 'RESUME') selected.currentExecutionStatus = 'READY'
    if (command.operation === 'CANCEL') {
      selected.currentExecutionStatus = 'CANCELLED'
      selected.status = 'CANCELLED'
    }
    if (command.operation === 'RETRY') {
      selected.currentExecutionId = crypto.randomUUID()
      selected.currentAttempt = (selected.currentAttempt ?? 0) + 1
      selected.currentExecutionStatus = 'READY'
      selected.status = 'ACTIVE'
    }
    selected.currentWaitingReason = null
    selected.version += 1
    return {
      commandId: crypto.randomUUID(), domainEventId: crypto.randomUUID(),
      committedVersion: selected.version, correlationId: crypto.randomUUID(),
    }
  }

  async listTasks(query: TaskListQuery, signal?: AbortSignal): Promise<TaskPage> {
    throwIfAborted(signal)
    this.listCalls.push(structuredClone(query))
    const filtered = (this.tasks[query.teamId] ?? []).filter(item =>
      (!query.projectId || item.projectId === query.projectId)
      && (!query.status || item.status === query.status)
      && (!query.ownerPrincipalId || item.ownerPrincipalId === query.ownerPrincipalId),
    )
    const offset = query.after ? Number(query.after) : 0
    const size = Math.min(query.limit ?? this.pageSize, this.pageSize)
    const items = filtered.slice(offset, offset + size)
    const next = offset + items.length
    return { items: structuredClone(items), nextCursor: next < filtered.length ? String(next) : null }
  }

  async getTask(scope: TaskScope, taskId: string, signal?: AbortSignal): Promise<TaskDetails> {
    throwIfAborted(signal)
    const summary = this.tasks[scope.teamId]?.find(item => item.id === taskId)
    if (!summary) throw new Error('Task not found')
    return details(summary)
  }

  async listAttempts(scope: TaskScope, taskId: string, signal?: AbortSignal): Promise<TaskExecution[]> {
    throwIfAborted(signal)
    const summary = this.tasks[scope.teamId]?.find(item => item.id === taskId)
    if (!summary) throw new Error('Task not found')
    const current = execution()
    current.id = summary.currentExecutionId ?? current.id
    current.attempt = summary.currentAttempt ?? current.attempt
    current.status = summary.currentExecutionStatus ?? current.status
    current.version = summary.version + 1
    current.waiting = summary.currentWaitingReason
      ? { reason: summary.currentWaitingReason, waitingSince: summary.updatedAt }
      : null
    return current.attempt > 1 ? [current, previousExecution()] : [current]
  }

  async getRuntimeFacts(
    scope: TaskScope,
    taskId: string,
    executionId: string,
    signal?: AbortSignal,
  ): Promise<TaskRuntimeFacts> {
    throwIfAborted(signal)
    await this.getTask(scope, taskId, signal)
    this.runtimeCalls.push(executionId)
    const facts = runtimeFacts(executionId)
    const attempt = (await this.listAttempts(scope, taskId, signal)).find(item => item.id === executionId)
    if (attempt) facts.execution = attempt
    return facts
  }

  async getRuntimeHealth(scope: TaskScope, signal?: AbortSignal): Promise<RuntimeFleetSummary> {
    throwIfAborted(signal)
    return {
      environment: 'production',
      observedAt: '2026-08-15T12:01:00Z',
      health: scope.teamId === fixtureIds.teamSecurity ? 'HEALTHY' : 'DEGRADED',
      runtimeCount: 2,
      workerCount: 3,
      activeWorkerCount: 2,
      staleWorkerCount: scope.teamId === fixtureIds.teamSecurity ? 0 : 1,
      drainingWorkerCount: 0,
      capacity: { maximum: 6, active: 4, available: 2 },
      waitingRuntimeExecutions: 1,
      waitingCauses: [{ cause: 'CAPACITY', count: 1 }],
    }
  }

  async listEvents(
    _scope: TaskScope,
    taskId: string,
    after?: string,
    _limit?: number,
    signal?: AbortSignal,
  ): Promise<TaskEventPage> {
    throwIfAborted(signal)
    this.eventCalls.push({ taskId, after })
    const sequence = after ? 2 : 1
    return {
      items: [{
        cursor: `cursor-${sequence}`,
        context: { taskId, taskExecutionId: taskIds.execution, stepExecutionId: null, agentRunId: null, executionLeaseId: null },
        projectionGap: false,
        event: {
          eventId: `event-${sequence}`,
          domainEventId: `domain-${sequence}`,
          streamType: 'TASK',
          eventType: 'TASK_PROGRESS_RECORDED',
          schemaVersion: '1',
          aggregateType: 'TaskExecution',
          aggregateId: taskIds.execution,
          aggregateVersion: sequence,
          correlationId: 'correlation',
          causationId: null,
          occurredAt: '2026-08-15T12:00:00Z',
          payload: { progress: sequence * 10 },
        },
      }],
      hasMore: !after,
      taskTerminal: false,
      nextCursor: after ? null : 'cursor-1',
    }
  }

  async streamEvents(
    _scope: TaskScope,
    _taskId: string,
    _after?: string,
    signal?: AbortSignal,
  ): Promise<TaskEventConnection> {
    return { events: emptyTaskEvents(signal) }
  }

  async listByWorkItem(
    scope: TaskScope,
    _projectId: string,
    _workItemId: string,
    after?: string,
  ): Promise<TaskAssociationPage> {
    return this.associationPage(scope, `work-item:${after ?? 'first'}`)
  }

  async listByConversation(scope: TaskScope, _conversationId: string, after?: string): Promise<TaskAssociationPage> {
    return this.associationPage(scope, `conversation:${after ?? 'first'}`)
  }

  async getAssociations(scope: TaskScope, taskId: string): Promise<TaskAssociations> {
    this.associationCalls.push(`task:${taskId}`)
    const task = this.tasks[scope.teamId]?.find(item => item.id === taskId)
    if (!task) throw new Error('Task not found')
    return {
      task: { id: task.id, projectId: task.projectId, workItemId: task.workItemId, status: task.status, objective: task.objective, href: `/work?task=${task.id}` },
      workItem: { id: task.workItemId, projectId: task.projectId, key: 'CRW-18', title: 'Task 前端', status: 'IN_PROGRESS', href: `/work?workItem=${task.workItemId}` },
      conversations: { items: [], nextCursor: null },
    }
  }

  private associationPage(scope: TaskScope, key: string): TaskAssociationPage {
    this.associationCalls.push(key)
    const task = this.tasks[scope.teamId]?.[0]
    return {
      items: task ? [{ origin: 'WORK_ITEM_ROOT', associatedAt: task.createdAt, task: { ...task, href: `/work?task=${task.id}` } }] : [],
      nextCursor: null,
    }
  }
}

async function* emptyTaskEvents(signal?: AbortSignal): AsyncGenerator<never> {
  if (signal?.aborted) throw new DOMException('Aborted', 'AbortError')
}

function taskSummary(id: string, projectId: string, objective: string): TaskSummary {
  return {
    id,
    workspaceId: projectId === fixtureIds.projectRuntime ? fixtureIds.workspaceSecurity : fixtureIds.workspacePlatform,
    projectId,
    workItemId: taskIds.workItem,
    objective,
    acceptanceCriteria: ['通过自动化测试'],
    status: 'ACTIVE',
    currentExecutionId: taskIds.execution,
    currentAttempt: 1,
    currentExecutionStatus: 'RUNNING',
    currentWaitingReason: null,
    ownerPrincipalId: fixtureIds.principal,
    version: 1,
    createdAt: '2026-08-15T10:00:00Z',
    updatedAt: '2026-08-15T12:00:00Z',
  }
}

export function details(summary: TaskSummary): TaskDetails {
  return {
    id: summary.id,
    teamId: summary.projectId === fixtureIds.projectRuntime ? fixtureIds.teamSecurity : fixtureIds.teamPlatform,
    workspaceId: summary.workspaceId,
    projectId: summary.projectId,
    workItemId: summary.workItemId,
    objective: summary.objective,
    acceptanceCriteria: summary.acceptanceCriteria,
    source: { type: 'WORK_ITEM', workItemVersion: 1, conversationId: null, inputType: null, inputId: null, inputVersion: null },
    responsibilitySnapshot: [],
    responsibilityCapturedAt: summary.createdAt,
    status: summary.status,
    currentExecutionId: summary.currentExecutionId,
    cancellation: null,
    version: summary.version,
    audit: { createdByPrincipalId: fixtureIds.principal, createdAt: summary.createdAt, updatedByPrincipalId: fixtureIds.principal, updatedAt: summary.updatedAt },
    attempts: [execution()],
  }
}

export function execution(): TaskExecution {
  return {
    id: taskIds.execution,
    attempt: 1,
    maxAttempts: 3,
    parentExecutionId: null,
    priority: 50,
    notBefore: '2026-08-15T10:00:00Z',
    status: 'RUNNING',
    waiting: null,
    controlRequest: null,
    terminal: null,
    executorPrincipalId: fixtureIds.principal,
    currentPlanVersionId: taskIds.plan,
    version: 2,
    audit: { createdByPrincipalId: fixtureIds.principal, createdAt: '2026-08-15T10:00:00Z', updatedByPrincipalId: fixtureIds.principal, updatedAt: '2026-08-15T12:00:00Z' },
  }
}

export function previousExecution(): TaskExecution {
  const value = execution()
  return {
    ...value,
    id: taskIds.previousExecution,
    attempt: 1,
    status: 'FAILED',
    currentPlanVersionId: taskIds.previousPlan,
    terminal: {
      status: 'FAILED',
      decidedByPrincipalId: fixtureIds.principal,
      decidedAt: '2026-08-15T09:55:00Z',
      failureClass: 'TRANSIENT',
      failureCode: 'WORKER_LOST',
    },
  }
}

export function runtimeFacts(executionId: string = taskIds.execution): TaskRuntimeFacts {
  if (executionId === taskIds.previousExecution) {
    return {
      execution: previousExecution(),
      planVersions: [{
        id: taskIds.previousPlan, revision: 1, parentVersionId: null, changeReason: '初始计划',
        markdown: '建立失败前的执行基线。',
        steps: [{ key: 'prepare', sequence: 1, title: '准备工作区', type: 'ACTION', dependencyKeys: [], requiredCapabilities: ['CODE'], requiredTools: ['github'], critical: true }],
        todoSummary: [{ content: '准备工作区', status: 'FAILED', priority: 'HIGH', planStepKey: 'prepare' }],
        publishedByPrincipalId: fixtureIds.principal, publishedAt: '2026-08-15T09:01:00Z',
      }],
      steps: [], sessions: [], agentRuns: [], interrupts: [], snapshots: [], leases: [],
    }
  }
  return {
    execution: execution(),
    planVersions: [
      {
        id: taskIds.previousPlan, revision: 1, parentVersionId: null, changeReason: '初始计划',
        markdown: '先建立公开契约，再实现详情视图。',
        steps: [{ key: 'contract', sequence: 1, title: '稳定公开契约', type: 'ACTION', dependencyKeys: [], requiredCapabilities: ['CODE'], requiredTools: [], critical: true }],
        todoSummary: [{ content: '稳定公开契约', status: 'COMPLETED', priority: 'HIGH', planStepKey: 'contract' }],
        publishedByPrincipalId: fixtureIds.principal, publishedAt: '2026-08-15T10:05:00Z',
      },
      {
        id: taskIds.plan, revision: 2, parentVersionId: taskIds.previousPlan, changeReason: '补充 Runtime 可观测性',
        markdown: '展示 Task、责任、Plan、Step、AgentRun 与 Lease 的安全事实。',
        steps: [
          { key: 'contract', sequence: 1, title: '稳定公开契约', type: 'ACTION', dependencyKeys: [], requiredCapabilities: ['CODE'], requiredTools: [], critical: true },
          { key: 'view', sequence: 2, title: '实现详情视图', type: 'ACTION', dependencyKeys: ['contract'], requiredCapabilities: ['CODE'], requiredTools: ['github'], critical: true },
          { key: 'verify', sequence: 3, title: '验证安全边界', type: 'GATE', dependencyKeys: ['view'], requiredCapabilities: [], requiredTools: [], critical: true },
        ],
        todoSummary: [
          { content: '稳定公开契约', status: 'COMPLETED', priority: 'HIGH', planStepKey: 'contract' },
          { content: '实现详情视图', status: 'IN_PROGRESS', priority: 'HIGH', planStepKey: 'view' },
          { content: '验证安全边界', status: 'TODO', priority: 'HIGH', planStepKey: 'verify' },
        ],
        publishedByPrincipalId: fixtureIds.principal, publishedAt: '2026-08-15T10:20:00Z',
      },
    ],
    steps: [
      {
        id: taskIds.stepRunning, planVersionId: taskIds.plan, planStepKey: 'contract', sequence: 1,
        critical: true, runAttempt: 1, maxRunAttempts: 2, status: 'COMPLETED', waitReason: null,
        checkpoint: { sequence: 2, code: 'CONTRACT_READY', recordedByPrincipalId: fixtureIds.principal, recordedAt: '2026-08-15T10:30:00Z' },
        failureClass: null, failureCode: null, version: 2,
        audit: { createdByPrincipalId: fixtureIds.principal, createdAt: '2026-08-15T10:20:00Z', updatedByPrincipalId: fixtureIds.principal, updatedAt: '2026-08-15T10:30:00Z' },
      },
      {
        id: taskIds.stepWaiting, planVersionId: taskIds.plan, planStepKey: 'view', sequence: 2,
        critical: true, runAttempt: 1, maxRunAttempts: 2, status: 'WAITING', waitReason: 'WAITING_RUNTIME',
        checkpoint: null, failureClass: null, failureCode: null, version: 1,
        audit: { createdByPrincipalId: fixtureIds.principal, createdAt: '2026-08-15T10:31:00Z', updatedByPrincipalId: fixtureIds.principal, updatedAt: '2026-08-15T12:00:00Z' },
      },
    ],
    sessions: [{
      id: taskIds.session, stepExecutionId: taskIds.stepWaiting, purpose: '实现 Task 详情',
      agentPrincipalId: fixtureIds.principal, agentProfileId: '00000000-0000-0000-0000-000000003261',
      agentProfileVersion: 2, status: 'ACTIVE', version: 1,
      audit: { createdByPrincipalId: fixtureIds.principal, createdAt: '2026-08-15T10:31:00Z', updatedByPrincipalId: fixtureIds.principal, updatedAt: '2026-08-15T12:00:00Z' },
    }],
    agentRuns: [{
      id: taskIds.agentRun, stepExecutionId: taskIds.stepWaiting, runtimeSessionId: taskIds.session,
      agentPrincipalId: fixtureIds.principal, agentProfileId: '00000000-0000-0000-0000-000000003261',
      agentProfileVersion: 2, runSequence: 2, status: 'INTERRUPTED',
      segments: [{ sequence: 1, kind: 'PRIMARY', resumedFromInterruptId: null, status: 'ENDED', startedAt: '2026-08-15T10:31:00Z', endedAt: '2026-08-15T11:58:00Z' }],
      continuityGap: { previousRunId: 'run-previous', lastValidSnapshotId: null, firstMissingCheckpoint: 3, lastMissingCheckpoint: 4, reason: 'WORKER_LOST', detectedAt: '2026-08-15T11:59:00Z' },
      terminal: null, version: 2,
      audit: { createdByPrincipalId: fixtureIds.principal, createdAt: '2026-08-15T10:31:00Z', updatedByPrincipalId: fixtureIds.principal, updatedAt: '2026-08-15T11:59:00Z' },
    }],
    interrupts: [{
      id: 'interrupt-1', agentRunId: taskIds.agentRun, segmentSequence: 1, kind: 'RUNTIME_LOST', status: 'OPEN',
      resolvedByPrincipalId: null, resolvedAt: null, version: 0,
      audit: { createdByPrincipalId: null, createdAt: '2026-08-15T11:59:00Z', updatedByPrincipalId: null, updatedAt: '2026-08-15T11:59:00Z' },
    }],
    snapshots: [{
      id: 'snapshot-1', agentRunId: taskIds.agentRun, runtimeSessionId: taskIds.session,
      agentProfileId: '00000000-0000-0000-0000-000000003261', agentProfileVersion: 2,
      snapshotSequence: 2, checkpointSequence: 2, sizeBytes: 4096, status: 'VALID', invalidReasonCode: null,
      version: 0, audit: { createdByPrincipalId: null, createdAt: '2026-08-15T11:50:00Z', updatedByPrincipalId: null, updatedAt: '2026-08-15T11:50:00Z' },
    }],
    leases: [{
      id: taskIds.lease, environment: 'production', runtimeId: 'runtime-safe-id', workerId: 'worker-safe-id',
      phase: 'EXECUTING', status: 'EXPIRED', acquiredAt: '2026-08-15T10:30:00Z',
      lastHeartbeatAt: '2026-08-15T11:58:00Z', expiresAt: '2026-08-15T11:59:00Z',
      releaseReason: 'WORKER_LOST', releasedAt: '2026-08-15T11:59:00Z', version: 3,
    }],
  }
}

function throwIfAborted(signal?: AbortSignal): void {
  if (signal?.aborted) throw new DOMException('Aborted', 'AbortError')
}
