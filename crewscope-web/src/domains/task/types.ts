import type { CommandReceipt } from '../scope/types'
import type { CodingTargetSelection } from '../coding/types'

/** Team boundary shared by all member-facing Task queries. */
export interface TaskScope {
  organizationId: string
  teamId: string
}

export type TaskStatus = 'CREATED' | 'ACTIVE' | 'WAITING' | 'COMPLETED' | 'FAILED' | 'CANCELLED'
export const taskStatuses: readonly TaskStatus[] = [
  'CREATED', 'ACTIVE', 'WAITING', 'COMPLETED', 'FAILED', 'CANCELLED',
]
export type TaskExecutionStatus =
  | 'CREATED' | 'READY' | 'CLAIMED' | 'PREPARING' | 'RUNNING' | 'WAITING'
  | 'PAUSE_REQUESTED' | 'PAUSED' | 'RECOVERING' | 'CANCEL_REQUESTED'
  | 'MANUAL_TAKEOVER' | 'COMPLETED' | 'FAILED' | 'CANCELLED'

/**
 * Task-runtime enums exactly as the server serialises them (`{Enum}.name()`).
 *
 * The runtime-facts interfaces below keep these fields typed as `string` because the Task gateway
 * is a pass-through adapter over the public projection. The unions live here so `task/labels.ts`
 * can be typed against them: an incomplete or invented mapping then fails typechecking instead of
 * printing a raw Java constant in the Task drawer.
 */
export const taskExecutionWaitReasons = [
  'RUNTIME', 'COLLABORATION', 'REVIEW', 'CONFIRMATION', 'USER_INPUT', 'EXTERNAL_EXECUTION',
  'EVENT', 'MANUAL',
] as const
export const taskExecutionControlRequestTypes = ['PAUSE', 'CANCEL'] as const
export const taskExecutionFailureClasses = [
  'TRANSIENT', 'RATE_LIMITED', 'TIMEOUT', 'RUNTIME_UNAVAILABLE', 'MODEL_UNAVAILABLE',
  'TOOL_UNAVAILABLE', 'RESOURCE_EXHAUSTED', 'RECOVERY_INTERRUPTED', 'VALIDATION',
  'AUTHENTICATION', 'AUTHORIZATION', 'POLICY_VIOLATION', 'CAPABILITY_UNSUPPORTED', 'NOT_FOUND',
  'CONFLICT', 'INTERNAL',
] as const
export const taskSourceTypes = ['WORK_ITEM', 'CONVERSATION'] as const
export const planStepTypes = ['ANALYSIS', 'IMPLEMENTATION', 'VALIDATION', 'REVIEW', 'DELIVERY'] as const
export const planChangeReasons = [
  'INITIAL_PLAN', 'REQUIREMENTS_CHANGED', 'POLICY_CHANGED', 'RECOVERY_REPLAN', 'REVIEW_FEEDBACK',
  'MANUAL_REVISION',
] as const
export const todoStatuses = ['PENDING', 'IN_PROGRESS', 'COMPLETED'] as const
export const stepWaitReasons = [
  'AGENT_INTERRUPT', 'COLLABORATION', 'REVIEW', 'HANDOFF', 'TAKEOVER', 'CONFIRMATION',
  'EXTERNAL_EXECUTION', 'EVENT', 'USER_INPUT', 'MANUAL',
] as const
export const stepExecutionStatuses = [
  'PENDING', 'READY', 'RUNNING', 'WAITING', 'SUCCEEDED', 'FAILED_RETRYABLE', 'FAILED_FINAL',
  'SKIPPED', 'CANCELLED',
] as const
export const agentSessionPurposes = ['TASK', 'STEP', 'SPECIALIST'] as const
export const agentRuntimeSessionStatuses = ['ACTIVE', 'DISABLED', 'ARCHIVED'] as const
export const agentRunStatuses = ['RUNNING', 'INTERRUPTED', 'COMPLETED', 'FAILED', 'CANCELLED'] as const
export const agentRunSegmentKinds = ['INVOKE', 'RESUME', 'RECOVERY'] as const
export const agentRunSegmentStatuses = ['ACTIVE', 'INTERRUPTED', 'COMPLETED', 'FAILED', 'CANCELLED'] as const
export const agentInterruptKinds = ['CLARIFICATION', 'PERMISSION', 'APPROVAL', 'PAUSE'] as const
export const agentInterruptStatuses = ['PENDING', 'RESOLVED', 'CANCELLED', 'EXPIRED'] as const
export const agentRunContinuityGapReasons = [
  'SNAPSHOT_MISSING',
  'SNAPSHOT_CORRUPT',
  'SNAPSHOT_IDENTITY_MISMATCH',
  'REDIS_STATE_LOST',
  'UNSAFE_CHECKPOINT',
] as const
export const agentStateSnapshotStatuses = ['CURRENT', 'SUPERSEDED', 'INVALID'] as const
export const executionLeasePhases = ['PREPARE', 'RUN'] as const
/** Derived by `ExecutionLeaseResponse` from release presence, not a domain enum. */
export const executionLeaseStatuses = ['ACTIVE', 'RELEASED'] as const
export const executionLeaseReleaseReasons = [
  'COMPLETED', 'FAILED', 'CANCELLED', 'PAUSED', 'WAITING', 'EXPIRED', 'MANUAL_TAKEOVER',
  'WORKER_SHUTDOWN',
] as const

export type TaskExecutionWaitReason = typeof taskExecutionWaitReasons[number]
export type TaskExecutionControlRequestType = typeof taskExecutionControlRequestTypes[number]
export type TaskExecutionFailureClass = typeof taskExecutionFailureClasses[number]
export type TaskSourceType = typeof taskSourceTypes[number]
export type PlanStepType = typeof planStepTypes[number]
export type PlanChangeReason = typeof planChangeReasons[number]
export type TodoStatus = typeof todoStatuses[number]
export type StepExecutionStatus = typeof stepExecutionStatuses[number]
export type StepWaitReason = typeof stepWaitReasons[number]
export type AgentSessionPurpose = typeof agentSessionPurposes[number]
export type AgentRuntimeSessionStatus = typeof agentRuntimeSessionStatuses[number]
export type AgentRunStatus = typeof agentRunStatuses[number]
export type AgentRunSegmentKind = typeof agentRunSegmentKinds[number]
export type AgentRunSegmentStatus = typeof agentRunSegmentStatuses[number]
export type AgentInterruptKind = typeof agentInterruptKinds[number]
export type AgentInterruptStatus = typeof agentInterruptStatuses[number]
export type AgentRunContinuityGapReason = typeof agentRunContinuityGapReasons[number]
export type AgentStateSnapshotStatus = typeof agentStateSnapshotStatuses[number]
export type ExecutionLeasePhase = typeof executionLeasePhases[number]
export type ExecutionLeaseStatus = typeof executionLeaseStatuses[number]
export type ExecutionLeaseReleaseReason = typeof executionLeaseReleaseReasons[number]

/** `RuntimeFleetHealth` / `RuntimeWaitCause` in the application layer. */
export const runtimeFleetHealths = ['HEALTHY', 'DEGRADED', 'UNAVAILABLE'] as const
export const runtimeWaitCauses = [
  'CAPABILITY_UNAVAILABLE', 'NO_ACTIVE_WORKER', 'HEARTBEAT_STALE', 'DRAINING',
  'CAPACITY_EXHAUSTED', 'REQUEUE_PENDING',
] as const
export type RuntimeFleetHealth = typeof runtimeFleetHealths[number]
export type RuntimeWaitCause = typeof runtimeWaitCauses[number]

export interface TaskListQuery extends TaskScope {
  projectId?: string
  status?: TaskStatus
  ownerPrincipalId?: string
  after?: string
  limit?: number
}

export interface TaskSummary {
  id: string
  workspaceId: string
  projectId: string
  workItemId: string
  objective: string
  acceptanceCriteria: string[]
  status: TaskStatus
  currentExecutionId: string | null
  currentAttempt: number | null
  currentExecutionStatus: TaskExecutionStatus | null
  /*
   * 收窄成枚举，和同一条记录里的 status / currentExecutionStatus 一致。
   * 留成 `string` 的后果是看得见的：E2E 与两份组件测试各自编了 `CAPACITY`、`WAITING_APPROVAL`
   * 这样并不存在的等待原因（真值只有 RUNTIME / COLLABORATION / REVIEW / CONFIRMATION /
   * USER_INPUT / EXTERNAL_EXECUTION / EVENT / MANUAL 八个），而 enumLabel 查不到就原样回显，
   * 于是「等待原因 · CAPACITY」这样的裸枚举一路渲染到了截图基线里——M9 要消灭的正是这个。
   * 收窄之后，编错一个等待原因会在 typecheck 阶段失败，而不是在界面上。
   */
  currentWaitingReason: TaskExecutionWaitReason | null
  ownerPrincipalId: string | null
  version: number
  createdAt: string
  updatedAt: string
}

export interface CreateTaskInput {
  objective: string
  acceptanceCriteria: string[]
  executorAgentProfileId: string
  agentConfigurationRevision: number
  conversationSource: { conversationId: string, messageId: string } | null
  providerBindingIds: string[]
  codingTarget?: CodingTargetSelection | null
}

export interface TaskDelegationSelection {
  executorAgentProfileId: string
  agentConfigurationRevision: number | null
}

export interface TaskDelegationModelSelection {
  role: string
  providerKey: string
  connectionId: string
  connectionOwnerType: string
  modelId: string
  catalogRevision: number
  modelRevision: string
  priceRevision: number
}

/** Public, secret-free coordinates returned by the authoritative Task preflight. */
export interface TaskDelegationPreflight {
  agentProfileId: string
  agentProfileVersion: number
  executionScope: 'PERSONAL' | 'TEAM'
  configurationRevision: number
  configurationHash: string
  bindingSource: string
  templateVersion: string
  primary: TaskDelegationModelSelection
  fallback: TaskDelegationModelSelection | null
  policyPackId: string
  policyPackVersion: number
  resolutionHash: string
}

export interface CreateTaskCommand {
  scope: TaskScope
  projectId: string
  workItemId: string
  expectedVersion: number
  input: CreateTaskInput
}

export type TaskCommandReceipt = CommandReceipt

export type MemberTaskCommandOperation = 'PAUSE' | 'RESUME' | 'CANCEL' | 'RETRY'

/** Member command coordinates are always bound to the current durable attempt version. */
export interface MemberTaskCommand {
  scope: TaskScope
  taskId: string
  executionId: string
  expectedVersion: number
  operation: MemberTaskCommandOperation
  reason?: string
  agentConfigurationRevision?: number
}

export interface TaskCommandVersionConflict {
  operation: MemberTaskCommandOperation
  attemptedVersion: number
  currentVersion: number | null
}

export interface TaskPage {
  items: TaskSummary[]
  nextCursor: string | null
}

export interface AuditSummary {
  createdByPrincipalId: string | null
  createdAt: string
  updatedByPrincipalId: string | null
  updatedAt: string
}

export interface TaskSource {
  type: string
  workItemVersion: number
  conversationId: string | null
  inputType: string | null
  inputId: string | null
  inputVersion: number | null
}

export interface TaskResponsibilitySnapshot {
  assignmentId: string
  assignmentVersion: number
  role: string
  principalId: string
  principalType: string
  memberId: string | null
  assignedAt: string
  acceptedAt: string
}

export interface TaskExecutionWaiting {
  reason: TaskExecutionWaitReason
  waitingSince: string
}

export interface TaskControlRequest {
  type: string
  requestedByPrincipalId: string
  requestedAt: string
  reason: string
}

export interface TaskExecutionTerminal {
  status: string
  decidedByPrincipalId: string
  decidedAt: string
  failureClass: string | null
  failureCode: string | null
}

export interface TaskExecution {
  id: string
  attempt: number
  maxAttempts: number
  parentExecutionId: string | null
  priority: number
  notBefore: string
  status: TaskExecutionStatus
  waiting: TaskExecutionWaiting | null
  controlRequest: TaskControlRequest | null
  terminal: TaskExecutionTerminal | null
  executorPrincipalId: string | null
  currentPlanVersionId: string | null
  version: number
  audit: AuditSummary
}

export interface TaskDetails {
  id: string
  teamId: string
  workspaceId: string
  projectId: string
  workItemId: string
  objective: string
  acceptanceCriteria: string[]
  source: TaskSource
  responsibilitySnapshot: TaskResponsibilitySnapshot[]
  responsibilityCapturedAt: string
  status: TaskStatus
  currentExecutionId: string | null
  cancellation: { cancelledByPrincipalId: string, cancelledAt: string, reason: string } | null
  version: number
  audit: AuditSummary
  attempts: TaskExecution[]
}

export interface PlanStep {
  key: string
  sequence: number
  title: string
  type: string
  dependencyKeys: string[]
  requiredCapabilities: string[]
  requiredTools: string[]
  critical: boolean
}

export interface PlanTodo {
  content: string
  status: string
  priority: string | null
  planStepKey: string | null
}

export interface PlanVersion {
  id: string
  revision: number
  parentVersionId: string | null
  changeReason: string
  markdown: string
  steps: PlanStep[]
  todoSummary: PlanTodo[]
  publishedByPrincipalId: string
  publishedAt: string
}

export interface StepExecution {
  id: string
  planVersionId: string
  planStepKey: string
  sequence: number
  critical: boolean
  runAttempt: number
  maxRunAttempts: number
  status: string
  waitReason: string | null
  checkpoint: { sequence: number, code: string, recordedByPrincipalId: string, recordedAt: string } | null
  failureClass: string | null
  failureCode: string | null
  version: number
  audit: AuditSummary
}

export interface AgentSessionSummary {
  id: string
  stepExecutionId: string | null
  purpose: string
  agentPrincipalId: string
  agentProfileId: string
  agentProfileVersion: number
  status: string
  version: number
  audit: AuditSummary
}

export interface AgentRunSummary {
  id: string
  stepExecutionId: string | null
  runtimeSessionId: string
  agentPrincipalId: string
  agentProfileId: string
  agentProfileVersion: number
  runSequence: number
  status: string
  segments: Array<{
    sequence: number
    kind: string
    resumedFromInterruptId: string | null
    status: string
    startedAt: string
    endedAt: string | null
  }>
  continuityGap: {
    previousRunId: string
    lastValidSnapshotId: string | null
    firstMissingCheckpoint: number
    lastMissingCheckpoint: number
    reason: string
    detectedAt: string
  } | null
  terminal: {
    status: string
    failureCode: string | null
    resultArtifactId: string | null
    occurredAt: string
  } | null
  version: number
  audit: AuditSummary
}

export interface AgentInterruptSummary {
  id: string
  agentRunId: string
  segmentSequence: number
  kind: string
  status: string
  resolvedByPrincipalId: string | null
  resolvedAt: string | null
  version: number
  audit: AuditSummary
}

export interface AgentStateSnapshotSummary {
  id: string
  agentRunId: string
  runtimeSessionId: string
  agentProfileId: string
  agentProfileVersion: number
  snapshotSequence: number
  checkpointSequence: number
  sizeBytes: number
  status: string
  invalidReasonCode: string | null
  version: number
  audit: AuditSummary
}

export interface ExecutionLeaseSummary {
  id: string
  environment: string
  runtimeId: string
  workerId: string
  phase: string
  status: string
  acquiredAt: string
  lastHeartbeatAt: string
  expiresAt: string
  releaseReason: string | null
  releasedAt: string | null
  version: number
}

/** Public runtime projection. Claim/Task tokens, hashes, credentials and raw AgentState are absent. */
export interface TaskRuntimeFacts {
  execution: TaskExecution
  planVersions: PlanVersion[]
  steps: StepExecution[]
  sessions: AgentSessionSummary[]
  agentRuns: AgentRunSummary[]
  interrupts: AgentInterruptSummary[]
  snapshots: AgentStateSnapshotSummary[]
  leases: ExecutionLeaseSummary[]
}

/** Team-member-safe fleet projection. Runtime and Worker identities are intentionally absent. */
export interface RuntimeFleetSummary {
  environment: string
  observedAt: string
  health: string
  runtimeCount: number
  workerCount: number
  activeWorkerCount: number
  staleWorkerCount: number
  drainingWorkerCount: number
  capacity: {
    maximum: number
    active: number
    available: number
  }
  waitingRuntimeExecutions: number
  /* 同上：`cause` 也收窄成枚举，存量替身里写的 `CAPACITY` 其实是 `CAPACITY_EXHAUSTED`。 */
  waitingCauses: Array<{
    cause: RuntimeWaitCause
    count: number
  }>
}

export interface TaskEventContext {
  taskId: string
  taskExecutionId: string | null
  stepExecutionId: string | null
  agentRunId: string | null
  executionLeaseId: string | null
}

export interface TaskPublicEvent {
  eventId: string
  domainEventId: string | null
  streamType: string
  eventType: string
  schemaVersion: string
  aggregateType: string | null
  aggregateId: string | null
  aggregateVersion: number | null
  correlationId: string
  causationId: string | null
  occurredAt: string
  payload: Record<string, unknown>
}

export interface TaskEventItem {
  cursor: string
  context: TaskEventContext
  projectionGap: boolean
  event: TaskPublicEvent
}

export interface TaskEventPage {
  items: TaskEventItem[]
  hasMore: boolean
  taskTerminal: boolean
  nextCursor: string | null
}

export interface TaskAssociationSummary {
  origin: string
  associatedAt: string
  task: TaskSummary & { href: string }
}

export interface TaskAssociationPage {
  items: TaskAssociationSummary[]
  nextCursor: string | null
}

export interface TaskAssociations {
  task: { id: string, projectId: string, workItemId: string, status: TaskStatus, objective: string, href: string }
  workItem: { id: string, projectId: string, key: string, title: string, status: string, href: string }
  conversations: {
    items: Array<{
      id: string
      title: string
      visibility: string
      status: string
      origin: string
      associatedAt: string
      href: string
    }>
    nextCursor: string | null
  }
}
