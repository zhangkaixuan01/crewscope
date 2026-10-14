import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory } from 'vue-router'
import { AUTH_PRINCIPAL, permissions, type AuthenticatedPrincipal } from '../app/auth'
import { createCrewScopeRouter } from '../app/router'
import { fixtureAuthStore } from '../test/authFixtures'
import { CrewScopeApiError } from '../api/client'
import { AUTH_STORE } from '../domains/identity/store'
import type { QuotaGateway } from '../domains/quota/gateway'
import { QUOTA_STORE, createQuotaStore } from '../domains/quota/store'
import type { QuotaUsageView } from '../domains/quota/types'
import QuotaSettingsPage from './QuotaSettingsPage.vue'

const organizationId = '00000000-0000-0000-0000-000000000001'
const ANCHOR = '2026-10'

/** The three role tiers the page must progressively render (M12-F02 permission matrix). */
const plainMember: AuthenticatedPrincipal = {
  id: 'p-member', accountId: 'a-1', displayName: '普通成员', role: 'Member',
  organizationId, organization: 'CrewScope Organization',
  permissions: new Set([permissions.scopeRead]),
}
const auditor: AuthenticatedPrincipal = {
  ...plainMember, id: 'p-auditor', displayName: '组织审计员', role: 'ORG_AUDITOR',
  permissions: new Set([permissions.orgAuditRead]),
}
const admin: AuthenticatedPrincipal = {
  ...plainMember, id: 'p-admin', displayName: '组织管理员', role: 'ORG_ADMIN',
  permissions: new Set([permissions.orgAuditRead, permissions.orgManageSettings]),
}

describe('QuotaSettingsPage', () => {
  it('renders the static forbidden panel for a member without dispatching anything', async () => {
    const gateway = fakeGateway()
    const { wrapper } = await mountPage(plainMember, gateway)

    expect(wrapper.text()).toContain('需要组织审计角色')
    expect(wrapper.text()).toContain('查看权限说明')
    // The gate must not dispatch: a 403 here would fire the global revocation sink.
    expect(gateway.quota).not.toHaveBeenCalled()
    expect(gateway.usage).not.toHaveBeenCalled()
  })

  it('lets an auditor read configuration and usage without any editing entry', async () => {
    const gateway = fakeGateway()
    const { wrapper } = await mountPage(auditor, gateway)

    expect(wrapper.text()).toContain('每月任务受理上限 未配置')
    expect(wrapper.text()).toContain('每月 Token 预算 未配置')
    expect(wrapper.text()).toContain('任务受理（2026-10）')
    expect(wrapper.findAll('button').some(button => button.text().includes('调整配额'))).toBe(false)
    // The anchored window: one parameterless call plus the five previous months.
    expect(gateway.usage).toHaveBeenCalledWith(organizationId, null)
    expect(gateway.usage).toHaveBeenCalledWith(organizationId, '2026-05', expect.any(AbortSignal))
  })

  it('persists both dimensions explicitly, mapping empty inputs to cleared dimensions', async () => {
    const gateway = fakeGateway()
    gateway.quota
      .mockResolvedValueOnce({ monthlyTaskIntakeLimit: null, monthlyTokenBudget: null, version: 0 })
      .mockResolvedValueOnce({ monthlyTaskIntakeLimit: 5, monthlyTokenBudget: 2_000_000, version: 1 })
    const { wrapper } = await mountPage(admin, gateway)

    await wrapper.findAll('button').find(button => button.text().includes('调整配额'))!.trigger('click')
    await wrapper.get('#quota-intake').setValue('5')
    await wrapper.get('#quota-budget').setValue('2000000')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(gateway.updateQuota).toHaveBeenCalledWith(
      organizationId,
      { monthlyTaskIntakeLimit: 5, monthlyTokenBudget: 2_000_000 },
      0,
      expect.any(String),
    )
    expect(wrapper.text()).toContain('配额已保存')

    // An emptied field means "clear the dimension", never zero.
    await wrapper.findAll('button').find(button => button.text().includes('调整配额'))!.trigger('click')
    await wrapper.get('#quota-intake').setValue('')
    await wrapper.get('#quota-budget').setValue('2000000')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(gateway.updateQuota).toHaveBeenLastCalledWith(
      organizationId,
      { monthlyTaskIntakeLimit: null, monthlyTokenBudget: 2_000_000 },
      1,
      expect.any(String),
    )
  })

  it('blocks a sub-1 limit before submitting and names the server 422 copy', async () => {
    const gateway = fakeGateway()
    const { wrapper } = await mountPage(admin, gateway)

    await wrapper.findAll('button').find(button => button.text().includes('调整配额'))!.trigger('click')
    await wrapper.get('#quota-intake').setValue('0')
    expect((wrapper.get('button[type="submit"]').element as HTMLButtonElement).disabled).toBe(true)
    await wrapper.get('form').trigger('submit')
    expect(wrapper.get('#quota-intake').attributes('aria-invalid')).toBe('true')
    expect(gateway.updateQuota).not.toHaveBeenCalled()

    gateway.updateQuota.mockRejectedValueOnce(apiError(422, 'invalid_value'))
    await wrapper.get('#quota-intake').setValue('5')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(wrapper.text()).toContain('配额值无效')
  })

  it('explains a 409 conflict and re-reads so the next save quotes the persisted version', async () => {
    const gateway = fakeGateway()
    gateway.quota
      .mockResolvedValueOnce({ monthlyTaskIntakeLimit: null, monthlyTokenBudget: null, version: 0 })
      .mockResolvedValueOnce({ monthlyTaskIntakeLimit: 6, monthlyTokenBudget: null, version: 3 })
    gateway.updateQuota.mockRejectedValueOnce(apiError(409, 'optimistic_lock_conflict'))
    const { wrapper } = await mountPage(admin, gateway)

    await wrapper.findAll('button').find(button => button.text().includes('调整配额'))!.trigger('click')
    await wrapper.get('#quota-intake').setValue('5')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(wrapper.text()).toContain('内容已变化')
    expect(gateway.quota).toHaveBeenCalledTimes(2)
  })

  it('navigates months through the URL and loads the navigated month on demand', async () => {
    const gateway = fakeGateway()
    const { wrapper, router } = await mountPage(auditor, gateway)

    await wrapper.findAll('.quota-month-nav__item')
      .find(button => button.text() === '2026-07')!.trigger('click')
    await flushPromises()

    expect(router.currentRoute.value.query.month).toBe('2026-07')
    expect(wrapper.text()).toContain('任务受理（2026-07）')
    expect(gateway.usage).toHaveBeenCalledWith(organizationId, '2026-07', expect.any(AbortSignal))
  })

  it('falls a future deep link back to the anchor instead of dispatching a guaranteed 400', async () => {
    const gateway = fakeGateway()
    const { wrapper } = await mountPage(auditor, gateway, '?month=2026-12')

    expect(wrapper.text()).toContain('任务受理（2026-10）')
    // Exact-arg matching once let this pass while the pre-anchor race still dispatched.
    expect(gateway.usage.mock.calls.filter(([, month]) => month === '2026-12')).toEqual([])
  })

  it('flags the projection lag when the selected month is not materialized', async () => {
    const gateway = fakeGateway()
    const { wrapper } = await mountPage(auditor, gateway, '?month=2026-10')

    expect(wrapper.text()).toContain('实际用量统计存在投影延迟')
  })

  it('renders the three trend charts from the loaded window', async () => {
    const gateway = fakeGateway()
    gateway.usage.mockImplementation(async (_id, month) => usage(month ?? ANCHOR, {
      intake: 3, reserved: 600_000, actual: { input: 100_000, output: 50_000, cachedInput: 25_000, total: 175_000 },
      materialized: true,
    }))
    const { wrapper } = await mountPage(admin, gateway)

    expect(wrapper.findAll('svg[role="group"]')).toHaveLength(3)
    expect(wrapper.text()).toContain('每月任务受理数趋势')
    expect(wrapper.text()).toContain('每月 Token 趋势')
    expect(wrapper.text()).toContain('实际 Token 构成')
    // The equivalent table states every window month for screen readers.
    expect(wrapper.get('table[aria-label="每月任务受理数趋势（数据表）"]').text()).toContain('2026-05')
  })
})

async function mountPage(
  principal: AuthenticatedPrincipal,
  gateway: QuotaGateway = fakeGateway(),
  query = '',
) {
  const authStore = fixtureAuthStore(principal)
  const router = createCrewScopeRouter(createMemoryHistory(), authStore)
  const store = createQuotaStore(gateway)
  await router.push(`/settings/quota${query}`)
  await router.isReady()
  const wrapper = mount(QuotaSettingsPage, {
    global: {
      plugins: [router],
      provide: {
        [AUTH_PRINCIPAL as symbol]: principal,
        [AUTH_STORE as symbol]: authStore,
        [QUOTA_STORE as symbol]: store,
      },
      stubs: { AppShell: { template: '<main><slot name="actions"/><slot/></main>' } },
    },
  })
  await flushPromises()
  await flushPromises()
  return { wrapper, gateway, store, router }
}

function fakeGateway(): QuotaGateway & {
  quota: ReturnType<typeof vi.fn>
  usage: ReturnType<typeof vi.fn>
  updateQuota: ReturnType<typeof vi.fn>
} {
  return {
    quota: vi.fn(async () => ({ monthlyTaskIntakeLimit: null, monthlyTokenBudget: null, version: 0 })),
    usage: vi.fn(async (_organizationId: string, month: string | null) => usage(month ?? ANCHOR)),
    updateQuota: vi.fn(async () => ({
      commandId: 'command-1', domainEventId: 'event-1', committedVersion: 1,
      correlationId: 'c-1', replayed: false,
    })),
  }
}

function usage(
  month: string,
  overrides: {
    intake?: number,
    reserved?: number,
    actual?: { input: number, output: number, cachedInput: number, total: number },
    materialized?: boolean,
  } = {},
): QuotaUsageView {
  return {
    month,
    monthlyTaskIntake: { limit: null, count: overrides.intake ?? 0 },
    monthlyTokenBudget: {
      limit: null, reservedTokens: overrides.reserved ?? 0,
      actualTokens: overrides.actual ?? { input: 0, output: 0, cachedInput: 0, total: 0 },
    },
    materialized: overrides.materialized ?? false,
  }
}

function apiError(status: number, code: string): CrewScopeApiError {
  return new CrewScopeApiError(status, {
    code, message: 'server message', correlationId: 'c-1', retryable: false, currentVersion: null, details: {},
  })
}
