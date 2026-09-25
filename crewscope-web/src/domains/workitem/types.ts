import type { CommandReceipt } from '../scope/types'
import { workItemStateMachine } from '../../api/generated/state-machines'

/** Status values come from the generated domain state machine; the client must not fork this list. */
export const workItemStatuses = workItemStateMachine.states

export const workItemTypes = ['TASK', 'BUG', 'FEATURE', 'INCIDENT'] as const
export const workItemPriorities = ['LOW', 'MEDIUM', 'HIGH', 'URGENT'] as const
export const workItemResourceTypes = [
  'TASK',
  'CONVERSATION',
  'REPOSITORY',
  'BRANCH',
  'COMMIT',
  'PULL_REQUEST',
  'ARTIFACT',
  'EXTERNAL_URL',
] as const

export type WorkItemStatus = typeof workItemStateMachine.states[number]
export type WorkItemType = typeof workItemTypes[number]
export type WorkItemPriority = typeof workItemPriorities[number]
export type WorkItemResourceType = typeof workItemResourceTypes[number]

export interface WorkItemSummary {
  id: string
  organizationId: string
  teamId: string
  workspaceId: string
  projectId: string
  key: string
  type: WorkItemType
  title: string
  description: string | null
  status: WorkItemStatus
  priority: WorkItemPriority
  labels: string[]
  dueAt: string | null
  source: string
  sourceReference: string | null
  version: number
  createdAt: string
  createdByPrincipalId: string | null
  updatedAt: string
  updatedByPrincipalId: string | null
  /**
   * The server's verdict on every edge leaving this status, disabled entries and their reasons
   * included.
   *
   * The list and detail responses both inline it, so a list row or a card never has to ask a second
   * endpoint per row to find out what it can do — which is also why a 500-item page costs one
   * request rather than 501. The per-object availability endpoint still exists and returns the same
   * entries; this field is not a second rule, it is the same rule delivered earlier.
   */
  availableActions: WorkItemAvailableTransition[]
  /**
   * The M9b-A06 execution/todo read model, assembled server-side in the same request.
   *
   * `null` means the server published no facts for this row (for example the item left the scope
   * between the page read and the summary batch); it is not an error and never blocks the row.
   */
  summary: WorkItemExecutionSummary | null
}

/**
 * The server-owned orderings of the M9b-A06 work list. The client sends the name; the tie-breaker
 * and the cursor that continues each ordering stay server-side.
 */
export const workItemSorts = ['updatedAt', 'priority', 'dueAt', 'createdAt'] as const
export type WorkItemSortField = typeof workItemSorts[number]

export interface WorkItemPage {
  items: WorkItemSummary[]
  nextCursor: string | null
}

export interface WorkItemScope {
  organizationId: string
  teamId: string
  projectId: string
}

/**
 * Multi-value filters are arrays on the wire and comma-joined in the query string; every dimension
 * is optional and only sent when present, so existing callers' requests stay byte-identical.
 */
export interface WorkItemListQuery extends WorkItemScope {
  status?: WorkItemStatus
  type?: readonly WorkItemType[]
  priority?: readonly WorkItemPriority[]
  responsibilityRole?: ResponsibilityRole
  sort?: WorkItemSortField
  after?: string
  limit?: number
}

/**
 * The store-side filter shape: the query dimensions without scope, cursor or page size. Any change
 * to any dimension produces a different canonical key, which restarts the page from its beginning
 * because a continuation cursor is only valid for the filter that minted it.
 */
export interface WorkItemListFilter {
  status?: WorkItemStatus
  type?: readonly WorkItemType[]
  priority?: readonly WorkItemPriority[]
  responsibilityRole?: ResponsibilityRole
  sort?: WorkItemSortField
}

/** One explainable wait aggregated into the summary. */
export interface WorkItemBlockedReason {
  code: string
  taskId: string | null
  executionId: string | null
  since: string | null
  waitingOnPrincipalId: string | null
}

/**
 * The execution/todo summary of one WorkItem (§4.1): how many tasks, how many active, what is
 * waiting and on whom. The three current-attempt fields are present together or null together, and
 * a multi-task item reports `selectionRequired` instead of guessing a current attempt.
 */
export interface WorkItemExecutionSummary {
  workItemId: string
  workItemVersion: number
  workStatus: WorkItemStatus
  taskCount: number
  activeTaskCount: number
  pendingReviewCount: number
  currentTaskId: string | null
  currentExecutionId: string | null
  executionStatus: string | null
  selectionRequired: boolean
  blockedReasons: WorkItemBlockedReason[]
  resultSummary: string | null
  resultSourceReference: string | null
  projectionVersion: number
  observedAt: string
}

export interface CreateWorkItemInput {
  key?: string
  type: WorkItemType
  title: string
  description: string | null
  priority: WorkItemPriority
  labels: string[]
  dueAt: string | null
}

export interface WorkItemComment {
  id: string
  workItemId: string
  authorPrincipalId: string
  content: string
  source: string
  externalId: string | null
  createdAt: string
}

export interface WorkItemResourceLink {
  id: string
  workItemId: string
  resourceType: WorkItemResourceType
  resourceReference: string
  label: string | null
  createdAt: string
  createdByPrincipalId: string | null
}

export interface WorkItemDetails {
  workItem: WorkItemSummary
  comments: WorkItemComment[]
  resourceLinks: WorkItemResourceLink[]
}

export interface AddWorkItemCommentInput {
  content: string
}

export interface LinkWorkItemResourceInput {
  resourceType: WorkItemResourceType
  resourceReference: string
  label: string | null
}

export type ResponsibilityRole = 'OWNER' | 'EXECUTOR' | 'REVIEWER'

/** Active responsibility fact returned by the policy-safe A06 query contract. */
export interface ResponsibilityAssignment {
  id: string
  workItemId: string
  role: ResponsibilityRole
  actorPrincipalId: string
  actorType: string
  actorMemberId: string | null
  actorDisplayName: string
  actorAgentProfileId: string | null
  status: string
  assignedByPrincipalId: string
  assignedAt: string
  acceptedAt: string
  version: number
}

export interface ReplaceOwnerInput {
  actorPrincipalId: string
  expectedAssignmentId: string | null
  expectedVersion: number | null
}

export interface AssignResponsibilityInput {
  actorPrincipalId: string
}

export interface WorkItemTimelineEvent {
  eventId: string
  domainEventId: string | null
  source: string
  eventType: string
  schemaVersion: string
  aggregateType: string
  aggregateId: string
  aggregateVersion: number | null
  actorType: string
  actorPrincipalId: string | null
  actorDisplayName: string | null
  correlationId: string
  causationId: string | null
  occurredAt: string
  outcome: string
  payload: Record<string, unknown>
}

export interface WorkItemTimelinePage {
  items: WorkItemTimelineEvent[]
  nextCursor: string | null
}

export interface WorkItemVersionConflict {
  attemptedVersion: number
  currentVersion: number | null
}

/** Generated from the domain aggregate; retained as a typed view for existing consumers. */
export const allowedWorkItemTransitions: Readonly<Record<WorkItemStatus, readonly WorkItemStatus[]>> =
  workItemStateMachine.transitions as Readonly<Record<WorkItemStatus, readonly WorkItemStatus[]>>

export const workItemTransitionStrengths = ['PRIMARY', 'SECONDARY', 'DANGER'] as const
export const workItemTransitionBlockReasons = [
  'STATUS_NOT_ALLOWED',
  'PERMISSION_DENIED',
  'REVIEWER_REQUIRED',
  'DUTY_SEPARATION_CONFLICT',
  'GATE_NOT_PASSED',
  'BLOCKED_BY_DEPENDENCY',
  'EXTERNAL_PROVIDER_MANAGED',
  'ARCHIVED',
] as const

export type WorkItemTransitionStrength = typeof workItemTransitionStrengths[number]
export type WorkItemTransitionBlockReason = typeof workItemTransitionBlockReasons[number]

/**
 * Runtime availability for one state-machine edge, decided by the server.
 *
 * The generated state machine says which edges *exist*; this says which of them the current member
 * may execute right now and, when it may not, why and where to go next. The UI must never widen
 * this set — an action the server did not return is an action that would be rejected.
 */
export interface WorkItemAvailableTransition {
  actionId: string
  targetStatus: WorkItemStatus
  label: string
  strength: WorkItemTransitionStrength
  reversible: boolean
  enabled: boolean
  reason: WorkItemTransitionBlockReason | null
  reasonMessage: string | null
  remedyLabel: string | null
  remedyRoute: string | null
}

/** A successful reversible transition, undoable until {@link expiresAt}. */
export interface WorkItemUndoOffer {
  workItemId: string
  actionLabel: string
  fromStatus: WorkItemStatus
  toStatus: WorkItemStatus
  expectedVersion: number
  expiresAt: number
}

export type WorkItemCommandReceipt = CommandReceipt
