import { CrewScopeApiClient } from '../../api/client'
import { fixtureIds } from '../../test/scopeFixtures'
import { reviewDetails, reviewIds, reviewSummary } from '../../test/reviewFixtures'
import { taskIds } from '../../test/taskFixtures'
import { HttpReviewGateway } from './gateway'

describe('HttpReviewGateway', () => {
  const scope = { organizationId: fixtureIds.organization, teamId: fixtureIds.teamPlatform }
  const coordinates = { taskId: taskIds.first, executionId: taskIds.execution }

  it('whitelists Review history and detail while retaining the strong ETag', async () => {
    const rawDetail = {
      ...reviewDetails(), patch: 'secret patch', prompt: 'private prompt', credential: 'private key',
      findings: [{ ...reviewDetails().findings[0], rawModelOutput: 'private reasoning' }],
    }
    const fetcher = vi.fn<typeof fetch>()
      .mockResolvedValueOnce(jsonResponse({ items: [{ ...reviewSummary(), prompt: 'private' }] }))
      .mockResolvedValueOnce(jsonResponse(rawDetail, 200, { ETag: '"4"' }))
    const gateway = new HttpReviewGateway(new CrewScopeApiClient('/api/v1', fetcher))

    const list = await gateway.list(scope, coordinates)
    const detail = await gateway.get(scope, coordinates, reviewIds.request)

    expect(fetcher.mock.calls[0]?.[0]).toContain(`/tasks/${taskIds.first}/attempts/${taskIds.execution}/reviews`)
    expect(detail.etag).toBe('"4"')
    expect(detail.value.findings[0]?.evidence[0]?.startLine).toBe(42)
    expect(JSON.stringify({ list, detail })).not.toMatch(/secret patch|private prompt|private key|rawModelOutput|reasoning/i)
  })

  it('sends Reviewer and member Gate commands with exact strong-version bodies', async () => {
    const receipt = { commandId: crypto.randomUUID(), domainEventId: crypto.randomUUID(), committedVersion: 5, correlationId: crypto.randomUUID() }
    const fetcher = vi.fn<typeof fetch>()
      .mockResolvedValueOnce(jsonResponse({
        receipt, reviewRequestId: reviewIds.request, reviewRequestVersion: 5,
        status: 'COMPLETED', effectiveFindingCount: 1, insertedFindingCount: 1,
        duplicateObservationCount: 0, rawModelOutput: 'private',
      }))
      .mockImplementation(async () => jsonResponse(receipt, 202))
    const gateway = new HttpReviewGateway(new CrewScopeApiClient('/api/v1', fetcher))

    await gateway.execute(scope, coordinates, reviewIds.request, 4, 'execute-key')
    await gateway.decide(scope, coordinates, reviewIds.request, 5, {
      type: 'APPROVED', rationale: '证据完整且验收通过',
    }, 'decision-key')
    await gateway.requestChanges(scope, coordinates, reviewIds.request, 6, '补充空值测试', 'modify-key')

    const execute = fetcher.mock.calls[0]!
    expect(execute[0]).toContain(`/${reviewIds.request}/execute`)
    expect(execute[1]?.body).toBeUndefined()
    expect(new Headers(execute[1]?.headers).get('If-Match')).toBe('"4"')
    expect(new Headers(execute[1]?.headers).get('Idempotency-Key')).toBe('execute-key')
    expect(fetcher.mock.calls[1]?.[1]?.body).toBe(JSON.stringify({ type: 'APPROVED', rationale: '证据完整且验收通过' }))
    expect(fetcher.mock.calls[2]?.[0]).toContain('/modifications')
    expect(fetcher.mock.calls[2]?.[1]?.body).toBe(JSON.stringify({ rationale: '补充空值测试' }))
  })
})

function jsonResponse(body: unknown, status = 200, headers: Record<string, string> = {}): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json', ...headers },
  })
}
