import { CrewScopeApiClient } from '../../api/client'
import { HttpQuotaGateway } from './gateway'

const organizationId = '00000000-0000-0000-0000-000000000001'

describe('HttpQuotaGateway', () => {
  it('maps the configuration with null dimensions preserved as unconfigured', async () => {
    const harness = gatewayWith(vi.fn(async () => json({ monthlyTaskIntakeLimit: null, monthlyTokenBudget: null, version: 0 })))

    const configuration = await harness.gateway.quota(organizationId)

    expect(configuration).toEqual({ monthlyTaskIntakeLimit: null, monthlyTokenBudget: null, version: 0 })
    expect(harness.lastPath()).toBe(`/organizations/${organizationId}/quota`)
  })

  it('rejects a negative limit instead of coercing it to zero semantics', async () => {
    const harness = gatewayWith(vi.fn(async () => json({ monthlyTaskIntakeLimit: -3, monthlyTokenBudget: null, version: 0 })))

    await expect(harness.gateway.quota(organizationId)).rejects.toThrow(TypeError)
  })

  it('dispatches the anchor usage call without a month parameter', async () => {
    const harness = gatewayWith(vi.fn(async () => json(usageBody('2026-10'))))

    const view = await harness.gateway.usage(organizationId, null)

    expect(view.month).toBe('2026-10')
    expect(harness.lastPath()).toBe(`/organizations/${organizationId}/quota/usage`)
    expect(harness.lastRequest()?.url).not.toContain('month=')
  })

  it('encodes the explicit month onto the usage query', async () => {
    const harness = gatewayWith(vi.fn(async () => json(usageBody('2026-07'))))

    await harness.gateway.usage(organizationId, '2026-07')

    expect(new URL(harness.lastRequest()!.url, 'http://crewscope.local').searchParams.get('month')).toBe('2026-07')
  })

  it('maps the usage view through the allowlist, counters and all', async () => {
    const harness = gatewayWith(vi.fn(async () => json(usageBody('2026-10', {
      intake: { limit: 5, count: 3 },
      budget: { limit: 1_000_000, reserved: 600_000, actual: { input: 100_000, output: 50_000, cachedInput: 25_000, total: 175_000 } },
      materialized: true,
    }))))

    const view = await harness.gateway.usage(organizationId, '2026-10')

    expect(view).toEqual({
      month: '2026-10',
      monthlyTaskIntake: { limit: 5, count: 3 },
      monthlyTokenBudget: {
        limit: 1_000_000, reservedTokens: 600_000,
        actualTokens: { input: 100_000, output: 50_000, cachedInput: 25_000, total: 175_000 },
      },
      materialized: true,
    })
  })

  it('rejects a usage month that is not a reporting month', async () => {
    const harness = gatewayWith(vi.fn(async () => json(usageBody('2026-1'))))

    await expect(harness.gateway.usage(organizationId, '2026-10')).rejects.toThrow(TypeError)
  })

  it('puts both dimensions, the key and the quoted If-Match on the write', async () => {
    const harness = gatewayWith(vi.fn(async () => receipt(false)))

    const mapped = await harness.gateway.updateQuota(
      organizationId,
      { monthlyTaskIntakeLimit: 5, monthlyTokenBudget: null },
      0,
      'attempt-1',
    )

    const request = harness.lastRequest()
    expect(request?.method).toBe('PUT')
    expect(new URL(request!.url, 'http://crewscope.local').pathname).toBe(`/api/v1/organizations/${organizationId}/quota`)
    // Both dimensions ride every write — the "absent clears" wire default never applies here.
    expect(JSON.parse(String(request?.body))).toEqual({ monthlyTaskIntakeLimit: 5, monthlyTokenBudget: null })
    expect(new Headers(request?.headers).get('Idempotency-Key')).toBe('attempt-1')
    expect(new Headers(request?.headers).get('If-Match')).toBe('"0"')
    expect(mapped).toMatchObject({ commandId: 'command-1', committedVersion: 1, replayed: false })
  })

  it('marks a replayed receipt from the Idempotency-Replayed header', async () => {
    const harness = gatewayWith(vi.fn(async () => receipt(true)))

    const mapped = await harness.gateway.updateQuota(
      organizationId,
      { monthlyTaskIntakeLimit: null, monthlyTokenBudget: null },
      2,
      'attempt-2',
    )

    expect(mapped.replayed).toBe(true)
  })
})

function gatewayWith(fetcher: (input: RequestInfo | URL, init?: RequestInit) => Promise<Response>) {
  const calls: { url: string, init?: RequestInit }[] = []
  const wrapped = vi.fn<typeof fetch>(async (input, init) => {
    calls.push({ url: typeof input === 'string' ? input : String(input), init })
    return fetcher(input, init)
  })
  const gateway = new HttpQuotaGateway(new CrewScopeApiClient('/api/v1', wrapped as unknown as typeof fetch))
  return {
    gateway,
    lastPath: () => calls.length ? new URL(calls.at(-1)!.url, 'http://crewscope.local').pathname.replace('/api/v1', '') : '',
    lastRequest: () => ({ url: calls.at(-1)?.url ?? '', ...calls.at(-1)?.init }) as { url: string, method?: string, body?: unknown, headers?: HeadersInit } | undefined,
  }
}

function json(body: unknown, headers: Record<string, string> = {}): Response {
  return new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json', ...headers } })
}

function receipt(replayed: boolean): Response {
  return json({
    commandId: 'command-1',
    domainEventId: 'event-1',
    committedVersion: 1,
    correlationId: 'correlation-1',
    replayed: false,
  }, replayed ? { 'Idempotency-Replayed': 'true' } : {})
}

function usageBody(
  month: string,
  overrides: {
    intake?: { limit: number | null, count: number },
    budget?: { limit: number | null, reserved: number, actual: { input: number, output: number, cachedInput: number, total: number } },
    materialized?: boolean,
  } = {},
): unknown {
  return {
    month,
    monthlyTaskIntake: overrides.intake ?? { limit: null, count: 0 },
    monthlyTokenBudget: {
      limit: overrides.budget?.limit ?? null,
      reservedTokens: overrides.budget?.reserved ?? 0,
      actualTokens: overrides.budget?.actual ?? { input: 0, output: 0, cachedInput: 0, total: 0 },
    },
    materialized: overrides.materialized ?? false,
  }
}
