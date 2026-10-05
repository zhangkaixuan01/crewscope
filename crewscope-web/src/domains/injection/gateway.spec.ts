import { CrewScopeApiClient } from '../../api/client'
import { fixtureIds } from '../../test/scopeFixtures'
import { HttpInjectionGateway } from './gateway'

const scope = { organizationId: fixtureIds.organization, teamId: fixtureIds.teamPlatform }
const taskId = '00000000-0000-0000-0000-000000006301'
const executionId = '00000000-0000-0000-0000-000000006401'

describe('HttpInjectionGateway', () => {
  it('targets the execution-keyed evidence URL without command metadata headers', async () => {
    const fetcher = vi.fn(async (_input: RequestInfo | URL, _init?: RequestInit) => json(viewPayload()))
    const gateway = gatewayWith(fetcher)

    const value = await gateway.list(scope, taskId, executionId)

    expect(String(fetcher.mock.calls[0]?.[0])).toBe(
      `/api/v1/organizations/${scope.organizationId}/teams/${scope.teamId}`
      + `/tasks/${taskId}/attempts/${executionId}/injection-references`,
    )
    const headers = new Headers(fetcher.mock.calls[0]?.[1]?.headers)
    expect(headers.get('Idempotency-Key')).toBeNull()
    expect(headers.get('If-Match')).toBeNull()
    expect(value.attempts[0]?.claimed).toBeNull()
  })

  it('keeps claimed null apart from a zero-claim receipt across attempts', async () => {
    const fetcher = vi.fn(async () => json({
      ...viewPayload(),
      attempts: [
        attemptPayload({ attempt: 1, claimed: null }),
        attemptPayload({ attempt: 2, claimed: [] }),
      ],
    }))
    const gateway = gatewayWith(fetcher)

    const value = await gateway.list(scope, taskId, executionId)

    expect(value.attempts.map(item => item.attempt)).toEqual([1, 2])
    expect(value.attempts[0]?.claimed).toBeNull()
    expect(value.attempts[1]?.claimed).toEqual([])
  })

  it('passes degradation codes through as an open vocabulary', async () => {
    const fetcher = vi.fn(async () => json({
      ...viewPayload(),
      attempts: [attemptPayload({ degradations: ['RETRIEVAL_DISABLED', 'FUTURE_CODE'] })],
    }))
    const gateway = gatewayWith(fetcher)

    const value = await gateway.list(scope, taskId, executionId)

    expect(value.attempts[0]?.degradations).toEqual(['RETRIEVAL_DISABLED', 'FUTURE_CODE'])
  })

  it('fail-closes unknown reference types and stages', async () => {
    const typeGateway = gatewayWith(vi.fn(async () => json({
      ...viewPayload(),
      attempts: [attemptPayload({ references: [referencePayload({ type: 'WIKI_PAGE' })], claimed: null })],
    })))
    await expect(typeGateway.list(scope, taskId, executionId)).rejects.toThrow('Injection reference type is invalid')

    const stageGateway = gatewayWith(vi.fn(async () => json({
      ...viewPayload(),
      attempts: [attemptPayload({ references: [referencePayload({ stage: 'EVICTED' })], claimed: null })],
    })))
    await expect(stageGateway.list(scope, taskId, executionId)).rejects.toThrow('Injection reference stage is invalid')
  })

  it('serialises the feedback version as a string and reads the number back', async () => {
    const fetcher = vi.fn(async (_input: RequestInfo | URL, _init?: RequestInit) => json(keyPayload()))
    const gateway = gatewayWith(fetcher)

    const receipt = await gateway.submitFeedback(scope, taskId, executionId, {
      type: 'KNOWLEDGE_ENTRY', sourceId: 'entry-1', version: 3, contentHash: 'a'.repeat(64),
    })

    expect(String(fetcher.mock.calls[0]?.[0])).toBe(
      `/api/v1/organizations/${scope.organizationId}/teams/${scope.teamId}`
      + `/tasks/${taskId}/attempts/${executionId}/injection-references/feedback`,
    )
    // The request body is exactly the stage-free quadruple — version as a string, no kind field.
    expect(JSON.parse(String(fetcher.mock.calls[0]?.[1]?.body))).toEqual({
      type: 'KNOWLEDGE_ENTRY', sourceId: 'entry-1', version: '3', contentHash: 'a'.repeat(64),
    })
    const headers = new Headers(fetcher.mock.calls[0]?.[1]?.headers)
    expect(headers.get('Idempotency-Key')).toBeNull()
    expect(headers.get('If-Match')).toBeNull()
    expect(receipt.version).toBe(3)
  })
})

function gatewayWith(fetcher: ReturnType<typeof vi.fn>): HttpInjectionGateway {
  return new HttpInjectionGateway(new CrewScopeApiClient('/api/v1', fetcher as unknown as typeof fetch))
}

function viewPayload(extra: Record<string, unknown> = {}) {
  return {
    executionId, taskId,
    attempts: [attemptPayload()], ...extra,
  }
}

function attemptPayload(extra: Record<string, unknown> = {}) {
  return {
    manifestId: '00000000-0000-0000-0000-000000006411',
    attempt: 1,
    createdAt: '2026-10-04T09:00:00Z',
    budget: { totalTokens: 8192, knowledgeTokens: 3072, chunkTokens: 4096, memoryTokens: 1024 },
    degradations: [],
    trims: [{ layer: 'REPOSITORY_CHUNK', trimmedCount: 2, reason: 'total budget exceeded' }],
    references: [referencePayload()],
    claimed: null,
    ...extra,
  }
}

function referencePayload(extra: Record<string, unknown> = {}) {
  return {
    type: 'KNOWLEDGE_ENTRY', sourceId: 'entry-1', version: 3, contentHash: 'a'.repeat(64),
    stage: 'INJECTED', notApplicable: false, ...extra,
  }
}

function keyPayload(extra: Record<string, unknown> = {}) {
  return {
    type: 'KNOWLEDGE_ENTRY', sourceId: 'entry-1', version: 3, contentHash: 'a'.repeat(64), ...extra,
  }
}

function json(body: unknown, status = 200, headers: Record<string, string> = {}): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json', ...headers },
  })
}
