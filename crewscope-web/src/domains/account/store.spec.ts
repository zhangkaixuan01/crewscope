import { CrewScopeApiError } from '../../api/client'
import type { AccountGateway } from './gateway'
import { createAccountStore } from './store'
import type { AccountProfile } from './types'

const csrf = { headerName: 'X-XSRF-TOKEN' as const, parameterName: '_csrf' as const, token: 'csrf-memory' }

describe('AccountStore', () => {
  it('loads a versioned profile and updates it without retaining the password proof', async () => {
    const gateway = fixtureGateway()
    const store = createAccountStore(gateway)
    await store.load()

    const success = await store.updateProfile({
      username: 'alice-next', currentPassword: 'one-way-secret', securityVersion: 3,
    }, csrf)

    expect(success).toBe(true)
    expect(gateway.updateProfile).toHaveBeenCalledWith(expect.objectContaining({ username: 'alice-next' }), expect.objectContaining({ expectedVersion: 4 }), expect.any(AbortSignal))
    expect(store.state.profile?.username).toBe('alice-next')
    expect(store.state.etag).toBe(5)
    expect(JSON.stringify(store.state)).not.toContain('one-way-secret')
  })

  it('presents a strong-version conflict without replacing current facts with server details', async () => {
    const gateway = fixtureGateway()
    gateway.updateProfile = vi.fn(async () => { throw apiError('optimistic_lock_conflict', 409, 5) })
    const store = createAccountStore(gateway)
    await store.load()

    expect(await store.updateProfile({ displayName: 'New Alice' }, csrf)).toBe(false)
    expect(store.state.commandProblem).toMatchObject({ code: 'optimistic_lock_conflict', conflict: true })
    expect(store.state.profile?.displayName).toBe('Alice')
  })

  it('uses one-way password/session arguments and resets every public fact on identity loss', async () => {
    const gateway = fixtureGateway()
    const store = createAccountStore(gateway)
    await store.load()

    expect(await store.changePassword({ currentPassword: 'old-private', newPassword: 'new-private-value', securityVersion: 3 }, csrf)).toBe(true)
    expect(JSON.stringify(store.state)).not.toMatch(/old-private|new-private-value/)
    store.reset()
    expect(store.state).toMatchObject({ phase: 'idle', profile: null, etag: null, commandPhase: 'idle' })
  })

  it('maps current-password failure to stable public text', async () => {
    const gateway = fixtureGateway()
    gateway.revokeAllSessions = vi.fn(async () => { throw apiError('invalid_credentials', 401) })
    const store = createAccountStore(gateway)
    await store.load()

    expect(await store.revokeAllSessions({ currentPassword: 'private', securityVersion: 3 }, csrf)).toBe(false)
    expect(store.state.commandProblem).toMatchObject({ code: 'invalid_credentials', title: '当前密码不正确' })
  })
})

function fixtureGateway(): AccountGateway {
  return {
    current: vi.fn(async () => ({ value: profile(), etag: 4 })),
    updateProfile: vi.fn(async input => ({ value: profile({ ...input, currentPassword: undefined, version: 5 }), etag: 5 })),
    changePassword: vi.fn(async () => 5),
    revokeAllSessions: vi.fn(async () => 5),
  }
}

function profile(overrides: Partial<AccountProfile> = {}): AccountProfile {
  return {
    accountId: 'account-1', username: 'alice', email: 'alice@example.com', displayName: 'Alice',
    status: 'ACTIVE', platformRole: 'USER', securityVersion: 3, version: 4,
    createdAt: '2026-08-01T00:00:00Z', updatedAt: '2026-08-29T00:00:00Z', ...overrides,
  }
}

function apiError(code: string, status: number, currentVersion: number | null = null): CrewScopeApiError {
  return new CrewScopeApiError(status, { code, message: 'private server detail', correlationId: 'corr', retryable: false, currentVersion, details: {} })
}
