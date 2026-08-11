import { CrewScopeApiClient } from '../../api/client'
import { fixtureConversationWorkItemAssociation } from '../../test/conversationWorkItemFixtures'
import { conversationIds } from '../../test/conversationFixtures'
import { fixtureIds } from '../../test/scopeFixtures'
import { workItemIds } from '../../test/workItemFixtures'
import { HttpConversationWorkItemLinkGateway } from './workItemLinkGateway'

describe('HttpConversationWorkItemLinkGateway', () => {
  it('reads both policy-filtered directions from their exact nested scopes', async () => {
    const fetcher = vi.fn<typeof fetch>().mockImplementation(async () => jsonResponse([fixtureConversationWorkItemAssociation]))
    const gateway = new HttpConversationWorkItemLinkGateway(new CrewScopeApiClient('/api/v1', fetcher))

    await gateway.listByConversation({
      organizationId: fixtureIds.organization,
      teamId: fixtureIds.teamPlatform,
      conversationId: conversationIds.provider,
    })
    await gateway.listByWorkItem({
      organizationId: fixtureIds.organization,
      teamId: fixtureIds.teamPlatform,
      projectId: fixtureIds.projectCrewScope,
    }, workItemIds.first)

    expect(fetcher.mock.calls.map(call => call[0])).toEqual([
      `/api/v1/organizations/${fixtureIds.organization}/teams/${fixtureIds.teamPlatform}/conversations/${conversationIds.provider}/work-items`,
      `/api/v1/organizations/${fixtureIds.organization}/teams/${fixtureIds.teamPlatform}/work-projects/${fixtureIds.projectCrewScope}/work-items/${workItemIds.first}/conversations`,
    ])
    expect(fetcher.mock.calls.every(call => new Headers(call[1]?.headers).get('Accept') === 'application/json')).toBe(true)
  })
})

function jsonResponse(body: unknown): Response {
  return new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } })
}
