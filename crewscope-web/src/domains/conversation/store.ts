import {getCurrentInstance, inject, reactive, readonly, type App, type InjectionKey} from 'vue'
import { registerStoreReset } from '../../app/storeLifecycle'
import { CrewScopeApiError } from '../../api/client'
import { commandFailureMessage, createCommandIntents } from '../../api/commandIntent'
import { acknowledgeCreation } from '../../api/creationRecovery'
import { secureId } from '../../api/secureId'
import { HttpConversationGateway, type ConversationGateway } from './gateway'
import type {
  ConversationDetails,
  ConversationScope,
  ConversationStatus,
  ConversationSummary,
  CreateConversationInput,
} from './types'
import { isAbort, statusOf, presentError } from '../../api/errors'

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
  synchronize(scope: ConversationScope, conversationId?: string | null, force?: boolean): Promise<void>
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
  const createIntents = createCommandIntents<CreateConversationInput, Awaited<ReturnType<ConversationGateway['createConversation']>>>()
  let activeCreate: Promise<string | null> | null = null
  let commandEpoch = 0
  let activeScopeKey: string | null = null
  // URL canonicalization can start a newer synchronization without changing the Team.
  // This version prevents the older call from restoring a Conversation that the newer URL removed.
  let synchronizationVersion = 0
  let collectionVersion = 0
  let detailVersion = 0
  let collectionAbort: AbortController | null = null
  let detailAbort: AbortController | null = null

  async function synchronize(scope: ConversationScope, conversationId?: string | null, force = false): Promise<void> {
    const version = ++synchronizationVersion
    const nextScopeKey = scopeKey(scope)
    const scopeChanged = activeScopeKey !== nextScopeKey
    if (scopeChanged) changeScope(scope)
    // M13d-A21: stores survive route switches, so a re-entry arrives with a ready list. force
    // re-reads the collection stale-while-refresh (cached rows stay on screen) — the page sets
    // it on the first synchronize after every mount so returning to Conversation reads current
    // server facts instead of showing the previous visit's list forever.
    const needsList = scopeChanged || state.phase === 'idle' || force
    if (!conversationId) {
      if (needsList) await load(scope, scopeChanged || force)
      if (version !== synchronizationVersion || activeScopeKey !== nextScopeKey) return
      clearSelection()
      return
    }
    // M13d-A12: the detail is fetched by id and never depends on the list page, so a deep link
    // starts both hops in parallel — messages paint without waiting for the list request.
    const listWork = needsList ? load(scope, scopeChanged || force) : Promise.resolve()
    await select(scope, conversationId)
    if (version !== synchronizationVersion || activeScopeKey !== nextScopeKey) return
    await listWork
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
      // M13b-F06（R12）：打开即读到消息尖端。回执只增不减，失败静默——未读没清，下次打开重试；
      // 幂等键每次打开一换，服务端按 max 推进，重复无害。
      // M13d-A11: the receipt is fire-and-forget — awaiting it used to block the detail→messages
      // handoff even though a failure is silently tolerated, i.e. it was never load-bearing.
      if (details.conversation.unreadCount > 0) {
        void gateway.submitReadReceipt(scope, conversationId, secureId())
          .then(() => {
            if (state.selectedConversationId !== conversationId || activeScopeKey !== nextScopeKey) return
            upsertSummary({ ...details.conversation, unreadCount: 0 })
          })
          .catch(() => { /* 未读保持，下次打开重试 */ })
      }
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

  function create(scope: ConversationScope, input: CreateConversationInput): Promise<string | null> {
    if (activeScopeKey !== scopeKey(scope)) changeScope(scope)
    if (activeCreate) return activeCreate
    const pending = runCreate({ ...scope }, { ...input })
    activeCreate = pending
    const clear = () => { if (activeCreate === pending) activeCreate = null }
    void pending.then(clear, clear)
    return pending
  }

  async function runCreate(scope: ConversationScope, input: CreateConversationInput): Promise<string | null> {
    const targetScopeKey = scopeKey(scope)
    const epoch = commandEpoch
    state.commandPending = true
    state.commandErrorMessage = null
    try {
      const receipt = await createIntents.execute({ ...scope, commandType: 'CREATE_CONVERSATION' }, input,
        (snapshot, key) => gateway.createConversation(scope, snapshot, key))
      if (commandEpoch !== epoch || activeScopeKey !== targetScopeKey) return null
      await load(scope, true)
      if (commandEpoch !== epoch || activeScopeKey !== targetScopeKey) return null
      if (!receipt.creation) throw new Error('Created Conversation result is not available')
      const id = receipt.creation.resourceId
      await select(scope, id)
      if (commandEpoch !== epoch || activeScopeKey !== targetScopeKey) return null
      if (state.detailPhase === 'ready') acknowledgeCreation(receipt.recoveryKey, scope.organizationId)
      return id
    } catch (error) {
      if (commandEpoch === epoch && activeScopeKey === targetScopeKey) {
        state.commandErrorMessage = commandFailureMessage(error, '暂时无法创建对话，请稍后重试')
      }
      throw error
    } finally {
      if (commandEpoch === epoch) state.commandPending = false
    }
  }

  function changeScope(scope: ConversationScope): void {
    commandEpoch += 1
    activeCreate = null
    state.commandPending = false
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
    commandEpoch += 1
    activeCreate = null
    createIntents.clear()
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

// M13d-P01: domain stores install lazily on the pages that consume them, keeping the domain out
// of the eager main chunk. Instances live on the app, so revisiting a page reuses cached state.
// Fixtures provided by specs through app-level provide always win and are never shadowed.
export function ensureConversationStore(app: App): ConversationStore {
  const existing = app.runWithContext(() => inject(CONVERSATION_STORE, null))
  if (existing) return existing
  const store = createConversationStore(new HttpConversationGateway())
  app.provide(CONVERSATION_STORE, store)
  registerStoreReset(app, () => store.reset())
  return store
}


export function useConversationStore(): ConversationStore {
  const installed = inject(CONVERSATION_STORE, null)
  if (installed) return installed
  // M13d-P01: fall back to lazy installation — a component declares its domain dependency
  // by calling use*, so no host page has to pre-install the store.
  const instance = getCurrentInstance()
  if (!instance) throw new Error('CrewScope Conversation Store is not installed')
  return ensureConversationStore(instance.appContext.app)
}

function scopeKey(scope: ConversationScope): string {
  return `${scope.organizationId}:${scope.teamId}`
}
