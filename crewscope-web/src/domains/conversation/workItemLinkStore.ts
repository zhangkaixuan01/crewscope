import { inject, reactive, readonly, type App, type InjectionKey } from 'vue'
import { CrewScopeApiError } from '../../api/client'
import type { WorkItemScope } from '../workitem/types'
import type { ConversationMessageScope } from './types'
import type {
  ConversationWorkItemAssociation,
  ConversationWorkItemLinkGateway,
} from './workItemLinkGateway'

export type ConversationWorkItemLinkPhase = 'idle' | 'loading' | 'ready' | 'empty' | 'error'

interface ConversationWorkItemLinkState {
  phase: ConversationWorkItemLinkPhase
  associations: ConversationWorkItemAssociation[]
  errorMessage: string | null
  errorStatus: number | null
}

export interface ConversationWorkItemLinkStore {
  state: Readonly<ConversationWorkItemLinkState>
  loadByConversation(scope: ConversationMessageScope, force?: boolean): Promise<void>
  loadByWorkItem(scope: WorkItemScope, workItemId: string, force?: boolean): Promise<void>
  reset(): void
}

export const CONVERSATION_WORK_ITEM_LINK_STORE: InjectionKey<ConversationWorkItemLinkStore>
  = Symbol('crewscope-conversation-work-item-link-store')

export function createConversationWorkItemLinkStore(
  gateway: ConversationWorkItemLinkGateway,
): ConversationWorkItemLinkStore {
  const state = reactive<ConversationWorkItemLinkState>({
    phase: 'idle',
    associations: [],
    errorMessage: null,
    errorStatus: null,
  })
  let activeKey: string | null = null
  let requestVersion = 0
  let controller: AbortController | null = null

  async function loadByConversation(scope: ConversationMessageScope, force = false): Promise<void> {
    const key = `conversation:${scope.organizationId}:${scope.teamId}:${scope.conversationId}`
    await load(key, force, signal => gateway.listByConversation(scope, signal))
  }

  async function loadByWorkItem(
    scope: WorkItemScope,
    workItemId: string,
    force = false,
  ): Promise<void> {
    const key = `work-item:${scope.organizationId}:${scope.teamId}:${scope.projectId}:${workItemId}`
    await load(key, force, signal => gateway.listByWorkItem(scope, workItemId, signal))
  }

  async function load(
    key: string,
    force: boolean,
    query: (signal: AbortSignal) => Promise<ConversationWorkItemAssociation[]>,
  ): Promise<void> {
    if (!force && key === activeKey && ['ready', 'empty'].includes(state.phase)) return
    controller?.abort()
    controller = new AbortController()
    const currentController = controller
    const version = ++requestVersion
    const changed = key !== activeKey
    activeKey = key
    state.phase = 'loading'
    state.errorMessage = null
    state.errorStatus = null
    if (changed) state.associations = []
    try {
      const associations = await query(currentController.signal)
      if (version !== requestVersion || activeKey !== key) return
      state.associations = associations
      state.phase = associations.length === 0 ? 'empty' : 'ready'
    } catch (error) {
      if (version !== requestVersion || activeKey !== key || currentController.signal.aborted) return
      state.phase = 'error'
      state.errorMessage = presentError(error)
      state.errorStatus = error instanceof CrewScopeApiError ? error.status : null
    }
  }

  function reset(): void {
    controller?.abort()
    controller = null
    requestVersion += 1
    activeKey = null
    state.phase = 'idle'
    state.associations = []
    state.errorMessage = null
    state.errorStatus = null
  }

  return {
    state: readonly(state) as Readonly<ConversationWorkItemLinkState>,
    loadByConversation,
    loadByWorkItem,
    reset,
  }
}

export function installConversationWorkItemLinkStore(
  app: App,
  gateway: ConversationWorkItemLinkGateway,
): ConversationWorkItemLinkStore {
  const store = createConversationWorkItemLinkStore(gateway)
  app.provide(CONVERSATION_WORK_ITEM_LINK_STORE, store)
  return store
}

export function useConversationWorkItemLinkStore(): ConversationWorkItemLinkStore {
  const store = inject(CONVERSATION_WORK_ITEM_LINK_STORE)
  if (!store) throw new Error('CrewScope Conversation WorkItem Link Store is not installed')
  return store
}

function presentError(error: unknown): string {
  return error instanceof CrewScopeApiError
    ? error.envelope.message
    : '暂时无法加载对话与工作项的关联事实'
}
