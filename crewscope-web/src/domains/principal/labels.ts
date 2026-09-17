import type { PlatformRole, PrincipalKind, PrincipalStatus, PrincipalType } from './types'

export const principalKindLabels: Record<PrincipalKind, string> = {
  USER: '成员',
  AGENT: 'Agent',
}

/** The actor behind a responsibility, a Conversation turn or an audit entry. */
export const principalTypeLabels: Record<PrincipalType, string> = {
  USER: '成员',
  PERSONAL_AGENT: '个人 Agent',
  TEAM_AGENT: '团队 Agent',
  SPECIALIST_AGENT: 'Specialist Agent',
  SERVICE: '服务账号',
}

/** The account-level role shown in the account menu and on the account page. */
export const platformRoleLabels: Record<PlatformRole, string> = {
  USER: '普通成员',
  OPERATOR: '平台运维',
}

export const principalStatusLabels: Record<PrincipalStatus, string> = {
  ACTIVE: '正常',
  SUSPENDED: '已暂停',
  DISABLED: '已停用',
  ARCHIVED: '已归档',
}
