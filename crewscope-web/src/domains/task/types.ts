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
  currentWaitingReason: string | null
  ownerPrincipalId: string | null
  version: number
  createdAt: string
  updatedAt: string
}

export interface CreateTaskInput {
  objective: string
  acceptanceCriteria: string[]
  executorAgentProfileId: string
  conversationSource: { conversationId: string, messageId: string } | null
  providerBindingIds: string[]
  codingTarget?: CodingTargetSelection | null
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
  reason: string
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
  waitingCauses: Array<{
    cause: string
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
