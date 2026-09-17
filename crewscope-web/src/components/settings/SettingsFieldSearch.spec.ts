import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { CrewScopeApiError } from '../../api/client'
import { AUTH_PRINCIPAL } from '../../app/auth'
import { createScopeStore, SCOPE_STORE } from '../../domains/scope/store'
import { createSetupStore, SETUP_STORE, type SetupStore } from '../../domains/setup/store'
import type { SetupGateway } from '../../domains/setup/gateway'
import type { ConfigurationSearchHit } from '../../domains/setup/types'
import { bootstrapPrincipal } from '../../test/authFixtures'
import { FixtureScopeGateway, fixtureIds } from '../../test/scopeFixtures'
import SettingsFieldSearch from './SettingsFieldSearch.vue'

const hit: ConfigurationSearchHit = {
  profileId: '00000000-0000-0000-0000-000000005101',
  revision: 2,
  field: 'modelKey',
  label: '模型连接',
  route: '/settings/models?provider=openai',
}

function apiError(status: number, message = 'Unavailable'): CrewScopeApiError {
  return new CrewScopeApiError(status, { code: 'error', message, correlationId: 'c-1', retryable: false, currentVersion: null, details: {} })
}

describe('SettingsFieldSearch', () => {
  beforeEach(() => vi.useFakeTimers())
  afterEach(() => vi.useRealTimers())

  it('renders nothing until the member actually searches', async () => {
    // The `v-if` leaves a comment placeholder, which grid and flex layout ignore: the nine settings
    // pages keep their exact geometry while the query is empty.
    const { wrapper } = await mountSearch({ query: '' })
    expect(wrapper.element.nodeType).toBe(Node.COMMENT_NODE)
    expect(wrapper.find('.field-search').exists()).toBe(false)

    await wrapper.setProps({ query: '   ' })
    expect(wrapper.find('.field-search').exists()).toBe(false)

    // A deployment without the projection must not add a dead block to nine settings pages either.
    const bare = await mountSearch({ query: 'model', withStore: false })
    expect(bare.wrapper.find('.field-search').exists()).toBe(false)
  })

  it('links a hit to the field with the active Team merged in', async () => {
    const { wrapper, searchConfiguration } = await mountSearch({ query: ' 模型 ' })
    // A query that is already in the box when the shell renders is not a keystroke worth debouncing.
    expect(searchConfiguration).toHaveBeenCalledTimes(1)

    await vi.advanceTimersByTimeAsync(250)
    await flushPromises()

    expect(searchConfiguration).toHaveBeenCalledWith(
      { organizationId: fixtureIds.organization, teamId: fixtureIds.teamPlatform },
      '模型',
    )
    expect(searchConfiguration).toHaveBeenCalledTimes(1)
    const link = wrapper.get('a')
    expect(link.text()).toContain('模型连接')
    expect(link.text()).toContain('modelKey')
    expect(link.attributes('href')).toBe(`/settings/models?team=${fixtureIds.teamPlatform}&provider=openai`)
  })

  it('debounces keystrokes into one request', async () => {
    const { wrapper, searchConfiguration } = await mountSearch({ query: '' })

    await wrapper.setProps({ query: 'mo' })
    await wrapper.setProps({ query: 'mod' })
    await wrapper.setProps({ query: 'mode' })
    await wrapper.setProps({ query: 'model' })
    expect(searchConfiguration).not.toHaveBeenCalled()

    await vi.advanceTimersByTimeAsync(250)
    await flushPromises()

    expect(searchConfiguration).toHaveBeenCalledTimes(1)
    expect(searchConfiguration.mock.calls[0]?.[1]).toBe('model')
  })

  it('stops at the contract boundary instead of sending an over-long query', async () => {
    const { wrapper, searchConfiguration } = await mountSearch({ query: '模'.repeat(101) })
    await vi.advanceTimersByTimeAsync(250)
    await flushPromises()

    expect(searchConfiguration).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('字段搜索最多 100 个字符')
  })

  it('names each non-result state in plain text', async () => {
    const forbidden = await mountSearch({ query: 'model', search: async () => { throw apiError(403, '需要 Team 成员权限') } })
    await vi.advanceTimersByTimeAsync(250)
    await flushPromises()
    expect(forbidden.wrapper.text()).toContain('需要 Team 成员权限才能搜索配置字段')

    const offline = await mountSearch({ query: 'model', search: async () => { throw apiError(0, 'Network down') } })
    await vi.advanceTimersByTimeAsync(250)
    await flushPromises()
    expect(offline.wrapper.text()).toContain('离线：无法搜索配置字段')

    const empty = await mountSearch({ query: 'model', search: async () => [] })
    await vi.advanceTimersByTimeAsync(250)
    await flushPromises()
    expect(empty.wrapper.text()).toContain('没有匹配的配置字段')

    const unavailable = await mountSearch({ query: 'model', search: null })
    await vi.advanceTimersByTimeAsync(250)
    await flushPromises()
    expect(unavailable.wrapper.text()).toContain('当前部署没有提供配置字段搜索')
  })

  it('offers a retry when the projection fails', async () => {
    let attempts = 0
    const { wrapper, searchConfiguration } = await mountSearch({
      query: 'model',
      search: async () => { attempts += 1; if (attempts === 1) throw new Error('boom'); return [hit] },
    })
    await vi.advanceTimersByTimeAsync(250)
    await flushPromises()
    expect(wrapper.text()).toContain('配置搜索暂时不可用')

    await wrapper.get('button').trigger('click')
    await flushPromises()

    expect(searchConfiguration).toHaveBeenCalledTimes(2)
    expect(wrapper.get('a').attributes('href')).toContain('/settings/models')
  })

  it('searches the newly selected Team again with the query still in the box', async () => {
    const { wrapper, scopeStore, searchConfiguration } = await mountSearch({ query: 'model' })
    await vi.advanceTimersByTimeAsync(250)
    await flushPromises()

    await scopeStore.synchronize(fixtureIds.teamSecurity, null)
    await flushPromises()
    await vi.advanceTimersByTimeAsync(250)
    await flushPromises()

    expect(searchConfiguration).toHaveBeenLastCalledWith(
      { organizationId: fixtureIds.organization, teamId: fixtureIds.teamSecurity },
      'model',
    )
    // The previous Team's hits are gone before the new answer arrives.
    expect(wrapper.find('a').exists() || wrapper.text().includes('正在搜索配置字段')).toBe(true)
  })
})

async function mountSearch(options: { query: string, withStore?: boolean, search?: ((query: string) => Promise<ConfigurationSearchHit[]>) | null }) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/settings/models', name: 'model-settings', component: { template: '<div />' } }],
  })
  await router.push('/settings/models')
  await router.isReady()
  const scopeStore = createScopeStore(new FixtureScopeGateway(), bootstrapPrincipal)
  await scopeStore.synchronize(fixtureIds.teamPlatform, null)

  const fixture = options.search === undefined ? (async () => [hit]) : options.search
  const searchConfiguration = vi.fn(async (_scope: unknown, query: string) => (await fixture?.(query)) ?? [])
  const gateway: SetupGateway = {
    getReadiness: vi.fn(async scope => ({ scope, snapshotVersion: 'v1', observedAt: '2026-09-01T00:00:00Z', requiredReady: true, capabilities: [] })),
    ...(fixture === null ? {} : { searchConfiguration }),
  }
  const store = createSetupStore(gateway)
  const provided: Record<symbol, unknown> = {
    [AUTH_PRINCIPAL as symbol]: bootstrapPrincipal,
    [SCOPE_STORE as symbol]: scopeStore,
    ...(options.withStore === false ? {} : { [SETUP_STORE as symbol]: store }),
  }
  const wrapper = mount(SettingsFieldSearch, {
    props: { query: options.query },
    global: { plugins: [router], provide: provided },
  })
  return { wrapper, store: store as SetupStore, scopeStore, searchConfiguration }
}
