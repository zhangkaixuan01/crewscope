import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { FixtureScopeGateway, fixtureIds } from '../../test/scopeFixtures'
import { createScopeStore, SCOPE_STORE } from '../../domains/scope/store'
import { bootstrapPrincipal } from '../../test/authFixtures'
import { AUTH_PRINCIPAL } from '../../app/auth'
import ScopeSwitcher from './ScopeSwitcher.vue'

describe('ScopeSwitcher', () => {
  afterEach(() => {
    vi.useRealTimers()
    document.body.innerHTML = ''
  })

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
        provide: { [SCOPE_STORE as symbol]: store, [AUTH_PRINCIPAL as symbol]: bootstrapPrincipal },
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

  it('creates the first WorkProject and selects it in the current URL', async () => {
    vi.useFakeTimers()
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/today', name: 'today', component: { template: '<div />' } }],
    })
    await router.push(`/today?team=${fixtureIds.teamPlatform}`)
    await router.isReady()
    const gateway = new FixtureScopeGateway()
    gateway.projects[fixtureIds.teamPlatform] = []
    const store = createScopeStore(gateway, bootstrapPrincipal)
    await store.synchronize(fixtureIds.teamPlatform)
    const wrapper = mount(ScopeSwitcher, {
      attachTo: document.body,
      global: {
        plugins: [router],
        provide: { [SCOPE_STORE as symbol]: store, [AUTH_PRINCIPAL as symbol]: bootstrapPrincipal },
      },
    })

    await wrapper.get('.scope-switcher').trigger('click')
    await wrapper.findAll('button').find(button => button.text().includes('新建'))!.trigger('click')
    const inputs = document.body.querySelectorAll<HTMLInputElement>('.project-create-dialog input')
    inputs[0]!.value = 'crew'
    inputs[0]!.dispatchEvent(new Event('input', { bubbles: true }))
    inputs[1]!.value = 'CrewScope Platform'
    inputs[1]!.dispatchEvent(new Event('input', { bubbles: true }))
    await vi.advanceTimersByTimeAsync(250)
    await flushPromises()
    document.body.querySelector<HTMLFormElement>('.project-create-dialog')!
      .dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }))
    await flushPromises()

    expect(gateway.createdProjects[0]?.input).toEqual({ key: 'CREW', name: 'CrewScope Platform' })
    expect(router.currentRoute.value.query.project).toBe(store.state.selectedProjectId)
    expect(document.body.querySelector('.project-create-dialog')).toBeNull()
    wrapper.unmount()
  })
})
