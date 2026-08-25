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

/** Agent-authored advisory. Its relationship never grants Gate authority. */
export interface ReviewFinding {
  id: string
  severity: 'BLOCKER' | 'HIGH' | 'MEDIUM' | 'LOW'
  category: 'CORRECTNESS' | 'SECURITY' | 'RELIABILITY' | 'MAINTAINABILITY' | 'TESTING' | 'ACCEPTANCE'
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
