import { apiClient, type CrewScopeApiClient } from '../../api/client'
import type { WorkItemScope } from '../workitem/types'
import type { ConversationMessageScope } from './types'

export type ConversationWorkItemLinkOrigin =
  | 'TASK_INTENT_CONFIRMATION'
  | 'MANUAL'
  | 'WORK_ITEM_DISCUSSION'

export interface LinkedConversationSummary {
  id: string
  title: string
  visibility: 'PRIVATE' | 'TEAM'
  status: 'ACTIVE' | 'ARCHIVED'
}

export interface LinkedWorkItemSummary {
  id: string
  projectId: string
  key: string
  title: string
  status: string
}

/** Policy-filtered relation returned by both M2-A07 query directions. */
export interface ConversationWorkItemAssociation {
  linkId: string
  origin: ConversationWorkItemLinkOrigin
  createdAt: string
  conversation: LinkedConversationSummary
  workItem: LinkedWorkItemSummary
}

export interface ConversationWorkItemLinkGateway {
  listByConversation(
    scope: ConversationMessageScope,
    signal?: AbortSignal,
  ): Promise<ConversationWorkItemAssociation[]>
  listByWorkItem(
    scope: WorkItemScope,
    workItemId: string,
    signal?: AbortSignal,
  ): Promise<ConversationWorkItemAssociation[]>
}

/** Read-only adapter; link visibility remains owned by the server-side policies. */
export class HttpConversationWorkItemLinkGateway implements ConversationWorkItemLinkGateway {
  constructor(private readonly client: CrewScopeApiClient = apiClient) {}

  listByConversation(
    scope: ConversationMessageScope,
    signal?: AbortSignal,
  ): Promise<ConversationWorkItemAssociation[]> {
    return this.client.get(
      `/organizations/${segment(scope.organizationId)}/teams/${segment(scope.teamId)}`
        + `/conversations/${segment(scope.conversationId)}/work-items`,
      { signal },
    )
  }

  listByWorkItem(
    scope: WorkItemScope,
    workItemId: string,
    signal?: AbortSignal,
  ): Promise<ConversationWorkItemAssociation[]> {
    return this.client.get(
      `/organizations/${segment(scope.organizationId)}/teams/${segment(scope.teamId)}`
        + `/work-projects/${segment(scope.projectId)}/work-items/${segment(workItemId)}/conversations`,
      { signal },
    )
  }
}

function segment(value: string): string {
  return encodeURIComponent(value)
}
