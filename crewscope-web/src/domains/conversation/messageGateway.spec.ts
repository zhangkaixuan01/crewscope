import { CrewScopeApiClient } from '../../api/client'
import { conversationIds } from '../../test/conversationFixtures'
import { fixtureIds } from '../../test/scopeFixtures'
import { HttpConversationMessageGateway } from './messageGateway'

describe('HttpConversationMessageGateway', () => {
  const scope = {
    organizationId: fixtureIds.organization,
    teamId: fixtureIds.teamPlatform,
    conversationId: conversationIds.provider,
  }

  it('loads descending history through the opaque message Cursor', async () => {
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(jsonResponse({ items: [], nextCursor: null }))
    const gateway = new HttpConversationMessageGateway(new CrewScopeApiClient('/api/v1', fetcher))

    await gateway.listMessages({ ...scope, after: 'opaque+/message', limit: 25 })

    const url = new URL(String(fetcher.mock.calls[0]?.[0]), 'http://crewscope.test')
    expect(url.pathname).toBe(`/api/v1/organizations/${fixtureIds.organization}/teams/${fixtureIds.teamPlatform}/conversations/${conversationIds.provider}/messages`)
    expect(Object.fromEntries(url.searchParams)).toEqual({ limit: '25', after: 'opaque+/message' })
  })

  it('posts only Markdown content with the caller-owned Idempotency-Key', async () => {
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(jsonResponse({ commandId: 'command', domainEventId: 'event', committedVersion: 5, correlationId: 'correlation' }, 202))
    const gateway = new HttpConversationMessageGateway(new CrewScopeApiClient('/api/v1', fetcher))

    await gateway.postMessage(scope, { content: '**Review** this.' }, 'message-command')

    const request = fetcher.mock.calls[0]?.[1]
    expect(request?.body).toBe(JSON.stringify({ content: '**Review** this.' }))
    expect(request?.body).not.toContain('author')
    expect(new Headers(request?.headers).get('Idempotency-Key')).toBe('message-command')
  })
})

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}
