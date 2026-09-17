import type {
  ConversationMessageType,
  ConversationParticipantRole,
  ConversationParticipantStatus,
  ConversationStatus,
  ConversationVisibility,
  TaskIntentStatus,
} from './types'
import { principalTypeLabels } from '../principal/labels'

/**
 * User-facing text for the Conversation enums.
 *
 * Every map is typed against the union the server actually sends, so a new backend value fails
 * typechecking instead of printing a raw Java constant in the thread.
 */
export const conversationVisibilityLabels: Record<ConversationVisibility, string> = {
  PRIVATE: '仅自己可见',
  TEAM: '团队可见',
}

export const conversationStatusLabels: Record<ConversationStatus, string> = {
  ACTIVE: '进行中',
  ARCHIVED: '已归档',
}

export const conversationParticipantRoleLabels: Record<ConversationParticipantRole, string> = {
  OWNER: '发起人',
  MEMBER: '参与成员',
  AGENT: 'Agent',
}

export const conversationParticipantStatusLabels: Record<ConversationParticipantStatus, string> = {
  ACTIVE: '在会话中',
  LEFT: '已退出',
}

export const conversationMessageTypeLabels: Record<ConversationMessageType, string> = {
  USER_MESSAGE: '成员发言',
  AGENT_MESSAGE: 'Agent 回复',
  SYSTEM_NOTICE: '系统提示',
}

/**
 * A Conversation participant's `principalType` is the same enum the responsibility chain uses, so
 * the wording is shared rather than restated; a second copy would drift.
 */
export const conversationPrincipalTypeLabels = principalTypeLabels

/** Mirrors `TaskIntentStatus`: the lifecycle of one Agent-proposed Task before confirmation. */
export const taskIntentStatusLabels: Record<TaskIntentStatus, string> = {
  DRAFT: '草稿',
  READY: '待确认',
  CONFIRMED: '已确认',
  REJECTED: '已驳回',
  EXPIRED: '已过期',
}
