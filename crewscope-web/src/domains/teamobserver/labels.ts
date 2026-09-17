import type { TeamObserverEventType, TeamSummaryDataScope } from './types'

/**
 * User-facing text for the Team Observer read surface.
 *
 * The observer is a read-only Agent that writes prose about the team, so the only thing anchoring
 * that prose to fact is the data scope printed next to it. Naming the scope in the member's own
 * words is what turns "一句模型写的话" into "一句有来源的话".
 */
export const teamSummaryDataScopeLabels: Record<TeamSummaryDataScope, string> = {
  TEAM_ACTIVITY: '来自团队 Activity',
  TEAM_INBOX_SUMMARY: '来自团队 Inbox 概要',
  WORK_ITEM_SUMMARY: '来自 WorkItem 概要',
  TASK_SUMMARY: '来自 Task 概要',
  ARTIFACT_SUMMARY: '来自证据概要',
}

/** Observer session lifecycle as the event stream reports it. */
export const teamObserverEventTypeLabels: Record<TeamObserverEventType, string> = {
  STARTED: '已开始',
  SUMMARY_COMPLETED: '概要已生成',
  CANCELLED: '已取消',
  FAILED: '已失败',
}
