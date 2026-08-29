import { CrewScopeApiClient } from '../../api/client'
import { HttpAccountGateway } from './gateway'

const csrf = { headerName: 'X-XSRF-TOKEN' as const, parameterName: '_csrf' as const, token: 'csrf-account-memory' }

describe('HttpAccountGateway', () => {
  it('reconstructs the public profile and verifies its strong ETag', async () => {
    const gateway = gatewayWith(vi.fn(async () => profileResponse()))

    const result = await gateway.current()

    expect(result.etag).toBe(4)
    expect(result.value).toEqual(profile())
    expect(JSON.stringify(result)).not.toMatch(/password|credential|sessionId/i)
  })

  it('submits only changed profile fields with CSRF, SecurityVersion and If-Match', async () => {
    const fetcher = vi.fn<typeof fetch>(async () => profileResponse({ displayName: 'Alice Chen', version: 5 }, 5))
    const gateway = gatewayWith(fetcher)

    const result = await gateway.updateProfile({
      displayName: 'Alice Chen', username: 'alice-chen', currentPassword: 'one-way-proof', securityVersion: 3,
    }, { csrf, expectedVersion: 4 })

    const request = fetcher.mock.calls[0]?.[1]
    expect(request?.method).toBe('PATCH')
    expect(new Headers(request?.headers).get('If-Match')).toBe('"4"')
    expect(new Headers(request?.headers).get('X-XSRF-TOKEN')).toBe('csrf-account-memory')
    expect(JSON.parse(String(request?.body))).toEqual({
      username: 'alice-chen', displayName: 'Alice Chen', currentPassword: 'one-way-proof', securityVersion: 3,
    })
    expect(result.etag).toBe(5)
  })

  it('changes the password and revokes all Sessions without accepting Session coordinates', async () => {
    const fetcher = vi.fn<typeof fetch>(async () => new Response(null, { status: 204, headers: { ETag: '"5"' } }))
    const gateway = gatewayWith(fetcher)

    await expect(gateway.changePassword({ currentPassword: 'old-password', newPassword: 'new-password-value', securityVersion: 3 }, { csrf, expectedVersion: 4 })).resolves.toBe(5)
    await expect(gateway.revokeAllSessions({ currentPassword: 'new-password-value', securityVersion: 4 }, { csrf, expectedVersion: 5 })).resolves.toBe(5)

    expect(JSON.parse(String(fetcher.mock.calls[0]?.[1]?.body))).toEqual({
      currentPassword: 'old-password', newPassword: 'new-password-value', securityVersion: 3,
    })
    expect(JSON.parse(String(fetcher.mock.calls[1]?.[1]?.body))).toEqual({ currentPassword: 'new-password-value', securityVersion: 4 })
    expect(String(fetcher.mock.calls[1]?.[1]?.body)).not.toContain('sessionId')
  })

  it.each([
    new Response(JSON.stringify(profile()), { status: 200, headers: { 'Content-Type': 'application/json' } }),
    profileResponse({}, 8),
  ])('fails closed for missing or inconsistent response ETags', async response => {
    await expect(gatewayWith(vi.fn(async () => response)).current()).rejects.toThrow('ETag')
  })
})

function gatewayWith(fetcher: ReturnType<typeof vi.fn>): HttpAccountGateway {
  return new HttpAccountGateway(new CrewScopeApiClient('/api/v1', fetcher as unknown as typeof fetch))
}

function profile(overrides: Record<string, unknown> = {}) {
  return {
    accountId: 'account-1', username: 'alice', email: 'alice@example.com', displayName: 'Alice',
    status: 'ACTIVE', platformRole: 'USER', securityVersion: 3, version: 4,
    createdAt: '2026-08-01T00:00:00Z', updatedAt: '2026-08-29T00:00:00Z', ...overrides,
  }
}

function profileResponse(overrides: Record<string, unknown> = {}, etag = 4): Response {
  return new Response(JSON.stringify(profile(overrides)), {
    status: 200, headers: { 'Content-Type': 'application/json', ETag: `"${etag}"` },
  })
}
