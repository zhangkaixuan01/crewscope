import type { IdentityGateway } from './gateway'
import { createAuthStore, type AuthBroadcastChannel, type AuthBroadcastMessage } from './store'
import type { AuthSession } from './types'

describe('AuthStore', () => {
  it('restores one server-authored Principal and never persists identity state', async () => {
    const identity = gateway(async () => session(true))
    const store = createAuthStore(identity, { channelFactory: () => null })

    await store.ensureRestored()

    expect(store.state.phase).toBe('authenticated')
    expect(store.principal).toMatchObject({
      id: 'principal-1', displayName: 'Alice', organizationId: 'organization-1', role: 'Team Member',
    })
    expect([...store.principal.permissions]).toEqual(['scope:read', 'conversation:use'])
    expect(localStorage).toHaveLength(0)
    expect(sessionStorage).toHaveLength(0)
  })

  it('shares one startup request across concurrent guards', async () => {
    const pending = deferred<AuthSession>()
    const identity = gateway(async () => pending.promise)
    const store = createAuthStore(identity, { channelFactory: () => null })

    const first = store.ensureRestored()
    const second = store.ensureRestored()
    expect(identity.session).toHaveBeenCalledOnce()
    pending.resolve(session(false))
    await Promise.all([first, second])

    expect(store.state.phase).toBe('anonymous')
  })

  it('ignores an authenticated startup response that arrives after a cross-tab sign-out', async () => {
    const first = deferred<AuthSession>()
    let calls = 0
    const identity = gateway(async () => {
      calls += 1
      return calls === 1 ? first.promise : session(false)
    })
    const channel = new FixtureBroadcastChannel()
    const store = createAuthStore(identity, { channelFactory: () => channel })
    store.start()
    expect(identity.session).toHaveBeenCalledOnce()

    channel.receive({ type: 'signed-out' })
    await vi.waitFor(() => expect(identity.session).toHaveBeenCalledTimes(2))
    first.resolve(session(true))
    await vi.waitFor(() => expect(store.state.phase).toBe('anonymous'))

    expect(store.principal.id).toBe('')
    expect(channel.sent).toEqual([])
    store.stop()
  })

  it('broadcasts an authentication-required transition and refreshes anonymous CSRF', async () => {
    const channel = new FixtureBroadcastChannel()
    const identity = gateway(async () => session(false))
    const store = createAuthStore(identity, { channelFactory: () => channel })
    store.start()
    await store.ensureRestored()

    store.authenticationRequired()
    expect(channel.sent).toEqual([{ type: 'signed-out' }])
    await vi.waitFor(() => expect(identity.session).toHaveBeenCalledTimes(2))
    await vi.waitFor(() => expect(store.state.phase).toBe('anonymous'))
    store.stop()
  })

  it('refreshes the Session after login without exposing a blocking startup phase', async () => {
    let authenticated = false
    const identity = gateway(async () => session(authenticated))
    const store = createAuthStore(identity, { channelFactory: () => null })
    await store.ensureRestored()
    expect(store.state.phase).toBe('anonymous')

    authenticated = true
    const refresh = store.refresh()
    expect(store.state.phase).toBe('anonymous')
    expect(await refresh).toBe(true)
    expect(store.state.phase).toBe('authenticated')
  })
})

function gateway(load: () => Promise<AuthSession>): IdentityGateway {
  return {
    session: vi.fn(load),
    login: vi.fn(),
    logout: vi.fn(),
    register: vi.fn(),
  }
}

function session(authenticated: boolean): AuthSession {
  return {
    authenticated,
    registrationMode: 'OPEN',
    csrf: { headerName: 'X-XSRF-TOKEN', parameterName: '_csrf', token: authenticated ? 'csrf-authenticated' : 'csrf-anonymous' },
    account: authenticated ? {
      accountId: 'account-1', username: 'alice', displayName: 'Alice', platformRole: 'USER', securityVersion: 1, version: 1,
    } : null,
    principal: authenticated ? { principalId: 'principal-1', organizationId: 'organization-1' } : null,
    teams: authenticated ? [{ teamId: 'team-1', name: 'Platform', memberId: 'member-1', permissions: ['scope:read'] }] : [],
    permissions: authenticated ? ['scope:read', 'conversation:use'] : [],
  }
}

class FixtureBroadcastChannel implements AuthBroadcastChannel {
  readonly sent: AuthBroadcastMessage[] = []
  private listeners = new Set<(event: MessageEvent<AuthBroadcastMessage>) => void>()

  postMessage(message: AuthBroadcastMessage): void { this.sent.push(message) }
  addEventListener(_type: 'message', listener: (event: MessageEvent<AuthBroadcastMessage>) => void): void { this.listeners.add(listener) }
  removeEventListener(_type: 'message', listener: (event: MessageEvent<AuthBroadcastMessage>) => void): void { this.listeners.delete(listener) }
  close(): void { this.listeners.clear() }
  receive(message: AuthBroadcastMessage): void {
    for (const listener of this.listeners) listener({ data: message } as MessageEvent<AuthBroadcastMessage>)
  }
}

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>(accept => { resolve = accept })
  return { promise, resolve }
}
