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
      // The bottom bar and the rail mark "current" from the route's own section metadata.
      meta: { section: name },
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

  it('keeps appearance in the account page only — the top bar offers no theme or density toggle', async () => {
    const wrapper = await harness()

    expect(wrapper.text()).not.toContain('点击切换')
    expect(wrapper.find('.density-button').exists()).toBe(false)
    // 顶栏仍是「导航 | 搜索 | 通知」的工具分组：搜索与通知入口都在。
    expect(wrapper.get('.command-search').attributes('aria-label')).toBe('打开命令面板，搜索工作、成员或 Agent')
    expect(wrapper.get('[aria-label="打开通知 Inbox"]')).toBeTruthy()
    wrapper.unmount()
  })

  it('sends the logo to the same place / goes — today, not the conversation', async () => {
    const wrapper = await harness()

    expect(wrapper.get('.brand').attributes('href')).toMatch(/^\/today\?/)
    wrapper.unmount()
  })

  it('gives the phone bottom bar the three primary destinations', async () => {
    const wrapper = await harness()

    const links = wrapper.get('.mobile-mode').findAll('a')
    expect(links.map(link => link.text())).toEqual(['今日', '工作', '对话'])
    expect(links[0]!.attributes('href')).toMatch(/^\/today\?/)
    expect(links[1]!.attributes('href')).toMatch(/^\/work\?/)
    expect(links[2]!.attributes('href')).toMatch(/^\/conversation\?/)
    // 当前分区在底栏亮起，与侧栏的 aria-current 同源。
    expect(links[2]!.attributes('aria-current')).toBe('page')
    wrapper.unmount()
  })
})
