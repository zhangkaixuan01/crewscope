import type { SearchObjectType } from './types'

/** Stable user-facing labels for the server-owned search object enum. */
export const searchObjectTypeLabels: Record<SearchObjectType, string> = {
  WORK_ITEM: '工作项',
  CONVERSATION: '对话',
  TASK: '任务',
  REPOSITORY_BINDING: '仓库绑定',
  AGENT: 'Agent',
  TEAM_MEMBER: '团队成员',
}
