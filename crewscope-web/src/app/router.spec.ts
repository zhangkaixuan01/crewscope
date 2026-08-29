import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory } from 'vue-router'
import App from '../App.vue'
import { createCrewScopeRouter } from './router'
import { AUTH_PRINCIPAL, permissions, type AuthenticatedPrincipal } from './auth'
import { createScopeStore, SCOPE_STORE } from '../domains/scope/store'
import { createConversationStore, CONVERSATION_STORE } from '../domains/conversation/store'
import { createConversationMessageStore, CONVERSATION_MESSAGE_STORE } from '../domains/conversation/messageStore'
import type { ConversationMessageGateway } from '../domains/conversation/messageGateway'
import { createConversationRealtimeStore, CONVERSATION_REALTIME_STORE } from '../domains/conversation/realtimeStore'
import { createTaskIntentStore, TASK_INTENT_STORE } from '../domains/conversation/taskIntentStore'
import type { TaskIntentGateway } from '../domains/conversation/taskIntentGateway'
import { createConversationWorkItemLinkStore, CONVERSATION_WORK_ITEM_LINK_STORE } from '../domains/conversation/workItemLinkStore'
import type { ConversationWorkItemLinkGateway } from '../domains/conversation/workItemLinkGateway'
import { conversationIds, FixtureConversationGateway } from '../test/conversationFixtures'
import { FixtureConversationMessageGateway } from '../test/conversationMessageFixtures'
import { FixtureConversationRealtimeGateway } from '../test/conversationRealtimeFixtures'
import { FixtureScopeGateway, fixtureIds } from '../test/scopeFixtures'
import { createTaskStore, TASK_STORE } from '../domains/task/store'
import { FixtureTaskGateway } from '../test/taskFixtures'
import { createTeamObserverStore, TEAM_OBSERVER_STORE } from '../domains/teamobserver/store'
import type { TeamObserverGateway } from '../domains/teamobserver/gateway'
import type { ConversationMessagePage } from '../domains/conversation/types'
import { AUTH_STORE } from '../domains/identity/store'
import { fixtureAuthStore } from '../test/authFixtures'
import { createAuthStore } from '../domains/identity/store'
import type { IdentityGateway } from '../domains/identity/gateway'
import type { AuthSession } from '../domains/identity/types'

const principal: AuthenticatedPrincipal = {
  id: 'test-user',
  displayName: '测试成员',
  role: 'Owner',
  organizationId: fixtureIds.organization,
  organization: 'Test Organization',
  permissions: new Set(Object.values(permissions)),
}

describe('application routing', () => {
  it('waits for Session recovery and sends an anonymous protected target to login', async () => {
    const pending = deferred<AuthSession>()
    const authStore = createAuthStore(identityGateway(() => pending.promise), { channelFactory: () => null })
    const router = createCrewScopeRouter(createMemoryHistory(), authStore)
    const navigation = router.push('/today?team=team-1')

    expect(router.currentRoute.value.name).toBeUndefined()
    pending.resolve(authSession(false))
    await navigation
    await router.isReady()

    expect(router.currentRoute.value.name).toBe('login')
    expect(router.currentRoute.value.query.returnTo).toBe('/today?team=team-1')
  })

  it('uses restored permissions and redirects every open protected page after Session expiry', async () => {
    let authenticated = true
    const authStore = createAuthStore(identityGateway(async () => authSession(authenticated)), { channelFactory: () => null })
    const router = createCrewScopeRouter(createMemoryHistory(), authStore)
    await router.push('/conversation')
    await router.isReady()
    expect(router.currentRoute.value.name).toBe('conversation')

    authenticated = false
    authStore.authenticationRequired()

    await vi.waitFor(() => expect(router.currentRoute.value.name).toBe('login'))
    expect(router.currentRoute.value.query.returnTo).toBe('/conversation')
    await vi.waitFor(() => expect(authStore.state.phase).toBe('anonymous'))
  })

  it('does not reuse an administrator capability after routing to an ordinary-member Team', async () => {
    const scoped = authSession(true)
    scoped.permissions = []
    scoped.teams = [
      {
        teamId: 'team-admin',
        name: 'Admin Team',
        memberId: 'member-admin',
        permissions: [permissions.scopeRead, permissions.auditRead],
      },
      {
        teamId: 'team-member',
        name: 'Member Team',
        memberId: 'member-member',
        permissions: [permissions.scopeRead],
      },
    ]
    const authStore = createAuthStore(identityGateway(async () => scoped), { channelFactory: () => null })
    const router = createCrewScopeRouter(createMemoryHistory(), authStore)

    await router.push('/audit?team=team-admin')
    await router.isReady()
    expect(router.currentRoute.value.name).toBe('audit')
    expect(authStore.state.activeTeamId).toBe('team-admin')

    await router.push('/audit?team=team-member')
    expect(router.currentRoute.value.name).toBe('access-denied')
    expect(authStore.state.activeTeamId).toBe('team-member')
  })

  it('exposes the public login route without applying workspace permissions', async () => {
    const noPermissions = { ...principal, permissions: new Set<string>() }
    const router = createCrewScopeRouter(createMemoryHistory(), fixtureAuthStore(noPermissions))

    await router.push('/login?returnTo=%2Fwork')
    await router.isReady()

    expect(router.currentRoute.value.name).toBe('login')
    expect(router.currentRoute.value.query.returnTo).toBe('/work')
  })

  it('exposes the public registration route without applying workspace permissions', async () => {
    const noPermissions = { ...principal, permissions: new Set<string>() }
    const router = createCrewScopeRouter(createMemoryHistory(), fixtureAuthStore(noPermissions))

    await router.push('/register')
    await router.isReady()

    expect(router.currentRoute.value.name).toBe('register')
  })

  it('exposes the proof-carrying invitation route without workspace permissions', async () => {
    const noPermissions = { ...principal, permissions: new Set<string>() }
    const router = createCrewScopeRouter(createMemoryHistory(), fixtureAuthStore(noPermissions))

    await router.push(`/invite#token=${'A'.repeat(43)}`)
    await router.isReady()

    expect(router.currentRoute.value.name).toBe('invite')
    expect(router.currentRoute.value.hash).toContain('token=')
  })

  it('admits authenticated accounts to Onboarding without workspace permissions', async () => {
    const noPermissions = { ...principal, permissions: new Set<string>() }
    const router = createCrewScopeRouter(createMemoryHistory(), fixtureAuthStore(noPermissions))

    await router.push('/onboarding')
    await router.isReady()

    expect(router.currentRoute.value.name).toBe('onboarding')
  })

  it('preserves Onboarding as a protected login return target', async () => {
    const authStore = createAuthStore(identityGateway(async () => authSession(false)), { channelFactory: () => null })
    const router = createCrewScopeRouter(createMemoryHistory(), authStore)

    await router.push('/onboarding')
    await router.isReady()

    expect(router.currentRoute.value.name).toBe('login')
    expect(router.currentRoute.value.query.returnTo).toBe('/onboarding')
  })

  it('admits an authenticated account to account settings without Team permissions', async () => {
    const noPermissions = { ...principal, permissions: new Set<string>() }
    const router = createCrewScopeRouter(createMemoryHistory(), fixtureAuthStore(noPermissions))

    await router.push('/account')
    await router.isReady()

    expect(router.currentRoute.value.name).toBe('account')
  })

  it('redirects the root route to Conversation mode', async () => {
    const router = createCrewScopeRouter(createMemoryHistory(), fixtureAuthStore(principal))
    await router.push('/')
    await router.isReady()

    expect(router.currentRoute.value.name).toBe('conversation')
  })

  it('preserves the focused object across Conversation and Control modes', async () => {
    const authStore = fixtureAuthStore(principal)
    const router = createCrewScopeRouter(createMemoryHistory(), authStore)
    await router.push(`/conversation?focus=CRW-18&team=${fixtureIds.teamPlatform}&project=${fixtureIds.projectCrewScope}`)
    await router.isReady()
    const store = createScopeStore(new FixtureScopeGateway(), principal)
    const conversationStore = createConversationStore(new FixtureConversationGateway())
    const messageStore = createConversationMessageStore(new FixtureConversationMessageGateway())
    const realtimeStore = createConversationRealtimeStore(new FixtureConversationRealtimeGateway(), { storage: null })
    const taskIntentStore = createTaskIntentStore(quietTaskIntentGateway())
    const linkStore = createConversationWorkItemLinkStore(quietLinkGateway())
    const taskStore = createTaskStore(new FixtureTaskGateway(), { storage: null })
    const wrapper = mount(App, {
      global: {
        plugins: [router],
        provide: {
          [AUTH_PRINCIPAL as symbol]: principal,
          [AUTH_STORE as symbol]: authStore,
          [SCOPE_STORE as symbol]: store,
          [CONVERSATION_STORE as symbol]: conversationStore,
          [CONVERSATION_MESSAGE_STORE as symbol]: messageStore,
          [CONVERSATION_REALTIME_STORE as symbol]: realtimeStore,
          [TASK_INTENT_STORE as symbol]: taskIntentStore,
          [CONVERSATION_WORK_ITEM_LINK_STORE as symbol]: linkStore,
          [TASK_STORE as symbol]: taskStore,
        },
      },
    })
    await flushPromises()

    const workbenchLink = wrapper.findAll('a').find(link => link.attributes('href')?.startsWith('/today?'))
    expect(workbenchLink).toBeDefined()
    const href = workbenchLink!.attributes('href')!
    expect(href).toContain('focus=CRW-18')
    expect(href).toContain(`team=${fixtureIds.teamPlatform}`)
    expect(href).toContain(`project=${fixtureIds.projectCrewScope}`)
    await router.push(href)
    await flushPromises()

    expect(router.currentRoute.value.name).toBe('today')
    expect(router.currentRoute.value.query).toEqual({
      focus: 'CRW-18',
      team: fixtureIds.teamPlatform,
      project: fixtureIds.projectCrewScope,
    })
    expect(wrapper.text()).toContain('Platform Engineering')
  })

  it('does not restart a late Personal Conversation realtime chain after entering Team Observer', async () => {
    const authStore = fixtureAuthStore(principal)
    const router = createCrewScopeRouter(createMemoryHistory(), authStore)
    await router.push(`/conversation?team=${fixtureIds.teamPlatform}&project=${fixtureIds.projectCrewScope}&conversation=${conversationIds.provider}`)
    await router.isReady()
    const scopeStore = createScopeStore(new FixtureScopeGateway(), principal)
    const conversationStore = createConversationStore(new FixtureConversationGateway())
    const pendingMessages = deferred<ConversationMessagePage>()
    const messageGateway = new FixtureConversationMessageGateway() as ConversationMessageGateway
    messageGateway.listMessages = vi.fn(async () => pendingMessages.promise)
    const messageStore = createConversationMessageStore(messageGateway)
    const realtimeGateway = new FixtureConversationRealtimeGateway()
    const streamEvents = vi.spyOn(realtimeGateway, 'streamEvents')
    const realtimeStore = createConversationRealtimeStore(realtimeGateway, { storage: null })
    const taskIntentStore = createTaskIntentStore(quietTaskIntentGateway())
    const linkStore = createConversationWorkItemLinkStore(quietLinkGateway())
    const taskStore = createTaskStore(new FixtureTaskGateway(), { storage: null })
    const observerStore = createTeamObserverStore({} as TeamObserverGateway)
    const wrapper = mount(App, {
      global: {
        plugins: [router],
        provide: {
          [AUTH_PRINCIPAL as symbol]: principal,
          [AUTH_STORE as symbol]: authStore,
          [SCOPE_STORE as symbol]: scopeStore,
          [CONVERSATION_STORE as symbol]: conversationStore,
          [CONVERSATION_MESSAGE_STORE as symbol]: messageStore,
          [CONVERSATION_REALTIME_STORE as symbol]: realtimeStore,
          [TASK_INTENT_STORE as symbol]: taskIntentStore,
          [CONVERSATION_WORK_ITEM_LINK_STORE as symbol]: linkStore,
          [TASK_STORE as symbol]: taskStore,
          [TEAM_OBSERVER_STORE as symbol]: observerStore,
        },
      },
    })
    await vi.waitFor(() => expect(messageGateway.listMessages).toHaveBeenCalledTimes(1))

    await router.push({ name: 'conversation', query: { ...router.currentRoute.value.query, assistant: 'team-observer' } })
    await flushPromises()
    pendingMessages.resolve({ items: [], nextCursor: null })
    await flushPromises()

    expect(streamEvents).not.toHaveBeenCalled()
    expect(realtimeStore.state.invocationPhase).toBe('idle')
    wrapper.unmount()
  })

  it('redirects an unauthorized member route and records the denied destination', async () => {
    const readOnlyPrincipal = { ...principal, permissions: new Set([permissions.scopeRead]) }
    const router = createCrewScopeRouter(createMemoryHistory(), fixtureAuthStore(readOnlyPrincipal))

    await router.push(`/team/members?team=${fixtureIds.teamPlatform}`)
    await router.isReady()

    expect(router.currentRoute.value.name).toBe('access-denied')
    expect(router.currentRoute.value.query.from).toBe(`/team/members?team=${fixtureIds.teamPlatform}`)
  })

  it('redirects a principal without Conversation permission before the page loads', async () => {
    const readOnlyPrincipal = { ...principal, permissions: new Set([permissions.scopeRead]) }
    const router = createCrewScopeRouter(createMemoryHistory(), fixtureAuthStore(readOnlyPrincipal))
    const destination = `/conversation?team=${fixtureIds.teamPlatform}&project=${fixtureIds.projectCrewScope}`

    await router.push(destination)
    await router.isReady()

    expect(router.currentRoute.value.name).toBe('access-denied')
    expect(router.currentRoute.value.query.from).toBe(destination)
  })

  it('keeps the legacy Control URL as a query-preserving Today redirect', async () => {
    const router = createCrewScopeRouter(createMemoryHistory(), fixtureAuthStore(principal))

    await router.push(`/control?team=${fixtureIds.teamPlatform}&project=${fixtureIds.projectCrewScope}`)
    await router.isReady()

    expect(router.currentRoute.value.name).toBe('today')
    expect(router.currentRoute.value.query.project).toBe(fixtureIds.projectCrewScope)
  })

  it('guards WorkProject Repository settings with repository management permission', async () => {
    const readOnlyPrincipal = { ...principal, permissions: new Set([permissions.scopeRead]) }
    const router = createCrewScopeRouter(createMemoryHistory(), fixtureAuthStore(readOnlyPrincipal))
    const destination = `/settings/repositories?team=${fixtureIds.teamPlatform}&project=${fixtureIds.projectCrewScope}`

    await router.push(destination)
    await router.isReady()

    expect(router.currentRoute.value.name).toBe('access-denied')
    expect(router.currentRoute.value.query.from).toBe(destination)
  })

  it('guards the Audit Explorer with Audit read permission', async () => {
    const readOnlyPrincipal = { ...principal, permissions: new Set([permissions.scopeRead]) }
    const router = createCrewScopeRouter(createMemoryHistory(), fixtureAuthStore(readOnlyPrincipal))
    const destination = `/audit?team=${fixtureIds.teamPlatform}`

    await router.push(destination)
    await router.isReady()

    expect(router.currentRoute.value.name).toBe('access-denied')
    expect(router.currentRoute.value.query.from).toBe(destination)
  })

  it('allows a Team member to enter Operations health without administrator permission', async () => {
    const memberPrincipal = { ...principal, permissions: new Set<string>([permissions.scopeRead]) }
    const router = createCrewScopeRouter(createMemoryHistory(), fixtureAuthStore(memberPrincipal))
    const destination = `/operations?team=${fixtureIds.teamPlatform}`

    await router.push(destination)
    await router.isReady()

    expect(router.currentRoute.value.name).toBe('operations')
    expect(memberPrincipal.permissions.has(permissions.operationsManage)).toBe(false)
  })
})

function quietTaskIntentGateway(): TaskIntentGateway {
  return {
    async get() { throw new Error('No TaskIntent is selected in this routing fixture') },
    async revise() {},
    async previewConfirmation() { throw new Error('No TaskIntent is selected in this routing fixture') },
    async confirm() {},
    async reject() {},
  }
}

function quietLinkGateway(): ConversationWorkItemLinkGateway {
  return {
    async listByConversation() { return [] },
    async listByWorkItem() { return [] },
  }
}

function deferred<T>(): { promise: Promise<T>, resolve: (value: T) => void } {
  let resolve!: (value: T) => void
  const promise = new Promise<T>(done => { resolve = done })
  return { promise, resolve }
}

function identityGateway(load: () => Promise<AuthSession>): IdentityGateway {
  return { session: vi.fn(load), login: vi.fn(), logout: vi.fn(), register: vi.fn() }
}

function authSession(authenticated: boolean): AuthSession {
  return {
    authenticated,
    registrationMode: 'OPEN',
    csrf: { headerName: 'X-XSRF-TOKEN', parameterName: '_csrf', token: 'csrf-router' },
    account: authenticated ? {
      accountId: 'account-1', username: 'alice', displayName: 'Alice', platformRole: 'USER', securityVersion: 1, version: 1,
    } : null,
    principal: authenticated ? { principalId: principal.id, organizationId: principal.organizationId } : null,
    teams: authenticated ? [{ teamId: fixtureIds.teamPlatform, name: 'Platform', memberId: 'member-1', permissions: [...principal.permissions] }] : [],
    permissions: authenticated ? [...principal.permissions] : [],
  }
}
