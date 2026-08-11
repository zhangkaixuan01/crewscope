import type { ConversationGateway } from '../domains/conversation/gateway'
import type {
  ConversationCommandReceipt,
  ConversationDetails,
  ConversationListQuery,
  ConversationPage,
  ConversationScope,
  ConversationSummary,
  CreateConversationInput,
} from '../domains/conversation/types'
import { fixtureIds } from './scopeFixtures'

export const conversationIds = {
  provider: '00000000-0000-0000-0000-000000001101',
  release: '00000000-0000-0000-0000-000000001102',
  security: '00000000-0000-0000-0000-000000001103',
} as const

export const fixtureConversations: Record<string, ConversationSummary[]> = {
  [fixtureIds.teamPlatform]: [
    conversation(conversationIds.provider, fixtureIds.teamPlatform, '规划 GitHub Provider 接入', 'PRIVATE', 4),
    conversation(conversationIds.release, fixtureIds.teamPlatform, '协作准备 M2 发布', 'TEAM', null),
  ],
  [fixtureIds.teamSecurity]: [
    conversation(conversationIds.security, fixtureIds.teamSecurity, '检查 Runtime 身份边界', 'TEAM', 2),
  ],
}

export class FixtureConversationGateway implements ConversationGateway {
  readonly created: CreateConversationInput[] = []
  conversations: Record<string, ConversationSummary[]>

  constructor(conversations = fixtureConversations) {
    this.conversations = structuredClone(conversations)
  }

  async listConversations(query: ConversationListQuery, signal?: AbortSignal): Promise<ConversationPage> {
    throwIfAborted(signal)
    return { items: structuredClone(this.conversations[query.teamId] ?? []), nextCursor: null }
  }

  async getConversation(
    scope: ConversationScope,
    conversationId: string,
    signal?: AbortSignal,
  ): Promise<ConversationDetails> {
    throwIfAborted(signal)
    const conversation = this.conversations[scope.teamId]?.find(item => item.id === conversationId)
    if (!conversation) throw new Error('Conversation not found')
    return {
      conversation: structuredClone(conversation),
      participants: [
        {
          id: crypto.randomUUID(),
          conversationId,
          principalId: fixtureIds.principal,
          teamMemberId: fixtureIds.memberOwner,
          role: 'OWNER',
          status: 'ACTIVE',
          joinedByPrincipalId: fixtureIds.principal,
          joinedAt: conversation.createdAt,
          leftAt: null,
          version: 0,
        },
        {
          id: crypto.randomUUID(),
          conversationId,
          principalId: conversation.personalAgentPrincipalId,
          teamMemberId: null,
          role: 'AGENT',
          status: 'ACTIVE',
          joinedByPrincipalId: fixtureIds.principal,
          joinedAt: conversation.createdAt,
          leftAt: null,
          version: 0,
        },
      ],
    }
  }

  async createConversation(
    scope: ConversationScope,
    input: CreateConversationInput,
  ): Promise<ConversationCommandReceipt> {
    this.created.push(structuredClone(input))
    const created = conversation(crypto.randomUUID(), scope.teamId, input.title, input.visibility, null)
    this.conversations[scope.teamId] = [created, ...(this.conversations[scope.teamId] ?? [])]
    return receipt()
  }
}

function conversation(
  id: string,
  teamId: string,
  title: string,
  visibility: 'PRIVATE' | 'TEAM',
  lastMessageSequence: number | null,
): ConversationSummary {
  const workspaceId = teamId === fixtureIds.teamSecurity ? fixtureIds.workspaceSecurity : fixtureIds.workspacePlatform
  return {
    id,
    organizationId: fixtureIds.organization,
    teamId,
    workspaceId,
    ownerMemberId: fixtureIds.memberOwner,
    ownerPrincipalId: fixtureIds.principal,
    personalAgentPrincipalId: '00000000-0000-0000-0000-000000001201',
    title,
    visibility,
    status: 'ACTIVE',
    lastMessageSequence,
    version: 0,
    createdAt: '2026-08-08T01:00:00Z',
    updatedAt: '2026-08-08T03:00:00Z',
  }
}

function receipt(): ConversationCommandReceipt {
  return {
    commandId: crypto.randomUUID(),
    domainEventId: crypto.randomUUID(),
    committedVersion: 0,
    correlationId: crypto.randomUUID(),
  }
}

function throwIfAborted(signal?: AbortSignal): void {
  if (signal?.aborted) throw new DOMException('Aborted', 'AbortError')
}
