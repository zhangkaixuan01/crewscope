import type { CommandReceipt } from '../scope/types'
import type { WorkItemScope, WorkItemStatus } from '../workitem/types'

/**
 * Relation types as the API accepts them (M11 contract §2.1). The client submits only BLOCKS and
 * SPLIT_FROM — DEPENDS_ON(X, Y) normalises to the stored edge BLOCKS(Y, X) server-side, so the UI
 * always says what it means instead of relying on the rewrite. The value still appears in
 * {@link RelationView} because the adjacency projection renders both directions of one stored edge.
 */
export const workItemRelationTypes = ['BLOCKS', 'DEPENDS_ON', 'SPLIT_FROM'] as const
export type WorkItemRelationType = typeof workItemRelationTypes[number]

/** The types this client may submit; DEPENDS_ON is display-only (see {@link workItemRelationTypes}). */
export const submittableRelationTypes = ['BLOCKS', 'SPLIT_FROM'] as const
export type SubmittableRelationType = typeof submittableRelationTypes[number]

/**
 * Relation commands live at Team level (M11 contract §1): an edge may cross two WorkProjects, so
 * the resource hangs under no project. Derived reads keep the project path segment every other
 * work-item read uses, hence the two scope shapes.
 */
export interface WorkItemRelationCommandScope {
  organizationId: string
  teamId: string
}

export type WorkGraphQueryScope = WorkItemScope

/** One direction view of a stored edge (contract §3): `A BLOCKS B` also renders as `B DEPENDS_ON A`. */
export interface RelationView {
  type: WorkItemRelationType
  fromWorkItemId: string
  toWorkItemId: string
}

/**
 * One adjacency edge relative to the anchor. `direction` says which side of the stored edge the
 * anchor sits on; `views` carries the relation sentences the panel renders verbatim.
 */
export interface WorkItemRelationEdge {
  relationId: string
  direction: 'OUTGOING' | 'INCOMING'
  otherWorkItemId: string
  otherProjectId: string
  crossProject: boolean
  views: RelationView[]
  createdAt: string
}

export interface WorkItemRelations {
  anchor: string
  edges: WorkItemRelationEdge[]
  truncatedByRows: boolean
}

export interface BlockingChainStep {
  workItemId: string
  depth: number
}

export interface BlockingChain {
  anchor: string
  steps: BlockingChainStep[]
  truncatedByDepth: boolean
  truncatedByRows: boolean
}

/** Purely topological longest blocking chain ending at the anchor; truncated means lower bound. */
export interface CriticalPath {
  chain: string[]
  truncatedByDepth: boolean
  truncatedByRows: boolean
}

/** Blocker window summary inlined in every blocked row (contract §4, window ≤ 20). */
export interface BlockerSummary {
  workItemId: string
  title: string
  status: WorkItemStatus
  projectId: string
}

/**
 * One "currently blocked" row: at least one BLOCKS in-edge whose blocker is still executing.
 * `blockerCount` is the authoritative count; `blockers[]` is only the newest ≤ 20 window, so
 * `blockerCount > blockers.length` means the window was truncated — the UI reports that fact
 * instead of silently showing a shorter list as the whole truth.
 */
export interface BlockedWorkItemRow {
  workItemId: string
  itemKey: string
  title: string
  status: WorkItemStatus
  projectId: string
  blockedSince: string
  blockerCount: number
  crossesProject: boolean
  blockers: BlockerSummary[]
}

export interface BlockedWorkItemPage {
  items: BlockedWorkItemRow[]
  nextCursor: string | null
}

export interface CreateWorkItemRelationInput {
  type: SubmittableRelationType
  fromWorkItemId: string
  toWorkItemId: string
}

export type WorkGraphCommandReceipt = CommandReceipt

/** One node of the active blocking DAG: a blocked item or one of its blockers. */
export interface GraphNode {
  id: string
  itemKey: string | null
  title: string
  status: WorkItemStatus
  projectId: string
  blockedCount: number
}

/** One BLOCKS edge, always oriented blocker → blocked. */
export interface GraphEdge {
  blockerId: string
  blockedId: string
}
