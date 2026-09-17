import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory } from 'vue-router'
import { AUTH_PRINCIPAL, permissions, type AuthenticatedPrincipal } from '../app/auth'
import { createCrewScopeRouter } from '../app/router'
import { CrewScopeApiError } from '../api/client'
import { createSetupStore, SETUP_STORE } from '../domains/setup/store'
import type { SetupGateway } from '../domains/setup/gateway'
import type { ConfigurationComponent, ConfigurationHealthItem, ConfigurationHealthView, SetupCapability } from '../domains/setup/types'
import { createScopeStore, SCOPE_STORE } from '../domains/scope/store'
import { fixtureAuthStore } from '../test/authFixtures'
import { FixtureScopeGateway, fixtureIds } from '../test/scopeFixtures'
import SetupPage from './SetupPage.vue'

const principal: AuthenticatedPrincipal = {
  id: fixtureIds.principal,
  displayName: 'Zhang Kaixuan',
  role: 'Team Owner',
  organizationId: fixtureIds.organization,
  organization: 'Test Organization',
  permissions: new Set(Object.values(permissions)),
}

const capabilities: SetupCapability[] = ['PERSONAL_CONVERSATION', 'TEAM_TASK', 'CODING_REVIEW', 'GITHUB_DRAFT_PR', 'LARK_NOTIFICATIONS', 'TEAM_OBSERVER']

function healthItem(component: ConfigurationComponent, overrides: Partial<ConfigurationHealthItem> = {}): ConfigurationHealthItem {
  return { component, status: 'READY', reasonCode: 'READY', responsibleParty: 'Team 管理员', actionKey: null, ...overrides }
}

function healthView(items: ConfigurationHealthItem[], overallStatus: ConfigurationHealthView['overallStatus'] = 'READY'): ConfigurationHealthView {
  return { scope: { organizationId: fixtureIds.organization, teamId: fixtureIds.teamPlatform }, observedAt: '2026-09-01T00:00:00Z', overallStatus, items }
}

describe('SetupPage', () => {
  it('reports the four configuration-health components in member language', async () => {
    const { wrapper } = await mountPage(gatewayWithHealth([
      healthItem('AGENT_CONFIGURATION'),
      healthItem('MODEL_CONNECTION', { status: 'ACTION_REQUIRED', reasonCode: 'MODEL_CONNECTION_REQUIRED', actionKey: 'OPEN_MODEL_SETTINGS' }),
      healthItem('CREDENTIAL', { status: 'ACTION_REQUIRED', reasonCode: 'CREDENTIAL_EXPIRING' }),
      healthItem('INTEGRATION'),
    ], 'ACTION_REQUIRED'))

    const text = wrapper.text()
    expect(text).toContain('配置健康 · 四项组件')
    for (const label of ['Agent 配置', '模型连接', '凭证可用性', '集成连接']) expect(text).toContain(label)
    // The reason is stated in member language; the enum constant itself stays behind the boundary.
    expect(text).toContain('Agent 还没有可用的模型连接')
    expect(text).toContain('模型凭证即将过期，请尽快轮换')
    expect(text).not.toContain('MODEL_CONNECTION_REQUIRED')
    expect(text).not.toContain('CREDENTIAL_EXPIRING')
  })

  it('sends the member to the settings page the health projection names', async () => {
    const { wrapper, router } = await mountPage(gatewayWithHealth([
      healthItem('AGENT_CONFIGURATION'),
      healthItem('MODEL_CONNECTION', { status: 'ACTION_REQUIRED', reasonCode: 'MODEL_CONNECTION_UNHEALTHY', actionKey: 'OPEN_MODEL_SETTINGS' }),
      healthItem('CREDENTIAL'),
      healthItem('INTEGRATION'),
    ], 'ACTION_REQUIRED'))

    const action = wrapper.findAll('button').find(button => button.text().trim() === '配置模型与凭证')!
    expect(action).toBeTruthy()
    await action.trigger('click')
    // The target page is lazily imported, so the navigation settles a macrotask after the click.
    await vi.waitFor(() => expect(router.currentRoute.value.name).toBe('model-settings'))

    expect(router.currentRoute.value.query.team).toBe(fixtureIds.teamPlatform)
  })

  it('states an unavailable or failing projection instead of showing an empty component list', async () => {
    const withoutProjection = await mountPage({ getReadiness: async scope => readiness(scope) })
    expect(withoutProjection.wrapper.text()).toContain('当前部署没有提供配置健康投影')
    expect(withoutProjection.wrapper.text()).not.toContain('配置健康 · 四项组件')

    const failing = await mountPage({
      getReadiness: async scope => readiness(scope),
      getConfigurationHealth: async () => { throw new CrewScopeApiError(0, { code: 'error', message: 'Network down', correlationId: 'c-1', retryable: true, currentVersion: null, details: {} }) },
    })
    expect(failing.wrapper.text()).toContain('配置健康当前离线：恢复网络后可继续读取。')
  })

  it('refreshes readiness and health together', async () => {
    const { wrapper, gateway } = await mountPage(gatewayWithHealth([healthItem('AGENT_CONFIGURATION'), healthItem('MODEL_CONNECTION'), healthItem('CREDENTIAL'), healthItem('INTEGRATION')]))

    await wrapper.findAll('button').find(button => button.text().includes('刷新事实'))!.trigger('click')
    await flushPromises()

    expect(gateway.getReadiness).toHaveBeenCalledTimes(2)
    expect(gateway.getConfigurationHealth).toHaveBeenCalledTimes(2)
  })
})

function readiness(scope: { organizationId: string, teamId: string }) {
  return {
    scope,
    snapshotVersion: 'v1',
    observedAt: '2026-09-01T00:00:00Z',
    requiredReady: false,
    capabilities: capabilities.map(capability => ({
      capability, required: capability === 'TEAM_TASK', status: 'ACTION_REQUIRED' as const,
      reasonCode: `capability-${capability}`, canConfigure: false, responsibleParty: 'Team 管理员', actionKey: null,
    })),
  }
}

function gatewayWithHealth(items: ConfigurationHealthItem[], overallStatus: ConfigurationHealthView['overallStatus'] = 'READY') {
  return {
    getReadiness: vi.fn(async (scope: { organizationId: string, teamId: string }) => readiness(scope)),
    getConfigurationHealth: vi.fn(async (scope: { organizationId: string, teamId: string }) => ({ ...healthView(items, overallStatus), scope })),
  } satisfies SetupGateway
}

async function mountPage(gateway: SetupGateway) {
  const router = createCrewScopeRouter(createMemoryHistory(), fixtureAuthStore(principal))
  const scopeStore = createScopeStore(new FixtureScopeGateway(), principal)
  await scopeStore.synchronize(fixtureIds.teamPlatform, null)
  await router.push(`/setup?team=${fixtureIds.teamPlatform}`)
  await router.isReady()
  const store = createSetupStore(gateway)
  const wrapper = mount(SetupPage, {
    global: {
      plugins: [router],
      provide: {
        [AUTH_PRINCIPAL as symbol]: principal,
        [SCOPE_STORE as symbol]: scopeStore,
        [SETUP_STORE as symbol]: store,
      },
    },
  })
  await flushPromises()
  return { wrapper, router, gateway, store }
}
