import type {
  ActionBundleValidity,
  ActionCancellationReason,
  ActionDispatchStatus,
  ActionInvalidationReason,
  ActionKind,
  ActionReceiptResult,
  ActionResultSource,
  ActionRiskLevel,
  CompensationDisposition,
  ConfirmationStatus,
  ExternalObjectStatus,
  ExternalObjectType,
  ExternalResultSource,
  GitHubAuthenticationType,
  GitHubConnectionOwnerType,
  GitHubExecutionIdentity,
  GitHubImportFailureCode,
  ProviderConnectionStatus,
  ProviderCredentialStatus,
  RepositoryVisibility,
} from './types'

/**
 * User-facing text for the Action delivery enums.
 *
 * Delivery is the one surface where the product performs irreversible external writes, so the
 * wording here has to be precise about what is and is not known: `UNKNOWN` and `FAILED` are not
 * the same verdict, and `MANUALLY_SUCCEEDED` says a human, not the system, closed the loop. Maps
 * are typed `Record<Enum, string>` so a new backend value fails typechecking.
 */
export const actionKindLabels: Record<ActionKind, string> = {
  PUSH_BRANCH: '推送分支',
  CREATE_DRAFT_PR: '创建 Draft PR',
  NOTIFY_COLLABORATION: '发送协作通知',
}

export const actionRiskLevelLabels: Record<ActionRiskLevel, string> = {
  READ_ONLY: '只读',
  LOW_RISK_WRITE: '低风险写入',
  HIGH_RISK_WRITE: '高风险写入',
  DESTRUCTIVE: '破坏性操作',
}

/**
 * Mirrors `ActionDispatchStatus`. `UNKNOWN` and `RECONCILING` deliberately do not read as failures:
 * the external side effect may well have succeeded and the system is still querying the provider.
 */
export const actionDispatchStatusLabels: Record<ActionDispatchStatus, string> = {
  READY: '待执行',
  RUNNING: '执行中',
  UNKNOWN: '结果未确定',
  RECONCILING: '对账中',
  MANUAL_REVIEW: '待人工终结',
  SUCCEEDED: '已成功',
  FAILED: '已失败',
  MANUALLY_SUCCEEDED: '人工判定成功',
  MANUALLY_FAILED: '人工判定失败',
  CANCELLED: '已取消',
}

export const actionReceiptResultLabels: Record<ActionReceiptResult, string> = {
  SUCCEEDED: '已成功',
  FAILED: '已失败',
  MANUALLY_SUCCEEDED: '人工判定成功',
  MANUALLY_FAILED: '人工判定失败',
  CANCELLED: '已取消',
}

/** Where the verdict came from. A `MANUAL` receipt is a member's decision, not a provider fact. */
export const actionResultSourceLabels: Record<ActionResultSource, string> = {
  WRITE_RESPONSE: '写请求响应',
  ACTIVE_QUERY: '主动查询',
  WEBHOOK: 'Webhook 回调',
  MANUAL: '人工终结',
  CONTROL: '控制面判定',
}

export const externalResultSourceLabels: Record<ExternalResultSource, string> = {
  WRITE_RESPONSE: '写请求响应',
  WEBHOOK: 'Webhook 回调',
  ACTIVE_QUERY: '主动查询',
}

export const actionCancellationReasonLabels: Record<ActionCancellationReason, string> = {
  CONFIRMATION_CANCELLED: '确认已撤回',
  MEMBER_CANCELLED: '成员主动取消',
  DEPENDENCY_FAILED: '前置动作失败',
  BUNDLE_EXPIRED: 'ActionBundle 已过期',
  AUTHORITY_INVALIDATED: '执行授权已失效',
}

/** Mirrors `ActionInvalidationReason`: why a planned bundle can no longer be confirmed as-is. */
export const actionInvalidationReasonLabels: Record<ActionInvalidationReason, string> = {
  EXPIRED: '已过有效期',
  REVIEW_CHANGED: 'Review 结论已变化',
  RESPONSIBILITY_CHANGED: '责任链已变化',
  PROVIDER_AUTHORIZATION_CHANGED: 'Provider 授权已变化',
  POLICY_CHANGED: '策略已变化',
  SAFETY_OVERLAY_CHANGED: '安全叠加策略已变化',
  TARGET_PRECONDITION_CHANGED: '目标前置条件已变化',
  AUTHORITY_UNAVAILABLE: '执行授权不可用',
}

export const actionBundleValidityLabels: Record<ActionBundleValidity, string> = {
  CURRENT: '与当前事实一致',
  STALE: '已失效',
}

export const confirmationStatusLabels: Record<ConfirmationStatus, string> = {
  ACTIVE: '生效中',
  CANCELLED: '已撤回',
}

/**
 * Mirrors `CompensationDisposition`. There is no automatic value on purpose: an external write that
 * may have landed is never silently undone, it is handed to a member.
 */
export const compensationDispositionLabels: Record<CompensationDisposition, string> = {
  NOT_REQUIRED: '无需补偿',
  MANUAL_REVIEW_REQUIRED: '需人工补偿',
}

export const externalObjectTypeLabels: Record<ExternalObjectType, string> = {
  BRANCH: '分支',
  PULL_REQUEST: 'Pull Request',
}

/** `ExternalObjectStatus` spans both object types, so it carries branch and PR values together. */
export const externalObjectStatusLabels: Record<ExternalObjectStatus, string> = {
  PRESENT: '已存在',
  MISSING: '不存在',
  OPEN: '开启中',
  CLOSED: '已关闭',
  MERGED: '已合并',
}

export const githubConnectionOwnerTypeLabels: Record<GitHubConnectionOwnerType, string> = {
  USER: '个人',
  TEAM: '团队',
}

export const githubAuthenticationTypeLabels: Record<GitHubAuthenticationType, string> = {
  APP_INSTALLATION: 'GitHub App 安装',
  OAUTH_USER: 'OAuth 用户授权',
}

export const githubExecutionIdentityLabels: Record<GitHubExecutionIdentity, string> = {
  TEAM: '团队身份',
  USER: '成员身份',
}

/** `ConnectionStatus` as GitHub connections report it, including the expiry-only terminal value. */
export const providerConnectionStatusLabels: Record<ProviderConnectionStatus, string> = {
  ACTIVE: '已授权',
  SUSPENDED: '已暂停',
  REVOKED: '已吊销',
  EXPIRED: '已过期',
}

/** GitHub repository visibility as the catalog reports it; not a CrewScope domain enum. */
/** Credential lifecycle, shared across providers. `ROTATING` is explicitly not a failure. */
export const providerCredentialStatusLabels: Record<ProviderCredentialStatus, string> = {
  ACTIVE: '有效',
  ROTATING: '轮换中',
  REVOKED: '已吊销',
}

/**
 * Import failures, worded so the member can tell授权问题 from 镜像问题 without opening logs —
 * the three families are collapsed into one map because the job projection reports them in one field.
 */
export const githubImportFailureCodeLabels: Record<GitHubImportFailureCode, string> = {
  AUTHENTICATION_REQUIRED: '需要重新授权',
  PERMISSION_DENIED: '权限不足',
  RATE_LIMITED: 'GitHub 速率限制',
  RESOURCE_UNAVAILABLE: '远端资源不可用',
  CONFLICT: '远端状态冲突',
  VALIDATION_FAILED: '请求校验失败',
  PROVIDER_UNAVAILABLE: 'GitHub 暂不可用',
  CONNECTION_UNAVAILABLE: 'Connection 不可用',
  GRANT_UNAVAILABLE: '授权 Grant 不可用',
  CREDENTIAL_UNAVAILABLE: '凭证不可用',
  IDENTITY_MISMATCH: '执行身份不匹配',
  REPOSITORY_BLOCKED: '仓库不在 Allowlist',
  REPOSITORY_STALE: '仓库目录已过期',
  DEFAULT_BRANCH_MISMATCH: '默认分支不一致',
  AUTHORITY_STALE: '执行授权已过期',
  MIRROR_UNAVAILABLE: '本地镜像不可用',
  BASELINE_MISMATCH: '基线提交不一致',
  DELIVERY_HEAD_MISMATCH: '交付 HEAD 不一致',
  REMOTE_HEAD_CONFLICT: '远端 HEAD 冲突',
  NON_FAST_FORWARD: '非快进推送被拒',
  PROTECTED_BRANCH: '目标分支受保护',
  PUSH_REJECTED: '推送被拒绝',
  UNKNOWN: '未知失败',
  IMPORT_FAILED: '导入过程异常',
}

export const repositoryVisibilityLabels: Record<RepositoryVisibility, string> = {
  PUBLIC: '公开',
  PRIVATE: '私有',
  INTERNAL: '组织内可见',
}
