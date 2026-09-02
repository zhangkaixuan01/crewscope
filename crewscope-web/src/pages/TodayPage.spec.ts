import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { AUTH_PRINCIPAL } from '../app/auth'
import { SCOPE_STORE, createScopeStore } from '../domains/scope/store'
import { bootstrapPrincipal } from '../test/authFixtures'
import { FixtureScopeGateway, fixtureIds } from '../test/scopeFixtures'
import TodayPage from './TodayPage.vue'

describe('TodayPage', () => {
  afterEach(() => {
    vi.useRealTimers()
    document.body.innerHTML = ''
  })

  it('creates the first WorkProject from the empty state and updates the URL scope', async () => {
    vi.useFakeTimers()
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/today', name: 'today', component: TodayPage },
        { path: '/work', name: 'work', component: { template: '<div />' } },
        { path: '/conversation', name: 'conversation', component: { template: '<div />' } },
        { path: '/team/members', name: 'team-members', component: { template: '<div />' } },
        { path: '/setup', name: 'setup', component: { template: '<div />' } },
      ],
    })
    await router.push(`/today?team=${fixtureIds.teamPlatform}`)
    await router.isReady()
    const gateway = new FixtureScopeGateway()
    gateway.projects[fixtureIds.teamPlatform] = []
    const store = createScopeStore(gateway, bootstrapPrincipal)
    await store.synchronize(fixtureIds.teamPlatform)
    const wrapper = mount(TodayPage, {
      attachTo: document.body,
      global: {
        plugins: [router],
        provide: {
          [SCOPE_STORE as symbol]: store,
          [AUTH_PRINCIPAL as symbol]: bootstrapPrincipal,
        },
        stubs: {
          AppShell: { template: '<main><slot name="actions"/><slot/></main>' },
        },
      },
    })

    expect(wrapper.text()).toContain('这个 Team 还没有 WorkProject')
    await wrapper.findAll('button').find(button => button.text().includes('创建 WorkProject'))!.trigger('click')
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

    expect(store.selectedProject.value?.key).toBe('CREW')
    expect(router.currentRoute.value.query.project).toBe(store.state.selectedProjectId)
    expect(wrapper.text()).toContain('CrewScope Platform')
    wrapper.unmount()
  })
})
