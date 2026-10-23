import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory } from 'vue-router'
import { CrewScopeApiError } from '../api/client'
import { createCrewScopeRouter } from '../app/router'
import { F05_EPOCH_KEY } from '../app/f05Storage'
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
    // The post-login target is a lazy route. Under the full parallel suite the module can take
    // longer than Vitest's default 1s polling window, so keep the assertion bounded like the
    // authenticated-session redirect case below.
    await vi.waitFor(() => expect(router.currentRoute.value.fullPath).toBe('/today?team=team-1'), { timeout: 5_000 })
    expect((wrapper.get<HTMLInputElement>('input[name="password"]').element).value).toBe('')
    // The scoped-namespace epoch marker is device-level metadata, not persisted state.
    expect(Object.keys(localStorage).filter(key => key !== F05_EPOCH_KEY)).toHaveLength(0)
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

  it('aborts the in-flight login on unmount and a late resolution writes nothing', async () => {
    const pending = deferred<{ authenticated: true, accountId: string, displayName: string }>()
    const identity = gateway({ loginPromise: pending.promise })
    const { wrapper } = await mountPage(identity)
    await wrapper.get('input[name="identifier"]').setValue('alice')
    await wrapper.get('input[name="password"]').setValue('password')
    await wrapper.get('form').trigger('submit')
    const sessionCalls = vi.mocked(identity.session).mock.calls.length

    wrapper.unmount()
    // Disposal aborts the request channel (M12b-F05): the linked transport signal is cancelled…
    expect(vi.mocked(identity.login).mock.calls[0]?.[2]?.aborted).toBe(true)
    // …and a late resolution cannot resume the flow — no session refresh runs for a dead page.
    pending.resolve({ authenticated: true, accountId: 'account-1', displayName: 'Alice' })
    await flushPromises()
    expect(identity.session).toHaveBeenCalledTimes(sessionCalls)
  })

  it('redirects an already authenticated Session without rendering the form', async () => {
    const identity = gateway({ authenticated: true })
    const { wrapper, router } = await mountPage(identity, '/login?returnTo=https://attacker.example/work')

    // 全量套件会并行加载多个 Vue 测试环境，给异步路由守卫保留稳定的调度窗口。
    await vi.waitFor(
      () => expect(router.currentRoute.value.fullPath).toBe('/conversation'),
      { timeout: 5_000 },
    )
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

  // M13b-F07（R09）：SSO 入口是整页导航，query returnTo 活不过去——点击前把白名单内的
  // 目的地停进 sessionStorage；外站/未知目标不停车，跳完落在权限裁决的第一屏。
  it('parks a whitelisted returnTo before the SSO hop and parks nothing for unsafe targets', async () => {
    const safe = gateway({ sessionOverrides: { ssoAuthorizationUrl: '/oauth2/authorization/team-beta' } })
    const { wrapper } = await mountPage(safe, '/login?returnTo=%2Ftoday%3Fteam%3Dteam-1')
    await wrapper.get('[data-testid="login-sso-entry"]').trigger('click')

    const parked = sessionStorage.getItem('crewscope:sso-return:v1')
    expect(parked && JSON.parse(parked).path).toBe('/today?team=team-1')
    wrapper.unmount()
    sessionStorage.clear()

    const unsafe = gateway({ sessionOverrides: { ssoAuthorizationUrl: '/oauth2/authorization/team-beta' } })
    const second = await mountPage(unsafe, '/login?returnTo=https%3A%2F%2Fattacker.example%2Fwork')
    await second.wrapper.get('[data-testid="login-sso-entry"]').trigger('click')
    expect(sessionStorage.getItem('crewscope:sso-return:v1')).toBeNull()
    second.wrapper.unmount()
  })

  it('consumes a parked SSO return when an authenticated session lands on the login page', async () => {
    sessionStorage.setItem('crewscope:sso-return:v1', JSON.stringify({
      path: '/today?team=team-1', principalId: null, savedAt: Date.now(),
    }))
    const identity = gateway({ authenticated: true })
    const { wrapper, router } = await mountPage(identity, '/login')

    await vi.waitFor(
      () => expect(router.currentRoute.value.fullPath).toBe('/today?team=team-1'),
      { timeout: 5_000 },
    )
    expect(sessionStorage.getItem('crewscope:sso-return:v1')).toBeNull()
    wrapper.unmount()
  })

  it('drops a parked SSO return when the password login supersedes the hop', async () => {
    sessionStorage.setItem('crewscope:sso-return:v1', JSON.stringify({
      path: '/today?team=team-1', principalId: null, savedAt: Date.now(),
    }))
    const identity = gateway()
    const { wrapper, router } = await mountPage(identity, '/login?returnTo=%2Faccount')
    await wrapper.get('input[name="identifier"]').setValue('alice@example.com')
    await wrapper.get('input[name="password"]').setValue('one-way-password')

    await wrapper.get('form').trigger('submit')
    await flushPromises()

    await vi.waitFor(() => expect(router.currentRoute.value.fullPath).toBe('/account'), { timeout: 5_000 })
    expect(sessionStorage.getItem('crewscope:sso-return:v1')).toBeNull()
    wrapper.unmount()
  })

  // M13b-F07（R44）：sso-only 且入口未配置是部署状态而非用户死端——说明缺失原因并保留
  // 可操作的重新检查，而不是渲染零可操作元素。
  it('explains the missing SSO entry under sso-only and stays operable', async () => {
    const identity = gateway({ sessionOverrides: { identityPolicy: 'sso-only', ssoAuthorizationUrl: null } })
    const { wrapper } = await mountPage(identity)

    expect(wrapper.find('input[name="password"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="login-sso-entry"]').exists()).toBe(false)
    expect(wrapper.text()).toContain('本部署仅开放单点登录，但登录入口尚未配置。')
    expect(wrapper.text()).toContain('管理员完成身份提供方配置后，回到本页即可进入。')
    const recheck = wrapper.findAll('button').find(button => button.text() === '重新检查')
    expect(recheck).toBeDefined()
    expect(recheck!.attributes('disabled')).toBeUndefined()
    wrapper.unmount()
  })

  // M13b-F07（R48）：入口 URL 里的 registrationId 是部署内唯一可区分 provider 的名字——
  // 具名入口取代统一泛称；形态意外时回退泛称。
  it('names the provider from the entry URL and falls back to the generic label', async () => {
    const named = gateway({ sessionOverrides: { ssoAuthorizationUrl: '/oauth2/authorization/team-beta' } })
    const first = await mountPage(named)
    expect(first.wrapper.get('[data-testid="login-sso-entry"]').text()).toBe('使用 team-beta 企业单点登录进入')
    first.wrapper.unmount()

    const unnamed = gateway({ sessionOverrides: { ssoAuthorizationUrl: '/sso/entry' } })
    const second = await mountPage(unnamed)
    expect(second.wrapper.get('[data-testid="login-sso-entry"]').text()).toBe('使用企业单点登录进入')
    second.wrapper.unmount()
  })

  // M13b-F07（R47）：SSO 共存时键盘用户不再被排除在输入框之外——初始焦点照常落在标识符
  // 输入框，SSO 入口保持 Tab 可达。
  it('focuses the identifier field on mount even when the SSO entry coexists', async () => {
    const identity = gateway({ sessionOverrides: { ssoAuthorizationUrl: '/oauth2/authorization/team-beta' } })
    const { wrapper } = await mountPage(identity)

    expect(document.activeElement).toBe(wrapper.get('input[name="identifier"]').element)
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
  sessionOverrides?: Partial<AuthSession>
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
      return { ...session(authenticated), ...options.sessionOverrides }
    }),
    login,
    logout: vi.fn(),
    register: vi.fn(),
    sessions: vi.fn(async () => []),
    deleteSession: vi.fn(),
  }
}

function session(authenticated = false): AuthSession {
  return {
    authenticated,
    registrationMode: 'OPEN',
    identityPolicy: 'coexist',
    ssoAuthorizationUrl: null,
    csrf: { headerName: 'X-XSRF-TOKEN', parameterName: '_csrf', token: 'csrf-memory-only' },
    account: authenticated ? {
      accountId: 'account-1', username: 'alice', displayName: 'Alice', platformRole: 'USER', securityVersion: 1, version: 1,
      loginIdentities: [{ provider: 'local', status: 'ACTIVE', lastAuthenticatedAt: null }],
    } : null,
    principal: authenticated ? { principalId: 'principal-1', organizationId: 'org-1' } : null,
    organization: null,
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
