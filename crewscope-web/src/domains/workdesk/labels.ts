import { enumLabel } from '../shared/labels'
import { taskExecutionStatusLabels } from '../task/labels'
import { workItemResponsibilityRoleLabels, workItemStatusLabels } from '../workitem/labels'
import type {
  WorkDeskItem,
  WorkDeskObjectType,
  WorkDeskRequestStatus,
  WorkDeskResponsibilityRole,
  WorkDeskUrgency,
} from './types'

/**
 * User-facing text for the personal WorkDesk projection.
 *
 * The WorkDesk is a cross-aggregate feed: `JdbcWorkDeskRepositoryAdapter` emits five object types
 * and `status` means a different enum in each one. A single flat map would therefore be wrong by
 * construction, so the status goes through {@link workDeskStatusLabel}, which dispatches on
 * `objectType` and reuses the owning domain's own label map.
 */
export const workDeskObjectTypeLabels: Record<WorkDeskObjectType, string> = {
  WORK_ITEM: '工作项',
  TASK_EXECUTION: 'Agent 执行',
  HUMAN_GATE: '人工决策',
  REVIEW_REQUEST: '评审请求',
  INBOX: 'Inbox',
}

/** The same three roles the WorkItem domain names, so the workbench and the drawer agree. */
export const workDeskResponsibilityRoleLabels: Record<WorkDeskResponsibilityRole, string> =
  workItemResponsibilityRoleLabels

/**
 * Derived by the adapter from `WorkItemPriority`, so it shares that enum's constants.
 *
 * `NORMAL` is 常规 rather than 待处理 because the workbench prints the urgency beside the row's
 * status, and a ReviewRequest in `OPEN` already reads 待处理 — the same words twice with two
 * different meanings on one line reads as one fact stated twice, not as two.
 */
export const workDeskUrgencyLabels: Record<WorkDeskUrgency, string> = {
  URGENT: '紧急',
  HIGH: '高优先级',
  NORMAL: '常规',
  LOW: '低优先级',
}

/** `ReviewRequestStatus`, which the adapter also reuses for the Human Gate and Inbox rows. */
export const workDeskRequestStatusLabels: Record<WorkDeskRequestStatus, string> = {
  OPEN: '待处理',
  IN_PROGRESS: '处理中',
  COMPLETED: '已完成',
  INVALIDATED: '已失效',
}

/** Reads the WorkDesk row's status through the label map of whichever aggregate produced it. */
export function workDeskStatusLabel(item: Pick<WorkDeskItem, 'objectType' | 'status'>): string {
  switch (item.objectType) {
    case 'WORK_ITEM':
      return enumLabel(item.status, workItemStatusLabels)
    case 'TASK_EXECUTION':
      return enumLabel(item.status, taskExecutionStatusLabels)
    case 'HUMAN_GATE':
    case 'REVIEW_REQUEST':
    case 'INBOX':
      return enumLabel(item.status, workDeskRequestStatusLabels)
    default:
      // An object type this build does not know about still renders its raw status rather than
      // pretending the row is empty; the WorkDesk projection is additive on the server side.
      return item.status
  }
}
