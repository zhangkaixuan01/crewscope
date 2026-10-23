import { apiClient, type CrewScopeApiClient } from '../../api/client'
import { createAndLocate } from '../../api/creationRecovery'
import type {
  ConversationCommandReceipt,
  ConversationDetails,
  ConversationListQuery,
  ConversationPage,
  ConversationScope,
  CreateConversationInput,
} from './types'
import { segment } from '../../api/gatewayUtils'

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
  /**
   * M13b-F06（R12/A07）：打开对话即读到消息尖端——body-less 命令，服务端按 `max` 幂等推进
   * last_read_sequence，只增不减。
   */
  submitReadReceipt(
    scope: ConversationScope,
    conversationId: string,
    idempotencyKey: string,
    signal?: AbortSignal,
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
    return createAndLocate(this.client, scope, 'CONVERSATION', idempotencyKey,
      () => this.client.post(root(scope), input, { idempotencyKey }))
  }

  submitReadReceipt(
    scope: ConversationScope,
    conversationId: string,
    idempotencyKey: string,
    signal?: AbortSignal,
  ): Promise<ConversationCommandReceipt> {
    return this.client.post(`${root(scope)}/${segment(conversationId)}/read-receipts`, undefined, { idempotencyKey, signal })
  }
}

function root(scope: ConversationScope): string {
  return `/organizations/${segment(scope.organizationId)}/teams/${segment(scope.teamId)}/conversations`
}
