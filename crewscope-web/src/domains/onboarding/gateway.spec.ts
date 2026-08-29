import { CrewScopeApiClient } from '../../api/client'
import { HttpOnboardingGateway } from './gateway'

describe('HttpOnboardingGateway', () => {
  it('maps the closed onboarding status and rejects inconsistent server facts', async () => {
    const valid = gatewayWith({ state: 'TEAM_REQUIRED', onboardingRequired: true, activeTeamCount: 0 })
    await expect(valid.status()).resolves.toEqual({
      state: 'TEAM_REQUIRED', onboardingRequired: true, activeTeamCount: 0,
    })

    const invalid = gatewayWith({ state: 'COMPLETE', onboardingRequired: false, activeTeamCount: 0 })
    await expect(invalid.status()).rejects.toThrow('Team count')
  })

  it('sends only the Team name, CSRF and idempotency coordinates and reads replay proof', async () => {
    const fetcher = vi.fn<typeof fetch>(async (_input, init) => {
      const headers = new Headers(init?.headers)
      expect(init?.method).toBe('POST')
      expect(init?.body).toBe(JSON.stringify({ name: 'Platform Engineering' }))
      expect(headers.get('Idempotency-Key')).toBe('first-team-key')
      expect(headers.get('X-XSRF-TOKEN')).toBe('csrf-memory')
      return new Response(JSON.stringify(receipt()), {
        status: 202,
        headers: { 'Content-Type': 'application/json', 'Idempotency-Replayed': 'true' },
      })
    })
    const gateway = new HttpOnboardingGateway(new CrewScopeApiClient('/api/v1', fetcher))

    await expect(gateway.createFirstTeam({
      name: 'Platform Engineering',
      csrf: { headerName: 'X-XSRF-TOKEN', parameterName: '_csrf', token: 'csrf-memory' },
      idempotencyKey: 'first-team-key',
    })).resolves.toEqual({ ...receipt(), replayed: true })
  })

  it('fails closed for an unsupported replay header', async () => {
    const fetcher = vi.fn<typeof fetch>(async () => new Response(JSON.stringify(receipt()), {
      status: 202,
      headers: { 'Content-Type': 'application/json', 'Idempotency-Replayed': 'false' },
    }))
    const gateway = new HttpOnboardingGateway(new CrewScopeApiClient('/api/v1', fetcher))

    await expect(gateway.createFirstTeam({
      name: 'Platform',
      csrf: { headerName: 'X-XSRF-TOKEN', parameterName: '_csrf', token: 'csrf' },
      idempotencyKey: 'key',
    })).rejects.toThrow('replay header')
  })
})

function gatewayWith(body: unknown): HttpOnboardingGateway {
  const fetcher = vi.fn<typeof fetch>(async () => new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  }))
  return new HttpOnboardingGateway(new CrewScopeApiClient('/api/v1', fetcher))
}

function receipt() {
  return {
    commandId: 'command-1', domainEventId: 'event-1', committedVersion: 0, correlationId: 'correlation-1',
  }
}
