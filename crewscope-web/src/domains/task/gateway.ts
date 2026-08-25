import { apiClient, type CrewScopeApiClient } from '../../api/client'
import { parseServerSentEvents } from '../../api/sse'
import type {
  AgentInterruptSummary,
  AgentRunSummary,
  AgentSessionSummary,
  AgentStateSnapshotSummary,
  AuditSummary,
  CreateTaskCommand,
  ExecutionLeaseSummary,
  PlanVersion,
  RuntimeFleetSummary,
  StepExecution,
  TaskAssociationPage,
  TaskAssociations,
  TaskDetails,
  TaskEventItem,
  TaskEventPage,
  TaskExecution,
  TaskListQuery,
  MemberTaskCommand,
  TaskPage,
  TaskRuntimeFacts,
  TaskScope,
  TaskSummary,
  TaskCommandReceipt,
  TaskDelegationPreflight,
  TaskDelegationSelection,
} from './types'

export interface TaskGateway {
  createTask(command: CreateTaskCommand, idempotencyKey: string): Promise<TaskCommandReceipt>
  preflightDelegation(
    scope: TaskScope,
    projectId: string,
    workItemId: string,
    selection: TaskDelegationSelection,
    signal?: AbortSignal,
  ): Promise<TaskDelegationPreflight>
  commandTask(command: MemberTaskCommand, idempotencyKey: string): Promise<TaskCommandReceipt>
  listTasks(query: TaskListQuery, signal?: AbortSignal): Promise<TaskPage>
  getTask(scope: TaskScope, taskId: string, signal?: AbortSignal): Promise<TaskDetails>
  listAttempts(scope: TaskScope, taskId: string, signal?: AbortSignal): Promise<TaskExecution[]>
  getRuntimeFacts(scope: TaskScope, taskId: string, executionId: string, signal?: AbortSignal): Promise<TaskRuntimeFacts>
  getRuntimeHealth(scope: TaskScope, signal?: AbortSignal): Promise<RuntimeFleetSummary>
  listEvents(scope: TaskScope, taskId: string, after?: string, limit?: number, signal?: AbortSignal): Promise<TaskEventPage>
  streamEvents(scope: TaskScope, taskId: string, after?: string, signal?: AbortSignal): Promise<TaskEventConnection>
  listByWorkItem(
    scope: TaskScope,
    projectId: string,
    workItemId: string,
    after?: string,
    limit?: number,
    signal?: AbortSignal,
  ): Promise<TaskAssociationPage>
  listByConversation(
    scope: TaskScope,
    conversationId: string,
    after?: string,
    limit?: number,
    signal?: AbortSignal,
  ): Promise<TaskAssociationPage>
  getAssociations(
    scope: TaskScope,
    taskId: string,
    after?: string,
    limit?: number,
    signal?: AbortSignal,
  ): Promise<TaskAssociations>
}

export interface TaskEventConnection {
  events: AsyncIterable<TaskEventItem>
}

/** Member-facing Task HTTP adapter with an explicit response whitelist. */
export class HttpTaskGateway implements TaskGateway {
  constructor(private readonly client: CrewScopeApiClient = apiClient) {}

  createTask(command: CreateTaskCommand, idempotencyKey: string): Promise<TaskCommandReceipt> {
    return this.client.post(
      `/organizations/${segment(command.scope.organizationId)}/teams/${segment(command.scope.teamId)}`
        + `/work-projects/${segment(command.projectId)}/work-items/${segment(command.workItemId)}/tasks`,
      command.input,
      { idempotencyKey, expectedVersion: command.expectedVersion },
    )
  }

  async preflightDelegation(
    scope: TaskScope,
    projectId: string,
    workItemId: string,
    selection: TaskDelegationSelection,
    signal?: AbortSignal,
  ): Promise<TaskDelegationPreflight> {
    const value = await this.client.post<TaskDelegationPreflight>(
      `/organizations/${segment(scope.organizationId)}/teams/${segment(scope.teamId)}`
        + `/work-projects/${segment(projectId)}/work-items/${segment(workItemId)}/tasks/preflight`,
      selection,
      { signal },
    )
    return mapDelegationPreflight(value)
  }

  commandTask(command: MemberTaskCommand, idempotencyKey: string): Promise<TaskCommandReceipt> {
    const body = command.operation === 'PAUSE' || command.operation === 'CANCEL'
      ? { reason: command.reason }
      : command.operation === 'RETRY' && command.agentConfigurationRevision !== undefined
        ? { agentConfigurationRevision: command.agentConfigurationRevision }
        : undefined
    return this.client.post(
      `${root(command.scope)}/${segment(command.taskId)}/attempts/${segment(command.executionId)}`
        + `/${command.operation.toLowerCase()}`,
      body,
      { idempotencyKey, expectedVersion: command.expectedVersion },
    )
  }

  async listTasks(query: TaskListQuery, signal?: AbortSignal): Promise<TaskPage> {
    const search = pageSearch(query.after, query.limit)
    if (query.projectId) search.set('projectId', query.projectId)
    if (query.status) search.set('status', query.status)
    if (query.ownerPrincipalId) search.set('ownerPrincipalId', query.ownerPrincipalId)
    const value = await this.client.get<TaskPage>(`${root(query)}?${search}`, { signal })
    return { items: value.items.map(mapTaskSummary), nextCursor: value.nextCursor }
  }

  async getTask(scope: TaskScope, taskId: string, signal?: AbortSignal): Promise<TaskDetails> {
    const value = await this.client.get<TaskDetails>(`${root(scope)}/${segment(taskId)}`, { signal })
    return mapTaskDetails(value)
  }

  async listAttempts(scope: TaskScope, taskId: string, signal?: AbortSignal): Promise<TaskExecution[]> {
    const values = await this.client.get<TaskExecution[]>(`${root(scope)}/${segment(taskId)}/attempts`, { signal })
    return values.map(mapExecution)
  }

  async getRuntimeFacts(
    scope: TaskScope,
    taskId: string,
    executionId: string,
    signal?: AbortSignal,
  ): Promise<TaskRuntimeFacts> {
    const value = await this.client.get<TaskRuntimeFacts>(
      `${root(scope)}/${segment(taskId)}/attempts/${segment(executionId)}/runtime-facts`,
      { signal },
    )
    return mapRuntimeFacts(value)
  }

  async getRuntimeHealth(scope: TaskScope, signal?: AbortSignal): Promise<RuntimeFleetSummary> {
    const value = await this.client.get<RuntimeFleetSummary>(
      `/organizations/${segment(scope.organizationId)}/teams/${segment(scope.teamId)}/runtime-health`,
      { signal },
    )
    return {
      ...pick(value, [
        'environment', 'observedAt', 'health', 'runtimeCount', 'workerCount', 'activeWorkerCount',
        'staleWorkerCount', 'drainingWorkerCount', 'waitingRuntimeExecutions',
      ]),
      capacity: { ...pick(value.capacity, ['maximum', 'active', 'available']) },
      waitingCauses: value.waitingCauses.map(item => ({ ...pick(item, ['cause', 'count']) })),
    }
  }

  async listEvents(
    scope: TaskScope,
    taskId: string,
    after?: string,
    limit = 50,
    signal?: AbortSignal,
  ): Promise<TaskEventPage> {
    const value = await this.client.get<TaskEventPage>(
      `${root(scope)}/${segment(taskId)}/events?${pageSearch(after, limit)}`,
      { signal },
    )
    return {
      items: value.items.map(mapEvent),
      hasMore: value.hasMore,
      taskTerminal: value.taskTerminal,
      nextCursor: value.nextCursor,
    }
  }

  async streamEvents(
    scope: TaskScope,
    taskId: string,
    after?: string,
    signal?: AbortSignal,
  ): Promise<TaskEventConnection> {
    const search = new URLSearchParams()
    if (after) search.set('after', after)
    const suffix = search.size > 0 ? `?${search.toString()}` : ''
    const response = await this.client.open(
      `${root(scope)}/${segment(taskId)}/events${suffix}`,
      { method: 'GET', signal },
      'text/event-stream',
    )
    if (!response.body) throw new TypeError('Task SSE response body is unavailable')
    return { events: taskEvents(response.body, taskId) }
  }

  async listByWorkItem(
    scope: TaskScope,
    projectId: string,
    workItemId: string,
    after?: string,
    limit = 50,
    signal?: AbortSignal,
  ): Promise<TaskAssociationPage> {
    const value = await this.client.get<TaskAssociationPage>(
      `/organizations/${segment(scope.organizationId)}/teams/${segment(scope.teamId)}`
        + `/work-projects/${segment(projectId)}/work-items/${segment(workItemId)}/tasks`
        + `?${pageSearch(after, limit)}`,
      { signal },
    )
    return mapAssociationPage(value)
  }

  async listByConversation(
    scope: TaskScope,
    conversationId: string,
    after?: string,
    limit = 50,
    signal?: AbortSignal,
  ): Promise<TaskAssociationPage> {
    const value = await this.client.get<TaskAssociationPage>(
      `/organizations/${segment(scope.organizationId)}/teams/${segment(scope.teamId)}`
        + `/conversations/${segment(conversationId)}/tasks?${pageSearch(after, limit)}`,
      { signal },
    )
    return mapAssociationPage(value)
  }

  async getAssociations(
    scope: TaskScope,
    taskId: string,
    after?: string,
    limit = 50,
    signal?: AbortSignal,
  ): Promise<TaskAssociations> {
    const value = await this.client.get<TaskAssociations>(
      `${root(scope)}/${segment(taskId)}/associations?${pageSearch(after, limit)}`,
      { signal },
    )
    return {
      task: { ...pick(value.task, ['id', 'projectId', 'workItemId', 'status', 'objective', 'href']) },
      workItem: { ...pick(value.workItem, ['id', 'projectId', 'key', 'title', 'status', 'href']) },
      conversations: {
        items: value.conversations.items.map(item => ({
          ...pick(item, ['id', 'title', 'visibility', 'status', 'origin', 'associatedAt', 'href']),
        })),
        nextCursor: value.conversations.nextCursor,
      },
    }
  }
}

function mapDelegationPreflight(value: TaskDelegationPreflight): TaskDelegationPreflight {
  return {
    ...pick(value, [
      'agentProfileId', 'agentProfileVersion', 'executionScope', 'configurationRevision',
      'configurationHash', 'bindingSource', 'templateVersion', 'policyPackId',
      'policyPackVersion', 'resolutionHash',
    ]),
    primary: mapDelegationModel(value.primary),
    fallback: value.fallback ? mapDelegationModel(value.fallback) : null,
  }
}

function mapDelegationModel(value: TaskDelegationPreflight['primary']): TaskDelegationPreflight['primary'] {
  return { ...pick(value, [
    'role', 'providerKey', 'connectionId', 'connectionOwnerType', 'modelId',
    'catalogRevision', 'modelRevision', 'priceRevision',
  ]) }
}

function root(scope: TaskScope): string {
  return `/organizations/${segment(scope.organizationId)}/teams/${segment(scope.teamId)}/tasks`
}

function pageSearch(after?: string, limit = 50): URLSearchParams {
  const search = new URLSearchParams({ limit: String(limit) })
  if (after) search.set('after', after)
  return search
}

function segment(value: string): string {
  return encodeURIComponent(value)
}

function mapTaskSummary(value: TaskSummary): TaskSummary {
  return { ...pick(value, [
    'id', 'workspaceId', 'projectId', 'workItemId', 'objective', 'acceptanceCriteria', 'status',
    'currentExecutionId', 'currentAttempt', 'currentExecutionStatus', 'currentWaitingReason',
    'ownerPrincipalId',
    'version', 'createdAt', 'updatedAt',
  ]) }
}

function mapTaskDetails(value: TaskDetails): TaskDetails {
  return {
    ...pick(value, [
      'id', 'teamId', 'workspaceId', 'projectId', 'workItemId', 'objective', 'acceptanceCriteria',
      'responsibilityCapturedAt', 'status', 'currentExecutionId', 'version',
    ]),
    source: { ...pick(value.source, ['type', 'workItemVersion', 'conversationId', 'inputType', 'inputId', 'inputVersion']) },
    responsibilitySnapshot: value.responsibilitySnapshot.map(item => ({ ...pick(item, [
      'assignmentId', 'assignmentVersion', 'role', 'principalId', 'principalType', 'memberId',
      'assignedAt', 'acceptedAt',
    ]) })),
    cancellation: value.cancellation
      ? { ...pick(value.cancellation, ['cancelledByPrincipalId', 'cancelledAt', 'reason']) }
      : null,
    audit: mapAudit(value.audit),
    attempts: value.attempts.map(mapExecution),
  }
}

function mapExecution(value: TaskExecution): TaskExecution {
  return {
    ...pick(value, [
      'id', 'attempt', 'maxAttempts', 'parentExecutionId', 'priority', 'notBefore', 'status',
      'executorPrincipalId', 'currentPlanVersionId', 'version',
    ]),
    waiting: value.waiting ? { ...pick(value.waiting, ['reason', 'waitingSince']) } : null,
    controlRequest: value.controlRequest
      ? { ...pick(value.controlRequest, ['type', 'requestedByPrincipalId', 'requestedAt', 'reason']) }
      : null,
    terminal: value.terminal
      ? { ...pick(value.terminal, ['status', 'decidedByPrincipalId', 'decidedAt', 'failureClass', 'failureCode']) }
      : null,
    audit: mapAudit(value.audit),
  }
}

function mapRuntimeFacts(value: TaskRuntimeFacts): TaskRuntimeFacts {
  return {
    execution: mapExecution(value.execution),
    planVersions: value.planVersions.map(mapPlanVersion),
    steps: value.steps.map(mapStep),
    sessions: value.sessions.map(mapSession),
    agentRuns: value.agentRuns.map(mapAgentRun),
    interrupts: value.interrupts.map(mapInterrupt),
    snapshots: value.snapshots.map(mapSnapshot),
    leases: value.leases.map(mapLease),
  }
}

function mapPlanVersion(value: PlanVersion): PlanVersion {
  return {
    ...pick(value, ['id', 'revision', 'parentVersionId', 'changeReason', 'markdown', 'publishedByPrincipalId', 'publishedAt']),
    steps: value.steps.map(step => ({ ...pick(step, [
      'key', 'sequence', 'title', 'type', 'dependencyKeys', 'requiredCapabilities', 'requiredTools', 'critical',
    ]) })),
    todoSummary: value.todoSummary.map(todo => ({ ...pick(todo, ['content', 'status', 'priority', 'planStepKey']) })),
  }
}

function mapStep(value: StepExecution): StepExecution {
  return {
    ...pick(value, [
      'id', 'planVersionId', 'planStepKey', 'sequence', 'critical', 'runAttempt', 'maxRunAttempts',
      'status', 'waitReason', 'failureClass', 'failureCode', 'version',
    ]),
    checkpoint: value.checkpoint
      ? { ...pick(value.checkpoint, ['sequence', 'code', 'recordedByPrincipalId', 'recordedAt']) }
      : null,
    audit: mapAudit(value.audit),
  }
}

function mapSession(value: AgentSessionSummary): AgentSessionSummary {
  return {
    ...pick(value, [
      'id', 'stepExecutionId', 'purpose', 'agentPrincipalId', 'agentProfileId', 'agentProfileVersion',
      'status', 'version',
    ]),
    audit: mapAudit(value.audit),
  }
}

function mapAgentRun(value: AgentRunSummary): AgentRunSummary {
  return {
    ...pick(value, [
      'id', 'stepExecutionId', 'runtimeSessionId', 'agentPrincipalId', 'agentProfileId',
      'agentProfileVersion', 'runSequence', 'status', 'version',
    ]),
    segments: value.segments.map(item => ({ ...pick(item, [
      'sequence', 'kind', 'resumedFromInterruptId', 'status', 'startedAt', 'endedAt',
    ]) })),
    continuityGap: value.continuityGap ? { ...pick(value.continuityGap, [
      'previousRunId', 'lastValidSnapshotId', 'firstMissingCheckpoint', 'lastMissingCheckpoint',
      'reason', 'detectedAt',
    ]) } : null,
    terminal: value.terminal
      ? { ...pick(value.terminal, ['status', 'failureCode', 'resultArtifactId', 'occurredAt']) }
      : null,
    audit: mapAudit(value.audit),
  }
}

function mapInterrupt(value: AgentInterruptSummary): AgentInterruptSummary {
  return {
    ...pick(value, [
      'id', 'agentRunId', 'segmentSequence', 'kind', 'status', 'resolvedByPrincipalId',
      'resolvedAt', 'version',
    ]),
    audit: mapAudit(value.audit),
  }
}

function mapSnapshot(value: AgentStateSnapshotSummary): AgentStateSnapshotSummary {
  return {
    ...pick(value, [
      'id', 'agentRunId', 'runtimeSessionId', 'agentProfileId', 'agentProfileVersion',
      'snapshotSequence', 'checkpointSequence', 'sizeBytes', 'status', 'invalidReasonCode', 'version',
    ]),
    audit: mapAudit(value.audit),
  }
}

function mapLease(value: ExecutionLeaseSummary): ExecutionLeaseSummary {
  return { ...pick(value, [
    'id', 'environment', 'runtimeId', 'workerId', 'phase', 'status', 'acquiredAt',
    'lastHeartbeatAt', 'expiresAt', 'releaseReason', 'releasedAt', 'version',
  ]) }
}

function mapAudit(value: AuditSummary): AuditSummary {
  return { ...pick(value, ['createdByPrincipalId', 'createdAt', 'updatedByPrincipalId', 'updatedAt']) }
}

function mapEvent(value: TaskEventItem): TaskEventItem {
  return {
    cursor: value.cursor,
    context: { ...pick(value.context, [
      'taskId', 'taskExecutionId', 'stepExecutionId', 'agentRunId', 'executionLeaseId',
    ]) },
    projectionGap: value.projectionGap,
    event: {
      ...pick(value.event, [
        'eventId', 'domainEventId', 'streamType', 'eventType', 'schemaVersion', 'aggregateType',
        'aggregateId', 'aggregateVersion', 'correlationId', 'causationId', 'occurredAt',
      ]),
      // Keep a second disclosure boundary in the browser. A future or incorrectly mapped
      // server event may keep its envelope, but unreviewed payload fields never enter Web state.
      payload: mapEventPayload(value.event.eventType, value.event.payload),
    },
  }
}

const eventPayloadFields: Readonly<Record<string, readonly string[]>> = {
  TASK_DELEGATED_TO_AGENT: [
    'objective', 'acceptanceCriteria', 'taskStatus', 'executionStatus',
  ],
  MEMBER_TASK_PAUSE_ACCEPTED: ['targetAttempt', 'operation', 'taskStatus', 'executionStatus'],
  MEMBER_TASK_RESUME_ACCEPTED: ['targetAttempt', 'operation', 'taskStatus', 'executionStatus'],
  MEMBER_TASK_CANCEL_ACCEPTED: ['targetAttempt', 'operation', 'taskStatus', 'executionStatus'],
  MEMBER_TASK_RETRY_ACCEPTED: [
    'targetAttempt', 'operation', 'taskStatus', 'executionStatus', 'successorAttempt',
  ],
  WORKER_TASK_PREPARE_ACCEPTED: workerEventFields(),
  WORKER_TASK_START_ACCEPTED: workerEventFields(),
  WORKER_TASK_HEARTBEAT_ACCEPTED: workerEventFields(),
  WORKER_TASK_PROGRESS_ACCEPTED: workerEventFields(),
  WORKER_TASK_COMPLETE_ACCEPTED: workerEventFields(),
  WORKER_TASK_FAIL_ACCEPTED: workerEventFields(),
  TASK_EXECUTION_RECOVERY_STARTED: [
    'attempt', 'expiredPhase', 'leaseExpiredAt', 'recoveryStartedAt',
  ],
  AGENT_RUN_RESUMED: ['resumedSegmentSequence'],
  AGENT_RUN_EVENT_RECORDED: [
    'attempt', 'eventKind', 'runtimeOccurredAt', 'safeText', 'name', 'status', 'referenceType',
    'succeeded', 'progressPercent', 'modelAttempt',
    'modelMaxAttempts', 'usage', 'failure',
  ],
  EXECUTION_WORKSPACE_CHANGED: [
    'workspaceId', 'attempt', 'status', 'recoveryTargetStatus', 'recoveryGeneration',
    'completionReason', 'failureCode', 'workspaceVersion',
  ],
  WORKSPACE_DIFF_RESET: diffEventFields(),
  WORKSPACE_DIFF_DELTA: diffEventFields(),
  TEST_EVIDENCE_PUBLISHED: [
    'testEvidenceId', 'workspaceId', 'attempt', 'evidenceSequence', 'diffGeneration',
    'manifestHash', 'succeeded', 'total', 'passed', 'failed', 'errors', 'skipped',
    'acceptancePassed', 'acceptanceFailed', 'acceptanceNotEvaluated', 'evidenceHash',
  ],
  FINAL_DIFF_ARTIFACT_PUBLISHED: [
    'diffArtifactId', 'workspaceId', 'attempt', 'diffGeneration', 'manifestHash',
    'fileCount', 'additions', 'deletions', 'finalHash',
  ],
}

function workerEventFields(): readonly string[] {
  return [
    'attempt', 'operation', 'safeSummary', 'progressPercent', 'failureClass', 'failureCode',
  ]
}

function diffEventFields(): readonly string[] {
  return [
    'workspaceId', 'attempt', 'streamEpoch', 'sequence', 'diffGeneration', 'changeKind',
    'manifestHash', 'upserts', 'removals',
  ]
}

function mapEventPayload(eventType: string, value: Record<string, unknown>): Record<string, unknown> {
  const fields = eventPayloadFields[eventType]
  if (!fields || !value || typeof value !== 'object' || Array.isArray(value)) return {}
  const result: Record<string, unknown> = {}
  for (const field of fields) {
    if (!(field in value)) continue
    if (eventType === 'AGENT_RUN_EVENT_RECORDED' && field === 'usage') {
      result[field] = pickRecord(value[field], ['inputTokens', 'outputTokens', 'cachedTokens', 'totalTokens'])
    } else if (eventType === 'AGENT_RUN_EVENT_RECORDED' && field === 'failure') {
      result[field] = pickRecord(value[field], ['category', 'retryable', 'safeMessage', 'runtimeCode'])
    } else if (field === 'acceptanceCriteria') {
      result[field] = Array.isArray(value[field])
        ? value[field].filter(item => typeof item === 'string').slice(0, 100)
        : []
    } else if (eventType.startsWith('WORKSPACE_DIFF_') && field === 'upserts') {
      const files = mapDiffFiles(value[field])
      if (files !== null) result[field] = files
    } else if (eventType.startsWith('WORKSPACE_DIFF_') && field === 'removals') {
      const removals = mapDiffRemovals(value[field])
      if (removals !== null) result[field] = removals
    } else {
      const scalar = publicScalar(value[field])
      if (scalar !== undefined) result[field] = scalar
    }
  }
  return result
}

function mapDiffFiles(value: unknown): Record<string, string | number | boolean | null>[] | null {
  if (!Array.isArray(value) || value.length > 200) return null
  const mappedFiles: Record<string, string | number | boolean | null>[] = []
  for (const item of value) {
    if (!item || typeof item !== 'object' || Array.isArray(item)) return null
    const source = item as Record<string, unknown>
    if (!canonicalDiffPath(source.path)
      || !(source.oldPath === null || canonicalDiffPath(source.oldPath))
      || !['ADDED', 'MODIFIED', 'DELETED', 'RENAMED', 'COPIED'].includes(String(source.changeType))
      || !nonNegativeSafeInteger(source.additions)
      || !nonNegativeSafeInteger(source.deletions)
      || typeof source.binary !== 'boolean'
      || typeof source.patchTruncated !== 'boolean'
      || typeof source.patchSha256 !== 'string'
      || !/^[a-f0-9]{64}$/i.test(source.patchSha256)) return null
    mappedFiles.push({
      path: source.path,
      oldPath: source.oldPath,
      changeType: source.changeType as string,
      additions: source.additions,
      deletions: source.deletions,
      binary: source.binary,
      patchTruncated: source.patchTruncated,
      patchSha256: source.patchSha256,
    })
  }
  return mappedFiles
}

function mapDiffRemovals(value: unknown): string[] | null {
  return Array.isArray(value) && value.length <= 10_000 && value.every(canonicalDiffPath)
    ? [...value]
    : null
}

function canonicalDiffPath(value: unknown): value is string {
  if (typeof value !== 'string' || value.length === 0 || value.length > 1_024
    || value.startsWith('/') || value.startsWith('\\') || value.includes('\\')
    || /^[A-Za-z]:/.test(value) || [...value].some(character => character.codePointAt(0)! < 0x20)) return false
  return value.split('/').every(component => component.length > 0 && component !== '.' && component !== '..')
}

function nonNegativeSafeInteger(value: unknown): value is number {
  return typeof value === 'number' && Number.isSafeInteger(value) && value >= 0
}

function pickRecord(value: unknown, fields: readonly string[]): Record<string, unknown> {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return {}
  const source = value as Record<string, unknown>
  return Object.fromEntries(fields.flatMap(field => {
    const scalar = publicScalar(source[field])
    return scalar === undefined ? [] : [[field, scalar]]
  }))
}

function publicScalar(value: unknown): string | number | boolean | undefined {
  if (typeof value === 'string' || typeof value === 'boolean') return value
  return typeof value === 'number' && Number.isFinite(value) ? value : undefined
}

async function* taskEvents(body: ReadableStream<Uint8Array>, taskId: string): AsyncGenerator<TaskEventItem> {
  for await (const frame of parseServerSentEvents(body)) {
    const value = JSON.parse(frame.data) as TaskEventItem
    if (!value || typeof value !== 'object'
      || typeof value.cursor !== 'string'
      || typeof value.event?.eventId !== 'string'
      || typeof value.event?.eventType !== 'string'
      || value.context?.taskId !== taskId) {
      throw new TypeError('Invalid CrewScope Task event')
    }
    yield mapEvent(value)
  }
}

function mapAssociationPage(value: TaskAssociationPage): TaskAssociationPage {
  return {
    items: value.items.map(item => ({
      origin: item.origin,
      associatedAt: item.associatedAt,
      task: {
        ...mapTaskSummary({ ...item.task, currentWaitingReason: item.task.currentWaitingReason ?? null }),
        href: item.task.href,
      },
    })),
    nextCursor: value.nextCursor,
  }
}

function pick<T extends object, K extends keyof T>(value: T, keys: readonly K[]): Pick<T, K> {
  return Object.fromEntries(keys.map(key => [key, value[key]])) as Pick<T, K>
}
