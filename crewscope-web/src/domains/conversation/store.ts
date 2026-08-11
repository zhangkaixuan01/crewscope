import { inject, reactive, readonly, type App, type InjectionKey } from 'vue'
import { CrewScopeApiError } from '../../api/client'
import type { ConversationGateway } from './gateway'
import type {
  ConversationDetails,
  ConversationScope,
  ConversationStatus,
  ConversationSummary,
  CreateConversationInput,
} from './types'

export type ConversationPhase = 'idle' | 'loading' | 'ready' | 'empty' | 'error'
export type ConversationDetailPhase = 'idle' | 'loading' | 'ready' | 'error'

interface ConversationState {
  phase: ConversationPhase
  items: ConversationSummary[]
  nextCursor: string | null
  loadingMore: boolean
  selectedConversationId: string | null
  detailPhase: ConversationDetailPhase
  details: ConversationDetails | null
  commandPending: boolean
  errorMessage: string | null
  errorStatus: number | null
  detailErrorMessage: string | null
  detailErrorStatus: number | null
  commandErrorMessage: string | null
}

export interface ConversationStore {
  state: Readonly<ConversationState>
  synchronize(scope: ConversationScope, conversationId?: string | null): Promise<void>
  load(scope: ConversationScope, force?: boolean): Promise<void>
  loadMore(): Promise<void>
  select(scope: ConversationScope, conversationId: string): Promise<void>
  clearSelection(): void
  create(scope: ConversationScope, input: CreateConversationInput): Promise<string | null>
  reset(): void
}

export const CONVERSATION_STORE: InjectionKey<ConversationStore> = Symbol('crewscope-conversation-store')

export function createConversationStore(gateway: ConversationGateway): ConversationStore {
  const state = reactive<ConversationState>({
    phase: 'idle',
    items: [],
    nextCursor: null,
    loadingMore: false,
    selectedConversationId: null,
    detailPhase: 'idle',
    details: null,
    commandPending: false,
    errorMessage: null,
    errorStatus: null,
    detailErrorMessage: null,
    detailErrorStatus: null,
    commandErrorMessage: null,
  })

  let activeScope: ConversationScope | null = null
  let activeScopeKey: string | null = null
  // URL canonicalization can start a newer synchronization without changing the Team.
  // This version prevents the older call from restoring a Conversation that the newer URL removed.
  let synchronizationVersion = 0
  let collectionVersion = 0
  let detailVersion = 0
  let collectionAbort: AbortController | null = null
  let detailAbort: AbortController | null = null

  async function synchronize(scope: ConversationScope, conversationId?: string | null): Promise<void> {
    const version = ++synchronizationVersion
    const nextScopeKey = scopeKey(scope)
    if (activeScopeKey !== nextScopeKey) {
      changeScope(scope)
      await load(scope, true)
    } else if (state.phase === 'idle') {
      await load(scope)
    }
    if (version !== synchronizationVersion || activeScopeKey !== nextScopeKey) return
    if (conversationId) await select(scope, conversationId)
    else clearSelection()
  }

  async function load(scope: ConversationScope, force = false): Promise<void> {
    const nextScopeKey = scopeKey(scope)
    if (activeScopeKey !== nextScopeKey) changeScope(scope)
    if (!force && ['ready', 'empty'].includes(state.phase)) return
    const version = ++collectionVersion
    collectionAbort?.abort()
    const controller = new AbortController()
    collectionAbort = controller
    state.phase = 'loading'
    state.errorMessage = null
    state.errorStatus = null
    state.items = []
    state.nextCursor = null
    state.loadingMore = false
    try {
      const page = await gateway.listConversations(
        { ...scope, status: 'ACTIVE' satisfies ConversationStatus, limit: 50 },
        controller.signal,
      )
      if (version !== collectionVersion || activeScopeKey !== nextScopeKey) return
      state.items = page.items
      state.nextCursor = page.nextCursor
      state.phase = page.items.length === 0 ? 'empty' : 'ready'
    } catch (error) {
      if (isAbort(error) || version !== collectionVersion || activeScopeKey !== nextScopeKey) return
      state.phase = 'error'
      state.errorMessage = presentError(error, '暂时无法加载对话，请稍后重试')
      state.errorStatus = statusOf(error)
    } finally {
      if (collectionAbort === controller) collectionAbort = null
    }
  }

  async function loadMore(): Promise<void> {
    if (!activeScope || !state.nextCursor || state.loadingMore) return
    const scope = { ...activeScope }
    const nextScopeKey = activeScopeKey
    const cursor = state.nextCursor
    const version = collectionVersion
    collectionAbort?.abort()
    const controller = new AbortController()
    collectionAbort = controller
    state.loadingMore = true
    state.errorMessage = null
    try {
      const page = await gateway.listConversations(
        { ...scope, status: 'ACTIVE', after: cursor, limit: 50 },
        controller.signal,
      )
      if (version !== collectionVersion || activeScopeKey !== nextScopeKey) return
      const known = new Set(state.items.map(item => item.id))
      state.items.push(...page.items.filter(item => !known.has(item.id)))
      state.nextCursor = page.nextCursor
    } catch (error) {
      if (!isAbort(error) && version === collectionVersion && activeScopeKey === nextScopeKey) {
        state.errorMessage = presentError(error, '暂时无法加载更多对话，请稍后重试')
      }
    } finally {
      if (collectionAbort === controller) collectionAbort = null
      if (version === collectionVersion && activeScopeKey === nextScopeKey) state.loadingMore = false
    }
  }

  async function select(scope: ConversationScope, conversationId: string): Promise<void> {
    const nextScopeKey = scopeKey(scope)
    if (activeScopeKey !== nextScopeKey) changeScope(scope)
    if (state.selectedConversationId === conversationId && state.detailPhase === 'ready') return
    const version = ++detailVersion
    detailAbort?.abort()
    const controller = new AbortController()
    detailAbort = controller
    state.selectedConversationId = conversationId
    state.detailPhase = 'loading'
    state.details = null
    state.detailErrorMessage = null
    state.detailErrorStatus = null
    try {
      const details = await gateway.getConversation(scope, conversationId, controller.signal)
      if (version !== detailVersion || activeScopeKey !== nextScopeKey) return
      state.details = details
      state.detailPhase = 'ready'
      upsertSummary(details.conversation)
    } catch (error) {
      if (isAbort(error) || version !== detailVersion || activeScopeKey !== nextScopeKey) return
      state.detailPhase = 'error'
      state.detailErrorMessage = presentError(error, '暂时无法加载对话详情，请稍后重试')
      state.detailErrorStatus = statusOf(error)
    } finally {
      if (detailAbort === controller) detailAbort = null
    }
  }

  function clearSelection(): void {
    detailVersion += 1
    detailAbort?.abort()
    detailAbort = null
    state.selectedConversationId = null
    state.detailPhase = 'idle'
    state.details = null
    state.detailErrorMessage = null
    state.detailErrorStatus = null
  }

  async function create(scope: ConversationScope, input: CreateConversationInput): Promise<string | null> {
    const targetScopeKey = scopeKey(scope)
    if (activeScopeKey !== targetScopeKey) changeScope(scope)
    const knownIds = new Set(state.items.map(item => item.id))
    state.commandPending = true
    state.commandErrorMessage = null
    try {
      await gateway.createConversation(scope, input, crypto.randomUUID())
      if (activeScopeKey !== targetScopeKey) return null
      await load(scope, true)
      if (activeScopeKey !== targetScopeKey) return null
      // A01 returns a CommandReceipt rather than the created aggregate identity. Reconcile only
      // against the refreshed server collection so the browser never invents a Conversation ID.
      const created = state.items.find(item => !knownIds.has(item.id) && item.title === input.title)
        ?? state.items.find(item => !knownIds.has(item.id))
        ?? null
      if (created) await select(scope, created.id)
      return created?.id ?? null
    } catch (error) {
      if (activeScopeKey === targetScopeKey) {
        state.commandErrorMessage = presentError(error, '暂时无法创建对话，请稍后重试')
      }
      throw error
    } finally {
      state.commandPending = false
    }
  }

  function changeScope(scope: ConversationScope): void {
    collectionVersion += 1
    detailVersion += 1
    collectionAbort?.abort()
    detailAbort?.abort()
    collectionAbort = null
    detailAbort = null
    activeScope = { ...scope }
    activeScopeKey = scopeKey(scope)
    state.phase = 'idle'
    state.items = []
    state.nextCursor = null
    state.loadingMore = false
    state.errorMessage = null
    state.errorStatus = null
    state.commandErrorMessage = null
    clearSelectionState()
  }

  function reset(): void {
    synchronizationVersion += 1
    collectionVersion += 1
    detailVersion += 1
    collectionAbort?.abort()
    detailAbort?.abort()
    collectionAbort = null
    detailAbort = null
    activeScope = null
    activeScopeKey = null
    state.phase = 'idle'
    state.items = []
    state.nextCursor = null
    state.loadingMore = false
    state.commandPending = false
    state.errorMessage = null
    state.errorStatus = null
    state.commandErrorMessage = null
    clearSelectionState()
  }

  function clearSelectionState(): void {
    state.selectedConversationId = null
    state.detailPhase = 'idle'
    state.details = null
    state.detailErrorMessage = null
    state.detailErrorStatus = null
  }

  function upsertSummary(summary: ConversationSummary): void {
    const index = state.items.findIndex(item => item.id === summary.id)
    if (index >= 0) state.items[index] = summary
    else state.items.unshift(summary)
  }

  return {
    state: readonly(state) as Readonly<ConversationState>,
    synchronize,
    load,
    loadMore,
    select,
    clearSelection,
    create,
    reset,
  }
}

export function installConversationStore(app: App, gateway: ConversationGateway): ConversationStore {
  const store = createConversationStore(gateway)
  app.provide(CONVERSATION_STORE, store)
  return store
}

export function useConversationStore(): ConversationStore {
  const store = inject(CONVERSATION_STORE)
  if (!store) throw new Error('CrewScope Conversation Store is not installed')
  return store
}

function scopeKey(scope: ConversationScope): string {
  return `${scope.organizationId}:${scope.teamId}`
}

function isAbort(error: unknown): boolean {
  return error instanceof DOMException && error.name === 'AbortError'
}

function statusOf(error: unknown): number | null {
  return error instanceof CrewScopeApiError ? error.status : null
}

function presentError(error: unknown, fallback: string): string {
  return error instanceof CrewScopeApiError ? error.envelope.message : fallback
}
