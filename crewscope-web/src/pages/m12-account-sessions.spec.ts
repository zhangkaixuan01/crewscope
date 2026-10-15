import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { CrewScopeApiError } from '../api/client'
import type { AccountGateway } from '../domains/account/gateway'
import { ACCOUNT_STORE, createAccountStore } from '../domains/account/store'
import type { AccountProfile } from '../domains/account/types'
import { IDENTITY_GATEWAY, type IdentityGateway } from '../domains/identity/gateway'
import { AUTH_STORE, type AuthStore } from '../domains/identity/store'
import type { AuthCsrfCoordinate, AuthSession, BrowserSessionSummary } from '../domains/identity/types'
import AccountPage from './AccountPage.vue'

/**
 * M12-F03 C2: the account page grows an owner-facing browser-session list on top of
 * GET/DELETE /auth/sessions (M12-I01 §2). Deleting another device refreshes the list;
 * deleting the current session is a logout equivalent and routes to the login page;
 * a 404 folds into "already gone elsewhere" plus a refresh.
 */
describe('AccountPage session management (M12-F03)', () => {
  it('lists the browser sessions with the current-device badge and the login identities', async () => {
    const { wrapper } = await mountPage({ sessions: twoSessions() })

    const rows = wrapper.findAll('[data-testid^="session-row-"]')
    expect(rows).toHaveLength(2)
    expect(wrapper.get('[data-testid="session-row-current"]').text()).toContain('当前设备')
    expect(wrapper.get('[data-testid="session-row-current"]').text()).toContain('本机')
    expect(wrapper.get('[data-testid="session-row-other"]').text()).toContain('其他设备')
    // Login identities render through the localized labels, never the raw enum.
    expect(wrapper.get('[data-testid="login-identity-local"]').text()).toContain('账号密码')
    expect(wrapper.get('[data-testid="login-identity-local"]').text()).toContain('可用')
    expect(wrapper.get('[data-testid="login-identity-oidc/team-beta"]').text()).toContain('企业单点登录')
    expect(wrapper.text()).not.toContain('ACTIVE')
    wrapper.unmount()
  })

  it('revokes another device session directly and refreshes the list', async () => {
    const identity = identityGateway({ sessions: twoSessions() })
    const { wrapper } = await mountPage({ identity })

    await wrapper.get('[data-testid="session-row-other"] button').trigger('click')
    await flushPromises()

    expect(identity.deleteSession).toHaveBeenCalledExactlyOnceWith('session-other', csrfCoordinate())
    expect(identity.sessions).toHaveBeenCalledTimes(2)
    wrapper.unmount()
  })

  it('confirms before deleting the current session, then signs out to the login page', async () => {
    const identity = identityGateway({ sessions: twoSessions() })
    const { wrapper, router, authStore } = await mountPage({ identity })

    await wrapper.get('[data-testid="session-row-current"] button').trigger('click')
    expect(identity.deleteSession).not.toHaveBeenCalled()
    const dialog = wrapper.get('[role="dialog"]')
    expect(dialog.text()).toContain('退出本设备？')

    await dialog.findAll('button')[0]!.trigger('click')
    await flushPromises()

    expect(identity.deleteSession).toHaveBeenCalledExactlyOnceWith('session-current', csrfCoordinate())
    expect(authStore.signedOut).toBe(true)
    await vi.waitFor(() => expect(router.currentRoute.value.path).toBe('/login'))
    wrapper.unmount()
  })

  it('folds a 404 into an already-gone notice and refreshes the list', async () => {
    const identity = identityGateway({
      sessions: twoSessions(),
      deleteError: apiError(404, 'session_not_found', 'session private'),
    })
    const { wrapper } = await mountPage({ identity })

    await wrapper.get('[data-testid="session-row-other"] button').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('该会话已经不存在，列表已刷新。')
    expect(wrapper.text()).not.toContain('private')
    expect(identity.sessions).toHaveBeenCalledTimes(2)
    wrapper.unmount()
  })

  it('surfaces a transient delete failure without losing the list', async () => {
    const identity = identityGateway({
      sessions: twoSessions(),
      deleteError: apiError(503, 'authentication_unavailable', 'store private'),
    })
    const { wrapper } = await mountPage({ identity })

    await wrapper.get('[data-testid="session-row-other"] button').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('暂时无法撤销这个会话，请稍后重试。')
    expect(wrapper.findAll('[data-testid^="session-row-"]')).toHaveLength(2)
    wrapper.unmount()
  })

  it('renders the empty state and an error state with retry', async () => {
    const empty = await mountPage({ identity: identityGateway({ sessions: [] }) })
    expect(empty.wrapper.text()).toContain('当前没有活跃的浏览器会话。')
    empty.wrapper.unmount()

    const identity = identityGateway({ sessionsError: apiError(503, 'authentication_unavailable', 'private') })
    const { wrapper } = await mountPage({ identity })
    expect(wrapper.text()).toContain('暂时无法读取登录会话。')
    // StatePanel renders its recovery action with the shared「刷新事实」fallback label.
    const retry = wrapper.findAll('button').find(button => button.text().includes('刷新事实'))
    expect(retry).toBeDefined()
    wrapper.unmount()
  })
})

function twoSessions(): BrowserSessionSummary[] {
  return [
    { sessionId: 'session-current', createdAt: '2026-10-15T08:00:00Z', lastAccessedAt: '2026-10-15T09:30:00Z', current: true },
    { sessionId: 'session-other', createdAt: '2026-10-14T08:00:00Z', lastAccessedAt: '2026-10-14T20:00:00Z', current: false },
  ]
}

function csrfCoordinate(): AuthCsrfCoordinate {
  return { headerName: 'X-XSRF-TOKEN', parameterName: '_csrf', token: 'csrf-memory-only' }
}

function apiError(status: number, code: string, message: string): CrewScopeApiError {
  return new CrewScopeApiError(status, {
    code, message, correlationId: 'safe-correlation', retryable: status >= 429, currentVersion: null, details: {},
  })
}

async function mountPage(options: {
  identity?: IdentityGateway
  sessions?: BrowserSessionSummary[]
} = {}) {
  const identity = options.identity ?? identityGateway({ sessions: options.sessions ?? twoSessions() })
  const accountGateway: AccountGateway = {
    current: vi.fn(async () => ({ value: profile(), etag: 4 })),
    updateProfile: vi.fn(), changePassword: vi.fn(), revokeAllSessions: vi.fn(),
  }
  const authStore = authStoreWithSession()
  const router = createRouter({ history: createMemoryHistory(), routes: [
    { path: '/account', name: 'account', component: { template: '<div />' } },
    { path: '/login', name: 'login', component: { template: '<div />' } },
  ] })
  await router.push('/account')
  await router.isReady()
  const wrapper = mount(AccountPage, {
    attachTo: document.body,
    global: {
      plugins: [router],
      provide: {
        [ACCOUNT_STORE as symbol]: createAccountStore(accountGateway),
        [AUTH_STORE as symbol]: authStore,
        [IDENTITY_GATEWAY as symbol]: identity,
      },
      stubs: {
        TeamNotificationPreferenceCard: { template: '<section data-notification-card />' },
        RouterLink: { props: ['to'], template: '<a><slot /></a>' },
      },
    },
  })
  await flushPromises()
  return { wrapper, router, authStore }
}

function identityGateway(options: {
  sessions?: BrowserSessionSummary[]
  sessionsError?: unknown
  deleteError?: unknown
} = {}): IdentityGateway {
  return {
    session: vi.fn(),
    login: vi.fn(),
    logout: vi.fn(),
    register: vi.fn(),
    sessions: vi.fn(async () => {
      if (options.sessionsError) throw options.sessionsError
      return options.sessions ?? []
    }),
    deleteSession: vi.fn(async () => {
      if (options.deleteError) throw options.deleteError
    }),
  }
}

/** The page reads csrf and loginIdentities straight from the auth session projection. */
function authStoreWithSession(): AuthStore & { signedOut: boolean } {
  const session: AuthSession = {
    authenticated: true,
    registrationMode: 'OPEN',
    identityPolicy: 'coexist',
    ssoAuthorizationUrl: null,
    csrf: csrfCoordinate(),
    account: {
      accountId: 'account-1', username: 'alice', displayName: 'Alice', platformRole: 'USER',
      securityVersion: 1, version: 1,
      loginIdentities: [
        { provider: 'local', status: 'ACTIVE', lastAuthenticatedAt: '2026-10-15T08:00:00Z' },
        { provider: 'oidc/team-beta', status: 'ACTIVE', lastAuthenticatedAt: null },
      ],
    },
    principal: { principalId: 'principal-1', organizationId: 'org-1' },
    organization: null,
    teams: [],
    permissions: [],
  }
  const listeners = new Set<Parameters<AuthStore['subscribe']>[0]>()
  const store = {
    state: { phase: 'authenticated' as const, session, activeTeamId: null, errorCode: null, errorMessage: null },
    principal: { id: 'principal-1', accountId: 'account-1', displayName: 'Alice', role: 'Member', organizationId: 'org-1', organization: 'CrewScope', permissions: new Set<string>() },
    signedOut: false,
    start() {}, stop() {}, async ensureRestored() {}, async refresh() { return true }, async retry() {},
    selectTeam() {}, authenticationRequired() {},
    signOutLocally() { store.signedOut = true },
    async switchAccount() { return true },
    subscribe(listener: Parameters<AuthStore['subscribe']>[0]) {
      listeners.add(listener)
      return () => listeners.delete(listener)
    },
  }
  return store
}

function profile(): AccountProfile {
  return {
    accountId: 'account-1', username: 'alice', email: 'alice@example.com', displayName: 'Alice',
    status: 'ACTIVE', platformRole: 'USER', securityVersion: 3, version: 4,
    createdAt: '2026-08-29T00:00:00Z', updatedAt: '2026-08-29T00:00:00Z',
  }
}
