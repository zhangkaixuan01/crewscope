import type {
  ConversationWorkItemAssociation,
  ConversationWorkItemLinkGateway,
} from '../domains/conversation/workItemLinkGateway'
import type { ConversationMessageScope } from '../domains/conversation/types'
import type { WorkItemScope } from '../domains/workitem/types'
import { conversationIds } from './conversationFixtures'
import { fixtureIds } from './scopeFixtures'
import { workItemIds } from './workItemFixtures'

export const fixtureConversationWorkItemAssociation: ConversationWorkItemAssociation = {
  linkId: '00000000-0000-0000-0000-000000001401',
  origin: 'TASK_INTENT_CONFIRMATION',
  createdAt: '2026-08-11T05:00:00Z',
  conversation: {
    id: conversationIds.provider,
    title: '规划 GitHub Provider 接入',
    visibility: 'PRIVATE',
    status: 'ACTIVE',
  },
  workItem: {
    id: workItemIds.first,
    projectId: fixtureIds.projectCrewScope,
    key: 'CRW-18',
    title: '建立团队看板',
    status: 'IN_PROGRESS',
  },
}

export class FixtureConversationWorkItemLinkGateway implements ConversationWorkItemLinkGateway {
  conversationQueries: ConversationMessageScope[] = []
  workItemQueries: Array<{ scope: WorkItemScope; workItemId: string }> = []
  associations: ConversationWorkItemAssociation[] = [fixtureConversationWorkItemAssociation]

  async listByConversation(scope: ConversationMessageScope): Promise<ConversationWorkItemAssociation[]> {
    this.conversationQueries.push(structuredClone(scope))
    return structuredClone(this.associations.filter(item => item.conversation.id === scope.conversationId))
  }

  async listByWorkItem(
    scope: WorkItemScope,
    workItemId: string,
  ): Promise<ConversationWorkItemAssociation[]> {
    this.workItemQueries.push({ scope: structuredClone(scope), workItemId })
    return structuredClone(this.associations.filter(item => item.workItem.id === workItemId))
  }
}
