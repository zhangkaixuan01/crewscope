import type { CommandReceipt } from '../scope/types'

/** Organization and Team boundary shared by every Review workbench request. */
export interface ReviewScope {
  organizationId: string
  teamId: string
}

export interface ReviewCoordinates {
  taskId: string
  executionId: string
}

export type ReviewRequestStatus = 'OPEN' | 'IN_PROGRESS' | 'COMPLETED' | 'INVALIDATED'
export type ReviewerRelationship = 'INDEPENDENT' | 'SELF_REVIEW'
export type ReviewDecisionType = 'COMMENTED' | 'APPROVED' | 'CHANGES_REQUESTED' | 'REJECTED'

export interface ReviewSummary {
  id: string
  revision: number
  version: number
  status: ReviewRequestStatus
  invalidationReason: string | null
  contextHash: string
  findingCount: number
  blockerCount: number
  highCount: number
  latestDecisionType: ReviewDecisionType | null
  modificationRound: number
}

export interface ReviewFindingEvidence {
  path: string
  startLine: number
  endLine: number
  acceptanceCriterionIndex: number
}

/**
 * Review enums the detail projection currently types as `string`. Naming them here lets
 * `review/labels.ts` be typed against the exact Java constants.
 */
export const reviewerEligibilityModes = ['STRICT_SEPARATION', 'SINGLE_MEMBER_OVERRIDE'] as const
export const reviewInvalidationReasons = [
  'SUBJECT_CHANGED', 'DIFF_CHANGED', 'TEST_EVIDENCE_CHANGED', 'REVIEWER_CONFIGURATION_CHANGED',
  'POLICY_CHANGED', 'CONTEXT_CHANGED',
] as const
export const reviewFindingSeverities = ['BLOCKER', 'HIGH', 'MEDIUM', 'LOW'] as const
export const reviewFindingCategories = [
  'CORRECTNESS', 'SECURITY', 'RELIABILITY', 'MAINTAINABILITY', 'TESTING', 'ACCEPTANCE',
] as const
export type ReviewerEligibilityMode = typeof reviewerEligibilityModes[number]
export type ReviewInvalidationReason = typeof reviewInvalidationReasons[number]
export type ReviewFindingSeverity = typeof reviewFindingSeverities[number]
export type ReviewFindingCategory = typeof reviewFindingCategories[number]

export type ReviewCommentSide = 'OLD' | 'NEW'
export type ReviewCommentAnchorState = 'ACTIVE' | 'OUTDATED'

/** Member-safe line comment projection used by the Diff reader. */
export interface ReviewLineComment {
  id: string
  reviewRequestId: string
  taskExecutionId: string
  filePath: string
  side: ReviewCommentSide
  lineNumber: number
  hunkHeader: string
  lineContentHash: string
  diffGeneration: number
  content: string
  authorPrincipalId: string
  anchorState: ReviewCommentAnchorState
  deleted: boolean
  version: number
  createdAt: string
  updatedAt: string
}

/** Agent-authored advisory. Its relationship never grants Gate authority. */
export interface ReviewFinding {
  id: string
  severity: ReviewFindingSeverity
  category: ReviewFindingCategory
  title: string
  claim: string
  suggestedFix: string
  relationship: ReviewerRelationship
  fingerprint: string
  evidence: ReviewFindingEvidence[]
}

export interface ReviewDecision {
  id: string
  revision: number
  type: ReviewDecisionType
  rationale: string
  reviewerMemberId: string
  eligibilityMode: string
  decidedAt: string
}

export interface ReviewModificationRound {
  id: string
  roundNumber: number
  sourceReviewRequestId: string
  triggerDecisionId: string
  createdAt: string
}

/** Public, secret-free workbench view returned by M5-A05. */
export interface ReviewDetails {
  id: string
  revision: number
  version: number
  status: ReviewRequestStatus
  invalidationReason: string | null
  reviewerRelationship: ReviewerRelationship
  reviewerAgentProfileId: string
  contextPackageId: string
  contextHash: string
  diffArtifactId: string
  diffArtifactHash: string
  baselineCommit: string
  deliveryCommit: string
  changedPaths: string[]
  testEvidenceId: string
  testEvidenceHash: string
  findings: ReviewFinding[]
  decisions: ReviewDecision[]
  modificationRounds: ReviewModificationRound[]
}

export interface EtaggedReview {
  value: ReviewDetails
  etag: string
}

export interface ReviewerExecutionResult {
  receipt: CommandReceipt
  reviewRequestId: string
  reviewRequestVersion: number
  status: ReviewRequestStatus
  effectiveFindingCount: number
  insertedFindingCount: number
  duplicateObservationCount: number
}

export interface ReviewDecisionInput {
  type: ReviewDecisionType
  rationale: string
}
