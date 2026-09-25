import { CrewScopeApiClient } from '../../api/client'
import { fixtureIds } from '../../test/scopeFixtures'
import { HttpPrincipalDirectoryGateway } from './gateway'

const scope = { organizationId: fixtureIds.organization, teamId: fixtureIds.teamPlatform }

describe('HttpPrincipalDirectoryGateway', () => {
  it('keeps the legacy request shape when no A06 parameter is sent', async () => {
    const fetcher = vi.fn(async (_input: RequestInfo | URL) => json(pagePayload()))
    const gateway = gatewayWith(fetcher)

    const page = await gateway.search(scope)

    expect(String(fetcher.mock.calls[0]?.[0])).toContain('offset=0&limit=20')
    expect(String(fetcher.mock.calls[0]?.[0])).not.toContain('q=')
    expect(String(fetcher.mock.calls[0]?.[0])).not.toContain('types=')
    expect(String(fetcher.mock.calls[0]?.[0])).not.toContain('ids=')
    expect(String(fetcher.mock.calls[0]?.[0])).not.toContain('after=')
    expect(page.nextCursor).toBeNull()
    expect(page.nextOffset).toBe(20)
  })

  it('appends the filter parameters only when they are sent', async () => {
    const fetcher = vi.fn(async (_input: RequestInfo | URL) => json(pagePayload({ nextCursor: 'signed-tail' })))
    const gateway = gatewayWith(fetcher)

    const page = await gateway.search({
      ...scope, namePrefix: '评审', types: ['USER', 'AGENT'],
      ids: [fixtureIds.principal], after: 'signed-tail',
    })

    expect(String(fetcher.mock.calls[0]?.[0])).toContain('namePrefix=')
    expect(String(fetcher.mock.calls[0]?.[0])).toContain('types=USER%2CAGENT')
    expect(String(fetcher.mock.calls[0]?.[0])).toContain(`ids=${fixtureIds.principal}`)
    expect(String(fetcher.mock.calls[0]?.[0])).toContain('after=signed-tail')
    expect(page.nextCursor).toBe('signed-tail')
  })

  it('sends q as the alias of an agreeing namePrefix and rejects a disagreement', async () => {
    const fetcher = vi.fn(async (_input: RequestInfo | URL) => json(pagePayload()))
    const gateway = gatewayWith(fetcher)

    await gateway.search({ ...scope, q: 'Al', namePrefix: 'Al' })
    expect(String(fetcher.mock.calls[0]?.[0])).toContain('q=Al')
    expect(String(fetcher.mock.calls[0]?.[0])).not.toContain('namePrefix=')

    await expect(gateway.search({ ...scope, q: 'Al', namePrefix: 'Bob' }))
      .rejects.toThrow('must agree')
    expect(fetcher).toHaveBeenCalledTimes(1)
  })

  it('fails closed on unknown kinds, oversized id sets and malformed rows', async () => {
    const gateway = gatewayWith(vi.fn(async (_input: RequestInfo | URL) => json(pagePayload())))

    await expect(gateway.search({ ...scope, types: ['SERVICE' as never] }))
      .rejects.toThrow('Invalid Principal kind filter')
    await expect(gateway.search({ ...scope, ids: Array.from({ length: 51 }, () => fixtureIds.principal) }))
      .rejects.toThrow('must not exceed 50')
    const broken = gatewayWith(
      vi.fn(async (_input: RequestInfo | URL) => json({ items: [{ ...entryPayload(), kind: 'SERVICE' }] })))
    await expect(broken.search(scope)).rejects.toThrow('Invalid Principal kind')
  })
})

function gatewayWith(fetcher: ReturnType<typeof vi.fn>): HttpPrincipalDirectoryGateway {
  return new HttpPrincipalDirectoryGateway(
    new CrewScopeApiClient('/api/v1', fetcher as unknown as typeof fetch))
}

function entryPayload(extra: Record<string, unknown> = {}) {
  return {
    principalId: fixtureIds.principal,
    kind: 'USER',
    displayName: 'Owner',
    status: 'ACTIVE',
    roles: ['TEAM_OWNER'],
    ...extra,
  }
}

function pagePayload(extra: Record<string, unknown> = {}) {
  return { items: [entryPayload()], nextOffset: 20, ...extra }
}

function json(body: unknown): Response {
  return new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } })
}
