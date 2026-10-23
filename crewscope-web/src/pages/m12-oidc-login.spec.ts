import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory } from 'vue-router'
import { CrewScopeApiError } from '../api/client'
import { createCrewScopeRouter } from '../app/router'
import { IDENTITY_GATEWAY, type IdentityGateway } from '../domains/identity/gateway'
import {
  presentLoginProblem,
  presentSsoReturnProblem,
} from '../domains/identity/presentation'
import type { AuthSession, IdentityPolicy, LoginResult } from '../domains/identity/types'
import { createAuthStore, AUTH_STORE } from '../domains/identity/store'
import LoginPage from './LoginPage.vue'

/**
 * M12-F03 C1: the OIDC login entry rides on two facts the session endpoint already carries —
 * `ssoAuthorizationUrl` (framework entry, full-page navigation) and `identityPolicy`
 * (local-only / sso-only / coexist). The `sso_error` return codes are a fixed server enum;
 * the raw query value must never reach the DOM.
 */
describe('LoginPage OIDC entry (M12-F03)', () => {
  it('renders the SSO entry beside the local form on coexist deployments', async () => {
    const { wrapper } = await mountPage(gateway({ policy: 'coexist', ssoUrl: '/oauth2/authorization/team-beta' }))

    const entry = wrapper.get('a[data-testid="login-sso-entry"]')
    expect(entry.attributes('href')).toBe('/oauth2/authorization/team-beta')
    // M13b-F07（R48）：registrationId 具名入口——team-beta 是部署内唯一可区分的名字。
    expect(entry.text()).toContain('使用 team-beta 企业单点登录进入')
    expect(wrapper.text()).toContain('或使用账号密码')
    expect(wrapper.find('input[name="identifier"]').exists()).toBe(true)
    wrapper.unmount()
  })

  it('keeps the M11 local-only shape when no OIDC registration exists', async () => {
    const { wrapper } = await mountPage(gateway({ policy: 'coexist', ssoUrl: null }))

    expect(wrapper.find('a[data-testid="login-sso-entry"]').exists()).toBe(false)
    expect(wrapper.find('input[name="identifier"]').exists()).toBe(true)
    expect(wrapper.text()).not.toContain('或使用账号密码')
    wrapper.unmount()
  })

  it('hides the local password form on sso-only deployments and points to rescue', async () => {
    const { wrapper } = await mountPage(gateway({ policy: 'sso-only', ssoUrl: '/oauth2/authorization/team-beta' }))

    expect(wrapper.find('form').exists()).toBe(false)
    expect(wrapper.get('a[data-testid="login-sso-entry"]').attributes('href')).toBe('/oauth2/authorization/team-beta')
    expect(wrapper.text()).toContain('本部署仅支持企业单点登录')
    expect(wrapper.text()).toContain('请联系你的管理员恢复访问')
    expect(wrapper.find('a[href="/register"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it.each([
    ['denied', '单点登录未能完成'],
    ['locked', '这个账号已被登录防护暂时锁定'],
    ['registration_blocked', '当前部署不接受新的单点登录账号'],
    ['unavailable', '登录基础设施暂时不可用'],
  ])('presents the fixed sso_error=%s banner without echoing the raw value', async (code, expected) => {
    const { wrapper, router } = await mountPage(
      gateway({ policy: 'coexist', ssoUrl: '/oauth2/authorization/team-beta' }),
      `/login?sso_error=${encodeURIComponent(code)}`,
    )

    const alert = wrapper.get('[role="alert"]')
    expect(alert.text()).toContain(expected)
    // The code is scrubbed from the address bar so a refresh or shared link cannot replay it.
    await vi.waitFor(() => expect(router.currentRoute.value.query.sso_error).toBeUndefined())
    wrapper.unmount()
  })

  it('folds an unknown sso_error value into the generic denial', async () => {
    const { wrapper } = await mountPage(
      gateway({ policy: 'coexist', ssoUrl: null }),
      '/login?sso_error=%3Cscript%3Ealert(1)%3C%2Fscript%3E',
    )

    const alert = wrapper.get('[role="alert"]')
    expect(alert.text()).toContain('单点登录未能完成')
    expect(alert.text()).not.toContain('<script>')
    wrapper.unmount()
  })

  it('maps the sso-only 403 login code to actionable copy', () => {
    const problem = presentLoginProblem(apiError(403, 'local_login_disabled', 'identity policy private'))

    expect(problem.title).toBe('账号密码登录已停用')
    expect(problem.message).toContain('企业单点登录')
    expect(problem.message).not.toContain('private')
  })

  it('presents every fixed sso_error return code from the server enum', () => {
    expect(presentSsoReturnProblem('denied').title).toBe('单点登录未能完成')
    expect(presentSsoReturnProblem('locked').title).toBe('账号暂时无法登录')
    expect(presentSsoReturnProblem('registration_blocked').title).toBe('本部署未开放单点登录注册')
    expect(presentSsoReturnProblem('unavailable').title).toBe('登录服务暂时不可用')
    expect(presentSsoReturnProblem('anything-else').title).toBe('单点登录未能完成')
  })
})

async function mountPage(identity: IdentityGateway, initialRoute = '/login') {
  const authStore = createAuthStore(identity, { channelFactory: () => null })
  const router = createCrewScopeRouter(createMemoryHistory(), authStore)
  await router.push(initialRoute)
  await router.isReady()
  const wrapper = mount(LoginPage, {
    attachTo: document.body,
    global: { plugins: [router], provide: { [IDENTITY_GATEWAY as symbol]: identity, [AUTH_STORE as symbol]: authStore } },
  })
  await flushPromises()
  return { wrapper, router }
}

function gateway(options: { policy: IdentityPolicy, ssoUrl: string | null }): IdentityGateway {
  return {
    session: vi.fn(async () => session(options.policy, options.ssoUrl)),
    login: vi.fn(async (): Promise<LoginResult> => ({ authenticated: true, accountId: 'account-1', displayName: 'Alice' })),
    logout: vi.fn(),
    register: vi.fn(),
    sessions: vi.fn(async () => []),
    deleteSession: vi.fn(),
  }
}

function session(policy: IdentityPolicy, ssoUrl: string | null): AuthSession {
  return {
    authenticated: false,
    registrationMode: 'OPEN',
    identityPolicy: policy,
    ssoAuthorizationUrl: ssoUrl,
    csrf: { headerName: 'X-XSRF-TOKEN', parameterName: '_csrf', token: 'csrf-memory-only' },
    account: null,
    principal: null,
    organization: null,
    teams: [],
    permissions: [],
  }
}

function apiError(status: number, code: string, message: string): CrewScopeApiError {
  return new CrewScopeApiError(status, {
    code, message, correlationId: 'safe-correlation', retryable: status >= 429, currentVersion: null, details: {},
  })
}
