// @vitest-environment node
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
    const receipt = { commandId: 'command', domainEventId: 'event', committedVersion: 0, correlationId: 'correlation' }
    const fetcher = vi.fn<typeof fetch>()
      .mockResolvedValueOnce(jsonResponse(receipt, 202))
      .mockResolvedValueOnce(jsonResponse({ receipt, result: { ...scope, type: 'CONVERSATION', stage: 'COMMITTED',
        projectId: null, resourceId: conversationIds.provider, committedVersion: 0 } }))
      .mockResolvedValueOnce(jsonResponse({ conversation: fixtureConversations[fixtureIds.teamPlatform]![0], participants: [] }))
    const gateway = new HttpConversationGateway(new CrewScopeApiClient('/api/v1', fetcher))

    await gateway.createConversation(scope, { title: '建立团队对话', visibility: 'TEAM' }, 'conversation-command')

    const request = fetcher.mock.calls[0]?.[1]
    expect(request?.body).toBe(JSON.stringify({ title: '建立团队对话', visibility: 'TEAM' }))
    expect(request?.body).not.toContain('owner')
    expect(new Headers(request?.headers).get('Idempotency-Key')).toBe('conversation-command')
  })

  // M13b-F06（R12/A07）：回执是 body-less 命令——URL 段、幂等键头、无 Content-Type。
  it('submits a body-less read receipt with an Idempotency-Key', async () => {
    const receipt = { commandId: 'command', domainEventId: 'event', committedVersion: 0, correlationId: 'correlation' }
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(jsonResponse(receipt, 202))
    const gateway = new HttpConversationGateway(new CrewScopeApiClient('/api/v1', fetcher))

    await gateway.submitReadReceipt(scope, conversationIds.provider, 'read-receipt-key')

    const request = fetcher.mock.calls[0]?.[1]
    expect(fetcher.mock.calls[0]?.[0]).toBe(`/api/v1/organizations/${fixtureIds.organization}/teams/${fixtureIds.teamPlatform}/conversations/${conversationIds.provider}/read-receipts`)
    expect(request?.method).toBe('POST')
    expect(request?.body).toBeUndefined()
    expect(new Headers(request?.headers).get('Idempotency-Key')).toBe('read-receipt-key')
    expect(new Headers(request?.headers).get('Content-Type')).toBeNull()
  })
})

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}