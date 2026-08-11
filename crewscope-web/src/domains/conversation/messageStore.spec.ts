import { CrewScopeApiError } from '../../api/client'
import { conversationIds } from '../../test/conversationFixtures'
import { FixtureConversationMessageGateway, fixtureMessages } from '../../test/conversationMessageFixtures'
import { fixtureIds } from '../../test/scopeFixtures'
import type { ConversationMessageGateway } from './messageGateway'
import { createConversationMessageStore } from './messageStore'

const provider = { organizationId: fixtureIds.organization, teamId: fixtureIds.teamPlatform, conversationId: conversationIds.provider }
const release = { organizationId: fixtureIds.organization, teamId: fixtureIds.teamPlatform, conversationId: conversationIds.release }

describe('conversation message store', () => {
  it('normalizes descending server history into ascending display order', async () => {
    const store = createConversationMessageStore(new FixtureConversationMessageGateway())

    await store.synchronize(provider)

    expect(store.state.items.map(item => item.sequence)).toEqual([1, 2, 3, 4])
    expect(store.state.phase).toBe('ready')
  })

  it('continues an opaque Cursor and removes duplicate committed messages', async () => {
    const fixture = new FixtureConversationMessageGateway()
    const calls: Array<string | undefined> = []
    const gateway: ConversationMessageGateway = {
      async listMessages(query) {
        calls.push(query.after)
        const all = fixtureMessages[conversationIds.provider] ?? []
        return query.after
          ? { items: structuredClone([all[1]!, all[0]!]), nextCursor: null }
          : { items: structuredClone([all[3]!, all[2]!, all[1]!]), nextCursor: 'older+/cursor' }
      },
      postMessage: fixture.postMessage.bind(fixture),
    }
    const store = createConversationMessageStore(gateway)

    await store.synchronize(provider)
    await store.loadOlder()

    expect(calls).toEqual([undefined, 'older+/cursor'])
    expect(store.state.items.map(item => item.sequence)).toEqual([1, 2, 3, 4])
  })

  it('merges an optimistic USER message into the refreshed committed fact', async () => {
    const gateway = new FixtureConversationMessageGateway()
    const store = createConversationMessageStore(gateway)
    await store.synchronize(provider)

    const sent = await store.send(provider, '  请补充审计边界。  ', fixtureIds.principal)

    expect(sent).toBe(true)
    expect(gateway.posted[0]?.input).toEqual({ content: '请补充审计边界。' })
    expect(gateway.posted[0]?.idempotencyKey).toBeTruthy()
    expect(store.state.pending).toEqual([])
    expect(store.state.items.at(-1)?.content).toBe('请补充审计边界。')
  })

  it('keeps a failed optimistic message and retries with the same Idempotency-Key', async () => {
    const fixture = new FixtureConversationMessageGateway()
    let attempts = 0
    const keys: string[] = []
    const gateway: ConversationMessageGateway = {
      listMessages: fixture.listMessages.bind(fixture),
      async postMessage(scope, input, key, signal) {
        attempts += 1
        keys.push(key)
        if (attempts === 1) throw new CrewScopeApiError(0, envelope('network_unavailable', '网络连接不可用'))
        return fixture.postMessage(scope, input, key, signal)
      },
    }
    const store = createConversationMessageStore(gateway)
    await store.synchronize(provider)

    expect(await store.send(provider, '保留这条消息', fixtureIds.principal)).toBe(false)
    const pending = store.state.pending[0]!
    expect(pending.status).toBe('failed')

    expect(await store.retry(provider, pending.clientId)).toBe(true)
    expect(keys).toEqual([pending.idempotencyKey, pending.idempotencyKey])
    expect(store.state.pending).toEqual([])
  })

  it('clears pending and committed content when the selected Conversation changes', async () => {
    let rejectPost!: (error: unknown) => void
    const fixture = new FixtureConversationMessageGateway()
    const gateway: ConversationMessageGateway = {
      listMessages: fixture.listMessages.bind(fixture),
      postMessage: () => new Promise((_resolve, reject) => { rejectPost = reject }),
    }
    const store = createConversationMessageStore(gateway)
    await store.synchronize(provider)

    const staleSend = store.send(provider, '旧 Conversation 消息', fixtureIds.principal)
    await Promise.resolve()
    await store.synchronize(release)
    rejectPost(new DOMException('Aborted', 'AbortError'))
    await staleSend

    expect(store.state.phase).toBe('empty')
    expect(store.state.items).toEqual([])
    expect(store.state.pending).toEqual([])
  })

  it('renders a Team without committed messages as an explicit empty history', async () => {
    const store = createConversationMessageStore(new FixtureConversationMessageGateway())

    await store.synchronize(release)

    expect(store.state.phase).toBe('empty')
    expect(store.state.nextCursor).toBeNull()
  })
})

function envelope(code: string, messageText: string) {
  return { code, message: messageText, correlationId: 'test', retryable: true, currentVersion: null, details: {} }
}
