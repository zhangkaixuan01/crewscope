import { nextTick } from 'vue'
import { mount, type VueWrapper } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { AUTH_PRINCIPAL } from '../app/auth'
import { activateF05Identity, readF05Reading } from '../app/f05Storage'
import { CONVERSATION_MESSAGE_STORE, createConversationMessageStore } from '../domains/conversation/messageStore'
import { CONVERSATION_REALTIME_STORE, createConversationRealtimeStore } from '../domains/conversation/realtimeStore'
import { CONVERSATION_STORE, createConversationStore } from '../domains/conversation/store'
import { TASK_INTENT_STORE, createTaskIntentStore } from '../domains/conversation/taskIntentStore'
import type { TaskIntentGateway } from '../domains/conversation/taskIntentGateway'
import type { TaskIntent, VersionedTaskIntentConfirmationPreview } from '../domains/conversation/types'
import type { ConversationWorkItemLinkGateway } from '../domains/conversation/workItemLinkGateway'
import { CONVERSATION_WORK_ITEM_LINK_STORE, createConversationWorkItemLinkStore } from '../domains/conversation/workItemLinkStore'
import { SCOPE_STORE, createScopeStore } from '../domains/scope/store'
import { TASK_STORE, createTaskStore } from '../domains/task/store'
import { bootstrapPrincipal } from '../test/authFixtures'
import { conversationIds, FixtureConversationGateway } from '../test/conversationFixtures'
import { FixtureConversationMessageGateway } from '../test/conversationMessageFixtures'
import { FixtureConversationRealtimeGateway } from '../test/conversationRealtimeFixtures'
import { fixtureIds, FixtureScopeGateway } from '../test/scopeFixtures'
import { fixtureConfirmationPreview, fixtureTaskIntent, taskIntentIds } from '../test/taskIntentFixtures'
import { FixtureTaskGateway } from '../test/taskFixtures'
import ConversationPage from './ConversationPage.vue'

/**
 * M9b-F03 page-level reading contract: scrolling a conversation persists the anchor (first visible
 * message + pixel offset) into the scoped F05 `conversation-anchor` segment, and a remount restores
 * it; the TaskIntent pre-message card arrives folded as a summary and expands only on demand (R19).
 */
interface StoredAnchor { messageId: string; offsetWithin: number; sequence: number; savedAt: string }

/** The provider fixture conversation's second message (sequence 2, agent reply). */
const SECOND_MESSAGE_ID = '00000000-0000-0000-0000-000000001302'

class FixtureTaskIntentGateway implements TaskIntentGateway {
  intent: TaskIntent = fixtureTaskIntent()

  async get(): Promise<{ value: TaskIntent, etag: string }> {
    return { value: structuredClone(this.intent), etag: '"v2"' }
  }

  async revise(): Promise<void> {}
  async confirm(): Promise<void> {}
  async reject(): Promise<void> {}
  async previewConfirmation(): Promise<VersionedTaskIntentConfirmationPreview> {
    return { value: fixtureConfirmationPreview(this.intent), etag: '"v2"' }
  }
}

class FixtureWorkItemLinkGateway implements ConversationWorkItemLinkGateway {
  async listByConversation(): Promise<never[]> { return [] }
  async listByWorkItem(): Promise<never[]> { return [] }
}

async function harness(): Promise<{ wrapper: VueWrapper, taskIntentStore: ReturnType<typeof createTaskIntentStore> }> {
  // Scoped reading state only persists inside an activated F05 identity (sign-in epoch).
  activateF05Identity(bootstrapPrincipal.accountId)
  // RouterLink resolves every navigation name during render; unknown names throw, so register them all.
  const navigationNames = ['access-denied', 'account', 'activity', 'agent-settings', 'audit',
    'conversation', 'github-settings', 'inbox', 'invite', 'lark-settings', 'login', 'model-settings',
    'not-found', 'onboarding', 'operations', 'register', 'repository-settings', 'search', 'setup',
    'team-members', 'team-observer', 'today', 'work']
  const router = createRouter({
    history: createMemoryHistory(),
    routes: navigationNames.map(name => ({
      path: `/${name}`,
      name,
      component: { render: () => null },
    })),
  })
  // The project parameter must already be canonical: AppShell's scope canonicalization treats a
  // missing project as a scope change and strips `conversation` from the URL while selecting one.
  await router.push(`/conversation?team=${fixtureIds.teamPlatform}&project=${fixtureIds.projectCrewScope}&conversation=${conversationIds.provider}`)
  await router.isReady()
  const scopeStore = createScopeStore(new FixtureScopeGateway(), bootstrapPrincipal)
  await scopeStore.synchronize(fixtureIds.teamPlatform)
  const taskIntentStore = createTaskIntentStore(new FixtureTaskIntentGateway())
  const wrapper = mount(ConversationPage, {
    global: {
      plugins: [router],
      provide: {
        [SCOPE_STORE as symbol]: scopeStore,
        [CONVERSATION_STORE as symbol]: createConversationStore(new FixtureConversationGateway()),
        [CONVERSATION_MESSAGE_STORE as symbol]: createConversationMessageStore(new FixtureConversationMessageGateway()),
        [CONVERSATION_REALTIME_STORE as symbol]: createConversationRealtimeStore(new FixtureConversationRealtimeGateway()),
        [TASK_INTENT_STORE as symbol]: taskIntentStore,
        [CONVERSATION_WORK_ITEM_LINK_STORE as symbol]: createConversationWorkItemLinkStore(new FixtureWorkItemLinkGateway()),
        [TASK_STORE as symbol]: createTaskStore(new FixtureTaskGateway()),
        [AUTH_PRINCIPAL as symbol]: bootstrapPrincipal,
      },
    },
  })
  // The history container mounts once the message phase is ready; waitFor yields macrotasks so the
  // page's async restore chain settles before the test touches scroll state.
  await vi.waitFor(() => { expect(wrapper.find('.message-history').exists()).toBe(true) })
  await nextTick()
  return { wrapper, taskIntentStore }
}

/** jsdom reports 0 for every layout box; the virtual list measures rows, so give them a height. */
function mockScrollMetrics(element: HTMLElement, metrics: { scrollTop: number; clientHeight: number; scrollHeight: number }): void {
  const state = { scrollTop: metrics.scrollTop, scrollHeight: metrics.scrollHeight }
  Object.defineProperty(element, 'scrollTop', {
    get: () => state.scrollTop,
    set: (value: number) => { state.scrollTop = value },
    configurable: true,
  })
  Object.defineProperty(element, 'clientHeight', { get: () => metrics.clientHeight, configurable: true })
  Object.defineProperty(element, 'scrollHeight', { get: () => state.scrollHeight, configurable: true })
}

const readingScope = {
  accountId: bootstrapPrincipal.accountId,
  principalId: bootstrapPrincipal.id,
  organizationId: bootstrapPrincipal.organizationId,
  teamId: fixtureIds.teamPlatform,
  projectId: null,
  objectId: null,
}

describe('ConversationPage', () => {
  let rectSpy: ReturnType<typeof vi.spyOn>

  beforeEach(() => {
    window.localStorage.clear()
    // jsdom has no layout, so rows get deterministic boxes: 108px tall on a 16px gap (the exact
    // geometry the virtual list's offset maths assumes), translated by the container's mocked
    // scrollTop. Everything else reports a 0×0 box — which is also what keeps the model paths
    // (listContentOffset) on their no-layout fallback.
    rectSpy = vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockImplementation(function (this: HTMLElement) {
      const rows = this.parentElement
        ? Array.from(this.parentElement.children).filter(child => child instanceof HTMLElement && child.dataset.virtualKey)
        : []
      const index = rows.indexOf(this)
      if (!this.dataset.virtualKey || index < 0) {
        return { x: 0, y: 0, width: 0, height: 0, top: 0, right: 0, bottom: 0, left: 0 } as DOMRect
      }
      const top = index * 124 - (this.closest<HTMLElement>('.message-history')?.scrollTop ?? 0)
      return { x: 0, y: top, width: 320, height: 108, top, right: 320, bottom: top + 108, left: 0 } as DOMRect
    })
  })

  afterEach(() => {
    rectSpy.mockRestore()
    window.localStorage.clear()
  })

  it('persists the reading anchor on scroll and restores it on the next visit', async () => {
    const first = await harness()
    // Rows measure 108px with a 16px grid gap, so the second row's top is 124 and the four rows
    // span 480px; with a 200px viewport that leaves 156px below the fold — past the 80px follow
    // threshold, so this is reading, not following.
    const history = first.wrapper.get('.message-history').element as HTMLElement
    mockScrollMetrics(history, { scrollTop: 124, clientHeight: 200, scrollHeight: 480 })
    history.dispatchEvent(new Event('scroll'))

    const stored = readF05Reading<Record<string, StoredAnchor>>(readingScope, 'conversation-anchor')
    expect(stored).not.toBeNull()
    expect(stored![conversationIds.provider]).toEqual({
      messageId: SECOND_MESSAGE_ID,
      offsetWithin: 0,
      sequence: 2,
      savedAt: expect.any(String),
    })
    first.wrapper.unmount()

    // A cold remount goes through the restore chain: the persisted anchor wins over first-unread/latest.
    const second = await harness()
    const restored = second.wrapper.get('.message-history').element as HTMLElement
    expect(restored.scrollTop).toBe(124)
    second.wrapper.unmount()
  })

  it('folds the TaskIntent pre-message card to a summary that expands on demand', async () => {
    const { wrapper, taskIntentStore } = await harness()
    await taskIntentStore.load(
      { organizationId: bootstrapPrincipal.organizationId, teamId: fixtureIds.teamPlatform, conversationId: conversationIds.provider },
      taskIntentIds.release,
    )
    await nextTick()

    const text = () => wrapper.text()
    expect(text()).toContain('结构化任务提案')
    expect(text()).toContain('展开提案')
    expect(text()).not.toContain('预检并确认')
    expect(text()).not.toContain('验收标准')

    await wrapper.findAll('button').find(button => button.text() === '展开提案')!.trigger('click')
    await nextTick()
    expect(text()).toContain('预检并确认')
    expect(text()).toContain('验收标准')
    wrapper.unmount()
  })
})
