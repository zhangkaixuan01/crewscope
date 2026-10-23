import { mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { AUTH_PRINCIPAL } from '../../app/auth'
import { SCOPE_STORE, createScopeStore } from '../../domains/scope/store'
import { TEAM_OPS_STORE, type TeamOpsStore } from '../../domains/teamops/store'
import type { InboxCounts } from '../../domains/teamops/types'
import { bootstrapPrincipal } from '../../test/authFixtures'
import { FixtureScopeGateway, fixtureIds } from '../../test/scopeFixtures'
import AppShell from './AppShell.vue'

/**
 * The fill variant is the L03 height chain: chat-style pages size their panels from the container's
 * real height, so the shell must hand the workspace the remaining viewport instead of growing with
 * content. The class pair is the contract; every other page keeps the scrolling body.
 */
async function harness(principalOverride?: typeof bootstrapPrincipal, teamOps?: TeamOpsStore) {
  // RouterLink resolves every navigation name during render; unknown names throw, so register them all.
  const navigationNames = ['access-denied', 'account', 'activity', 'agent-settings', 'audit',
    'conversation', 'github-settings', 'inbox', 'invite', 'knowledge-base', 'knowledge-index', 'lark-settings', 'login',
    'model-settings', 'not-found', 'observability', 'onboarding', 'operations', 'organization-settings', 'quota-settings',
    'register', 'repository-settings', 'search', 'setup', 'skill-catalog', 'team-members', 'team-observer', 'today', 'work']
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
  const activePrincipal = principalOverride ?? bootstrapPrincipal
  const scopeStore = createScopeStore(new FixtureScopeGateway(), activePrincipal)
  await scopeStore.synchronize(fixtureIds.teamPlatform)
  const wrapper = mount(AppShell, {
    props: { title: '对话', eyebrow: '沟通 · 项目范围' },
    global: {
      plugins: [router],
      provide: {
        [SCOPE_STORE as symbol]: scopeStore,
        [AUTH_PRINCIPAL as symbol]: activePrincipal,
        ...(teamOps ? { [TEAM_OPS_STORE as symbol]: teamOps } : {}),
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

  // M13b-F06（R24）：铃铛读 Inbox /counts 的未读数——徽标并入按钮 aria-label，本体装饰化；
  // Scope 就绪即对齐激活域并读一次计数（Inbox 处置后的联动刷新由同一 store 承担）。
  it('badges the bell with the Inbox unread count once the scope is ready', async () => {
    const counts = (unread: number): InboxCounts => ({
      total: unread + 2,
      unread,
      byType: {},
    })
    const teamOps = {
      state: { inboxCounts: { phase: 'ready', value: counts(3), error: null } },
      activateScope: vi.fn(),
      loadInboxCounts: vi.fn(async () => {}),
    } as unknown as TeamOpsStore
    const wrapper = await harness(undefined, teamOps)

    const bell = wrapper.get('.topbar-bell')
    expect(bell.attributes('aria-label')).toBe('打开通知 Inbox，3 条未读')
    expect(bell.get('.topbar-bell__badge').text()).toBe('3')
    expect(teamOps.loadInboxCounts).toHaveBeenCalled()
    wrapper.unmount()
  })

  it('keeps the bell unlabeled when there is nothing unread', async () => {
    const teamOps = {
      state: { inboxCounts: { phase: 'ready', value: { total: 2, unread: 0, byType: {} }, error: null } },
      activateScope: vi.fn(),
      loadInboxCounts: vi.fn(async () => {}),
    } as unknown as TeamOpsStore
    const wrapper = await harness(undefined, teamOps)

    const bell = wrapper.get('.topbar-bell')
    expect(bell.attributes('aria-label')).toBe('打开通知 Inbox')
    expect(bell.find('.topbar-bell__badge').exists()).toBe(false)
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

  // M13b-F01（R01）：纯组织角色此前侧栏全空（三个 team 粒度组全被过滤）——组织组是其唯一
  // 导航入口；固定指向 Today 的品牌/模式切换/底栏对该会话是死链，须隐藏或改指可达页。
  it('keeps a pure organization role on the organization rail with no dead team links', async () => {
    const orgOnly = { ...bootstrapPrincipal, permissions: new Set(['org:audit-read']) }
    const wrapper = await harness(orgOnly)

    const rail = wrapper.get('.rail-navigation').text()
    expect(rail).toContain('组织')
    expect(rail).toContain('组织设置')
    expect(rail).toContain('配额与用量')
    expect(rail).not.toContain('今日')
    expect(rail).not.toContain('团队成员')
    expect(wrapper.get('.brand').attributes('href')).toBe('/settings/organization')
    expect(wrapper.find('.mode-switcher').exists()).toBe(false)
    expect(wrapper.find('.mobile-mode').exists()).toBe(false)
    wrapper.unmount()
  })
})
