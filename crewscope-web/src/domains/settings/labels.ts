import type {
  ModelConnectionHealthFailureCode,
  ModelConnectionHealthStatus,
  ModelConnectionOwnerType,
  ModelConnectionRevocationReason,
  ModelConnectionStatus,
  ModelDataRetentionMode,
  ModelSubjectType,
  ModelTrainingUsagePolicy,
} from '../model/types'

/**
 * User-facing labels for configuration enums. Keep backend values at the domain boundary.
 *
 * Every map is typed against the union the server actually sends, so adding a backend value or
 * misremembering one fails typechecking rather than printing a raw Java constant on screen.
 * Use {@link import('../shared/labels').enumLabel} to read them.
 */
export const ownerTypeLabels: Record<ModelConnectionOwnerType, string> = {
  USER: '我的',
  TEAM: '团队',
  ORGANIZATION: '组织',
}

export const connectionStatusLabels: Record<ModelConnectionStatus, string> = {
  ACTIVE: '启用',
  SUSPENDED: '已暂停',
  REVOKED: '已吊销',
}

export const healthStatusLabels: Record<ModelConnectionHealthStatus, string> = {
  HEALTHY: '健康',
  UNHEALTHY: '异常',
  UNKNOWN: '未知',
}

/** Who the connection bills, phrased as the owner reads it rather than as the domain names it. */
export const modelSubjectTypeLabels: Record<ModelSubjectType, string> = {
  PRINCIPAL: '成员自付',
  TEAM: '团队承担',
  ORGANIZATION: '组织承担',
}

/**
 * The stable health failure vocabulary. These are the only failure words the UI may show — the
 * Provider's own error text stays behind the server boundary — so each one has to be self-explanatory.
 */
export const modelConnectionHealthFailureCodeLabels: Record<ModelConnectionHealthFailureCode, string> = {
  AUTHENTICATION_FAILED: '身份验证失败',
  ENDPOINT_UNREACHABLE: 'Provider 不可达',
  TIMEOUT: '验证超时',
  RATE_LIMITED: '触发速率限制',
  PROVIDER_REJECTED: 'Provider 拒绝请求',
  POLICY_REJECTED: '组织策略拒绝',
}

/** Revocation is terminal, so the reason is the only record of why — it is never abbreviated. */
export const modelConnectionRevocationReasonLabels: Record<ModelConnectionRevocationReason, string> = {
  OWNER_REQUESTED: 'Owner 主动撤销',
  CREDENTIAL_REVOKED: '凭证已在 Provider 撤销',
  PROVIDER_DISABLED: 'Provider 已停用',
  POLICY_REVOKED: '策略撤销',
  SECURITY_INCIDENT: '安全事件',
}

export const retentionLabels: Record<ModelDataRetentionMode, string> = {
  NONE: '零留存',
  TIME_BOUND: '有限留存',
  PROVIDER_MANAGED: 'Provider 自管',
}

export const trainingPolicyLabels: Record<ModelTrainingUsagePolicy, string> = {
  PROHIBITED: '不用于训练',
  EXPLICIT_OPT_IN: '需显式授权',
  PROVIDER_DEFAULT: 'Provider 默认',
}
