import { CrewScopeApiClient } from '../../api/client'
import { fixtureIds } from '../../test/scopeFixtures'
import { conversationIds, fixtureConversations } from '../../test/conversationFixtures'
import { HttpConversationGateway } from './gateway'

describe('HttpConversationGateway', () => {
  const scope = { organizationId: fixtureIds.organization, teamId: fixtureIds.teamPlatform }

  it('lists active Conversations with an opaque Cursor and bounded page size', async () => {
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(jsonResponse({ items: fixtureConversations[fixtureIds.teamPlatform], nextCursor: null }))
    const gateway = new HttpConversationGateway(new CrewScopeApiClient('/api/v1', fetcher))

    await gateway.listConversations({ ...scope, status: 'ACTIVE', after: 'opaque+/cursor', limit: 25 })

    const url = new URL(String(fetcher.mock.calls[0]?.[0]), 'http://crewscope.test')
    expect(url.pathname).toBe(`/api/v1/organizations/${fixtureIds.organization}/teams/${fixtureIds.teamPlatform}/conversations`)
    expect(Object.fromEntries(url.searchParams)).toEqual({ limit: '25', status: 'ACTIVE', after: 'opaque+/cursor' })
  })

  it('loads one nested Conversation using its stable server identity', async () => {
    const details = { conversation: fixtureConversations[fixtureIds.teamPlatform]![0], participants: [] }
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(jsonResponse(details))
    const gateway = new HttpConversationGateway(new CrewScopeApiClient('/api/v1', fetcher))

    await gateway.getConversation(scope, conversationIds.provider)

    expect(fetcher.mock.calls[0]?.[0]).toBe(`/api/v1/organizations/${fixtureIds.organization}/teams/${fixtureIds.teamPlatform}/conversations/${conversationIds.provider}`)
  })

  it('creates a Conversation with an Idempotency-Key and no client-authored owner facts', async () => {
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(jsonResponse({ commandId: 'command', domainEventId: 'event', committedVersion: 0, correlationId: 'correlation' }, 202))
    const gateway = new HttpConversationGateway(new CrewScopeApiClient('/api/v1', fetcher))

    await gateway.createConversation(scope, { title: '建立团队对话', visibility: 'TEAM' }, 'conversation-command')

    const request = fetcher.mock.calls[0]?.[1]
    expect(request?.body).toBe(JSON.stringify({ title: '建立团队对话', visibility: 'TEAM' }))
    expect(request?.body).not.toContain('owner')
    expect(new Headers(request?.headers).get('Idempotency-Key')).toBe('conversation-command')
  })
})

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}
