import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory } from 'vue-router'
import { AUTH_PRINCIPAL, permissions, type AuthenticatedPrincipal } from '../app/auth'
import { createCrewScopeRouter } from '../app/router'
import { CrewScopeApiError } from '../api/client'
import { createSetupStore, SETUP_STORE } from '../domains/setup/store'
import type { SetupGateway } from '../domains/setup/gateway'
import type { ConfigurationComponent, ConfigurationHealthItem, ConfigurationHealthView, SetupCapability, SetupReadinessItem } from '../domains/setup/types'
import { createScopeStore, SCOPE_STORE } from '../domains/scope/store'
import { fixtureAuthStore } from '../test/authFixtures'
import { FixtureScopeGateway, fixtureIds } from '../test/scopeFixtures'
import SetupPage from './SetupPage.vue'

const principal: AuthenticatedPrincipal = {
  id: fixtureIds.principal,
  accountId: '00000000-0000-0000-0000-000000000201',
  displayName: 'Zhang Kaixuan',
  role: 'Team Owner',
  organizationId: fixtureIds.organization,
  organization: 'Test Organization',
  permissions: new Set(Object.values(permissions)),
}

function capabilityItem(capability: SetupCapability, overrides: Partial<SetupReadinessItem> = {}): SetupReadinessItem {
  return {
    capability, required: false, status: 'READY', reasonCode: 'READY', canConfigure: false,
    responsibleParty: 'Team 管理员', actionKey: null, ...overrides,
  }
}

function healthItem(component: ConfigurationComponent, overrides: Partial<ConfigurationHealthItem> = {}): ConfigurationHealthItem {
  return { component, status: 'READY', reasonCode: 'READY', responsibleParty: 'Team 管理员', actionKey: null, ...overrides }
}

function healthView(items: ConfigurationHealthItem[], overallStatus: ConfigurationHealthView['overallStatus'] = 'READY'): ConfigurationHealthView {
  return { scope: { organizationId: fixtureIds.organization, teamId: fixtureIds.teamPlatform }, observedAt: '2026-09-01T00:00:00Z', overallStatus, items }
}

/*
 * F02 场景：Personal 对话侧只差 Team Agent，Coding 侧只差执行默认值——两目标各自聚合
 * 自己的缺口并给出不同的下一步，这正是目标导向 setup 的核心行为。
 */
const goalFixture: SetupReadinessItem[] = [
  capabilityItem('PERSONAL_CONVERSATION', { required: true }),
  capabilityItem('TEAM_TASK', { required: true, status: 'ACTION_REQUIRED', reasonCode: 'TEAM_AGENT_CONFIGURATION_REQUIRED', canConfigure: true, actionKey: 'OPEN_AGENT_SETTINGS' }),
  capabilityItem('CODING_REVIEW', { required: true, status: 'ACTION_REQUIRED', reasonCode: 'EXECUTION_DEFAULTS_REQUIRED', canConfigure: true, actionKey: 'OPEN_EXECUTION_DEFAULTS' }),
  capabilityItem('GITHUB_DRAFT_PR'),
  capabilityItem('LARK_NOTIFICATIONS'),
  capabilityItem('TEAM_OBSERVER'),
]

function readiness(items: SetupReadinessItem[], requiredReady = false) {
  return (scope: { organizationId: string, teamId: string }) => ({
    scope, snapshotVersion: 'v1', observedAt: '2026-09-01T00:00:00Z', requiredReady, capabilities: items,
  })
}

function gatewayWith(items: SetupReadinessItem[], health?: ConfigurationHealthItem[], overallStatus: ConfigurationHealthView['overallStatus'] = 'READY') {
  return {
    getReadiness: vi.fn(async (scope: { organizationId: string, teamId: string }) => readiness(items)(scope)),
    getConfigurationHealth: vi.fn(async (scope: { organizationId: string, teamId: string }) => ({ ...healthView(health ?? [], overallStatus), scope })),
  } satisfies SetupGateway
}

describe('SetupPage', () => {
  it('aggregates each goal card from its own capability gaps and offers its next step', async () => {
    const { wrapper } = await mountPage(gatewayWith(goalFixture, [healthItem('AGENT_CONFIGURATION'), healthItem('MODEL_CONNECTION'), healthItem('CREDENTIAL'), healthItem('INTEGRATION')]))

    const text = wrapper.text()
    expect(text).toContain('先开始对话')
    expect(text).toContain('先开始 Coding')
    const conversation = wrapper.findAll('article').find(article => article.text().includes('先开始对话'))!
    const coding = wrapper.findAll('article').find(article => article.text().includes('先开始 Coding'))!
    // 每张卡只聚合自己的能力：对话侧 1 缺口、Coding 侧 1 缺口。
    expect(conversation.text()).toContain('还需 1 项')
    expect(coding.text()).toContain('还需 1 项')
    expect(conversation.text()).toContain('Team Agent 尚未完成模型配置')
    expect(conversation.text()).toContain('配置 Agent')
    expect(coding.text()).toContain('项目执行默认值缺少可用的仓库绑定或构建方案')
    expect(coding.text()).toContain('补配执行默认值')
  })

  it('keeps goal selection immediate and un-persisted', async () => {
    const { wrapper } = await mountPage(gatewayWith(goalFixture))
    const toggle = wrapper.findAll('button').find(button => button.text().trim() === '选择此目标')!
    expect(toggle.attributes('aria-pressed')).toBe('false')
    await toggle.trigger('click')
    expect(toggle.attributes('aria-pressed')).toBe('true')
    expect(toggle.text()).toContain('已选中')
    await toggle.trigger('click')
    expect(toggle.attributes('aria-pressed')).toBe('false')
  })

  it('states the migration-trial boundary on the entry card without promising unsupported paths', async () => {
    const { wrapper } = await mountPage(gatewayWith(goalFixture))
    const card = wrapper.findAll('section').find(section => section.text().includes('从现有系统迁移试用'))!

    // F02 关闭条件之一：指南摘要必须把不支持的能力说清楚，而不是只报喜。
    expect(card.text()).toContain('不强填进 Key 字段')
    expect(card.text()).toContain('不伪造历史、不做双向同步、没有批量导入')
    expect(card.text()).toContain('本机 CLI 深度集成与附件批量迁移未在当前范围')
    expect(card.text()).toContain('docs/guides/M9b-单项目迁移试用指南.md')
  })

  it('lands the execution-defaults gap on repository settings registered as the setup origin', async () => {
    const { wrapper, router } = await mountPage(gatewayWith(goalFixture))
    const coding = wrapper.findAll('article').find(article => article.text().includes('先开始 Coding'))!
    await coding.findAll('button').find(button => button.text().includes('补配执行默认值'))!.trigger('click')
    await vi.waitFor(() => expect(router.currentRoute.value.name).toBe('repository-settings'))
    expect(router.currentRoute.value.query.from).toBe('setup')
    expect(router.currentRoute.value.query.team).toBe(fixtureIds.teamPlatform)
  })

  it('sends the WorkProject gap to Today with a one-shot create intent', async () => {
    const items = goalFixture.map(entry => entry.capability === 'CODING_REVIEW'
      ? { ...entry, reasonCode: 'WORKPROJECT_REQUIRED', actionKey: 'OPEN_WORKPROJECT_SETTINGS' }
      : entry)
    const { wrapper, router } = await mountPage(gatewayWith(items))
    const checklist = wrapper.findAll('article').find(article => article.text().includes('Coding & Review'))!
    await checklist.findAll('button').find(button => button.text().includes('创建 WorkProject'))!.trigger('click')
    await vi.waitFor(() => expect(router.currentRoute.value.name).toBe('today'))
    expect(router.currentRoute.value.query.intent).toBe('create-project')
    expect(router.currentRoute.value.query.team).toBe(fixtureIds.teamPlatform)
  })

  it('folds ready capabilities behind a summary row', async () => {
    const { wrapper } = await mountPage(gatewayWith(goalFixture))
    expect(wrapper.text()).not.toContain('Personal Conversation')
    const expand = wrapper.findAll('button').find(button => button.text().includes('显示已就绪能力（4）'))!
    await expand.trigger('click')
    expect(wrapper.text()).toContain('Personal Conversation')
    expect(wrapper.findAll('button').some(button => button.text().includes('收起已就绪能力'))).toBe(true)
  })

  it('collapses the four configuration-health components until asked for', async () => {
    const { wrapper } = await mountPage(gatewayWith(goalFixture, [
      healthItem('AGENT_CONFIGURATION'),
      healthItem('MODEL_CONNECTION', { status: 'ACTION_REQUIRED', reasonCode: 'MODEL_CONNECTION_REQUIRED', actionKey: 'OPEN_MODEL_SETTINGS' }),
      healthItem('CREDENTIAL', { status: 'ACTION_REQUIRED', reasonCode: 'CREDENTIAL_EXPIRING' }),
      healthItem('INTEGRATION'),
    ], 'ACTION_REQUIRED'))

    // 默认收起：只见整体状态，不见组件明细（L07：健康诊断按需展开）。
    expect(wrapper.text()).toContain('配置健康 · 四项组件')
    expect(wrapper.text()).not.toContain('Agent 还没有可用的模型连接')
    const expand = wrapper.findAll('button').find(button => button.text().trim() === '展开')!
    expect(expand.attributes('aria-expanded')).toBe('false')
    await expand.trigger('click')
    const text = wrapper.text()
    for (const label of ['Agent 配置', '模型连接', '凭证可用性', '集成连接']) expect(text).toContain(label)
    // The reason is stated in member language; the enum constant itself stays behind the boundary.
    expect(text).toContain('Agent 还没有可用的模型连接')
    expect(text).toContain('模型凭证即将过期，请尽快轮换')
    expect(text).not.toContain('MODEL_CONNECTION_REQUIRED')
    expect(text).not.toContain('CREDENTIAL_EXPIRING')
  })

  it('sends the member to the settings page the health projection names', async () => {
    const { wrapper, router } = await mountPage(gatewayWith(goalFixture, [
      healthItem('AGENT_CONFIGURATION'),
      healthItem('MODEL_CONNECTION', { status: 'ACTION_REQUIRED', reasonCode: 'MODEL_CONNECTION_UNHEALTHY', actionKey: 'OPEN_MODEL_SETTINGS' }),
      healthItem('CREDENTIAL'),
      healthItem('INTEGRATION'),
    ], 'ACTION_REQUIRED'))
    await wrapper.findAll('button').find(button => button.text().trim() === '展开')!.trigger('click')

    const action = wrapper.findAll('button').find(button => button.text().trim() === '配置模型与凭证')!
    expect(action).toBeTruthy()
    await action.trigger('click')
    // The target page is lazily imported, so the navigation settles a macrotask after the click.
    await vi.waitFor(() => expect(router.currentRoute.value.name).toBe('model-settings'))

    expect(router.currentRoute.value.query.team).toBe(fixtureIds.teamPlatform)
  })

  it('keeps refresh as the secondary action while configuration stays primary', async () => {
    const { wrapper } = await mountPage(gatewayWith(goalFixture))
    const refresh = wrapper.findAll('button').find(button => button.text().includes('刷新事实'))!
    expect(refresh.classes()).toContain('base-button--secondary')
    const primary = wrapper.findAll('button').find(button => button.text().includes('配置 Agent'))!
    expect(primary.classes()).not.toContain('base-button--secondary')
  })

  it('offers a direct entry once a whole goal is ready', async () => {
    const allReady = goalFixture.map(entry => ({ ...entry, status: 'READY' as const, reasonCode: 'READY', actionKey: null, canConfigure: false }))
    const { wrapper } = await mountPage(gatewayWith(allReady, undefined))
    const conversation = wrapper.findAll('article').find(article => article.text().includes('先开始对话'))!
    expect(conversation.text()).toContain('可直接开始')
    expect(conversation.text()).toContain('进入对话')
  })

  it('states an unavailable or failing projection instead of showing an empty component list', async () => {
    const withoutProjection = await mountPage({ getReadiness: vi.fn(async (scope: { organizationId: string, teamId: string }) => readiness(goalFixture)(scope)) })
    expect(withoutProjection.wrapper.text()).toContain('当前部署没有提供配置健康投影')
    expect(withoutProjection.wrapper.text()).not.toContain('配置健康 · 四项组件')

    const failing = await mountPage({
      getReadiness: vi.fn(async (scope: { organizationId: string, teamId: string }) => readiness(goalFixture)(scope)),
      getConfigurationHealth: vi.fn(async () => { throw new CrewScopeApiError(0, { code: 'error', message: 'Network down', correlationId: 'c-1', retryable: true, currentVersion: null, details: {} }) }),
    })
    expect(failing.wrapper.text()).toContain('配置健康当前离线：恢复网络后可继续读取。')
  })

  it('refreshes readiness and health together', async () => {
    const { wrapper, gateway } = await mountPage(gatewayWith(goalFixture, [healthItem('AGENT_CONFIGURATION'), healthItem('MODEL_CONNECTION'), healthItem('CREDENTIAL'), healthItem('INTEGRATION')]))

    await wrapper.findAll('button').find(button => button.text().includes('刷新事实'))!.trigger('click')
    await flushPromises()

    expect(gateway.getReadiness).toHaveBeenCalledTimes(2)
    expect(gateway.getConfigurationHealth).toHaveBeenCalledTimes(2)
  })
})

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
