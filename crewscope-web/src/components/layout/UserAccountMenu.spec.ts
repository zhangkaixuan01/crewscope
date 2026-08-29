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

  it('uses a safe fallback when the display name is empty', () => {
    const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/', name: 'account', component: { template: '<div />' } }] })
    const wrapper = mount(UserAccountMenu, { props: { displayName: '', role: 'USER', compact: true }, global: { plugins: [router] } })
    expect(wrapper.get('.user-menu__avatar').text()).toBe('?')
  })
})
