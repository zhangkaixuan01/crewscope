import { mount } from '@vue/test-utils'
import AccountWorkspace from './AccountWorkspace.vue'
import type { AccountProfile } from '../../domains/account/types'

describe('AccountWorkspace', () => {
  it('renders the public profile and derives a Unicode-safe avatar fallback', () => {
    const wrapper = mountWorkspace({ profile: profile({ displayName: '张凯旋' }) })
    expect(wrapper.get('[aria-label="张凯旋 的头像回退"]').text()).toBe('张')
    expect(wrapper.text()).toContain('alice@example.com')
  })

  it('submits display-only changes without a password or SecurityVersion', async () => {
    const wrapper = mountWorkspace()
    await wrapper.get('button').trigger('click')
    await wrapper.get('input[name="displayName"]').setValue('Alice Chen')
    await wrapper.get('form').trigger('submit')

    expect(wrapper.emitted('saveProfile')?.[0]?.[0]).toEqual({ displayName: 'Alice Chen' })
    expect(wrapper.find('input[name="profileCurrentPassword"]').exists()).toBe(false)
  })

  it('requires current password when an identifier changes and emits the current SecurityVersion', async () => {
    const wrapper = mountWorkspace()
    await wrapper.get('button').trigger('click')
    await wrapper.get('input[name="username"]').setValue('alice-next')
    await wrapper.get('form').trigger('submit')
    expect(wrapper.get('[role="alert"]').text()).toContain('当前密码')

    await wrapper.get('input[name="profileCurrentPassword"]').setValue('private-proof')
    await wrapper.get('form').trigger('submit')
    expect(wrapper.emitted('saveProfile')?.[0]?.[0]).toEqual({
      username: 'alice-next', currentPassword: 'private-proof', securityVersion: 3,
    })
  })

  it('validates password confirmation and opens a focused all-device confirmation dialog', async () => {
    const wrapper = mountWorkspace()
    const buttons = wrapper.findAll('button')
    await buttons.find(button => button.text().includes('修改密码'))!.trigger('click')
    await wrapper.get('input[name="currentPassword"]').setValue('current-password')
    await wrapper.get('input[name="newPassword"]').setValue('new-password-value')
    await wrapper.get('input[name="confirmPassword"]').setValue('different-value')
    await wrapper.find('form.account-form--password').trigger('submit')
    expect(wrapper.get('[role="alert"]').text()).toContain('不一致')

    await buttons.find(button => button.text().includes('退出全部设备'))!.trigger('click')
    expect(wrapper.get('[role="dialog"]').text()).toContain('包括当前设备')
    expect(document.activeElement).toBe(wrapper.get('input[name="revokeCurrentPassword"]').element)
    await wrapper.get('[role="dialog"]').trigger('keydown', { key: 'Escape' })
    expect(document.activeElement).toBe(buttons.find(button => button.text().includes('退出全部设备'))!.element)
  })

  it('explains offline protection and disables every account mutation', () => {
    const wrapper = mountWorkspace({ online: false })
    expect(wrapper.get('[role="alert"]').text()).toContain('当前处于离线状态')
    for (const label of ['编辑资料', '修改密码', '退出全部设备']) {
      expect(wrapper.findAll('button').find(button => button.text().includes(label))?.attributes('disabled')).toBeDefined()
    }
  })
})

function mountWorkspace(overrides: Record<string, unknown> = {}) {
  return mount(AccountWorkspace, {
    attachTo: document.body,
    props: {
      profile: profile(), commandPhase: 'idle', operation: null, problem: null,
      commandGeneration: 0, online: true, ...overrides,
    },
  })
}

function profile(overrides: Partial<AccountProfile> = {}): AccountProfile {
  return {
    accountId: 'account-1', username: 'alice', email: 'alice@example.com', displayName: 'Alice',
    status: 'ACTIVE', platformRole: 'USER', securityVersion: 3, version: 4,
    createdAt: '2026-08-01T00:00:00Z', updatedAt: '2026-08-29T00:00:00Z', ...overrides,
  }
}
