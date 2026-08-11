import { CrewScopeApiClient } from '../../api/client'
import { fixtureIds } from '../../test/scopeFixtures'
import { fixtureConfirmationPreview, fixtureTaskIntent, taskIntentIds } from '../../test/taskIntentFixtures'
import { conversationIds } from '../../test/conversationFixtures'
import { HttpTaskIntentGateway } from './taskIntentGateway'

const scope = { organizationId: fixtureIds.organization, teamId: fixtureIds.teamPlatform, conversationId: conversationIds.provider }

describe('HttpTaskIntentGateway', () => {
  it('retains and validates the strong ETag on reads and confirmation previews', async () => {
    const intent = fixtureTaskIntent()
    const fetcher = vi.fn<typeof fetch>()
      .mockResolvedValueOnce(json(intent, { ETag: '"2"' }))
      .mockResolvedValueOnce(json(fixtureConfirmationPreview(intent), { ETag: '"2"' }))
    const gateway = new HttpTaskIntentGateway(new CrewScopeApiClient('/api/v1', fetcher))

    expect(await gateway.get(scope, intent.id)).toEqual({ value: intent, etag: '"2"' })
    expect((await gateway.previewConfirmation(scope, intent.id, 2)).etag).toBe('"2"')
    expect(new Headers(fetcher.mock.calls[1]?.[1]?.headers).get('If-Match')).toBe('"2"')
    await expect(new HttpTaskIntentGateway(new CrewScopeApiClient('/api/v1', vi.fn<typeof fetch>()
      .mockResolvedValue(json(intent, { ETag: 'W/"2"' })))).get(scope, intent.id)).rejects.toThrow('invalid ETag')
  })

  it('sends complete revisions and a strictly empty confirmation body', async () => {
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(json({ commandId: 'command' }, {}, 202))
    const gateway = new HttpTaskIntentGateway(new CrewScopeApiClient('/api/v1', fetcher))
    const intent = fixtureTaskIntent()
    const revision = {
      schemaVersion: '1' as const,
      objective: intent.proposal.objective,
      acceptanceCriteria: intent.proposal.acceptanceCriteria,
      workProjectId: taskIntentIds.project,
      ownerMemberId: taskIntentIds.ownerMember,
      executorPrincipalId: null,
      gateReviewerMemberId: null,
    }

    await gateway.revise(scope, intent.id, revision, 2, 'revise-key')
    await gateway.confirm(scope, intent.id, 2, 'confirm-key')
    await gateway.reject(scope, intent.id, '目标已变化', 2, 'reject-key')

    expect(fetcher.mock.calls[0]?.[1]?.body).toBe(JSON.stringify(revision))
    expect(fetcher.mock.calls[1]?.[1]?.body).toBeUndefined()
    expect(new Headers(fetcher.mock.calls[1]?.[1]?.headers).get('Content-Type')).toBeNull()
    expect(String(fetcher.mock.calls[1]?.[0])).toContain(`/task-intents/${taskIntentIds.release}/confirmations`)
    expect(fetcher.mock.calls[2]?.[1]?.body).toBe(JSON.stringify({ reason: '目标已变化' }))
  })
})

function json(value: unknown, headers: Record<string, string> = {}, status = 200): Response {
  return new Response(JSON.stringify(value), { status, headers: { 'Content-Type': 'application/json', ...headers } })
}
