import { apiClient, type CrewScopeApiClient } from '../../api/client'
import type {
  ConversationCommandReceipt,
  ConversationDetails,
  ConversationListQuery,
  ConversationPage,
  ConversationScope,
  CreateConversationInput,
} from './types'

export interface ConversationGateway {
  listConversations(query: ConversationListQuery, signal?: AbortSignal): Promise<ConversationPage>
  getConversation(
    scope: ConversationScope,
    conversationId: string,
    signal?: AbortSignal,
  ): Promise<ConversationDetails>
  createConversation(
    scope: ConversationScope,
    input: CreateConversationInput,
    idempotencyKey: string,
  ): Promise<ConversationCommandReceipt>
}

/** HTTP adapter for the M2 Conversation collection and detail contracts. */
export class HttpConversationGateway implements ConversationGateway {
  constructor(private readonly client: CrewScopeApiClient = apiClient) {}

  listConversations(query: ConversationListQuery, signal?: AbortSignal): Promise<ConversationPage> {
    const search = new URLSearchParams({ limit: String(query.limit ?? 50) })
    if (query.status) search.set('status', query.status)
    if (query.after) search.set('after', query.after)
    return this.client.get(`${root(query)}?${search.toString()}`, { signal })
  }

  getConversation(
    scope: ConversationScope,
    conversationId: string,
    signal?: AbortSignal,
  ): Promise<ConversationDetails> {
    return this.client.get(`${root(scope)}/${segment(conversationId)}`, { signal })
  }

  createConversation(
    scope: ConversationScope,
    input: CreateConversationInput,
    idempotencyKey: string,
  ): Promise<ConversationCommandReceipt> {
    return this.client.post(root(scope), input, { idempotencyKey })
  }
}

function root(scope: ConversationScope): string {
  return `/organizations/${segment(scope.organizationId)}/teams/${segment(scope.teamId)}/conversations`
}

function segment(value: string): string {
  return encodeURIComponent(value)
}
