/** User-facing labels for configuration enums. Keep backend values at the domain boundary. */
export const ownerTypeLabels: Record<string, string> = { USER: '我的', TEAM: '团队', ORGANIZATION: '组织' }
export const connectionStatusLabels: Record<string, string> = { ACTIVE: '启用', SUSPENDED: '已暂停', REVOKED: '已吊销' }
export const healthStatusLabels: Record<string, string> = { HEALTHY: '健康', UNHEALTHY: '异常', UNKNOWN: '未知' }
export const agentStatusLabels: Record<string, string> = { ACTIVE: '启用', DISABLED: '已禁用', ARCHIVED: '已归档' }
export const retentionLabels: Record<string, string> = { ZERO_RETENTION: '零留存', LIMITED_RETENTION: '有限留存', STANDARD: '标准留存' }
export const trainingPolicyLabels: Record<string, string> = { NO_TRAINING: '不用于训练', ALLOWED: '允许用于训练' }

export function enumLabel(value: string | null | undefined, labels: Record<string, string>): string {
  return value ? labels[value] ?? value : '—'
}
