import { mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { AUTH_PRINCIPAL } from '../../app/auth'
import { SCOPE_STORE, createScopeStore } from '../../domains/scope/store'
import { bootstrapPrincipal } from '../../test/authFixtures'
import { FixtureScopeGateway, fixtureIds } from '../../test/scopeFixtures'
import AppShell from './AppShell.vue'

/**
 * The fill variant is the L03 height chain: chat-style pages size their panels from the container's
 * real height, so the shell must hand the workspace the remaining viewport instead of growing with
 * content. The class pair is the contract; every other page keeps the scrolling body.
 */
async function harness() {
  // RouterLink resolves every navigation name during render; unknown names throw, so register them all.
  const navigationNames = ['access-denied', 'account', 'activity', 'agent-settings', 'audit',
    'conversation', 'github-settings', 'inbox', 'invite', 'lark-settings', 'login', 'model-settings',
    'not-found', 'onboarding', 'operations', 'register', 'repository-settings', 'search', 'setup',
    'team-members', 'team-observer', 'today', 'work']
  const router = createRouter({
    history: createMemoryHistory(),
    routes: navigationNames.map(name => ({
      path: `/${name}`,
      name,
      component: { render: () => null },
    })),
  })
  await router.push(`/conversation?team=${fixtureIds.teamPlatform}`)
  await router.isReady()
  const scopeStore = createScopeStore(new FixtureScopeGateway(), bootstrapPrincipal)
  await scopeStore.synchronize(fixtureIds.teamPlatform)
  const wrapper = mount(AppShell, {
    props: { title: '对话', eyebrow: '沟通 · 项目范围' },
    global: {
      plugins: [router],
      provide: {
        [SCOPE_STORE as symbol]: scopeStore,
        [AUTH_PRINCIPAL as symbol]: bootstrapPrincipal,
      },
    },
  })
  return wrapper
}

describe('AppShell', () => {
  it('defaults to the scrolling body and switches to the fill height chain on demand', async () => {
    const wrapper = await harness()

    expect(wrapper.get('.app-shell__body').classes()).not.toContain('app-shell__body--fill')
    expect(wrapper.get('.app-shell__workspace').classes()).not.toContain('app-shell__workspace--fill')

    await wrapper.setProps({ fill: true })
    expect(wrapper.get('.app-shell__body').classes()).toContain('app-shell__body--fill')
    expect(wrapper.get('.app-shell__workspace').classes()).toContain('app-shell__workspace--fill')

    await wrapper.setProps({ fill: false })
    expect(wrapper.get('.app-shell__body').classes()).not.toContain('app-shell__body--fill')
    expect(wrapper.get('.app-shell__workspace').classes()).not.toContain('app-shell__workspace--fill')
    wrapper.unmount()
  })
})
