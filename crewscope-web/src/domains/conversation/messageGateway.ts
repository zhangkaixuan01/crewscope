import { apiClient, type CrewScopeApiClient } from '../../api/client'
import type {
  ConversationCommandReceipt,
  ConversationMessageListQuery,
  ConversationMessagePage,
  ConversationMessageScope,
  PostConversationMessageInput,
} from './types'

export interface ConversationMessageGateway {
  listMessages(query: ConversationMessageListQuery, signal?: AbortSignal): Promise<ConversationMessagePage>
  postMessage(
    scope: ConversationMessageScope,
    input: PostConversationMessageInput,
    idempotencyKey: string,
    signal?: AbortSignal,
  ): Promise<ConversationCommandReceipt>
}

/** HTTP adapter for committed Conversation history and idempotent USER message commands. */
export class HttpConversationMessageGateway implements ConversationMessageGateway {
  constructor(private readonly client: CrewScopeApiClient = apiClient) {}

  listMessages(query: ConversationMessageListQuery, signal?: AbortSignal): Promise<ConversationMessagePage> {
    const search = new URLSearchParams({ limit: String(query.limit ?? 50) })
    if (query.after) search.set('after', query.after)
    return this.client.get(`${root(query)}?${search.toString()}`, { signal })
  }

  postMessage(
    scope: ConversationMessageScope,
    input: PostConversationMessageInput,
    idempotencyKey: string,
    signal?: AbortSignal,
  ): Promise<ConversationCommandReceipt> {
    return this.client.post(root(scope), input, { idempotencyKey, signal })
  }
}

function root(scope: ConversationMessageScope): string {
  return `/organizations/${segment(scope.organizationId)}/teams/${segment(scope.teamId)}/conversations/${segment(scope.conversationId)}/messages`
}

function segment(value: string): string {
  return encodeURIComponent(value)
}
