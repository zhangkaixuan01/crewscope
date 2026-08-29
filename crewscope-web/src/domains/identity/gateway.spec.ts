import { CrewScopeApiClient } from '../../api/client'
import { HttpIdentityGateway } from './gateway'

const csrf = { headerName: 'X-XSRF-TOKEN' as const, parameterName: '_csrf' as const, token: 'csrf-memory-only' }

describe('HttpIdentityGateway', () => {
  it('reconstructs the anonymous Session through the public allowlist', async () => {
    const gateway = gatewayWith(vi.fn(async () => json({
      authenticated: false,
      registrationMode: 'OPEN',
      csrf: { ...csrf, cookieSecret: 'private' },
      account: null,
      principal: null,
      teams: [],
      permissions: [],
      sessionId: 'private',
    })))

    const session = await gateway.session()

    expect(session).toEqual({
      authenticated: false,
      registrationMode: 'OPEN',
      csrf,
      account: null,
      principal: null,
      teams: [],
      permissions: [],
    })
    expect(JSON.stringify(session)).not.toContain('sessionId')
    expect(JSON.stringify(session)).not.toContain('cookieSecret')
  })

  it('sends only identifier/password with the in-memory CSRF coordinate', async () => {
    const fetcher = vi.fn<typeof fetch>(async () => json({
      authenticated: true,
      accountId: 'account-1',
      displayName: 'Alice',
      credentialId: 'private',
    }))
    const gateway = gatewayWith(fetcher)

    const result = await gateway.login({ identifier: 'alice@example.com', password: 'one-way-password' }, csrf)

    const request = fetcher.mock.calls[0]?.[1]
    expect(JSON.parse(String(request?.body))).toEqual({ identifier: 'alice@example.com', password: 'one-way-password' })
    expect(new Headers(request?.headers).get('X-XSRF-TOKEN')).toBe('csrf-memory-only')
    expect(request?.credentials).toBe('same-origin')
    expect(result).toEqual({ authenticated: true, accountId: 'account-1', displayName: 'Alice' })
    expect(JSON.stringify(result)).not.toContain('private')
  })

  it('sends the registration proof once and reconstructs only committed public coordinates', async () => {
    const fetcher = vi.fn<typeof fetch>(async () => json({
      accountId: 'account-1',
      principalId: 'principal-1',
      organizationId: 'organization-1',
      teamId: null,
      memberId: null,
      onboardingRequired: true,
      commandId: 'command-1',
      domainEventId: 'event-1',
      committedVersion: 1,
      correlationId: 'correlation-1',
      replayed: false,
      passwordHash: 'private',
      sessionId: 'private',
    }))
    const gateway = gatewayWith(fetcher)

    const result = await gateway.register({
      username: 'alice',
      email: 'alice@example.com',
      displayName: 'Alice',
      password: 'one-way-password',
      invitationToken: 'A'.repeat(43),
    }, csrf, 'registration-key')

    const request = fetcher.mock.calls[0]?.[1]
    expect(JSON.parse(String(request?.body))).toEqual({
      username: 'alice', email: 'alice@example.com', displayName: 'Alice',
      password: 'one-way-password', invitationToken: 'A'.repeat(43),
    })
    expect(new Headers(request?.headers).get('Idempotency-Key')).toBe('registration-key')
    expect(new Headers(request?.headers).get('X-XSRF-TOKEN')).toBe('csrf-memory-only')
    expect(result.onboardingRequired).toBe(true)
    expect(JSON.stringify(result)).not.toMatch(/passwordHash|sessionId|private/)
  })

  it('logs out the current Session with only the in-memory CSRF coordinate', async () => {
    const fetcher = vi.fn<typeof fetch>(async () => new Response(null, { status: 204 }))
    const gateway = gatewayWith(fetcher)

    await gateway.logout(csrf)

    const request = fetcher.mock.calls[0]?.[1]
    expect(request?.method).toBe('POST')
    expect(request?.body).toBeUndefined()
    expect(new Headers(request?.headers).get('X-XSRF-TOKEN')).toBe('csrf-memory-only')
  })

  it('fails closed for inconsistent registration Team coordinates', async () => {
    const gateway = gatewayWith(vi.fn(async () => json({
      accountId: 'account-1', principalId: 'principal-1', organizationId: 'organization-1',
      teamId: 'team-1', memberId: null, onboardingRequired: false,
      commandId: 'command-1', domainEventId: 'event-1', committedVersion: 1,
      correlationId: 'correlation-1', replayed: false,
    })))

    await expect(gateway.register({
      username: 'alice', email: 'alice@example.com', displayName: 'Alice', password: 'one-way-password',
    }, csrf, 'registration-key')).rejects.toThrow('inconsistent')
  })

  it.each([
    { authenticated: false, registrationMode: 'OPEN', csrf, account: account(), principal: null, teams: [], permissions: [] },
    { authenticated: false, registrationMode: 'UNKNOWN', csrf, account: null, principal: null, teams: [], permissions: [] },
    { authenticated: false, registrationMode: 'OPEN', csrf: { ...csrf, headerName: 'X-ATTACKER' }, account: null, principal: null, teams: [], permissions: [] },
    { authenticated: false, registrationMode: 'OPEN', csrf, account: null, principal: null, teams: [{ teamId: 'team-1' }], permissions: [] },
  ])('fails closed for an inconsistent Session response', async payload => {
    await expect(gatewayWith(vi.fn(async () => json(payload))).session()).rejects.toThrow()
  })

  it('maps the authenticated account, principal and Team coordinates', async () => {
    const gateway = gatewayWith(vi.fn(async () => json({
      authenticated: true,
      registrationMode: 'INVITE_ONLY',
      csrf,
      account: account(),
      principal: { principalId: 'principal-1', organizationId: 'org-1' },
      teams: [{ teamId: 'team-1', name: 'Platform', memberId: 'member-1', permissions: ['scope:read'] }],
      permissions: ['scope:read'],
    })))

    const session = await gateway.session()

    expect(session.authenticated).toBe(true)
    expect(session.account?.username).toBe('alice')
    expect(session.teams[0]?.permissions).toEqual(['scope:read'])
  })
})

function gatewayWith(fetcher: ReturnType<typeof vi.fn>): HttpIdentityGateway {
  return new HttpIdentityGateway(new CrewScopeApiClient('/api/v1', fetcher as unknown as typeof fetch))
}

function account() {
  return {
    accountId: 'account-1', username: 'alice', displayName: 'Alice', platformRole: 'USER',
    securityVersion: 2, version: 4,
  }
}

function json(body: unknown): Response {
  return new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } })
}
