import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import type { AuthStore } from '../domains/identity/store'
import { AUTH_STORE } from '../domains/identity/store'
import type { InvitationGateway } from '../domains/invitation/gateway'
import { createInvitationStore, INVITATION_STORE } from '../domains/invitation/store'
import type { ScopeStore } from '../domains/scope/store'
import { SCOPE_STORE } from '../domains/scope/store'
import InvitePage from './InvitePage.vue'

const token = 'C'.repeat(43)

describe('InvitePage', () => {
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

async function mountPage(authenticated: boolean, hash = `#token=${token}`) {
  const gateway = fixtureGateway()
  const store = createInvitationStore(gateway)
  const authStore = fixtureAuthStore(authenticated)
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
    accept: vi.fn(async () => ({ commandId: 'command-1', domainEventId: 'event-1', committedVersion: 1, correlationId: 'correlation-1' })),
  }
}

function fixtureAuthStore(authenticated: boolean): AuthStore {
  const state = {
    phase: authenticated ? 'authenticated' as const : 'anonymous' as const,
    activeTeamId: null, errorCode: null, errorMessage: null,
    session: session(authenticated, false),
  }
  return {
    state,
    principal: { id: authenticated ? 'principal-1' : '', displayName: authenticated ? 'Alice' : '', role: authenticated ? 'Member' : '', organizationId: authenticated ? 'organization-1' : '', organization: 'CrewScope', permissions: new Set() },
    start() {}, stop() {}, async ensureRestored() {},
    refresh: vi.fn(async () => { state.session = session(true, true); return true }),
    async retry() {}, selectTeam() {}, authenticationRequired() {}, signOutLocally() {}, subscribe() { return () => undefined },
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
    loadMembers: vi.fn(), addMember: vi.fn(), reset: vi.fn(),
  }
}
