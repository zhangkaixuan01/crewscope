import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory } from 'vue-router'
import { CrewScopeApiError } from '../api/client'
import { createCrewScopeRouter } from '../app/router'
import { IDENTITY_GATEWAY, type IdentityGateway } from '../domains/identity/gateway'
import type { AuthSession, LoginResult } from '../domains/identity/types'
import { createAuthStore, AUTH_STORE } from '../domains/identity/store'
import LoginPage from './LoginPage.vue'

describe('LoginPage', () => {
  afterEach(() => {
    Object.defineProperty(window.navigator, 'onLine', { configurable: true, value: true })
    vi.useRealTimers()
    localStorage.clear()
    sessionStorage.clear()
  })

  it('loads the anonymous Session and exposes native login semantics', async () => {
    const { wrapper } = await mountPage(gateway())

    const identifier = wrapper.get<HTMLInputElement>('input[name="identifier"]')
    const password = wrapper.get<HTMLInputElement>('input[name="password"]')
    expect(identifier.attributes('autocomplete')).toBe('username')
    expect(identifier.attributes('maxlength')).toBe('1024')
    expect(password.attributes('autocomplete')).toBe('current-password')
    expect(password.attributes('maxlength')).toBe('512')
    expect(document.activeElement).toBe(identifier.element)
    expect(wrapper.text()).toContain('当前部署支持自行创建账号')
    expect(wrapper.get('a[href="/register"]').text()).toBe('创建账号')
    expect(wrapper.find('input[type="checkbox"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('submits with Enter once, clears the password and restores a safe target', async () => {
    const identity = gateway()
    const { wrapper, router } = await mountPage(identity, '/login?returnTo=%2Ftoday%3Fteam%3Dteam-1')
    await wrapper.get('input[name="identifier"]').setValue('  alice@example.com  ')
    await wrapper.get('input[name="password"]').setValue('one-way-password')

    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(identity.login).toHaveBeenCalledOnce()
    expect(identity.login).toHaveBeenCalledWith(
      { identifier: 'alice@example.com', password: 'one-way-password' },
      session().csrf,
      expect.any(AbortSignal),
    )
    await vi.waitFor(() => expect(router.currentRoute.value.fullPath).toBe('/today?team=team-1'))
    expect((wrapper.get<HTMLInputElement>('input[name="password"]').element).value).toBe('')
    expect(localStorage).toHaveLength(0)
    expect(sessionStorage).toHaveLength(0)
    wrapper.unmount()
  })

  it('shows one non-enumerating error and focuses it for invalid credentials', async () => {
    const identity = gateway({ loginError: apiError(401, 'invalid_credentials', 'account does not exist: private') })
    const { wrapper } = await mountPage(identity)
    await wrapper.get('input[name="identifier"]').setValue('unknown')
    await wrapper.get('input[name="password"]').setValue('wrong-password')

    await wrapper.get('form').trigger('submit')
    await flushPromises()

    const alert = wrapper.get('[role="alert"]')
    expect(alert.text()).toContain('登录信息无效，请检查后重试。')
    expect(alert.text()).not.toMatch(/账号不存在|密码错误|锁定|private/)
    expect(document.activeElement).toBe(alert.element)
    expect(wrapper.get<HTMLInputElement>('input[name="password"]').element.value).toBe('')
    wrapper.unmount()
  })

  it('separates capacity limits without disclosing account state', async () => {
    const identity = gateway({ loginError: apiError(429, 'too_many_requests', 'hash permit private') })
    const { wrapper } = await mountPage(identity)
    await wrapper.get('input[name="identifier"]').setValue('alice')
    await wrapper.get('input[name="password"]').setValue('password')

    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(wrapper.get('[role="alert"]').text()).toContain('请求过于频繁，请稍后再试。')
    expect(wrapper.text()).not.toContain('hash permit')
    wrapper.unmount()
  })

  it('blocks duplicate submit while the first login request is pending', async () => {
    const pending = deferred<{ authenticated: true, accountId: string, displayName: string }>()
    const identity = gateway({ loginPromise: pending.promise })
    const { wrapper } = await mountPage(identity)
    await wrapper.get('input[name="identifier"]').setValue('alice')
    await wrapper.get('input[name="password"]').setValue('password')

    await wrapper.get('form').trigger('submit')
    await wrapper.get('form').trigger('submit')

    expect(identity.login).toHaveBeenCalledOnce()
    expect(wrapper.get('button[type="submit"]').attributes('disabled')).toBeDefined()
    pending.resolve({ authenticated: true, accountId: 'account-1', displayName: 'Alice' })
    await flushPromises()
    wrapper.unmount()
  })

  it('redirects an already authenticated Session without rendering the form', async () => {
    const identity = gateway({ authenticated: true })
    const { wrapper, router } = await mountPage(identity, '/login?returnTo=https://attacker.example/work')

    await vi.waitFor(() => expect(router.currentRoute.value.fullPath).toBe('/conversation'))
    expect(wrapper.find('form').exists()).toBe(false)
    expect(identity.login).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('renders offline recovery when the browser transport is unavailable', async () => {
    const identity = gateway({ sessionError: apiError(0, 'network_unavailable', 'socket private') })
    const { wrapper } = await mountPage(identity)

    expect(identity.session).toHaveBeenCalledOnce()
    expect(wrapper.get('[role="alert"]').text()).toContain('当前处于离线状态')
    expect(wrapper.text()).not.toContain('socket private')
    wrapper.unmount()
  })

  it('converts a bounded login timeout into a focused retryable state', async () => {
    vi.useFakeTimers()
    const identity = gateway({ abortableLogin: true })
    const { wrapper } = await mountPage(identity, '/login', { loginTimeoutMs: 25 })
    await wrapper.get('input[name="identifier"]').setValue('alice')
    await wrapper.get('input[name="password"]').setValue('password')

    const submission = wrapper.get('form').trigger('submit')
    await vi.advanceTimersByTimeAsync(26)
    await submission
    await flushPromises()

    const alert = wrapper.get('[role="alert"]')
    expect(alert.text()).toContain('登录请求超时')
    expect(document.activeElement).toBe(alert.element)
    wrapper.unmount()
  })
})

async function mountPage(
  identity: IdentityGateway,
  initialRoute = '/login',
  props: { loginTimeoutMs?: number, sessionTimeoutMs?: number } = {},
) {
  const authStore = createAuthStore(identity, { channelFactory: () => null })
  const router = createCrewScopeRouter(createMemoryHistory(), authStore)
  await router.push(initialRoute)
  await router.isReady()
  const wrapper = mount(LoginPage, {
    attachTo: document.body,
    props,
    global: { plugins: [router], provide: { [IDENTITY_GATEWAY as symbol]: identity, [AUTH_STORE as symbol]: authStore } },
  })
  await flushPromises()
  return { wrapper, router }
}

function gateway(options: {
  authenticated?: boolean
  sessionError?: unknown
  loginError?: unknown
  loginPromise?: Promise<{ authenticated: true, accountId: string, displayName: string }>
  abortableLogin?: boolean
} = {}): IdentityGateway {
  let authenticated = Boolean(options.authenticated)
  const login = vi.fn<IdentityGateway['login']>(async (_credentials, _csrf, signal): Promise<LoginResult> => {
    if (options.loginError) throw options.loginError
    if (options.abortableLogin) {
      return new Promise<LoginResult>((_, reject) => signal?.addEventListener('abort', () => reject(new DOMException('aborted', 'AbortError')), { once: true }))
    }
    const result: LoginResult = options.loginPromise
      ? await options.loginPromise
      : { authenticated: true, accountId: 'account-1', displayName: 'Alice' }
    authenticated = true
    return result
  })
  return {
    session: vi.fn(async () => {
      if (options.sessionError) throw options.sessionError
      return session(authenticated)
    }),
    login,
    logout: vi.fn(),
    register: vi.fn(),
  }
}

function session(authenticated = false): AuthSession {
  return {
    authenticated,
    registrationMode: 'OPEN',
    csrf: { headerName: 'X-XSRF-TOKEN', parameterName: '_csrf', token: 'csrf-memory-only' },
    account: authenticated ? {
      accountId: 'account-1', username: 'alice', displayName: 'Alice', platformRole: 'USER', securityVersion: 1, version: 1,
    } : null,
    principal: authenticated ? { principalId: 'principal-1', organizationId: 'org-1' } : null,
    teams: authenticated ? [{ teamId: 'team-1', name: 'Platform', memberId: 'member-1', permissions: ['scope:read', 'conversation:use'] }] : [],
    permissions: authenticated ? ['scope:read', 'conversation:use'] : [],
  }
}

function apiError(status: number, code: string, message: string): CrewScopeApiError {
  return new CrewScopeApiError(status, {
    code, message, correlationId: 'safe-correlation', retryable: status >= 429, currentVersion: null, details: {},
  })
}

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>(accept => { resolve = accept })
  return { promise, resolve }
}
