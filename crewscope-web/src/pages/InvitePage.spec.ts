import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import type { AuthStore } from '../domains/identity/store'
import { AUTH_STORE } from '../domains/identity/store'
import type { InvitationGateway } from '../domains/invitation/gateway'
import { persistAcceptance } from '../domains/invitation/acceptanceRecovery'
import { createInvitationStore, INVITATION_STORE } from '../domains/invitation/store'
import type { ScopeStore } from '../domains/scope/store'
import { SCOPE_STORE } from '../domains/scope/store'
import InvitePage from './InvitePage.vue'

const token = 'C'.repeat(43)

describe('InvitePage', () => {
  beforeEach(() => {
    sessionStorage.clear()
  })

  it('clears the Fragment after capture and sends an anonymous existing user through a proof-free login return', async () => {
    const { wrapper, router, store } = await mountPage(false)

    expect(router.currentRoute.value.hash).toBe('')
    expect(wrapper.text()).toContain('Platform Engineering')
    expect(wrapper.text()).not.toContain(token)
    expect(store.hasProof()).toBe(true)
    await wrapper.findAll('button').find(button => button.text().includes('已有账号'))!.trigger('click')
    await flushPromises()

    expect(router.currentRoute.value.name).toBe('login')
    expect(router.currentRoute.value.query.returnTo).toBe('/invite')
    expect(router.currentRoute.value.fullPath).not.toContain(token)
    wrapper.unmount()
  })

  it('accepts with the current Session, refreshes scope and enters the newly joined Team', async () => {
    const { wrapper, router, gateway, scopeStore, store } = await mountPage(true)
    await wrapper.findAll('button').find(button => button.text().includes('接受邀请'))!.trigger('click')
    await flushPromises()

    expect(gateway.accept).toHaveBeenCalledWith(token, expect.objectContaining({
      csrf: expect.objectContaining({ token: 'csrf-invite-page' }), idempotencyKey: expect.any(String),
    }), expect.any(AbortSignal))
    expect(scopeStore.reset).toHaveBeenCalled()
    expect(scopeStore.synchronize).toHaveBeenCalledWith('team-new')
    expect(router.currentRoute.value.name).toBe('conversation')
    expect(router.currentRoute.value.query.team).toBe('team-new')
    expect(store.hasProof()).toBe(false)
    // R29: the entry landed, so the recovery record is consumed rather than left behind.
    expect(sessionStorage.getItem('crewscope:invitation-acceptance:v1')).toBeNull()
    wrapper.unmount()
  })

  it('recovers a committed acceptance from sessionStorage onto the joined Team after a reload', async () => {
    persistAcceptance({
      organizationId: 'organization-1', teamId: 'team-new', memberId: 'member-new',
      principalId: 'principal-1', acceptedAt: Date.now(),
    })
    const { wrapper, router, scopeStore, store } = await mountPage(true, '', true)

    // The reload lost the in-memory acceptance; the session still lists the earlier Team
    // first, and the recovery must land on the joined Team, not on teams[0].
    expect(scopeStore.synchronize).toHaveBeenCalledWith('team-new')
    expect(router.currentRoute.value.name).toBe('conversation')
    expect(router.currentRoute.value.query.team).toBe('team-new')
    expect(sessionStorage.getItem('crewscope:invitation-acceptance:v1')).toBeNull()
    wrapper.unmount()
  })

  it('keeps the accept pending when the session disagrees on the membership coordinate', async () => {
    persistAcceptance({
      organizationId: 'organization-1', teamId: 'team-new', memberId: 'member-from-another-identity',
      principalId: 'principal-1', acceptedAt: Date.now(),
    })
    const { wrapper, router, scopeStore } = await mountPage(true, '', true)

    expect(router.currentRoute.value.name).toBe('invite')
    expect(scopeStore.synchronize).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('接受已提交，会话待同步')
    expect(sessionStorage.getItem('crewscope:invitation-acceptance:v1')).not.toBeNull()
    wrapper.unmount()
  })

  it('switches accounts in place while the invitation proof stays in memory', async () => {
    const { wrapper, authStore, store } = await mountPage(true)
    await wrapper.findAll('button').find(button => button.text().includes('换账号接受'))!.trigger('click')
    await flushPromises()

    expect(authStore.switchAccount).toHaveBeenCalledWith(expect.objectContaining({ token: 'csrf-invite-page' }))
    // The proof survives the switch so the matching account can still accept (L12).
    expect(store.hasProof()).toBe(true)
    wrapper.unmount()
  })

  it('shows expired and malformed links without previewing private failure reasons', async () => {
    const { wrapper, router, gateway } = await mountPage(false, '#token=bad')
    expect(router.currentRoute.value.hash).toBe('')
    expect(wrapper.text()).toContain('这个邀请无法使用')
    expect(gateway.preview).not.toHaveBeenCalled()
    wrapper.unmount()
  })
})

async function mountPage(authenticated: boolean, hash = `#token=${token}`, joined = false) {
  const gateway = fixtureGateway()
  const store = createInvitationStore(gateway)
  const authStore = fixtureAuthStore(authenticated, joined)
  const scopeStore = fixtureScopeStore()
  const router = createRouter({ history: createMemoryHistory(), routes: [
    { path: '/invite', name: 'invite', component: { template: '<div />' } },
    { path: '/login', name: 'login', component: { template: '<div />' } },
    { path: '/register', name: 'register', component: { template: '<div />' } },
    { path: '/conversation', name: 'conversation', component: { template: '<div />' } },
  ] })
  await router.push(`/invite${hash}`)
  await router.isReady()
  const wrapper = mount(InvitePage, {
    attachTo: document.body,
    global: { plugins: [router], provide: {
      [INVITATION_STORE as symbol]: store,
      [AUTH_STORE as symbol]: authStore,
      [SCOPE_STORE as symbol]: scopeStore,
    } },
  })
  await flushPromises()
  return { wrapper, router, gateway, store, authStore, scopeStore }
}

function fixtureGateway(): InvitationGateway {
  return {
    list: vi.fn(), create: vi.fn(), revoke: vi.fn(),
    preview: vi.fn(async () => ({
      state: 'AVAILABLE' as const, invitationId: 'invitation-1', teamName: 'Platform Engineering',
      targetRole: 'MEMBER' as const, expiresAt: '2026-09-01T00:00:00Z', targetRestricted: true,
    })),
    accept: vi.fn(async () => ({
      command: { commandId: 'command-1', domainEventId: 'event-1', committedVersion: 1, correlationId: 'correlation-1' },
      acceptance: { teamId: 'team-new', memberId: 'member-new', invitationId: 'invitation-1', membershipDisposition: 'CREATED' as const, roleGrantCreated: true },
      replayed: false,
    })),
  }
}

function fixtureAuthStore(authenticated: boolean, joined = false): AuthStore {
  const state = {
    phase: authenticated ? 'authenticated' as const : 'anonymous' as const,
    activeTeamId: null, errorCode: null, errorMessage: null,
    session: session(authenticated, joined),
  }
  return {
    state,
    principal: { id: authenticated ? 'principal-1' : '', accountId: authenticated ? 'account-1' : '', displayName: authenticated ? 'Alice' : '', role: authenticated ? 'Member' : '', organizationId: authenticated ? 'organization-1' : '', organization: 'CrewScope', permissions: new Set() },
    start() {}, stop() {}, async ensureRestored() {},
    refresh: vi.fn(async () => { state.session = session(true, true); return true }),
    async retry() {}, selectTeam() {}, authenticationRequired() {}, signOutLocally() {}, switchAccount: vi.fn(async () => true), subscribe() { return () => undefined },
  }
}

function session(authenticated: boolean, joined: boolean) {
  return {
    authenticated, registrationMode: 'OPEN' as const,
    csrf: { headerName: 'X-XSRF-TOKEN' as const, parameterName: '_csrf' as const, token: 'csrf-invite-page' },
    account: authenticated ? { accountId: 'account-1', username: 'alice', displayName: 'Alice', platformRole: 'USER' as const, securityVersion: 1, version: 1 } : null,
    principal: authenticated ? { principalId: 'principal-1', organizationId: 'organization-1' } : null,
    teams: authenticated ? [
      { teamId: 'team-old', name: 'Existing Team', memberId: 'member-old', permissions: [] },
      ...(joined ? [{ teamId: 'team-new', name: 'Platform Engineering', memberId: 'member-new', permissions: [] }] : []),
    ] : [], permissions: [],
  }
}

function fixtureScopeStore(): ScopeStore {
  return {
    state: {} as ScopeStore['state'],
    selectedTeam: { value: null } as ScopeStore['selectedTeam'],
    selectedProject: { value: null } as ScopeStore['selectedProject'],
    synchronize: vi.fn(async teamId => ({ teamId: teamId ?? null, projectId: null })),
    reload: vi.fn(async () => ({ teamId: null, projectId: null })),
    loadMembers: vi.fn(),
    addMember: vi.fn(),
    suspendMember: vi.fn(),
    activateMember: vi.fn(),
    removeMember: vi.fn(),
    leaveTeam: vi.fn(),
    grantRole: vi.fn(),
    revokeRole: vi.fn(),
    transferOwnership: vi.fn(),
    previewResponsibilities: vi.fn(async () => []),
    createHandover: vi.fn(),
    processHandover: vi.fn(),
    getHandover: vi.fn(),
    cancelHandover: vi.fn(),
    clearHandover: vi.fn(),
    checkWorkProjectKey: vi.fn(),
    createWorkProject: vi.fn(),
    clearProjectCommand: vi.fn(),
    reset: vi.fn(),
  }
}
