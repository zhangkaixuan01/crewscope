import { CrewScopeApiClient } from '../../api/client'
import { fixtureIds } from '../../test/scopeFixtures'
import { HttpModelGateway } from './gateway'
import type { ModelConnectionSummary } from './types'

const scope = { organizationId: fixtureIds.organization, teamId: fixtureIds.teamPlatform }
const connectionId = '00000000-0000-0000-0000-000000005101'

describe('HttpModelGateway', () => {
  it('maps Provider and Catalog pages through explicit DTO whitelists and preserves offset paging', async () => {
    const fetcher = vi.fn(async (input: RequestInfo | URL) => String(input).includes('/catalog')
      ? json({ items: [catalogPayload({ endpoint: 'https://private.model', adapterKey: 'private-adapter' })] })
      : json({ items: [providerPayload({ defaultEndpoint: 'https://private.model', adapterKey: 'private-adapter' })] }))
    const gateway = gatewayWith(fetcher)

    const providers = await gateway.listProviders(scope.organizationId, 10, 1)
    const catalog = await gateway.listCatalog(scope.organizationId, 'deepseek', 20, 1)

    expect(providers.nextOffset).toBe(11)
    expect(catalog.nextOffset).toBe(21)
    expect(String(fetcher.mock.calls[0]?.[0])).toContain('offset=10&limit=1')
    expect(String(fetcher.mock.calls[1]?.[0])).toContain('/deepseek/catalog?offset=20&limit=1')
    expect(JSON.stringify({ providers, catalog })).not.toContain('private.model')
    expect(JSON.stringify({ providers, catalog })).not.toContain('adapterKey')
  })

  it('keeps Connection details inside the public whitelist and retains the strong ETag', async () => {
    const fetcher = vi.fn(async () => json(connectionPayload({
      credentialId: 'private-credential', apiKey: 'secret', endpoint: 'https://private.model',
    }), 200, { ETag: '"4"' }))
    const gateway = gatewayWith(fetcher)

    const result = await gateway.getConnection(scope.organizationId, connectionId)

    expect(result.etag).toBe('"4"')
    expect(result.value.id).toBe(connectionId)
    expect(JSON.stringify(result)).not.toContain('private-credential')
    expect(JSON.stringify(result)).not.toContain('secret')
    expect(JSON.stringify(result)).not.toContain('private.model')
  })

  it('uses exact owner paging and strong command metadata while admitting API Key only in one request body', async () => {
    const fetcher = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      if (init?.method === 'POST') return json(receiptPayload({ internalResult: 'private' }), 202)
      return json({ items: [connectionPayload()] })
    })
    const gateway = gatewayWith(fetcher)

    const page = await gateway.listConnections(scope, 'TEAM', 0, 50)
    const receipt = await gateway.createConnection({
      providerKey: 'deepseek', ownerType: 'USER', teamId: null, region: 'cn',
      apiKey: 'one-way-secret', credentialExpiresAt: null,
    }, scope.organizationId, 'create-key')
    await gateway.rotateCredential(scope.organizationId, connectionId, '"4"', {
      credentialVersion: 2, apiKey: 'rotated-secret',
    }, 'rotate-key')

    expect(page.nextOffset).toBeNull()
    expect(String(fetcher.mock.calls[0]?.[0])).toContain(`ownerType=TEAM&teamId=${fixtureIds.teamPlatform}`)
    const createHeaders = new Headers(fetcher.mock.calls[1]?.[1]?.headers)
    expect(createHeaders.get('Idempotency-Key')).toBe('create-key')
    expect(String(fetcher.mock.calls[1]?.[1]?.body)).toContain('one-way-secret')
    const rotateHeaders = new Headers(fetcher.mock.calls[2]?.[1]?.headers)
    expect(rotateHeaders.get('If-Match')).toBe('"4"')
    expect(rotateHeaders.get('Idempotency-Key')).toBe('rotate-key')
    expect(String(fetcher.mock.calls[2]?.[1]?.body)).toContain('rotated-secret')
    expect(receipt).not.toHaveProperty('internalResult')
  })

  it('fails closed when a versioned response has no strong ETag or an unknown owner type', async () => {
    const weak = gatewayWith(vi.fn(async () => json(connectionPayload(), 200, { ETag: 'W/"4"' })))
    await expect(weak.getConnection(scope.organizationId, connectionId)).rejects.toThrow('strong ETag')

    const invalidOwner = gatewayWith(vi.fn(async () => json({ ...connectionPayload(), ownerType: 'EXTERNAL' }, 200, { ETag: '"4"' })))
    await expect(invalidOwner.getConnection(scope.organizationId, connectionId)).rejects.toThrow('owner type')
  })
})

function gatewayWith(fetcher: ReturnType<typeof vi.fn>): HttpModelGateway {
  return new HttpModelGateway(new CrewScopeApiClient('/api/v1', fetcher as unknown as typeof fetch))
}

function providerPayload(extra: Record<string, unknown> = {}) {
  return {
    key: 'deepseek', displayName: 'DeepSeek', availableRegions: ['cn'], retentionMode: 'NONE',
    maximumRetentionSeconds: null, trainingUsagePolicy: 'DISABLED', status: 'ACTIVE', version: 1,
    ...extra,
  }
}

function catalogPayload(extra: Record<string, unknown> = {}) {
  return {
    id: 'catalog-1', providerKey: 'deepseek', modelId: 'deepseek-v4-flash', catalogRevision: 2,
    modelRevision: 'DeepSeek-V4-Flash-0731', displayName: 'DeepSeek V4 Flash',
    contextWindowTokens: 128_000, maximumOutputTokens: 16_384, capabilities: ['TOOLS'],
    availableRegions: ['cn'], status: 'ACTIVE', version: 1,
    effectivePrice: {
      revision: 1, effectiveFrom: '2026-08-01T00:00:00Z', inputPerMillionTokens: '0.1',
      outputPerMillionTokens: '0.2', cachedInputPerMillionTokens: '0.02', currencyCode: 'USD',
    },
    ...extra,
  }
}

function connectionPayload(extra: Record<string, unknown> = {}): ModelConnectionSummary & Record<string, unknown> {
  return {
    id: connectionId, organizationId: scope.organizationId, providerKey: 'deepseek', ownerType: 'TEAM',
    ownerId: scope.teamId, region: 'cn', billingSubjectType: 'TEAM', billingSubjectId: scope.teamId,
    credentialVersion: 2, status: 'ACTIVE', healthStatus: 'HEALTHY', healthFailureCode: null,
    checkedAt: '2026-08-24T01:00:00Z', lastHealthyAt: '2026-08-24T01:00:00Z', consecutiveFailures: 0,
    revocationReason: null, createdAt: '2026-08-23T01:00:00Z', updatedAt: '2026-08-24T01:00:00Z',
    version: 4, ...extra,
  }
}

function receiptPayload(extra: Record<string, unknown> = {}) {
  return {
    commandId: crypto.randomUUID(), domainEventId: crypto.randomUUID(), committedVersion: 4,
    correlationId: crypto.randomUUID(), ...extra,
  }
}

function json(body: unknown, status = 200, headers: Record<string, string> = {}): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json', ...headers },
  })
}
