import type {
  ActivityCategory,
  ActivityReferenceType,
  ActivitySubjectType,
  ActivityVisibility,
  AuditActorType,
  AuditEventCategory,
  AuditOutcome,
  AuditRetentionLevel,
  CorrelationEventSource,
  CorrelationObjectType,
  InboxCloseReason,
  InboxDispositionStatus,
  InboxItemType,
  InboxPriority,
  InboxSourceStatus,
  InboxSourceType,
  LarkConnectionStatus,
  LarkCredentialStatus,
  LarkHealthStatus,
  LarkMappingStatus,
  LarkMappingTerminalReason,
  NotificationDeliveryStatus,
  NotificationFailureCode,
  NotificationTemplateStatus,
  OperationsHealthComponent,
  OperationsHealthLevel,
  OperationsRecoveryAction,
  ProjectionGenerationStatus,
  ProjectionRebuildStatus,
  RecoveryCandidateType,
} from './types'
import type { ActivityRealtimePhase } from './activityRealtimeStore'
import { principalTypeLabels } from '../principal/labels'

/**
 * User-facing text for the team-operations enums: Inbox, Activity, Audit, the Lark provider and the
 * administrator's Operations console.
 *
 * These four surfaces share one property that makes raw constants especially bad here — they are
 * read as a record of what happened, often after the fact and often by someone who was not present.
 * `AUTHORIZATION_DRIFT` and `MEMBER_LEFT` both revoke a mapping but mean very different things to
 * the administrator reading the row. Every map is typed `Record<Enum, string>`, so a new backend
 * value fails typechecking rather than leaking.
 */
export const inboxItemTypeLabels: Record<InboxItemType, string> = {
  OWNERSHIP: '责任归属',
  EXECUTION: '执行进展',
  REVIEW: '评审',
  CONFIRMATION: '人工确认',
  EXCEPTION: '异常',
}

export const inboxPriorityLabels: Record<InboxPriority, string> = {
  URGENT: '紧急',
  HIGH: '高',
  NORMAL: '普通',
  LOW: '低',
}

/** What produced the Inbox item. The Inbox never owns a fact, it points at the aggregate that does. */
export const inboxSourceTypeLabels: Record<InboxSourceType, string> = {
  RESPONSIBILITY_ASSIGNMENT: '责任分配',
  REVIEW_REQUEST: '评审请求',
  ACTION_CONFIRMATION: '动作确认',
  TASK_EXECUTION: 'Task 执行',
  ACTION_DELIVERY: '外部交付',
  NOTIFICATION_DELIVERY: '通知投递',
}

/** Whether the source aggregate still needs anything, independent of the member's own disposition. */
export const inboxSourceStatusLabels: Record<InboxSourceStatus, string> = {
  OPEN: '来源仍待处理',
  CLOSED: '来源已结束',
}

export const inboxDispositionStatusLabels: Record<InboxDispositionStatus, string> = {
  UNREAD: '未读',
  READ: '已读',
  ACTED: '已处理',
  ARCHIVED: '已归档',
}

/**
 * Mirrors `InboxCloseReason`. The member sees this after the item has left their queue, so each
 * value has to explain why it left without requiring them to re-open the source.
 */
export const inboxCloseReasonLabels: Record<InboxCloseReason, string> = {
  RESPONSIBILITY_RELEASED: '责任已释放',
  RESPONSIBILITY_REPLACED: '责任已转移',
  REVIEW_COMPLETED: '评审已完成',
  REVIEW_SUPERSEDED: '评审已被新一轮取代',
  CONFIRMATION_COMPLETED: '确认已完成',
  CONFIRMATION_CANCELLED: '确认已撤回',
  CONFIRMATION_EXPIRED: '确认已过期',
  EXCEPTION_RECOVERED: '异常已自动恢复',
  EXCEPTION_RESOLVED: '异常已人工处理',
  MEMBER_NO_LONGER_ELIGIBLE: '成员已不再具备处理资格',
}

export const activityCategoryLabels: Record<ActivityCategory, string> = {
  TEAM: '团队',
  WORK_ITEM: '工作项',
  TASK: 'Task',
  REVIEW: '评审',
  ACTION: '外部动作',
  PROVIDER: 'Provider',
  SYSTEM: '系统',
}

/** Who an Activity entry is visible to. This is the reason the feed is safe to read as a team. */
export const activityVisibilityLabels: Record<ActivityVisibility, string> = {
  TEAM_MEMBERS: '团队成员可见',
  WORK_ITEM_PARTICIPANTS: '工作项参与者可见',
  TEAM_ADMINS: '团队管理员可见',
}

export const activitySubjectTypeLabels: Record<ActivitySubjectType, string> = {
  TEAM: '团队',
  WORK_ITEM: '工作项',
  TASK: 'Task',
  REVIEW: '评审',
  ACTION: '外部动作',
  PROVIDER_BINDING: 'Provider 绑定',
  ARTIFACT: '产物',
  CONVERSATION: '会话',
}

/**
 * `ActivityReferenceType` extends the subject types with the external objects an entry can point
 * at, so it reuses the subject wording rather than restating the eight shared values.
 */
export const activityReferenceTypeLabels: Record<ActivityReferenceType, string> = {
  ...activitySubjectTypeLabels,
  PULL_REQUEST: 'Pull Request',
}

export const auditEventCategoryLabels: Record<AuditEventCategory, string> = {
  IDENTITY: '身份',
  TEAM: '团队',
  WORK: '工作项',
  COLLABORATION: '协作',
  EXECUTION: '执行',
  AGENT: 'Agent',
  MODEL: '模型',
  REVIEW: '评审',
  ACTION: '外部动作',
  PROVIDER: 'Provider',
  NOTIFICATION: '通知',
  PROJECTION: 'Projection',
  SECURITY: '安全',
  SYSTEM: '系统',
}

/**
 * `AuditOutcome`. `DENIED` is not a failure — it records that a policy correctly refused the
 * request — so the wording keeps the two apart.
 */
export const auditOutcomeLabels: Record<AuditOutcome, string> = {
  SUCCEEDED: '已成功',
  DENIED: '已拒绝',
  FAILED: '已失败',
}

export const auditRetentionLevelLabels: Record<AuditRetentionLevel, string> = {
  STANDARD: '标准留存',
  EXTENDED: '延长留存',
  LEGAL_HOLD: '法务封存',
}

/**
 * `EventActorType` is `PrincipalType` under another name, so the audit trail shares the identity
 * wording instead of maintaining a second copy that could drift from the directory.
 */
export const auditActorTypeLabels: Record<AuditActorType, string> = principalTypeLabels

export const correlationEventSourceLabels: Record<CorrelationEventSource, string> = {
  DOMAIN_EVENT: '领域事件',
  AUDIT: '审计事件',
}

export const correlationObjectTypeLabels: Record<CorrelationObjectType, string> = {
  CONVERSATION: '会话',
  WORK_ITEM: '工作项',
  TASK: 'Task',
  REVIEW: '评审',
  ACTION: '外部动作',
  PULL_REQUEST: 'Pull Request',
  ACTIVITY: 'Activity 事件',
  INBOX: 'Inbox 项',
  NOTIFICATION: '通知',
  AUDIT: '审计事件',
}

export const larkConnectionStatusLabels: Record<LarkConnectionStatus, string> = {
  ACTIVE: '已连接',
  SUSPENDED: '已暂停',
  REVOKED: '已撤销',
  EXPIRED: '已过期',
}

export const larkCredentialStatusLabels: Record<LarkCredentialStatus, string> = {
  ACTIVE: '凭证有效',
  ROTATING: '凭证轮换中',
  REVOKED: '凭证已撤销',
}

export const larkMappingStatusLabels: Record<LarkMappingStatus, string> = {
  ACTIVE: '映射生效',
  REVOKED: '已撤销',
  INVALIDATED: '已失效',
}

/** Why a member's Lark mapping reached a terminal state — an administrator's audit question. */
export const larkMappingTerminalReasonLabels: Record<LarkMappingTerminalReason, string> = {
  ADMIN_REVOKED: '管理员撤销',
  MEMBER_LEFT: '成员已离开团队',
  AUTHORIZATION_DRIFT: '授权与飞书侧不一致',
  IDENTITY_REPLACED: '身份已被替换',
}

/**
 * Mirrors the Lark health probe result. Only `HEALTHY` means notifications will go out; the other
 * eleven values each point at a different owner (us, the administrator, or the provider).
 */
export const larkHealthStatusLabels: Record<LarkHealthStatus, string> = {
  HEALTHY: '健康',
  AUTHORIZATION_UNAVAILABLE: '授权不可用',
  AUTHENTICATION_REQUIRED: '需要重新认证',
  PERMISSION_DENIED: '权限不足',
  RESOURCE_UNAVAILABLE: '目标资源不可用',
  RATE_LIMITED: '触发限流',
  PROVIDER_UNAVAILABLE: '飞书服务不可用',
  INVALID_RESPONSE: '响应不合法',
  IDENTITY_MISMATCH: '身份不匹配',
  CONNECTION_UNAVAILABLE: 'Connection 不可用',
  CREDENTIAL_UNAVAILABLE: '凭证不可用',
  CANCELLED: '检查已取消',
}

export const notificationTemplateStatusLabels: Record<NotificationTemplateStatus, string> = {
  PUBLISHED: '已发布',
  RETIRED: '已下线',
}

/**
 * Mirrors `NotificationDeliveryStatus`. `UNKNOWN` and `RECONCILING` are not failures: a Lark send
 * whose response was lost may still have been delivered, and only `FAILED_FINAL` may be re-sent.
 */
export const notificationDeliveryStatusLabels: Record<NotificationDeliveryStatus, string> = {
  READY: '待投递',
  RUNNING: '投递中',
  RETRY_WAIT: '等待重试',
  UNKNOWN: '结果未确定',
  RECONCILING: '对账中',
  SUCCEEDED: '已送达',
  FAILED_FINAL: '已终态失败',
  INVALIDATED: '已失效',
  CANCELLED: '已取消',
}

export const notificationFailureCodeLabels: Record<NotificationFailureCode, string> = {
  RECIPIENT_UNAVAILABLE: '收件成员不可达',
  AUTHORIZATION_REVOKED: '授权已被撤销',
  PROVIDER_REJECTED: '飞书侧拒绝',
  RETRY_EXHAUSTED: '重试次数已用尽',
  RECONCILIATION_EXHAUSTED: '对账次数已用尽',
}

export const operationsHealthComponentLabels: Record<OperationsHealthComponent, string> = {
  PROJECTION: 'Projection',
  OUTBOX: 'Outbox',
  DEAD_LETTER: 'Dead Letter',
  CURSOR: 'Cursor',
  NOTIFICATION: 'Notification',
}

/**
 * `ATTENTION_REQUIRED` sits between degraded and unavailable on purpose: the component is still
 * serving requests but will not recover without an operator, so it must not read as a mere warning.
 */
export const operationsHealthLevelLabels: Record<OperationsHealthLevel, string> = {
  HEALTHY: '健康',
  DEGRADED: '已降级',
  ATTENTION_REQUIRED: '需要人工介入',
  UNAVAILABLE: '不可用',
}

export const projectionGenerationStatusLabels: Record<ProjectionGenerationStatus, string> = {
  BUILDING: '构建中',
  VALIDATING: '校验中',
  ACTIVE: '已启用',
  RETIRED: '已退役',
  FAILED: '已失败',
  CANCELLED: '已取消',
}

export const projectionRebuildStatusLabels: Record<ProjectionRebuildStatus, string> = {
  BUILDING: '构建中',
  VALIDATING: '校验中',
  COMPLETED: '已完成',
  FAILED: '已失败',
  CANCELLED: '已取消',
}

/** Which backlog a recovery candidate came out of — the action says what will be done to it. */
export const recoveryCandidateTypeLabels: Record<RecoveryCandidateType, string> = {
  OUTBOX_DEAD_LETTER: 'Outbox 死信',
  PROJECTION_DEAD_LETTER: 'Projection 死信',
  NOTIFICATION_DELIVERY: '通知投递',
}

/**
 * The Activity realtime phase is a client-side connection state, not a server enum, but it is shown
 * as a badge on two surfaces and was worded differently in each. `offline` and `error` are both
 * degraded, and neither means the loaded facts became untrustworthy — hence "已加载事实仍可读" wording
 * lives next to the badge rather than in it.
 */
export const activityRealtimePhaseLabels: Record<ActivityRealtimePhase, string> = {
  idle: '未连接',
  connecting: '连接中',
  live: '实时',
  reconnecting: '重连中',
  offline: '离线',
  forbidden: '无权限',
  'cursor-expired': '游标过期',
  error: '实时异常',
}

export const operationsRecoveryActionLabels: Record<OperationsRecoveryAction, string> = {
  REPLAY_OUTBOX_DEAD_LETTER: '回放 Outbox',
  REPLAY_PROJECTION_DEAD_LETTER: '回放 Projection',
  RETRY_NOTIFICATION_DELIVERY: '重试通知',
}
