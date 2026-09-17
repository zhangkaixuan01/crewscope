import type { TeamJoinMethod, TeamMemberStatus } from './types'

/**
 * User-facing text for the Team membership enums.
 *
 * Every key here is a value the server actually sends (`TeamMemberStatus.name()` and
 * `TeamJoinMethod.name()`); anything else would silently fall through to the raw constant, which
 * is the failure this file exists to prevent.
 */
export const teamMemberStatusLabels: Record<TeamMemberStatus, string> = {
  INVITED: '待加入',
  ACTIVE: '活跃',
  SUSPENDED: '已暂停',
  LEFT: '已退出',
  REMOVED: '已移除',
}

export const teamJoinMethodLabels: Record<TeamJoinMethod, string> = {
  BOOTSTRAP: '创建团队',
  INVITATION: '邀请加入',
  OIDC: 'OIDC 登录',
  SCIM: 'SCIM 同步',
  IMPORT: '目录导入',
}

/**
 * Role keys are `TeamRoleKey` values, so a Team may carry roles beyond the product-owned set.
 * The map is deliberately open: unknown custom keys render as their own key rather than as '—'.
 */
export const teamRoleLabels: Record<string, string> = {
  TEAM_OWNER: 'Owner',
  TEAM_ADMIN: '团队管理员',
  TEAM_LEAD: '团队负责人',
  MEMBER: '成员',
  AUDITOR: '审计员',
}
