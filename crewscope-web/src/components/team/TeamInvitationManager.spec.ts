import { flushPromises, mount } from '@vue/test-utils'
import { AUTH_STORE, type AuthStore } from '../../domains/identity/store'
import type { InvitationGateway } from '../../domains/invitation/gateway'
import { createInvitationStore, INVITATION_STORE } from '../../domains/invitation/store'
import type { TeamInvitationSummary } from '../../domains/invitation/types'
import TeamInvitationManager from './TeamInvitationManager.vue'

const token = 'B'.repeat(43)

describe('TeamInvitationManager', () => {
  it('creates and copies a one-time link without retaining it in Store state', async () => {
    const writeText = vi.fn(async () => undefined)
    Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText } })
    const { wrapper, store, gateway } = await mountManager()

    await wrapper.findAll('button').find(button => button.text().includes('创建邀请'))!.trigger('click')
    await wrapper.get('input[name="invitationEmail"]').setValue('new@example.com')
    await wrapper.get('select[name="invitationRole"]').setValue('TEAM_LEAD')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    const link = wrapper.get('input[aria-label="一次性邀请链接"]')
    expect(link.element).toBe(document.activeElement)
    expect((link.element as HTMLInputElement).value).toContain(`/invite#token=${token}`)
    expect(JSON.stringify(store.state)).not.toContain(token)
    expect(gateway.create).toHaveBeenCalledWith(
      'organization-1', 'team-1',
      { targetEmail: 'new@example.com', targetRole: 'TEAM_LEAD', expiresInMinutes: 10_080 },
      expect.objectContaining({ csrf: expect.objectContaining({ token: 'csrf-manager' }) }),
      expect.any(AbortSignal),
    )
    await wrapper.findAll('button').find(button => button.text().includes('复制链接'))!.trigger('click')
    expect(writeText).toHaveBeenCalledWith(expect.stringContaining(token))
    expect(wrapper.text()).toContain('已复制')
    wrapper.unmount()
  })

  it('confirms revocation, updates the list and restores trigger focus', async () => {
    const { wrapper, gateway } = await mountManager()
    const revoke = wrapper.findAll('button').find(button => button.text() === '撤销')!
    await revoke.trigger('click')
    expect(wrapper.get('[role="dialog"]').text()).toContain('不会移除已经加入的成员')

    await wrapper.findAll('button').find(button => button.text().includes('确认撤销'))!.trigger('click')
    await flushPromises()

    expect(gateway.revoke).toHaveBeenCalled()
    expect(wrapper.text()).toContain('已撤销')
    expect(document.activeElement).toBe(wrapper.get('#invitation-manager-title').element)
    wrapper.unmount()
  })

  it('does not render a recoverable proof on a receipt-only replay', async () => {
    const { wrapper, gateway } = await mountManager()
    vi.mocked(gateway.create).mockResolvedValueOnce({ command: receipt(), invitation: null, token: null, replayed: true })
    await wrapper.findAll('button').find(button => button.text().includes('创建邀请'))!.trigger('click')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(wrapper.get('[role="alert"]').text()).toContain('一次性链接无法再次显示')
    expect(wrapper.find('input[aria-label="一次性邀请链接"]').exists()).toBe(false)
    wrapper.unmount()
  })
})

async function mountManager() {
  const gateway = fixtureGateway()
  const store = createInvitationStore(gateway)
  const wrapper = mount(TeamInvitationManager, {
    attachTo: document.body,
    props: { organizationId: 'organization-1', teamId: 'team-1' },
    global: { provide: {
      [INVITATION_STORE as symbol]: store,
      [AUTH_STORE as symbol]: authStore(),
    } },
  })
  await flushPromises()
  return { wrapper, store, gateway }
}

function authStore(): AuthStore {
  return {
    state: {
      phase: 'authenticated', activeTeamId: null, errorCode: null, errorMessage: null,
      session: {
        authenticated: true, registrationMode: 'OPEN',
        csrf: { headerName: 'X-XSRF-TOKEN', parameterName: '_csrf', token: 'csrf-manager' },
        account: { accountId: 'account-1', username: 'alice', displayName: 'Alice', platformRole: 'USER', securityVersion: 1, version: 1 },
        principal: { principalId: 'principal-1', organizationId: 'organization-1' }, teams: [], permissions: [],
      },
    },
    principal: { id: 'principal-1', displayName: 'Alice', role: 'Owner', organizationId: 'organization-1', organization: 'CrewScope', permissions: new Set() },
    start() {}, stop() {}, async ensureRestored() {}, async refresh() { return true }, async retry() {},
    selectTeam() {},
    authenticationRequired() {}, signOutLocally() {}, subscribe() { return () => undefined },
  }
}

function fixtureGateway(): InvitationGateway {
  return {
    list: vi.fn(async () => ({ items: [invitation()], nextCursor: null })),
    create: vi.fn(async () => ({ command: receipt(), invitation: invitation({ id: 'invitation-new', targetEmail: 'new@example.com' }), token, replayed: false })),
    revoke: vi.fn(async () => receipt()), preview: vi.fn(), accept: vi.fn(),
  }
}

function invitation(overrides: Partial<TeamInvitationSummary> = {}): TeamInvitationSummary {
  return {
    id: 'invitation-1', organizationId: 'organization-1', teamId: 'team-1', invitedByPrincipalId: 'principal-1',
    targetEmail: 'member@example.com', targetRole: 'MEMBER', status: 'PENDING', expiresAt: '2026-09-01T00:00:00Z',
    acceptedMemberId: null, resolvedAt: null, version: 0,
    createdAt: '2026-08-29T00:00:00Z', updatedAt: '2026-08-29T00:00:00Z', ...overrides,
  }
}

function receipt() {
  return { commandId: 'command-1', domainEventId: 'event-1', committedVersion: 1, correlationId: 'correlation-1' }
}
