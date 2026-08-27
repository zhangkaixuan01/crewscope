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

const principal: AuthenticatedPrincipal = {
  id: 'test-user',
  displayName: '测试成员',
  role: 'Owner',
  organizationId: fixtureIds.organization,
  organization: 'Test Organization',
  permissions: new Set(Object.values(permissions)),
}

describe('application routing', () => {
  it('redirects the root route to Conversation mode', async () => {
    const router = createCrewScopeRouter(createMemoryHistory(), principal)
    await router.push('/')
    await router.isReady()

    expect(router.currentRoute.value.name).toBe('conversation')
  })

  it('preserves the focused object across Conversation and Control modes', async () => {
    const router = createCrewScopeRouter(createMemoryHistory(), principal)
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
    const router = createCrewScopeRouter(createMemoryHistory(), principal)
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
    const router = createCrewScopeRouter(createMemoryHistory(), readOnlyPrincipal)

    await router.push(`/team/members?team=${fixtureIds.teamPlatform}`)
    await router.isReady()

    expect(router.currentRoute.value.name).toBe('access-denied')
    expect(router.currentRoute.value.query.from).toBe(`/team/members?team=${fixtureIds.teamPlatform}`)
  })

  it('redirects a principal without Conversation permission before the page loads', async () => {
    const readOnlyPrincipal = { ...principal, permissions: new Set([permissions.scopeRead]) }
    const router = createCrewScopeRouter(createMemoryHistory(), readOnlyPrincipal)
    const destination = `/conversation?team=${fixtureIds.teamPlatform}&project=${fixtureIds.projectCrewScope}`

    await router.push(destination)
    await router.isReady()

    expect(router.currentRoute.value.name).toBe('access-denied')
    expect(router.currentRoute.value.query.from).toBe(destination)
  })

  it('keeps the legacy Control URL as a query-preserving Today redirect', async () => {
    const router = createCrewScopeRouter(createMemoryHistory(), principal)

    await router.push(`/control?team=${fixtureIds.teamPlatform}&project=${fixtureIds.projectCrewScope}`)
    await router.isReady()

    expect(router.currentRoute.value.name).toBe('today')
    expect(router.currentRoute.value.query.project).toBe(fixtureIds.projectCrewScope)
  })

  it('guards WorkProject Repository settings with repository management permission', async () => {
    const readOnlyPrincipal = { ...principal, permissions: new Set([permissions.scopeRead]) }
    const router = createCrewScopeRouter(createMemoryHistory(), readOnlyPrincipal)
    const destination = `/settings/repositories?team=${fixtureIds.teamPlatform}&project=${fixtureIds.projectCrewScope}`

    await router.push(destination)
    await router.isReady()

    expect(router.currentRoute.value.name).toBe('access-denied')
    expect(router.currentRoute.value.query.from).toBe(destination)
  })

  it('guards the Audit Explorer with Audit read permission', async () => {
    const readOnlyPrincipal = { ...principal, permissions: new Set([permissions.scopeRead]) }
    const router = createCrewScopeRouter(createMemoryHistory(), readOnlyPrincipal)
    const destination = `/audit?team=${fixtureIds.teamPlatform}`

    await router.push(destination)
    await router.isReady()

    expect(router.currentRoute.value.name).toBe('access-denied')
    expect(router.currentRoute.value.query.from).toBe(destination)
  })

  it('allows a Team member to enter Operations health without administrator permission', async () => {
    const memberPrincipal = { ...principal, permissions: new Set<string>([permissions.scopeRead]) }
    const router = createCrewScopeRouter(createMemoryHistory(), memberPrincipal)
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
