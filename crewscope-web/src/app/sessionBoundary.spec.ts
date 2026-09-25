import { subscribeSessionBoundary } from './sessionBoundary'
import { createAuthStore } from '../domains/identity/store'
import type { AuthSession } from '../domains/identity/types'

function fixture() {
  const current: AuthSession = {
    authenticated: true, registrationMode: 'OPEN',
    csrf: { headerName: 'X-XSRF-TOKEN', parameterName: '_csrf', token: 'test-only' },
    account: { accountId: 'account', username: 'alice', displayName: 'Alice', platformRole: 'USER', securityVersion: 1, version: 0 },
    principal: { principalId: 'principal', organizationId: 'org' }, teams: [], permissions: [],
  }
  const store = createAuthStore({ session: async () => structuredClone(current), login: vi.fn(), logout: vi.fn(), register: vi.fn() }, { channelFactory: () => null })
  const reset = vi.fn()
  subscribeSessionBoundary(store, reset)
  return { current, store, reset }
}

describe('domain session boundary', () => {
  it('does not discard current intents on an ordinary Session refresh', async () => {
    const { store, reset } = fixture()
    await store.ensureRestored()
    await store.refresh()
    expect(reset).toHaveBeenCalledTimes(1)
  })
  it.each(['account', 'principal', 'organization', 'security'] as const)('invalidates domains when %s changes without a sign-out event', async field => {
    const { current, store, reset } = fixture()
    await store.ensureRestored()
    if (field === 'account') current.account!.accountId = 'other'
    if (field === 'principal') current.principal!.principalId = 'other'
    if (field === 'organization') current.principal!.organizationId = 'other'
    if (field === 'security') current.account!.securityVersion += 1
    await store.refresh()
    expect(reset).toHaveBeenCalledTimes(2)
  })
  it('invalidates domains when a Session refresh discovers expiry', async () => {
    const { current, store, reset } = fixture()
    await store.ensureRestored()
    current.authenticated = false
    current.account = null
    current.principal = null
    await store.refresh()
    await store.refresh()
    expect(reset).toHaveBeenCalledTimes(2)
  })
})
