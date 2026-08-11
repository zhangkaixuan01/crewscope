import type { ConversationMessageGateway } from '../domains/conversation/messageGateway'
import type {
  ConversationCommandReceipt,
  ConversationMessage,
  ConversationMessageListQuery,
  ConversationMessagePage,
  ConversationMessageScope,
  PostConversationMessageInput,
} from '../domains/conversation/types'
import { conversationIds } from './conversationFixtures'
import { fixtureIds } from './scopeFixtures'

export const fixtureMessages: Record<string, ConversationMessage[]> = {
  [conversationIds.provider]: [
    message('00000000-0000-0000-0000-000000001301', conversationIds.provider, 1, 'USER_MESSAGE', fixtureIds.principal, '**目标**：规划 GitHub Provider 接入。', '2026-08-08T01:10:00Z'),
    message('00000000-0000-0000-0000-000000001302', conversationIds.provider, 2, 'AGENT_MESSAGE', '00000000-0000-0000-0000-000000001201', '已收到。我会先梳理 `Connection`、权限和审计边界。', '2026-08-08T01:11:00Z'),
    message('00000000-0000-0000-0000-000000001303', conversationIds.provider, 3, 'SYSTEM_NOTICE', null, 'Conversation 已切换为真实消息模式。', '2026-08-08T01:12:00Z'),
    message('00000000-0000-0000-0000-000000001304', conversationIds.provider, 4, 'USER_MESSAGE', fixtureIds.principal, '请保留团队协作与最小权限原则。', '2026-08-08T01:13:00Z'),
  ],
  [conversationIds.release]: [],
  [conversationIds.security]: [
    message('00000000-0000-0000-0000-000000001305', conversationIds.security, 1, 'USER_MESSAGE', fixtureIds.principal, '检查 Runtime 身份边界。', '2026-08-08T02:00:00Z'),
  ],
}

export class FixtureConversationMessageGateway implements ConversationMessageGateway {
  readonly posted: Array<{ input: PostConversationMessageInput; idempotencyKey: string }> = []
  messages: Record<string, ConversationMessage[]>

  constructor(messages = fixtureMessages) {
    this.messages = structuredClone(messages)
  }

  async listMessages(query: ConversationMessageListQuery, signal?: AbortSignal): Promise<ConversationMessagePage> {
    throwIfAborted(signal)
    const committed = [...(this.messages[query.conversationId] ?? [])].sort((left, right) => right.sequence - left.sequence)
    const offset = query.after ? Number(query.after.replace('message-offset-', '')) : 0
    const limit = query.limit ?? 50
    const items = committed.slice(offset, offset + limit)
    const nextOffset = offset + items.length
    return {
      items: structuredClone(items),
      nextCursor: nextOffset < committed.length ? `message-offset-${nextOffset}` : null,
    }
  }

  async postMessage(
    scope: ConversationMessageScope,
    input: PostConversationMessageInput,
    idempotencyKey: string,
    signal?: AbortSignal,
  ): Promise<ConversationCommandReceipt> {
    throwIfAborted(signal)
    this.posted.push({ input: structuredClone(input), idempotencyKey })
    const existing = this.messages[scope.conversationId] ?? []
    const sequence = existing.reduce((latest, item) => Math.max(latest, item.sequence), 0) + 1
    existing.push(message(crypto.randomUUID(), scope.conversationId, sequence, 'USER_MESSAGE', fixtureIds.principal, input.content, '2026-08-08T04:00:00Z'))
    this.messages[scope.conversationId] = existing
    return receipt(sequence)
  }
}

export function message(
  id: string,
  conversationId: string,
  sequence: number,
  type: ConversationMessage['type'],
  authorPrincipalId: string | null,
  content: string,
  createdAt: string,
): ConversationMessage {
  return {
    id,
    conversationId,
    sequence,
    type,
    participantId: type === 'SYSTEM_NOTICE' ? null : crypto.randomUUID(),
    authorPrincipalId,
    content,
    createdAt,
  }
}

function receipt(committedVersion: number): ConversationCommandReceipt {
  return {
    commandId: crypto.randomUUID(),
    domainEventId: crypto.randomUUID(),
    committedVersion,
    correlationId: crypto.randomUUID(),
  }
}

function throwIfAborted(signal?: AbortSignal): void {
  if (signal?.aborted) throw new DOMException('Aborted', 'AbortError')
}
