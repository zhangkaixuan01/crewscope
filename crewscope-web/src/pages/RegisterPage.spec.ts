import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory } from 'vue-router'
import { CrewScopeApiError } from '../api/client'
import { createCrewScopeRouter } from '../app/router'
import { F05_EPOCH_KEY } from '../app/f05Storage'
import { IDENTITY_GATEWAY, type IdentityGateway } from '../domains/identity/gateway'
import type { AuthSession, RegistrationResult } from '../domains/identity/types'
import { createAuthStore, AUTH_STORE } from '../domains/identity/store'
import RegisterPage from './RegisterPage.vue'

describe('RegisterPage', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
    Object.defineProperty(window.navigator, 'onLine', { configurable: true, value: true })
    localStorage.clear()
    sessionStorage.clear()
  })

  it('renders native registration semantics and sends an open registration to Onboarding', async () => {
    const identity = gateway()
    const { wrapper, router } = await mountPage(identity)

    expect(wrapper.get('input[name="username"]').attributes('autocomplete')).toBe('username')
    expect(wrapper.get('input[name="email"]').attributes('autocomplete')).toBe('email')
    expect(wrapper.get('input[name="displayName"]').attributes('autocomplete')).toBe('name')
    expect(wrapper.get('input[name="password"]').attributes('autocomplete')).toBe('new-password')
    expect(wrapper.text()).toContain('密码长度要求为 12 至 128 个字符')
    await fillValidForm(wrapper)
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(identity.register).toHaveBeenCalledOnce()
    expect(identity.register).toHaveBeenCalledWith({
      username: 'alice', email: 'alice@example.com', displayName: 'Alice', password: 'correct horse battery staple',
    }, session().csrf, expect.any(String), expect.any(AbortSignal))
    await vi.waitFor(() => expect(router.currentRoute.value.fullPath).toBe('/onboarding'), { timeout: 5_000 })
    // The scoped-namespace epoch marker is device-level metadata, not persisted state.
    expect(Object.keys(localStorage).filter(key => key !== F05_EPOCH_KEY)).toHaveLength(0)
    expect(sessionStorage).toHaveLength(0)
    wrapper.unmount()
  })

  it('consumes an invitation Fragment, clears it and skips Onboarding after atomic registration', async () => {
    const token = 'A'.repeat(43)
    const identity = gateway({ invited: true, mode: 'INVITE_ONLY' })
    const { wrapper, router } = await mountPage(identity, `/register#token=${token}`)

    expect(router.currentRoute.value.hash).toBe('')
    expect(wrapper.text()).toContain('已安全载入团队邀请')
    expect(wrapper.text()).not.toContain(token)
    await fillValidForm(wrapper)
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(identity.register).toHaveBeenCalledWith(
      expect.objectContaining({ invitationToken: token }),
      session(false, 'INVITE_ONLY').csrf,
      expect.any(String),
      expect.any(AbortSignal),
    )
    // A07: the committed teamId lands the new member directly on their joined Team.
    await vi.waitFor(() => expect(router.currentRoute.value.fullPath).toBe('/conversation?team=team-1'), { timeout: 5_000 })
    wrapper.unmount()
  })

  it.each([
    ['INVITE_ONLY', '通过团队邀请加入 CrewScope'],
    ['DISABLED', '当前部署未开放新账号'],
  ] as const)('fails closed without a form in %s mode', async (mode, message) => {
    const identity = gateway({ mode })
    const { wrapper } = await mountPage(identity)

    expect(wrapper.text()).toContain(message)
    expect(wrapper.find('form').exists()).toBe(false)
    expect(identity.register).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('rejects malformed invitation context without exposing its value', async () => {
    const identity = gateway()
    const { wrapper, router } = await mountPage(identity, '/register#token=private-invalid-token')

    expect(router.currentRoute.value.hash).toBe('')
    expect(wrapper.text()).toContain('无法继续这次邀请注册')
    expect(wrapper.text()).not.toContain('private-invalid-token')
    expect(wrapper.find('form').exists()).toBe(false)
    wrapper.unmount()
  })

  it('uses one non-enumerating conflict message and never renders server details', async () => {
    const identity = gateway({ registerError: apiError('registration_conflict', 'email alice@example.com already exists: private') })
    const { wrapper } = await mountPage(identity)
    await fillValidForm(wrapper)

    await wrapper.get('form').trigger('submit')
    await flushPromises()

    const alert = wrapper.get('[role="alert"]')
    expect(alert.text()).toContain('用户名或邮箱暂不可用，请修改后重试。')
    expect(alert.text()).not.toMatch(/alice@example.com|已存在|private/)
    expect(document.activeElement).toBe(alert.element)
    wrapper.unmount()
  })

  it('submits on HTTP with getRandomValues when randomUUID is unavailable', async () => {
    const getRandomValues = globalThis.crypto.getRandomValues.bind(globalThis.crypto)
    vi.stubGlobal('crypto', { getRandomValues })
    const identity = gateway()
    const { wrapper } = await mountPage(identity)
    await fillValidForm(wrapper)
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(identity.register).toHaveBeenCalledOnce()
    expect(vi.mocked(identity.register).mock.calls[0]?.[2]).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/)
    wrapper.unmount()
  })

  it('explains missing secure randomness without sending registration', async () => {
    const identity = gateway()
    const { wrapper } = await mountPage(identity)
    await fillValidForm(wrapper)
    vi.stubGlobal('crypto', undefined)
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(identity.register).not.toHaveBeenCalled()
    expect(wrapper.get('[role="alert"]').text()).toContain('本次操作尚未发送')
    wrapper.unmount()
  })

  it('keeps a 503 outcome unknown and preserves the old key across normalized edits', async () => {
    const identity = gateway({ registerError: apiError('registration_unavailable', 'private upstream text') })
    const { wrapper } = await mountPage(identity)
    await fillValidForm(wrapper)
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(wrapper.get('[role="alert"]').text()).toContain('注册结果尚未确认')
    const key = vi.mocked(identity.register).mock.calls[0]?.[2]
    await wrapper.get('input[name="displayName"]').setValue('Bob')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    await wrapper.get('input[name="displayName"]').setValue(' Alice ')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(vi.mocked(identity.register).mock.calls[1]?.[2]).not.toBe(key)
    expect(vi.mocked(identity.register).mock.calls[2]?.[2]).toBe(key)
    expect(localStorage.length).toBe(0)
    expect(sessionStorage.length).toBe(0)
    wrapper.unmount()
  })

  it('only refreshes the Session after a successful receipt instead of registering twice', async () => {
    const identity = gateway()
    const { wrapper } = await mountPage(identity)
    await fillValidForm(wrapper)
    vi.mocked(identity.session).mockRejectedValueOnce(new Error('session unavailable'))
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(wrapper.get('[role="alert"]').text()).toContain('登录会话尚未恢复')
    expect(wrapper.get('input[name="password"]').element).toHaveProperty('value', '')
    expect(wrapper.get('input[name="username"]').attributes('disabled')).toBeDefined()
    for (const name of ['username', 'email', 'displayName', 'password']) {
      const field = wrapper.get(`input[name="${name}"]`)
      const descriptions = field.attributes('aria-describedby')!.split(' ')
        .map(id => wrapper.get(`[id="${id}"]`).text()).join(' ')
      expect(descriptions).toContain('账号已创建')
    }
    expect(wrapper.get('button[type="submit"]').text()).toContain('继续登录')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(identity.register).toHaveBeenCalledOnce()
    wrapper.unmount()
  })

  it('blocks duplicate submissions and reuses the idempotency key for Session recovery', async () => {
    const first = deferred<RegistrationResult>()
    const identity = gateway({ registerPromise: first.promise })
    const { wrapper } = await mountPage(identity)
    await fillValidForm(wrapper)

    await wrapper.get('form').trigger('submit')
    await wrapper.get('form').trigger('submit')
    expect(identity.register).toHaveBeenCalledOnce()
    const firstKey = vi.mocked(identity.register).mock.calls[0]?.[2]
    first.reject(apiError('registration_session_unavailable', 'committed private'))
    await flushPromises()
    expect(wrapper.get('[role="alert"]').text()).toContain('登录会话尚未恢复')

    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(identity.register).toHaveBeenCalledTimes(2)
    expect(vi.mocked(identity.register).mock.calls[1]?.[2]).toBe(firstKey)
    wrapper.unmount()
  })
})

async function mountPage(identity: IdentityGateway, initialRoute = '/register') {
  const authStore = createAuthStore(identity, { channelFactory: () => null })
  const router = createCrewScopeRouter(createMemoryHistory(), authStore)
  await router.push(initialRoute)
  await router.isReady()
  const wrapper = mount(RegisterPage, {
    attachTo: document.body,
    global: { plugins: [router], provide: { [IDENTITY_GATEWAY as symbol]: identity, [AUTH_STORE as symbol]: authStore } },
  })
  await flushPromises()
  return { wrapper, router }
}

async function fillValidForm(wrapper: ReturnType<typeof mount>) {
  await wrapper.get('input[name="username"]').setValue(' alice ')
  await wrapper.get('input[name="email"]').setValue(' alice@example.com ')
  await wrapper.get('input[name="displayName"]').setValue(' Alice ')
  await wrapper.get('input[name="password"]').setValue('correct horse battery staple')
}

function gateway(options: {
  mode?: AuthSession['registrationMode']
  invited?: boolean
  registerError?: unknown
  registerPromise?: Promise<RegistrationResult>
} = {}): IdentityGateway {
  let authenticated = false
  return {
    session: vi.fn(async () => session(authenticated, options.mode)),
    login: vi.fn(),
    logout: vi.fn(),
    register: vi.fn(async () => {
      if (options.registerError) throw options.registerError
      const result = await (options.registerPromise ?? registrationResult(Boolean(options.invited)))
      authenticated = true
      return result
    }),
  }
}

function session(authenticated = false, mode: AuthSession['registrationMode'] = 'OPEN'): AuthSession {
  return {
    authenticated,
    registrationMode: mode,
    csrf: { headerName: 'X-XSRF-TOKEN', parameterName: '_csrf', token: 'csrf-memory-only' },
    account: authenticated ? {
      accountId: 'account-1', username: 'alice', displayName: 'Alice', platformRole: 'USER', securityVersion: 1, version: 1,
    } : null,
    principal: authenticated ? { principalId: 'principal-1', organizationId: 'organization-1' } : null,
    teams: authenticated ? [{ teamId: 'team-1', name: 'Platform', memberId: 'member-1', permissions: ['scope:read', 'conversation:use'] }] : [],
    permissions: authenticated ? ['scope:read', 'conversation:use'] : [],
  }
}

function registrationResult(invited: boolean): RegistrationResult {
  return {
    accountId: 'account-1', principalId: 'principal-1', organizationId: 'organization-1',
    teamId: invited ? 'team-1' : null, memberId: invited ? 'member-1' : null,
    onboardingRequired: !invited, commandId: 'command-1', domainEventId: 'event-1',
    committedVersion: 1, correlationId: 'correlation-1', replayed: false,
  }
}

function apiError(code: string, message: string): CrewScopeApiError {
  return new CrewScopeApiError(code === 'registration_conflict' ? 409 : 503, {
    code, message, correlationId: 'safe-correlation', retryable: true, currentVersion: null, details: {},
  })
}

function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason: unknown) => void
  const promise = new Promise<T>((accept, decline) => { resolve = accept; reject = decline })
  return { promise, resolve, reject }
}
