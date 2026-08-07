import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory } from 'vue-router'
import App from '../App.vue'
import { createCrewScopeRouter } from './router'
import { AUTH_PRINCIPAL, type AuthenticatedPrincipal } from './auth'

const principal: AuthenticatedPrincipal = {
  id: 'test-user',
  displayName: '测试成员',
  role: 'Owner',
  organization: 'Test',
  team: 'Platform',
  permissions: new Set(),
}

describe('application routing', () => {
  it('redirects the root route to Conversation mode', async () => {
    const router = createCrewScopeRouter(createMemoryHistory())
    await router.push('/')
    await router.isReady()

    expect(router.currentRoute.value.name).toBe('conversation')
  })

  it('preserves the focused object across Conversation and Control modes', async () => {
    const router = createCrewScopeRouter(createMemoryHistory())
    await router.push('/conversation?focus=CRW-18&team=platform')
    await router.isReady()
    const wrapper = mount(App, {
      global: {
        plugins: [router],
        provide: { [AUTH_PRINCIPAL as symbol]: principal },
      },
    })
    await flushPromises()

    const controlLink = wrapper.findAll('a').find(link => link.attributes('href')?.startsWith('/control?'))
    expect(controlLink).toBeDefined()
    const href = controlLink!.attributes('href')!
    expect(href).toContain('focus=CRW-18')
    expect(href).toContain('team=platform')
    await router.push(href)
    await flushPromises()

    expect(router.currentRoute.value.name).toBe('control')
    expect(router.currentRoute.value.query).toEqual({ focus: 'CRW-18', team: 'platform' })
    expect(wrapper.text()).toContain('Platform Engineering')
  })
})
