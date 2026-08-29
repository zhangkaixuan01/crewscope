import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { FixtureScopeGateway, fixtureIds } from '../../test/scopeFixtures'
import { createScopeStore, SCOPE_STORE } from '../../domains/scope/store'
import { bootstrapPrincipal } from '../../test/authFixtures'
import ScopeSwitcher from './ScopeSwitcher.vue'

describe('ScopeSwitcher', () => {
  it('writes Team and WorkProject changes into the current URL', async () => {
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/today', name: 'today', component: { template: '<div />' } }],
    })
    await router.push(`/today?team=${fixtureIds.teamPlatform}&project=${fixtureIds.projectCrewScope}&workItem=00000000-0000-0000-0000-000000000601&focus=CRW-18`)
    await router.isReady()
    const store = createScopeStore(new FixtureScopeGateway(), bootstrapPrincipal)
    await store.synchronize(fixtureIds.teamPlatform, fixtureIds.projectCrewScope)
    const wrapper = mount(ScopeSwitcher, {
      global: {
        plugins: [router],
        provide: { [SCOPE_STORE as symbol]: store },
      },
    })

    await wrapper.get('.scope-switcher').trigger('click')
    expect(wrapper.get('.scope-switcher').attributes('aria-expanded')).toBe('true')
    expect(wrapper.get('.scope-switcher').attributes('aria-haspopup')).toBeUndefined()
    expect(wrapper.get('.scope-menu').attributes('role')).toBeUndefined()
    const securityTeam = wrapper.findAll('button').find(button => button.text().includes('Security Engineering'))
    await securityTeam!.trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.query.team).toBe(fixtureIds.teamSecurity)
    expect(router.currentRoute.value.query.project).toBeUndefined()
    expect(router.currentRoute.value.query.workItem).toBeUndefined()
    expect(router.currentRoute.value.query.focus).toBeUndefined()

    await store.synchronize(fixtureIds.teamSecurity, fixtureIds.projectRuntime)
    await router.replace({ query: { team: fixtureIds.teamSecurity, project: fixtureIds.projectRuntime } })
    await wrapper.get('.scope-switcher').trigger('click')
    expect(wrapper.text()).toContain('Runtime Security')
  })
})
