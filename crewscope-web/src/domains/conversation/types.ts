import type { CommandReceipt } from '../scope/types'

export type ConversationVisibility = 'PRIVATE' | 'TEAM'
export type ConversationStatus = 'ACTIVE' | 'ARCHIVED'

export interface ConversationScope {
  organizationId: string
  teamId: string
}

export interface ConversationSummary extends ConversationScope {
  id: string
  workspaceId: string
  ownerMemberId: string
  ownerPrincipalId: string
  personalAgentPrincipalId: string
  title: string
  visibility: ConversationVisibility
  status: ConversationStatus
  lastMessageSequence: number | null
  version: number
  createdAt: string
  updatedAt: string
}

export interface ConversationParticipant {
  id: string
  conversationId: string
  principalId: string
  teamMemberId: string | null
  displayName: string
  principalType: 'USER' | 'PERSONAL_AGENT' | 'TEAM_AGENT' | 'SPECIALIST_AGENT' | 'SERVICE'
  ownerPrincipalId: string | null
  ownerDisplayName: string | null
  role: 'OWNER' | 'MEMBER' | 'AGENT'
  status: 'ACTIVE' | 'LEFT'
  joinedByPrincipalId: string
  joinedAt: string
  leftAt: string | null
  version: number
}

export interface ConversationDetails {
  conversation: ConversationSummary
  participants: ConversationParticipant[]
}

export interface ConversationPage {
  items: ConversationSummary[]
  nextCursor: string | null
}

export interface ConversationListQuery extends ConversationScope {
  status?: ConversationStatus
  after?: string
  limit?: number
}

export interface CreateConversationInput {
  title: string
  visibility: ConversationVisibility
}

export type ConversationCommandReceipt = CommandReceipt

export type ConversationMessageType = 'USER_MESSAGE' | 'AGENT_MESSAGE' | 'SYSTEM_NOTICE'

export interface ConversationMessage {
  id: string
  conversationId: string
  sequence: number
  type: ConversationMessageType
  participantId: string | null
  authorPrincipalId: string | null
  content: string
  createdAt: string
}

export interface ConversationMessagePage {
  items: ConversationMessage[]
  nextCursor: string | null
}

export interface ConversationMessageScope extends ConversationScope {
  conversationId: string
}

export interface ConversationMessageListQuery extends ConversationMessageScope {
  after?: string
  limit?: number
}

export interface PostConversationMessageInput {
  content: string
}

export interface RealtimeEventEnvelope<TPayload = Record<string, unknown>> {
  eventId: string
  domainEventId: string | null
  streamType: 'AG_UI' | 'CONVERSATION' | 'TEAM'
  eventType: string
  schemaVersion: string
  aggregateType: string | null
  aggregateId: string | null
  aggregateVersion: number | null
  correlationId: string
  causationId: string | null
  occurredAt: string
  payload: TPayload
}

export interface ConversationEventItem {
  cursor: string
  event: RealtimeEventEnvelope
}

export interface AgentInvocationInput {
  message: string
}

export interface ClarificationQuestion {
  fieldKey: string
  question: string
  context: string | null
  required: boolean
  choices: string[]
}

export interface ClarificationRequest {
  schemaVersion: '1'
  summary: string
  questions: ClarificationQuestion[]
}

export interface ClarificationResumeInput {
  answers: Record<string, string>
}

export type TaskIntentStatus = 'DRAFT' | 'READY' | 'CONFIRMED' | 'REJECTED' | 'EXPIRED'

export interface TaskIntentResponsibility {
  role: string
  principalId: string
  principalType: string
  teamMemberId: string | null
}

export interface TaskIntentProposal {
  workProjectId: string
  objective: string
  acceptanceCriteria: string[]
  owner: TaskIntentResponsibility
  executor: TaskIntentResponsibility | null
  gateReviewer: TaskIntentResponsibility | null
}

export interface TaskIntentDecision {
  status: string
  decidedByPrincipalId: string
  decidedAt: string
  reason: string | null
}

export interface TaskIntent {
  id: string
  conversationId: string
  proposedByPrincipalId: string
  schemaVersion: number
  proposalRevision: number
  status: TaskIntentStatus
  version: number
  proposal: TaskIntentProposal
  decision: TaskIntentDecision | null
  createdAt: string
  updatedAt: string
}

export interface VersionedTaskIntent {
  value: TaskIntent
  etag: string
}

export interface TaskIntentRevisionInput {
  schemaVersion: '1'
  objective: string
  acceptanceCriteria: string[]
  workProjectId: string
  ownerMemberId: string
  executorPrincipalId: string | null
  gateReviewerMemberId: string | null
}

export interface TaskIntentConfirmationPreview {
  confirmable: boolean
  taskIntentId: string
  proposalRevision: number
  version: number
  confirmingPrincipalId: string
  proposal: TaskIntentProposal
}

export interface VersionedTaskIntentConfirmationPreview {
  value: TaskIntentConfirmationPreview
  etag: string
}

export interface AgentCancelResponse {
  invocationId: string
  result: 'ACCEPTED' | 'ALREADY_TERMINAL' | 'NOT_FOUND'
  correlationId: string
}
