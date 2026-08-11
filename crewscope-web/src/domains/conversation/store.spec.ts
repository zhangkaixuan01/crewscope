import type { ConversationGateway } from './gateway'
import { createConversationStore } from './store'
import { conversationIds, FixtureConversationGateway } from '../../test/conversationFixtures'
import { fixtureIds } from '../../test/scopeFixtures'

const platform = { organizationId: fixtureIds.organization, teamId: fixtureIds.teamPlatform }
const security = { organizationId: fixtureIds.organization, teamId: fixtureIds.teamSecurity }

describe('conversation store', () => {
  it('restores a deep-linked Conversation and its current participants', async () => {
    const store = createConversationStore(new FixtureConversationGateway())

    await store.synchronize(platform, conversationIds.provider)

    expect(store.state.phase).toBe('ready')
    expect(store.state.selectedConversationId).toBe(conversationIds.provider)
    expect(store.state.details?.conversation.title).toBe('规划 GitHub Provider 接入')
    expect(store.state.details?.participants.map(item => item.role)).toEqual(['OWNER', 'AGENT'])
  })

  it('represents a Team without visible Conversations as an explicit empty state', async () => {
    const store = createConversationStore(new FixtureConversationGateway({}))

    await store.synchronize(platform)

    expect(store.state.phase).toBe('empty')
    expect(store.state.items).toEqual([])
  })

  it('refreshes the collection and selects the newly created server fact', async () => {
    const gateway = new FixtureConversationGateway()
    const store = createConversationStore(gateway)
    await store.synchronize(platform)

    const conversationId = await store.create(platform, { title: '准备新的协作目标', visibility: 'TEAM' })

    expect(gateway.created).toEqual([{ title: '准备新的协作目标', visibility: 'TEAM' }])
    expect(conversationId).toBeTruthy()
    expect(store.state.selectedConversationId).toBe(conversationId)
    expect(store.state.details?.conversation.title).toBe('准备新的协作目标')
  })

  it('continues from the opaque Cursor and removes duplicate Conversation facts', async () => {
    const fixture = new FixtureConversationGateway()
    const conversations = fixture.conversations[fixtureIds.teamPlatform] ?? []
    const cursors: Array<string | undefined> = []
    const gateway: ConversationGateway = {
      async listConversations(query) {
        cursors.push(query.after)
        return query.after
          ? { items: structuredClone(conversations), nextCursor: null }
          : { items: structuredClone(conversations.slice(0, 1)), nextCursor: 'opaque+/page-2' }
      },
      getConversation: fixture.getConversation.bind(fixture),
      createConversation: fixture.createConversation.bind(fixture),
    }
    const store = createConversationStore(gateway)

    await store.synchronize(platform)
    await store.loadMore()

    expect(cursors).toEqual([undefined, 'opaque+/page-2'])
    expect(store.state.items.map(item => item.id)).toEqual([conversationIds.provider, conversationIds.release])
    expect(store.state.nextCursor).toBeNull()
  })

  it('clears an old Conversation identity when the Team Scope changes', async () => {
    const store = createConversationStore(new FixtureConversationGateway())
    await store.synchronize(platform, conversationIds.provider)

    await store.synchronize(security)

    expect(store.state.items.map(item => item.id)).toEqual([conversationIds.security])
    expect(store.state.selectedConversationId).toBeNull()
    expect(store.state.details).toBeNull()
  })

  it('does not restore a stale deep link after the URL clears it during Scope loading', async () => {
    let resolveSecurity!: (value: Awaited<ReturnType<ConversationGateway['listConversations']>>) => void
    const fixture = new FixtureConversationGateway()
    const requestedDetails: string[] = []
    const gateway: ConversationGateway = {
      listConversations(query, signal) {
        if (query.teamId !== fixtureIds.teamSecurity) return fixture.listConversations(query, signal)
        return new Promise(resolve => { resolveSecurity = resolve })
      },
      async getConversation(scope, conversationId, signal) {
        requestedDetails.push(conversationId)
        return fixture.getConversation(scope, conversationId, signal)
      },
      createConversation: fixture.createConversation.bind(fixture),
    }
    const store = createConversationStore(gateway)

    const staleDeepLink = store.synchronize(security, conversationIds.provider)
    await Promise.resolve()
    const canonicalUrl = store.synchronize(security)
    resolveSecurity({ items: fixtureConversationsForSecurity(fixture), nextCursor: null })
    await Promise.all([staleDeepLink, canonicalUrl])

    expect(requestedDetails).toEqual([])
    expect(store.state.selectedConversationId).toBeNull()
    expect(store.state.items.map(item => item.id)).toEqual([conversationIds.security])
  })

  it('aborts a stale collection request before it can overwrite the next Team', async () => {
    let resolvePlatform!: (value: Awaited<ReturnType<ConversationGateway['listConversations']>>) => void
    let platformSignal: AbortSignal | undefined
    const fixture = new FixtureConversationGateway()
    const gateway: ConversationGateway = {
      ...fixture,
      listConversations(query, signal) {
        if (query.teamId !== fixtureIds.teamPlatform) return fixture.listConversations(query, signal)
        platformSignal = signal
        return new Promise(resolve => { resolvePlatform = resolve })
      },
      getConversation: fixture.getConversation.bind(fixture),
      createConversation: fixture.createConversation.bind(fixture),
    }
    const store = createConversationStore(gateway)

    const stale = store.synchronize(platform)
    await Promise.resolve()
    const current = store.synchronize(security)
    resolvePlatform({ items: [], nextCursor: null })
    await Promise.all([stale, current])

    expect(platformSignal?.aborted).toBe(true)
    expect(store.state.items.map(item => item.id)).toEqual([conversationIds.security])
  })
})

function fixtureConversationsForSecurity(fixture: FixtureConversationGateway) {
  return structuredClone(fixture.conversations[fixtureIds.teamSecurity] ?? [])
}
