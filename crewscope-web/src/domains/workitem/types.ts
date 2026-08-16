import type { CommandReceipt } from '../scope/types'

export const workItemStatuses = [
  'BACKLOG',
  'READY',
  'IN_PROGRESS',
  'IN_REVIEW',
  'BLOCKED',
  'DONE',
  'CANCELLED',
  'ARCHIVED',
] as const

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

export type WorkItemStatus = typeof workItemStatuses[number]
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
}

export interface WorkItemPage {
  items: WorkItemSummary[]
  nextCursor: string | null
}

export interface WorkItemScope {
  organizationId: string
  teamId: string
  projectId: string
}

export interface WorkItemListQuery extends WorkItemScope {
  status?: WorkItemStatus
  after?: string
  limit?: number
}

export interface CreateWorkItemInput {
  key: string
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

/** Mirrors the native WorkItem aggregate state machine for action discovery only. */
export const allowedWorkItemTransitions: Readonly<Record<WorkItemStatus, readonly WorkItemStatus[]>> = {
  BACKLOG: ['READY', 'CANCELLED'],
  READY: ['IN_PROGRESS', 'CANCELLED'],
  IN_PROGRESS: ['IN_REVIEW', 'BLOCKED', 'CANCELLED'],
  IN_REVIEW: ['IN_PROGRESS', 'BLOCKED', 'DONE', 'CANCELLED'],
  BLOCKED: ['READY', 'IN_PROGRESS', 'IN_REVIEW', 'CANCELLED'],
  DONE: ['ARCHIVED'],
  CANCELLED: ['ARCHIVED'],
  ARCHIVED: [],
}

export type WorkItemCommandReceipt = CommandReceipt
