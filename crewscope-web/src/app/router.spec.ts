import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory } from 'vue-router'
import App from '../App.vue'
import { createCrewScopeRouter } from './router'
import { AUTH_PRINCIPAL, permissions, type AuthenticatedPrincipal } from './auth'
import { createScopeStore, SCOPE_STORE } from '../domains/scope/store'
import { FixtureScopeGateway, fixtureIds } from '../test/scopeFixtures'

const principal: AuthenticatedPrincipal = {
  id: 'test-user',
  displayName: '测试成员',
  role: 'Owner',
  organizationId: fixtureIds.organization,
  organization: 'Test Organization',
  permissions: new Set(Object.values(permissions)),
}

describe('application routing', () => {
  it('redirects the root route to Conversation mode', async () => {
    const router = createCrewScopeRouter(createMemoryHistory(), principal)
    await router.push('/')
    await router.isReady()

    expect(router.currentRoute.value.name).toBe('conversation')
  })

  it('preserves the focused object across Conversation and Control modes', async () => {
    const router = createCrewScopeRouter(createMemoryHistory(), principal)
    await router.push(`/conversation?focus=CRW-18&team=${fixtureIds.teamPlatform}&project=${fixtureIds.projectCrewScope}`)
    await router.isReady()
    const store = createScopeStore(new FixtureScopeGateway(), principal)
    const wrapper = mount(App, {
      global: {
        plugins: [router],
        provide: {
          [AUTH_PRINCIPAL as symbol]: principal,
          [SCOPE_STORE as symbol]: store,
        },
      },
    })
    await flushPromises()

    const workbenchLink = wrapper.findAll('a').find(link => link.attributes('href')?.startsWith('/today?'))
    expect(workbenchLink).toBeDefined()
    const href = workbenchLink!.attributes('href')!
    expect(href).toContain('focus=CRW-18')
    expect(href).toContain(`team=${fixtureIds.teamPlatform}`)
    expect(href).toContain(`project=${fixtureIds.projectCrewScope}`)
    await router.push(href)
    await flushPromises()

    expect(router.currentRoute.value.name).toBe('today')
    expect(router.currentRoute.value.query).toEqual({
      focus: 'CRW-18',
      team: fixtureIds.teamPlatform,
      project: fixtureIds.projectCrewScope,
    })
    expect(wrapper.text()).toContain('Platform Engineering')
  })

  it('redirects an unauthorized member route and records the denied destination', async () => {
    const readOnlyPrincipal = { ...principal, permissions: new Set([permissions.scopeRead]) }
    const router = createCrewScopeRouter(createMemoryHistory(), readOnlyPrincipal)

    await router.push(`/team/members?team=${fixtureIds.teamPlatform}`)
    await router.isReady()

    expect(router.currentRoute.value.name).toBe('access-denied')
    expect(router.currentRoute.value.query.from).toBe(`/team/members?team=${fixtureIds.teamPlatform}`)
  })

  it('keeps the legacy Control URL as a query-preserving Today redirect', async () => {
    const router = createCrewScopeRouter(createMemoryHistory(), principal)

    await router.push(`/control?team=${fixtureIds.teamPlatform}&project=${fixtureIds.projectCrewScope}`)
    await router.isReady()

    expect(router.currentRoute.value.name).toBe('today')
    expect(router.currentRoute.value.query.project).toBe(fixtureIds.projectCrewScope)
  })
})
