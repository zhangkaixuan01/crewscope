import type { WorkItemAvailableTransition } from '../workitem/types'

/** Personal WorkDesk projection returned by the member-facing API. */
export interface WorkDeskScope {
  organizationId: string
  teamId: string
}

export const workDeskResponsibilityRoles = ['OWNER', 'EXECUTOR', 'REVIEWER'] as const
export type WorkDeskResponsibilityRole = typeof workDeskResponsibilityRoles[number]

/**
 * The five row kinds `JdbcWorkDeskRepositoryAdapter` emits, plus the two closed value sets it
 * derives itself. `WorkDeskItem.objectType` and `.status` stay `string` because the projection is
 * additive on the server side; these unions exist so `labels.ts` can be typed against them.
 */
export const workDeskObjectTypes = ['WORK_ITEM', 'TASK_EXECUTION', 'HUMAN_GATE', 'REVIEW_REQUEST', 'INBOX'] as const
export const workDeskUrgencies = ['URGENT', 'HIGH', 'NORMAL', 'LOW'] as const
export const workDeskRequestStatuses = ['OPEN', 'IN_PROGRESS', 'COMPLETED', 'INVALIDATED'] as const
export type WorkDeskObjectType = typeof workDeskObjectTypes[number]
export type WorkDeskUrgency = typeof workDeskUrgencies[number]
export type WorkDeskRequestStatus = typeof workDeskRequestStatuses[number]

export interface WorkDeskFilter {
  projectId?: string | null
  responsibilityRole?: WorkDeskResponsibilityRole | null
  onlyNeedsAction?: boolean
}

/**
 * The §4.1 minimal block per row: how much work the row stands for and what it is waiting on.
 * `null` means the server published no facts for the row; the row still renders without it.
 */
export interface WorkDeskRowSummary {
  taskCount: number
  activeTaskCount: number
  pendingReviewCount: number
  selectionRequired: boolean
  currentExecutionStatus: string | null
  waitingReason: string | null
  observedAt: string
  workItemVersion: number
}

/**
 * The person a waiting row is waiting on, when it is waiting on a person rather than a machine.
 * The server publishes only the principal ID; the display name and role stay null until the
 * surface resolves them against its own member list.
 */
export interface WorkDeskWaitingOn {
  principalId: string
  displayName: string | null
  role: string | null
}

export interface WorkDeskItem {
  objectType: string
  objectId: string
  projectId: string | null
  title: string | null
  status: string
  updatedAt: string
  responsibilityRole: WorkDeskResponsibilityRole | null
  needsAction: boolean
  urgency: string
  progress: number | null
  /**
   * Only the transitions the member may execute now, in the same shape the per-object availability
   * endpoint returns. Disabled actions are deliberately absent: a list row cannot explain them, so
   * the server does not offer them; the detail drawer fetches the full set with its reasons.
   */
  availableActions: WorkItemAvailableTransition[]
  route: string
  /** The WorkItem a row stands for, when the row is derived from one; links it to the shared read model. */
  workItemId: string | null
  workItemTitle: string | null
  rowSummary: WorkDeskRowSummary | null
  waitingOn: WorkDeskWaitingOn | null
}

export interface WorkDeskSection {
  key: string
  title: string
  priority: number
  /** The real full-set count, which may exceed the loaded items; `truncated` says which sections can continue. */
  total: number
  truncated: boolean
  items: WorkDeskItem[]
  /** Continues this one section from where its current page ended; server-signed and scope-bound. */
  nextCursor: string | null
}

export interface WorkDeskSummary {
  organizationId: string
  teamId: string
  projectId: string | null
  generatedAt: string
  sections: WorkDeskSection[]
}
