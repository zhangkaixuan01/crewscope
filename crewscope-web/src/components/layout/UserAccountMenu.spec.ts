import { mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import UserAccountMenu from './UserAccountMenu.vue'

describe('UserAccountMenu', () => {
  it('opens with focus on account settings and exposes current-device sign-out', async () => {
    const router = createRouter({ history: createMemoryHistory(), routes: [
      { path: '/', component: { template: '<div />' } },
      { path: '/account', name: 'account', component: { template: '<div />' } },
    ] })
    await router.push('/')
    const wrapper = mount(UserAccountMenu, { attachTo: document.body, props: { displayName: '张凯旋', role: 'Team Member' }, global: { plugins: [router] } })

    await wrapper.get('button[aria-haspopup="menu"]').trigger('click')
    expect(wrapper.get('[role="menu"]').text()).toContain('账号设置')
    expect(document.activeElement).toBe(wrapper.get('a[role="menuitem"]').element)
    await wrapper.findAll('button').find(button => button.text().includes('退出当前设备'))!.trigger('click')
    expect(wrapper.emitted('signOut')).toHaveLength(1)
  })

  // M13b-F08（R50）：主题切换前移进账号菜单——三选 radio 直达，持久化走与账号设置页同一键。
  it('switches the theme from the menu and persists through the shared preference key', async () => {
    const router = createRouter({ history: createMemoryHistory(), routes: [
      { path: '/', component: { template: '<div />' } },
      { path: '/account', name: 'account', component: { template: '<div />' } },
    ] })
    await router.push('/')
    window.localStorage.removeItem('cs.pref.device.theme.v1')
    const wrapper = mount(UserAccountMenu, { attachTo: document.body, props: { displayName: '张凯旋', role: 'Team Member' }, global: { plugins: [router] } })

    await wrapper.get('button[aria-haspopup="menu"]').trigger('click')
    const radios = wrapper.get('[role="group"][aria-label="主题"]').findAll('button[role="menuitemradio"]')
    expect(radios.map(radio => radio.text())).toEqual(['跟随系统', '浅色', '深色'])
    expect(radios[0]!.attributes('aria-checked')).toBe('true')

    await radios[2]!.trigger('click')
    expect(radios[2]!.attributes('aria-checked')).toBe('true')
    expect(radios[0]!.attributes('aria-checked')).toBe('false')
    expect(window.localStorage.getItem('cs.pref.device.theme.v1')).toContain('"value":"dark"')
    wrapper.unmount()
    window.localStorage.removeItem('cs.pref.device.theme.v1')
  })

  it('uses a safe fallback when the display name is empty', () => {
    const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/', name: 'account', component: { template: '<div />' } }] })
    const wrapper = mount(UserAccountMenu, { props: { displayName: '', role: 'USER', compact: true }, global: { plugins: [router] } })
    expect(wrapper.get('.user-menu__avatar').text()).toBe('?')
  })
})
