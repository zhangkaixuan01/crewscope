import { CrewScopeApiError } from '../../api/client'
import {
  fixtureConversationWorkItemAssociation,
  FixtureConversationWorkItemLinkGateway,
} from '../../test/conversationWorkItemFixtures'
import { conversationIds } from '../../test/conversationFixtures'
import { fixtureIds } from '../../test/scopeFixtures'
import { workItemIds } from '../../test/workItemFixtures'
import type { ConversationWorkItemLinkGateway } from './workItemLinkGateway'
import { createConversationWorkItemLinkStore } from './workItemLinkStore'

const conversationScope = {
  organizationId: fixtureIds.organization,
  teamId: fixtureIds.teamPlatform,
  conversationId: conversationIds.provider,
}
const workItemScope = {
  organizationId: fixtureIds.organization,
  teamId: fixtureIds.teamPlatform,
  projectId: fixtureIds.projectCrewScope,
}

describe('ConversationWorkItemLinkStore', () => {
  it('loads the same server association in both directions', async () => {
    const store = createConversationWorkItemLinkStore(new FixtureConversationWorkItemLinkGateway())

    await store.loadByConversation(conversationScope)
    expect(store.state.associations[0]?.workItem.key).toBe('CRW-18')

    await store.loadByWorkItem(workItemScope, workItemIds.first)
    expect(store.state.associations[0]?.conversation.id).toBe(conversationIds.provider)
    expect(store.state.phase).toBe('ready')
  })

  it('does not disclose a link when the policy-filtered result is empty', async () => {
    const gateway = new FixtureConversationWorkItemLinkGateway()
    gateway.associations = []
    const store = createConversationWorkItemLinkStore(gateway)

    await store.loadByConversation(conversationScope)

    expect(store.state.phase).toBe('empty')
    expect(store.state.associations).toEqual([])
  })

  it('keeps 403 status for the route authorization boundary', async () => {
    const gateway: ConversationWorkItemLinkGateway = {
      async listByConversation() { throw forbidden() },
      async listByWorkItem() { throw forbidden() },
    }
    const store = createConversationWorkItemLinkStore(gateway)

    await store.loadByConversation(conversationScope)

    expect(store.state.phase).toBe('error')
    expect(store.state.errorStatus).toBe(403)
    expect(store.state.errorMessage).toBe('关联不可见')
  })

  it('aborts an old direction before it can replace the current resource', async () => {
    let resolveConversation!: (value: typeof fixtureConversationWorkItemAssociation[]) => void
    let oldSignal: AbortSignal | undefined
    const fixture = new FixtureConversationWorkItemLinkGateway()
    const gateway: ConversationWorkItemLinkGateway = {
      listByConversation(_scope, signal) {
        oldSignal = signal
        return new Promise(resolve => { resolveConversation = resolve })
      },
      listByWorkItem: fixture.listByWorkItem.bind(fixture),
    }
    const store = createConversationWorkItemLinkStore(gateway)

    const oldRequest = store.loadByConversation(conversationScope)
    await Promise.resolve()
    await store.loadByWorkItem(workItemScope, workItemIds.first)
    resolveConversation([fixtureConversationWorkItemAssociation])
    await oldRequest

    expect(oldSignal?.aborted).toBe(true)
    expect(store.state.associations[0]?.workItem.id).toBe(workItemIds.first)
  })
})

function forbidden(): CrewScopeApiError {
  return new CrewScopeApiError(403, {
    code: 'forbidden',
    message: '关联不可见',
    correlationId: 'correlation',
    retryable: false,
    currentVersion: null,
    details: {},
  })
}
