import { mount, type VueWrapper } from '@vue/test-utils'
import { nextTick } from 'vue'
import IdentityExperienceFixture, { identityFixtureStates, type IdentityFixtureState } from './IdentityExperienceFixture.vue'

function mountFixture(state: IdentityFixtureState): VueWrapper {
  return mount(IdentityExperienceFixture, { props: { state }, attachTo: document.body })
}

describe('M7-S04 identity experience fixture', () => {
  it('renders every frozen identity state inside one primary landmark', () => {
    for (const state of identityFixtureStates) {
      const wrapper = mountFixture(state)
      expect(wrapper.findAll('main')).toHaveLength(1)
      expect(wrapper.get('main').attributes('id')).toBe('identity-primary')
      expect(wrapper.text()).toContain(state === 'account' ? '身份与安全' : 'M7 交互原型')
      wrapper.unmount()
    }
  })

  it('freezes login field semantics, focus order and generic credential errors', async () => {
    const wrapper = mountFixture('login')
    await nextTick()

    const identifier = wrapper.get<HTMLInputElement>('input[name="identifier"]')
    const password = wrapper.get<HTMLInputElement>('input[name="password"]')
    expect(identifier.attributes('autocomplete')).toBe('username')
    expect(password.attributes('autocomplete')).toBe('current-password')
    expect(document.activeElement).toBe(identifier.element)

    await wrapper.setProps({ state: 'login-error' })
    await nextTick()
    const alert = wrapper.get('[role="alert"]')
    expect(document.activeElement).toBe(alert.element)
    expect(alert.text()).toContain('登录信息无效')
    expect(alert.text()).not.toMatch(/账号不存在|账号已锁定|密码错误/)
    wrapper.unmount()
  })

  it('keeps password reveal ephemeral and exposes the measured password budget', async () => {
    const wrapper = mountFixture('register')
    await nextTick()

    const password = wrapper.get<HTMLInputElement>('input[name="newPassword"]')
    expect(password.attributes('type')).toBe('password')
    expect(password.attributes('autocomplete')).toBe('new-password')
    expect(wrapper.get('[aria-label="密码要求"]').text()).toContain('至少 12 个字符')
    expect(wrapper.get('[aria-label="密码要求"]').text()).toContain('最多 128 个字符')

    await wrapper.get('button[aria-label="显示密码"]').trigger('click')
    expect(password.attributes('type')).toBe('text')
    expect(wrapper.get('button[aria-label="隐藏密码"]').attributes('aria-pressed')).toBe('true')
    expect(localStorage).toHaveLength(0)
    expect(sessionStorage).toHaveLength(0)

    await wrapper.setProps({ state: 'login' })
    await nextTick()
    expect(wrapper.get<HTMLInputElement>('input[name="password"]').attributes('type')).toBe('password')
    wrapper.unmount()
  })

  it('separates onboarding, invitation and account information architecture', () => {
    const onboarding = mountFixture('onboarding')
    expect(onboarding.get('[aria-label="初始化步骤"]').text()).toContain('账号团队工作入口')
    expect(onboarding.get('[aria-label="将要创建的内容"]').text()).toContain('Personal Agent')
    onboarding.unmount()

    const invitation = mountFixture('invite')
    expect(invitation.get('dl').text()).toContain('Platform Engineering')
    expect(invitation.text()).toContain('邀请只能使用一次')
    invitation.unmount()

    const account = mountFixture('account')
    expect(account.get('[aria-label="账号设置导航"]').findAll('a')).toHaveLength(3)
    expect(account.text()).toContain('退出全部设备')
    expect(account.text()).not.toContain('M7 交互原型')
    account.unmount()
  })
})
