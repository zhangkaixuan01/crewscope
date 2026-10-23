import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory } from 'vue-router'
import { AUTH_PRINCIPAL, permissions, type AuthenticatedPrincipal } from '../app/auth'
import { createCrewScopeRouter } from '../app/router'
import { AUTH_STORE } from '../domains/identity/store'
import type { InvitationGateway } from '../domains/invitation/gateway'
import { createInvitationStore, INVITATION_STORE } from '../domains/invitation/store'
import { createScopeStore, SCOPE_STORE } from '../domains/scope/store'
import { fixtureAuthStore } from '../test/authFixtures'
import { FixtureScopeGateway, fixtureIds } from '../test/scopeFixtures'
import TeamMembersPage from './TeamMembersPage.vue'
import PrincipalPicker from '../components/domain/PrincipalPicker.vue'

const principal: AuthenticatedPrincipal = {
  id: fixtureIds.principal,
  accountId: '00000000-0000-0000-0000-000000000201',
  displayName: 'Zhang Kaixuan',
  role: 'Team Owner',
  organizationId: fixtureIds.organization,
  organization: 'Test Organization',
  permissions: new Set(Object.values(permissions)),
}

describe('TeamMembersPage', () => {
  it('locates the member a configuration search deep-linked to', async () => {
    const { wrapper } = await mountPage(`&member=${fixtureIds.memberSecond}`)

    expect(wrapper.text()).toContain('已定位到 Lin Chen')
    const rows = wrapper.findAll('.member-row')
    expect(rows[1]?.classes()).toContain('member-row--located')
    expect(rows[0]?.classes()).not.toContain('member-row--located')

    await wrapper.findAll('button').find(button => button.text().trim() === '清除定位')!.trigger('click')
    await flushPromises()

    expect(wrapper.find('.locate-note').exists()).toBe(false)
    expect(wrapper.findAll('.member-row').every(row => !row.classes().includes('member-row--located'))).toBe(true)
    wrapper.unmount()
  })

  it('admits that a deep-linked member is not on this Team', async () => {
    // The field search only returns ACTIVE members; a member who left the Team is exactly the miss
    // this wording exists for, so the page must not claim to have located them.
    const { wrapper } = await mountPage('&member=00000000-0000-0000-0000-000000009999')

    expect(wrapper.text()).toContain('当前 Team 的成员列表里没有这个成员')
    expect(wrapper.text()).not.toContain('已定位到')
    expect(wrapper.findAll('.member-row').every(row => !row.classes().includes('member-row--located'))).toBe(true)
    wrapper.unmount()
  })

  it('adds no locating markup while the URL asks for no member', async () => {
    const { wrapper } = await mountPage('')

    expect(wrapper.find('.locate-note').exists()).toBe(false)
    expect(wrapper.findAll('.member-row').every(row => !row.classes().includes('member-row--located'))).toBe(true)
    expect(wrapper.text()).toContain('Lin Chen')
    wrapper.unmount()
  })

  // M13b-F02（二轮 A02）：标题承诺「添加已有用户」，目录就是组织成员目录——不再是停用成员
  // 重加入的下拉，也不再要求任何 Principal ID。
  it('adds an existing organization member through the org directory picker', async () => {
    const { wrapper, gateway } = await mountPage('')
    const addMember = vi.spyOn(gateway, 'addMember')
    await wrapper.findAll('button').find(button => button.text().includes('添加成员'))!.trigger('click')

    const picker = wrapper.findComponent(PrincipalPicker)
    expect(picker.exists()).toBe(true)
    expect(picker.props('scope')).toEqual({ organizationId: fixtureIds.organization })
    expect(wrapper.text()).not.toContain('Principal ID')

    await wrapper.get('form.add-member').trigger('submit')
    await flushPromises()
    expect(wrapper.text()).toContain('请先从组织成员目录选择成员')
    expect(addMember).not.toHaveBeenCalled()

    picker.vm.$emit('update:modelValue', '00000000-0000-0000-0000-000000000101')
    await wrapper.get('form.add-member').trigger('submit')
    await flushPromises()

    // The store owns the idempotency key; the test only pins the directory coordinates.
    expect(addMember).toHaveBeenCalledWith(
      fixtureIds.organization, fixtureIds.teamPlatform, '00000000-0000-0000-0000-000000000101', expect.any(String),
    )
    expect(wrapper.find('form.add-member').exists()).toBe(false)
    wrapper.unmount()
  })
})

async function mountPage(extraQuery: string) {
  const router = createCrewScopeRouter(createMemoryHistory(), fixtureAuthStore(principal))
  const gateway = new FixtureScopeGateway()
  const scopeStore = createScopeStore(gateway, principal)
  await scopeStore.synchronize(fixtureIds.teamPlatform, null)
  // The invitation panel is a permission-gated child of this page; it reads its own store.
  const invitationGateway = {
    list: vi.fn(async () => ({ items: [], nextCursor: null })),
    create: vi.fn(), revoke: vi.fn(), preview: vi.fn(), accept: vi.fn(),
  } as unknown as InvitationGateway
  await router.push(`/team/members?team=${fixtureIds.teamPlatform}${extraQuery}`)
  await router.isReady()
  const wrapper = mount(TeamMembersPage, {
    global: {
      plugins: [router],
      provide: {
        [AUTH_PRINCIPAL as symbol]: principal,
        [AUTH_STORE as symbol]: fixtureAuthStore(principal),
        [SCOPE_STORE as symbol]: scopeStore,
        [INVITATION_STORE as symbol]: createInvitationStore(invitationGateway),
      },
    },
  })
  await flushPromises()
  return { wrapper, gateway }
}
