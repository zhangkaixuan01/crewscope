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

/** `HandoverJobStatus.name()` on the wire: PENDING / RUNNING / COMPLETED / CANCELLED. */
export const handoverJobStatusLabels: Record<string, string> = {
  PENDING: '待处理',
  RUNNING: '执行中',
  COMPLETED: '已完成',
  CANCELLED: '已取消',
}

/**
 * The closed set of codes `ResponsibilityHandoverApplicationService` settles stopped items with.
 * The map stays open-typed so an unknown future code renders as the constant instead of blank.
 */
export const handoverErrorCodeLabels: Record<string, string> = {
  OWNER_EXPECTATION_STALE: 'Owner 视图已过期',
  SOURCE_ASSIGNMENT_CHANGED: '原责任已变化',
  ASSIGNMENT_VERSION_STALE: '分派版本已变化',
  RESPONSIBILITY_SLOT_HELD: '责任槽位被占用',
  RESPONSIBILITY_MANAGE_DENIED: '缺少责任管理权限',
  TARGET_NOT_ELIGIBLE: '接手方不再满足条件',
  WORK_ITEM_MISSING: '工作项已不存在',
}
