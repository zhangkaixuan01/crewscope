import type {
  ResponsibilityRole,
  WorkItemPriority,
  WorkItemResourceType,
  WorkItemStatus,
  WorkItemTransitionBlockReason,
  WorkItemTransitionStrength,
  WorkItemType,
} from './types'

/**
 * User-facing labels for the server-owned WorkItem enums.
 *
 * These are typed as `Record<Enum, string>` on purpose: when the backend adds a status, type or
 * block reason, the generated union widens and this file fails to compile. That compile error is
 * the only thing standing between a new backend constant and a raw `IN_REVIEW` on screen.
 */
export const workItemStatusLabels: Record<WorkItemStatus, string> = {
  BACKLOG: '待规划',
  READY: '待执行',
  IN_PROGRESS: '进行中',
  IN_REVIEW: '评审中',
  BLOCKED: '已阻塞',
  DONE: '已完成',
  CANCELLED: '已取消',
  ARCHIVED: '已归档',
}

export const workItemTypeLabels: Record<WorkItemType, string> = {
  TASK: '任务',
  BUG: '缺陷',
  FEATURE: '需求',
  INCIDENT: '故障',
}

export const workItemPriorityLabels: Record<WorkItemPriority, string> = {
  LOW: '低',
  MEDIUM: '中',
  HIGH: '高',
  URGENT: '紧急',
}

export const workItemResourceTypeLabels: Record<WorkItemResourceType, string> = {
  TASK: '任务',
  CONVERSATION: '对话',
  REPOSITORY: '仓库',
  BRANCH: '分支',
  COMMIT: '提交',
  PULL_REQUEST: 'Pull Request',
  ARTIFACT: '产物',
  EXTERNAL_URL: '外部链接',
}

/**
 * The three responsibility roles a WorkItem can carry.
 *
 * One map for the whole product: the personal WorkDesk publishes the same three role names and
 * reuses this one, so a member who is 执行人 in the drawer is not 执行者 in the workbench.
 */
export const workItemResponsibilityRoleLabels: Record<ResponsibilityRole, string> = {
  OWNER: '负责人',
  EXECUTOR: '执行人',
  REVIEWER: 'Reviewer',
}

/** Mirrors `WorkItemTransitionBlockReason` in the application layer. */
export const workItemTransitionBlockReasonLabels: Record<WorkItemTransitionBlockReason, string> = {
  STATUS_NOT_ALLOWED: '当前状态不允许此操作',
  PERMISSION_DENIED: '当前成员没有执行此操作的权限',
  REVIEWER_REQUIRED: '需要先指派 Reviewer',
  DUTY_SEPARATION_CONFLICT: '当前成员不能同时承担冲突职责',
  GATE_NOT_PASSED: '前置人工决策尚未通过',
  BLOCKED_BY_DEPENDENCY: '存在未解决的阻塞项',
  EXTERNAL_PROVIDER_MANAGED: '此工作项由外部 Provider 管理',
  ARCHIVED: '工作项已归档',
}

/** Maps action strength onto the Design System button variants. */
export const workItemTransitionVariants: Record<WorkItemTransitionStrength, 'primary' | 'secondary' | 'danger'> = {
  PRIMARY: 'primary',
  SECONDARY: 'secondary',
  DANGER: 'danger',
}

/**
 * WorkItem `source` is an open provider identifier rather than a closed enum, so this map stays a
 * `Record<string, string>` and unknown providers fall through to their raw key by design.
 */
export const workItemSourceLabels: Record<string, string> = {
  CREWSCOPE: 'CrewScope',
  GITHUB: 'GitHub',
  LARK: '飞书',
}
