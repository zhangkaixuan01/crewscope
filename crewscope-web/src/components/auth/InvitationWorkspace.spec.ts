import { mount } from '@vue/test-utils'
import InvitationWorkspace from './InvitationWorkspace.vue'

describe('InvitationWorkspace', () => {
  it('shows only privacy-bounded facts and distinct account paths for an anonymous visitor', async () => {
    const wrapper = mountWorkspace()

    expect(wrapper.text()).toContain('Platform Engineering')
    expect(wrapper.text()).toContain('Team Lead')
    expect(wrapper.text()).toContain('邮箱需要与邀请目标匹配')
    expect(wrapper.text()).not.toMatch(/target@example|principal|organization/i)
    await wrapper.findAll('button').find(button => button.text().includes('已有账号'))!.trigger('click')
    await wrapper.findAll('button').find(button => button.text().includes('创建账号'))!.trigger('click')
    expect(wrapper.emitted('login')).toHaveLength(1)
    expect(wrapper.emitted('register')).toHaveLength(1)
  })

  it('offers one accepting action to an authenticated account', async () => {
    const wrapper = mountWorkspace({ authenticated: true })
    expect(wrapper.text()).toContain('接受邀请并加入团队')
    expect(wrapper.text()).not.toContain('创建账号并加入团队')
    await wrapper.findAll('button').find(button => button.text().includes('接受邀请'))!.trigger('click')
    expect(wrapper.emitted('accept')).toHaveLength(1)
  })

  it.each([
    ['expired', '这个邀请已经过期'],
    ['unavailable', '这个邀请无法使用'],
  ] as const)('renders the %s state without internal reason detail', (phase, title) => {
    const wrapper = mountWorkspace({ phase, preview: { ...preview(), state: phase === 'expired' ? 'EXPIRED' : 'UNAVAILABLE' } })
    expect(wrapper.get('h2').text()).toBe(title)
    expect(wrapper.text()).toContain('不会披露邀请目标')
  })
})

function mountWorkspace(overrides: Record<string, unknown> = {}) {
  return mount(InvitationWorkspace, { props: {
    phase: 'available', preview: preview(), problem: null, problemFocusKey: 0,
    authenticated: false, registrationAllowed: true, online: true, ...overrides,
  } })
}

function preview() {
  return {
    state: 'AVAILABLE' as const, invitationId: 'invitation-1', teamName: 'Platform Engineering',
    targetRole: 'TEAM_LEAD' as const, expiresAt: '2026-09-01T00:00:00Z', targetRestricted: true,
  }
}
