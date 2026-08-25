import type { EtaggedReview, ReviewDetails, ReviewSummary } from '../domains/review/types'

export const reviewIds = {
  request: '00000000-0000-0000-0000-000000009101',
  previousRequest: '00000000-0000-0000-0000-000000009102',
  finding: '00000000-0000-0000-0000-000000009103',
  agentProfile: '00000000-0000-0000-0000-000000009104',
  context: '00000000-0000-0000-0000-000000009105',
  diff: '00000000-0000-0000-0000-000000009106',
  test: '00000000-0000-0000-0000-000000009107',
} as const

export function reviewSummary(overrides: Partial<ReviewSummary> = {}): ReviewSummary {
  return {
    id: reviewIds.request,
    revision: 2,
    version: 4,
    status: 'COMPLETED',
    invalidationReason: null,
    contextHash: 'a'.repeat(64),
    findingCount: 1,
    blockerCount: 0,
    highCount: 1,
    latestDecisionType: null,
    modificationRound: 1,
    ...overrides,
  }
}

export function reviewDetails(overrides: Partial<ReviewDetails> = {}): ReviewDetails {
  return {
    id: reviewIds.request,
    revision: 2,
    version: 4,
    status: 'COMPLETED',
    invalidationReason: null,
    reviewerRelationship: 'SELF_REVIEW',
    reviewerAgentProfileId: reviewIds.agentProfile,
    contextPackageId: reviewIds.context,
    contextHash: 'a'.repeat(64),
    diffArtifactId: reviewIds.diff,
    diffArtifactHash: 'b'.repeat(64),
    baselineCommit: 'c'.repeat(40),
    deliveryCommit: 'd'.repeat(40),
    changedPaths: ['src/main/java/io/crewscope/Review.java', 'src/test/java/io/crewscope/ReviewTest.java'],
    testEvidenceId: reviewIds.test,
    testEvidenceHash: 'e'.repeat(64),
    findings: [{
      id: reviewIds.finding,
      severity: 'HIGH',
      category: 'CORRECTNESS',
      title: '空值分支未处理',
      claim: '配置缺失时会触发空指针异常。',
      suggestedFix: '在进入解析前显式校验配置。',
      relationship: 'SELF_REVIEW',
      fingerprint: 'f'.repeat(64),
      evidence: [{
        path: 'src/main/java/io/crewscope/Review.java',
        startLine: 42,
        endLine: 47,
        acceptanceCriterionIndex: 0,
      }],
    }],
    decisions: [],
    modificationRounds: [{
      id: '00000000-0000-0000-0000-000000009108',
      roundNumber: 1,
      sourceReviewRequestId: reviewIds.previousRequest,
      triggerDecisionId: '00000000-0000-0000-0000-000000009109',
      createdAt: '2026-08-25T08:00:00Z',
    }],
    ...overrides,
  }
}

export function etaggedReview(overrides: Partial<ReviewDetails> = {}): EtaggedReview {
  const value = reviewDetails(overrides)
  return { value, etag: `"${value.version}"` }
}
