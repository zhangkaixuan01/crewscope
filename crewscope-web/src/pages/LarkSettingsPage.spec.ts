import { flushPromises, mount } from '@vue/test-utils'
import { reactive } from 'vue'
import { createMemoryHistory } from 'vue-router'
import { AUTH_PRINCIPAL } from '../app/auth'
import { createCrewScopeRouter } from '../app/router'
import { createScopeStore, SCOPE_STORE } from '../domains/scope/store'
import { createTeamOpsStore, TEAM_OPS_STORE } from '../domains/teamops/store'
import type { TeamOpsGateway } from '../domains/teamops/gateway'
import type { CommandReceipt, LarkConnection } from '../domains/teamops/types'
import { bootstrapPrincipal, fixtureAuthStore } from '../test/authFixtures'
import { fixtureIds, FixtureScopeGateway } from '../test/scopeFixtures'
import LarkSettingsPage from './LarkSettingsPage.vue'

const connection: LarkConnection = {
  connectionId: uuid(1), teamId: fixtureIds.teamPlatform, providerBindingId: uuid(2), providerBindingVersion: 6,
  maskedAppId: '****test', status: 'ACTIVE', credentialStatus: 'ACTIVE', expiresAt: null,
  createdAt: '2026-09-22T00:00:00Z', updatedAt: '2026-09-22T00:00:00Z', version: 4,
}
const second = { ...connection, connectionId: uuid(3), providerBindingId: uuid(4) }
const receipt = (proof = uuid(5)): CommandReceipt => ({ commandId: uuid(6), domainEventId: proof, committedVersion: 1, correlationId: uuid(7) })

describe('Lark settings target wiring', () => {
  it('passes the rotation dialog target and its observed version through the page and store', async () => {
    const rotate = vi.fn(async () => receipt())
    const { wrapper, router } = await mountPage({ rotateLarkConnection: rotate })
    await router.replace({ query: { ...router.currentRoute.value.query, tab: 'connection' } })
    await flushPromises()
    await button(wrapper, '轮换凭证').trigger('click')
    const inputs = wrapper.get('[role="dialog"]').findAll('input')
    await inputs[0]!.setValue('test-app')
    await inputs[1]!.setValue('test-secret')
    await wrapper.get('[role="dialog"] form').trigger('submit')
    await flushPromises()
    expect(rotate).toHaveBeenCalledWith(expect.objectContaining({ teamId: fixtureIds.teamPlatform }), connection.connectionId, '"4"', { appId: 'test-app', appSecret: 'test-secret' }, expect.any(String))
    expect(wrapper.find('[role="dialog"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('passes the verified receipt proof and original member into confirmation; unknown retry keeps the same key', async () => {
    const confirm = vi.fn().mockRejectedValueOnce(new Error('response lost')).mockResolvedValueOnce(receipt())
    const verify = vi.fn(async () => receipt())
    const { wrapper } = await mountPage({ verifyLarkMember: verify, confirmLarkMapping: confirm })
    await verifyIdentity(wrapper)
    expect(verify).toHaveBeenCalledWith(expect.objectContaining({ teamId: fixtureIds.teamPlatform }), connection.providerBindingId, '"6"', 'ou_test_only', expect.any(String))
    expect((wrapper.get('.mapping-steps input').element as HTMLInputElement).value).toBe('')
    await button(wrapper, '确认映射').trigger('click')
    await flushPromises()
    await button(wrapper, '确认映射').trigger('click')
    await flushPromises()
    expect(confirm).toHaveBeenCalledTimes(2)
    expect(confirm.mock.calls[1]).toEqual(confirm.mock.calls[0])
    expect(confirm.mock.calls[0]![1]).toEqual({ memberId: fixtureIds.memberOwner, providerBindingId: connection.providerBindingId, proofId: uuid(5) })
    expect(button(wrapper, '确认映射').attributes('disabled')).toBeDefined()
    wrapper.unmount()
  })

  it('clears the old command when switching members and ignores its late proof while a new verification is pending', async () => {
    const old = deferred<CommandReceipt>()
    const current = deferred<CommandReceipt>()
    const verify = vi.fn().mockReturnValueOnce(old.promise).mockReturnValueOnce(current.promise)
    const confirm = vi.fn(async () => receipt())
    const { wrapper, store } = await mountPage({ verifyLarkMember: verify, confirmLarkMapping: confirm })
    await verifyIdentity(wrapper)
    await wrapper.get('.mapping-steps select').setValue(fixtureIds.memberSecond)
    expect(store.state.command.phase).toBe('idle')
    await verifyIdentity(wrapper)
    old.resolve(receipt(uuid(8)))
    await flushPromises()
    expect(store.state.command.phase).toBe('pending')
    expect(button(wrapper, '确认映射').attributes('disabled')).toBeDefined()
    current.resolve(receipt(uuid(9)))
    await flushPromises()
    await button(wrapper, '确认映射').trigger('click')
    await flushPromises()
    expect(confirm).toHaveBeenCalledWith(expect.anything(), { memberId: fixtureIds.memberSecond, providerBindingId: connection.providerBindingId, proofId: uuid(9) }, expect.any(String))
    wrapper.unmount()
  })

  it.each(['connection', 'team', 'unmount'] as const)('does not install a late proof after %s changes', async change => {
    const old = deferred<CommandReceipt>()
    const confirm = vi.fn(async () => receipt())
    const { wrapper, router, store } = await mountPage({ verifyLarkMember: vi.fn(() => old.promise), confirmLarkMapping: confirm })
    await verifyIdentity(wrapper)
    if (change === 'unmount') wrapper.unmount()
    else await router.replace({ query: { ...router.currentRoute.value.query,
      ...(change === 'team' ? { team: fixtureIds.teamSecurity } : { connection: second.connectionId }),
    } })
    await flushPromises()
    old.resolve(receipt())
    await flushPromises()
    expect(store.state.command.receipt).toBeNull()
    expect(confirm).not.toHaveBeenCalled()
    if (change !== 'unmount') {
      expect(button(wrapper, '确认映射').attributes('disabled')).toBeDefined()
      wrapper.unmount()
    }
  })
})

async function mountPage(overrides: Partial<TeamOpsGateway>) {
  const principal = reactive({ ...bootstrapPrincipal, permissions: new Set(bootstrapPrincipal.permissions) })
  const scopeStore = createScopeStore(new FixtureScopeGateway(), principal)
  await scopeStore.synchronize(fixtureIds.teamPlatform, null)
  const gateway = {
    larkConnections: async () => [connection, second],
    larkConnection: async (_scope, id) => ({ value: id === second.connectionId ? second : connection, etag: '"4"' }),
    larkMappings: async () => ({ items: [], nextCursor: null }),
    larkHealth: async () => ({ status: 'HEALTHY', retryable: false, retryAfterSeconds: null, evidenceCode: 'READY', checkedAt: '2026-09-22T00:00:00Z' }),
    notificationTemplates: async () => [],
    notificationDeliveries: async () => ({ items: [], nextCursor: null }),
    notificationPreference: async (_scope, memberId) => ({ value: { memberId, enabled: true, enabledItemTypes: ['REVIEW'], mutedUntil: null, version: 1 }, etag: '"1"' }),
    ...overrides,
  } as TeamOpsGateway
  const store = createTeamOpsStore(gateway)
  const router = createCrewScopeRouter(createMemoryHistory(), fixtureAuthStore(principal))
  await router.push(`/settings/integrations/lark?team=${fixtureIds.teamPlatform}&tab=mapping&connection=${connection.connectionId}`)
  await router.isReady()
  const wrapper = mount(LarkSettingsPage, { global: { plugins: [router], provide: {
    [AUTH_PRINCIPAL as symbol]: principal, [SCOPE_STORE as symbol]: scopeStore, [TEAM_OPS_STORE as symbol]: store,
  } } })
  await flushPromises()
  return { wrapper, router, store }
}

async function verifyIdentity(wrapper: ReturnType<typeof mount>) {
  await wrapper.get('.mapping-steps input').setValue('ou_test_only')
  await wrapper.get('.mapping-steps form').trigger('submit')
  await flushPromises()
}
function button(wrapper: ReturnType<typeof mount>, label: string) { return wrapper.findAll('button').find(item => item.text().includes(label))! }
function uuid(value: number) { return `00000000-0000-4000-8000-${String(value).padStart(12, '0')}` }
function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>(yes => { resolve = yes })
  return { promise, resolve }
}
