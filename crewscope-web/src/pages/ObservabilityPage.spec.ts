import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory } from 'vue-router'
import { AUTH_PRINCIPAL, permissions, type AuthenticatedPrincipal } from '../app/auth'
import { createCrewScopeRouter } from '../app/router'
import { fixtureAuthStore } from '../test/authFixtures'
import type { SettingsScope } from '../domains/settings/types'
import type { ObservabilityGateway } from '../domains/observability/gateway'
import { createObservabilityStore, OBSERVABILITY_STORE } from '../domains/observability/store'
import type {
  ObservabilityCostMonth,
  ObservabilityCostMonthDetail,
  ObservabilityCostMonthsPage,
  ObservabilityQualityMonth,
} from '../domains/observability/types'
import { createScopeStore, SCOPE_STORE } from '../domains/scope/store'
import { FixtureScopeGateway, fixtureIds } from '../test/scopeFixtures'
import ObservabilityPage from './ObservabilityPage.vue'

const member: AuthenticatedPrincipal = {
  id: fixtureIds.principal, accountId: '00000000-0000-0000-0000-000000000201', displayName: 'Platform Member', role: 'Member',
  organizationId: fixtureIds.organization, organization: 'Test Organization',
  permissions: new Set([permissions.scopeRead]),
}

describe('ObservabilityPage', () => {
  it('renders the newest month with its cost lanes, currency table, detail rows and both quality cards', async () => {
    const { wrapper } = await mountPage('')

    const workspace = wrapper.get('.observability-workspace')
    expect(workspace.text()).toContain('2026-09 成本概览')
    // The three source lanes name themselves; the totals stay token + call count.
    expect(workspace.text()).toContain('执行与对话')
    expect(workspace.text()).toContain('知识嵌入')
    expect(workspace.text()).toContain('1,260,000 token / 40 次调用')
    // Per-currency rows keep the plain-string amounts.
    expect(workspace.text()).toContain('USD')
    expect(workspace.text()).toContain('0.528000000000')
    // The detail table shows the price revision and the unpriced badge side by side.
    expect(workspace.text()).toContain('目录 2 · 价格 3')
    expect(workspace.text()).toContain('未计价')
    // Both quality cards name their denominators explicitly.
    expect(workspace.text()).toContain('分母：20 次执行尝试')
    expect(workspace.text()).toContain('分母：8 个进入 Review 的请求')
    wrapper.unmount()
  })

  it('shows the unpriced hint only when unpriced tokens exist', async () => {
    const { wrapper } = await mountPage('', { zeroUnpriced: true })

    expect(wrapper.text()).not.toContain('未能解析价格的用量')
    wrapper.unmount()

    const hinted = await mountPage('')
    expect(hinted.wrapper.text()).toContain('存在未能解析价格的用量')
    hinted.wrapper.unmount()
  })

  it('navigates months through the query and loads that month detail', async () => {
    const { wrapper, gateway, router } = await mountPage('')

    await wrapper.findAll('.month-nav__item').find(button => button.text() === '2026-08')!.trigger('click')
    await flushPromises()

    expect(router.currentRoute.value.query.month).toBe('2026-08')
    expect(gateway.monthCalls.at(-1)).toBe('2026-08')
    wrapper.unmount()
  })

  it('keeps a deep-linked month URL when that month is not on the loaded page yet', async () => {
    const { wrapper, gateway, router } = await mountPage('month=2026-07')

    // The newest month renders while the deep link waits for older months to load.
    expect(wrapper.text()).toContain('2026-09 成本概览')
    expect(gateway.monthCalls.at(-1)).toBe('2026-09')
    expect(router.currentRoute.value.query.month).toBe('2026-07')
    wrapper.unmount()
  })

  it('filters detail rows by role through the URL role parameter', async () => {
    const { wrapper, gateway, router } = await mountPage('')

    await wrapper.get('.role-filter select').setValue('EMBEDDING')
    await flushPromises()

    expect(router.currentRoute.value.query.role).toBe('EMBEDDING')
    // The filter is client-side over the loaded month; no second detail read.
    expect(gateway.monthCalls.length).toBe(1)
    // Only the EMBEDDING row stays in the table body — the CHAT_PRIMARY model is filtered out.
    expect(wrapper.findAll('.base-table tbody tr')).toHaveLength(1)
    expect(wrapper.get('.base-table tbody').text()).toContain('text-embedding-v4')
    expect(wrapper.get('.base-table tbody').text()).not.toContain('deepseek-v3')
    wrapper.unmount()
  })

  it('renders the empty state when the team has no usage months yet', async () => {
    const { wrapper } = await mountPage('', { emptyMonths: true })

    expect(wrapper.text()).toContain('本月还没有模型用量事实')
    wrapper.unmount()
  })
})

interface FixtureOverrides {
  emptyMonths?: boolean
  zeroUnpriced?: boolean
}

async function mountPage(query: string, overrides: FixtureOverrides = {}) {
  const router = createCrewScopeRouter(createMemoryHistory(), fixtureAuthStore(member))
  const scopeStore = createScopeStore(new FixtureScopeGateway(), member)
  await scopeStore.synchronize(fixtureIds.teamPlatform, fixtureIds.projectCrewScope)
  const gateway = new FixtureObservabilityGateway(overrides)
  const store = createObservabilityStore(gateway)
  await router.push(`/observability?team=${fixtureIds.teamPlatform}${query ? `&${query}` : ''}`)
  await router.isReady()
  const wrapper = mount(ObservabilityPage, {
    global: {
      plugins: [router],
      provide: {
        [AUTH_PRINCIPAL as symbol]: member,
        [SCOPE_STORE as symbol]: scopeStore,
        [OBSERVABILITY_STORE as symbol]: store,
      },
      stubs: { AppShell: { template: '<main><slot name="actions"/><slot/></main>' } },
    },
  })
  await flushPromises()
  await flushPromises()
  return { wrapper, gateway, store, router, scopeStore }
}

class FixtureObservabilityGateway implements ObservabilityGateway {
  monthCalls: string[] = []

  constructor(private readonly overrides: FixtureOverrides) {}

  async listCostMonths(_scope: SettingsScope): Promise<ObservabilityCostMonthsPage> {
    if (this.overrides.emptyMonths) return { months: [], nextAfter: null }
    return { months: [costMonth('2026-09', this.overrides.zeroUnpriced), costMonth('2026-08', true)], nextAfter: null }
  }

  async costMonth(_scope: SettingsScope, month: string): Promise<ObservabilityCostMonthDetail> {
    this.monthCalls.push(month)
    return { month, rows: [pricedRow(), unpricedRow()] }
  }

  async qualityMonth(_scope: SettingsScope, month: string): Promise<ObservabilityQualityMonth> {
    return {
      month,
      executionAttempts: { total: 20, completed: 15, failed: 4, cancelled: 1, successRate: 0.75 },
      reviewFirstPass: { enteredReview: 8, firstPassApproved: 6, firstPassRate: 0.75 },
    }
  }
}

function costMonth(month: string, zeroUnpriced = false): ObservabilityCostMonth {
  return {
    month,
    roles: {
      EXECUTION: { inputTokens: 1_200_000, outputTokens: 60_000, cachedTokens: 40_000, factCount: 40 },
      EMBEDDING: { inputTokens: 100_000, outputTokens: 0, cachedTokens: 0, factCount: 2 },
    },
    currencies: zeroUnpriced
      ? []
      : [{ currency: 'USD', inputCost: '0.528000000000', outputCost: '0.105600000000', cachedInputCost: '0' }],
    unpricedTokens: zeroUnpriced ? 0 : 300_000,
    totalFactCount: 42,
  }
}

function pricedRow() {
  return {
    role: 'CHAT_PRIMARY' as const, providerKey: 'dashscope', modelId: 'deepseek-v3', currencyCode: 'USD',
    catalogRevision: 2, priceRevision: 3, attempt: 1,
    inputTokens: 1_000_000, outputTokens: 50_000, cachedTokens: 10_000,
    inputCost: '0.528000000000', outputCost: '0.066000000000', cachedInputCost: null,
    factCount: 30, unreportedFactCount: 2, costStatus: 'PRICED' as const,
  }
}

function unpricedRow() {
  return {
    role: 'EMBEDDING' as const, providerKey: 'dashscope', modelId: 'text-embedding-v4', currencyCode: 'XXX',
    catalogRevision: null, priceRevision: null, attempt: 2,
    inputTokens: 300_000, outputTokens: 0, cachedTokens: 0,
    inputCost: null, outputCost: null, cachedInputCost: null,
    factCount: 4, unreportedFactCount: 0, costStatus: 'UNPRICED' as const,
  }
}
