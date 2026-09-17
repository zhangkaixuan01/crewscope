import type {
  ReviewCommentAnchorState,
  ReviewCommentSide,
  ReviewDecisionType,
  ReviewFindingCategory,
  ReviewFindingSeverity,
  ReviewInvalidationReason,
  ReviewRequestStatus,
  ReviewerEligibilityMode,
  ReviewerRelationship,
} from './types'
import { acceptanceStatusLabels } from '../coding/labels'

/**
 * User-facing text for the Review enums.
 *
 * Review is the surface where a raw constant is most expensive: `SELF_REVIEW` and `INDEPENDENT`
 * decide whether a Finding can gate a delivery, and `INVALIDATED` decides whether a conclusion
 * still counts. Each map is typed `Record<Enum, string>` so a new backend value fails typechecking.
 */
export const reviewRequestStatusLabels: Record<ReviewRequestStatus, string> = {
  OPEN: '待评审',
  IN_PROGRESS: '评审中',
  COMPLETED: '已完成',
  INVALIDATED: '已失效',
}

/**
 * Reviewer independence. The wording spells out the consequence rather than the constant, because
 * this single value is what separates an advisory Finding from a Gate-capable one.
 */
export const reviewerRelationshipLabels: Record<ReviewerRelationship, string> = {
  INDEPENDENT: '独立评审',
  SELF_REVIEW: '自审（仅参考）',
}

export const reviewDecisionTypeLabels: Record<ReviewDecisionType, string> = {
  COMMENTED: '留言',
  APPROVED: '通过',
  CHANGES_REQUESTED: '请求修改',
  REJECTED: '拒绝',
}

/** Mirrors `ReviewerEligibilityMode`: which separation-of-duty rule admitted this decision. */
export const reviewerEligibilityModeLabels: Record<ReviewerEligibilityMode, string> = {
  STRICT_SEPARATION: '严格职责分离',
  SINGLE_MEMBER_OVERRIDE: '单人团队豁免',
}

/** Mirrors `ReviewInvalidationReason`: why an earlier conclusion no longer controls delivery. */
export const reviewInvalidationReasonLabels: Record<ReviewInvalidationReason, string> = {
  SUBJECT_CHANGED: '被审对象已变化',
  DIFF_CHANGED: '代码变更已更新',
  TEST_EVIDENCE_CHANGED: '测试证据已更新',
  REVIEWER_CONFIGURATION_CHANGED: 'Reviewer 配置已变化',
  POLICY_CHANGED: '策略已变化',
  CONTEXT_CHANGED: 'Review Context 已变化',
}

export const reviewFindingSeverityLabels: Record<ReviewFindingSeverity, string> = {
  BLOCKER: '阻塞',
  HIGH: '高',
  MEDIUM: '中',
  LOW: '低',
}

export const reviewFindingCategoryLabels: Record<ReviewFindingCategory, string> = {
  CORRECTNESS: '正确性',
  SECURITY: '安全',
  RELIABILITY: '可靠性',
  MAINTAINABILITY: '可维护性',
  TESTING: '测试',
  ACCEPTANCE: '验收',
}

export const reviewCommentSideLabels: Record<ReviewCommentSide, string> = {
  OLD: '旧版本',
  NEW: '新版本',
}

export const reviewCommentAnchorStateLabels: Record<ReviewCommentAnchorState, string> = {
  ACTIVE: '锚点有效',
  OUTDATED: '锚点已过期',
}

/**
 * `AcceptanceStatus` is owned by the Coding evidence domain but the Review workbench renders the
 * same acceptance rows, so it re-exports the wording instead of restating it.
 */
export { acceptanceStatusLabels }
