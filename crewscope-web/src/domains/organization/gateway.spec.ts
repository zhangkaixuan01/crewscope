import { CrewScopeApiClient } from '../../api/client'
import { HttpOrganizationGateway } from './gateway'

const organizationId = '00000000-0000-0000-0000-000000000001'

describe('HttpOrganizationGateway', () => {
  it('maps the organization profile through the allowlist (organizationId, not id)', async () => {
    const harness = gatewayWith(vi.fn(async () => json({
      id: 'must-be-ignored',
      organizationId,
      name: 'CrewScope Organization',
      status: 'ACTIVE',
      version: 3,
      billingEmail: 'private@example.com',
    })))

    const profile = await harness.gateway.profile(organizationId)

    expect(profile).toEqual({ organizationId, name: 'CrewScope Organization', status: 'ACTIVE', version: 3 })
    expect(harness.lastPath()).toBe(`/organizations/${organizationId}`)
  })

  it('maps settings with the null forever-retention preserved', async () => {
    const harness = gatewayWith(vi.fn(async () => json({ auditEventRetentionDays: null, version: 0 })))

    const settings = await harness.gateway.settings(organizationId)

    expect(settings).toEqual({ auditEventRetentionDays: null, version: 0 })
    expect(harness.lastPath()).toBe(`/organizations/${organizationId}/settings`)
  })

  it('rejects unknown enum values instead of rendering them', async () => {
    const harness = gatewayWith(vi.fn(async () => json({ organizationId, name: 'org', status: 'SUSPENDED', version: 1 })))

    await expect(harness.gateway.profile(organizationId)).rejects.toThrow(TypeError)
  })

  it('puts the idempotency key and the quoted If-Match version on the settings write', async () => {
    const harness = gatewayWith(vi.fn(async () => receipt(false)))

    const mapped = await harness.gateway.updateSettings(organizationId, { auditEventRetentionDays: 90 }, 0, 'attempt-1')

    const request = harness.lastRequest()
    expect(request?.method).toBe('PUT')
    expect(new URL(request!.url, 'http://crewscope.local').pathname).toBe(`/api/v1/organizations/${organizationId}/settings`)
    expect(JSON.parse(String(request?.body))).toEqual({ auditEventRetentionDays: 90 })
    expect(new Headers(request?.headers).get('Idempotency-Key')).toBe('attempt-1')
    expect(new Headers(request?.headers).get('If-Match')).toBe('"0"')
    expect(mapped).toMatchObject({ commandId: 'command-1', committedVersion: 1, replayed: false })
  })

  // The command gateway keys in-flight deduplication on the response, and the page shows a
  // replay notice from this flag — the header is the only signal distinguishing a replay.
  it('marks a replayed receipt from the Idempotency-Replayed header', async () => {
    const harness = gatewayWith(vi.fn(async () => receipt(true)))

    const mapped = await harness.gateway.updateSettings(organizationId, { auditEventRetentionDays: null }, 2, 'attempt-2')

    expect(mapped.replayed).toBe(true)
  })
})

function gatewayWith(fetcher: (input: RequestInfo | URL, init?: RequestInit) => Promise<Response>) {
  const calls: { url: string, init?: RequestInit }[] = []
  const wrapped = vi.fn<typeof fetch>(async (input, init) => {
    calls.push({ url: typeof input === 'string' ? input : String(input), init })
    return fetcher(input, init)
  })
  const gateway = new HttpOrganizationGateway(new CrewScopeApiClient('/api/v1', wrapped as unknown as typeof fetch))
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
