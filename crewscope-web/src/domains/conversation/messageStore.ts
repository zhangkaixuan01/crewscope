import { inject, reactive, readonly, type App, type InjectionKey } from 'vue'
import { CrewScopeApiError } from '../../api/client'
import type { ConversationMessageGateway } from './messageGateway'
import type { ConversationMessage, ConversationMessageScope } from './types'

export type MessageHistoryPhase = 'idle' | 'loading' | 'ready' | 'empty' | 'error'
export type PendingMessageStatus = 'sending' | 'failed'

export interface PendingConversationMessage {
  clientId: string
  idempotencyKey: string
  authorPrincipalId: string
  content: string
  createdAt: string
  baselineSequence: number
  status: PendingMessageStatus
  errorMessage: string | null
}

interface ConversationMessageState {
  phase: MessageHistoryPhase
  items: ConversationMessage[]
  nextCursor: string | null
  loadingOlder: boolean
  pending: PendingConversationMessage[]
  errorMessage: string | null
  errorStatus: number | null
  olderErrorMessage: string | null
  commandErrorStatus: number | null
}

export interface ConversationMessageStore {
  state: Readonly<ConversationMessageState>
  synchronize(scope: ConversationMessageScope): Promise<void>
  load(scope: ConversationMessageScope, force?: boolean): Promise<void>
  refresh(scope: ConversationMessageScope): Promise<void>
  loadOlder(): Promise<void>
  send(scope: ConversationMessageScope, content: string, authorPrincipalId: string): Promise<boolean>
  retry(scope: ConversationMessageScope, clientId: string): Promise<boolean>
  reset(): void
}

export const CONVERSATION_MESSAGE_STORE: InjectionKey<ConversationMessageStore> = Symbol('crewscope-conversation-message-store')

export function createConversationMessageStore(gateway: ConversationMessageGateway): ConversationMessageStore {
  const state = reactive<ConversationMessageState>({
    phase: 'idle',
    items: [],
    nextCursor: null,
    loadingOlder: false,
    pending: [],
    errorMessage: null,
    errorStatus: null,
    olderErrorMessage: null,
    commandErrorStatus: null,
  })

  let activeScope: ConversationMessageScope | null = null
  let activeScopeKey: string | null = null
  let historyVersion = 0
  let historyAbort: AbortController | null = null
  let commandAbort: AbortController | null = null

  async function synchronize(scope: ConversationMessageScope): Promise<void> {
    const nextScopeKey = scopeKey(scope)
    if (activeScopeKey !== nextScopeKey) {
      changeScope(scope)
      await load(scope, true)
    } else if (state.phase === 'idle') {
      await load(scope)
    }
  }

  async function load(scope: ConversationMessageScope, force = false): Promise<void> {
    const nextScopeKey = scopeKey(scope)
    if (activeScopeKey !== nextScopeKey) changeScope(scope)
    if (!force && ['ready', 'empty'].includes(state.phase)) return
    const version = ++historyVersion
    historyAbort?.abort()
    const controller = new AbortController()
    historyAbort = controller
    state.phase = 'loading'
    state.items = []
    state.nextCursor = null
    state.loadingOlder = false
    state.errorMessage = null
    state.errorStatus = null
    state.olderErrorMessage = null
    try {
      const page = await gateway.listMessages({ ...scope, limit: 50 }, controller.signal)
      if (version !== historyVersion || activeScopeKey !== nextScopeKey) return
      state.items = canonicalMessages(page.items)
      state.nextCursor = page.nextCursor
      state.phase = page.items.length === 0 ? 'empty' : 'ready'
    } catch (error) {
      if (isAbort(error) || version !== historyVersion || activeScopeKey !== nextScopeKey) return
      state.phase = 'error'
      state.errorMessage = presentError(error, '暂时无法加载消息，请稍后重试')
      state.errorStatus = statusOf(error)
    } finally {
      if (historyAbort === controller) historyAbort = null
    }
  }

  async function loadOlder(): Promise<void> {
    if (!activeScope || !state.nextCursor || state.loadingOlder) return
    const scope = { ...activeScope }
    const targetScopeKey = activeScopeKey
    const cursor = state.nextCursor
    const version = historyVersion
    historyAbort?.abort()
    const controller = new AbortController()
    historyAbort = controller
    state.loadingOlder = true
    state.olderErrorMessage = null
    try {
      const page = await gateway.listMessages({ ...scope, after: cursor, limit: 50 }, controller.signal)
      if (version !== historyVersion || activeScopeKey !== targetScopeKey) return
      state.items = mergeMessages(state.items, page.items)
      state.nextCursor = page.nextCursor
      state.phase = state.items.length === 0 ? 'empty' : 'ready'
    } catch (error) {
      if (!isAbort(error) && version === historyVersion && activeScopeKey === targetScopeKey) {
        state.olderErrorMessage = presentError(error, '暂时无法加载更早消息，请重试')
      }
    } finally {
      if (historyAbort === controller) historyAbort = null
      if (version === historyVersion && activeScopeKey === targetScopeKey) state.loadingOlder = false
    }
  }

  async function send(
    scope: ConversationMessageScope,
    content: string,
    authorPrincipalId: string,
  ): Promise<boolean> {
    const normalizedContent = content.trim()
    if (!normalizedContent) return false
    if (activeScopeKey !== scopeKey(scope)) changeScope(scope)
    const pending: PendingConversationMessage = {
      clientId: crypto.randomUUID(),
      idempotencyKey: crypto.randomUUID(),
      authorPrincipalId,
      content: normalizedContent,
      createdAt: new Date().toISOString(),
      baselineSequence: newestSequence(state.items),
      status: 'sending',
      errorMessage: null,
    }
    state.pending.push(pending)
    // Continue with the reactive proxy stored in the array. Mutating the pre-reactive object would
    // update raw values without invalidating computed send state in the page.
    const reactivePending = state.pending.find(item => item.clientId === pending.clientId)!
    return deliver(scope, reactivePending)
  }

  async function retry(scope: ConversationMessageScope, clientId: string): Promise<boolean> {
    if (activeScopeKey !== scopeKey(scope)) return false
    const pending = state.pending.find(item => item.clientId === clientId)
    if (!pending || pending.status !== 'failed') return false
    pending.status = 'sending'
    pending.errorMessage = null
    return deliver(scope, pending)
  }

  async function deliver(scope: ConversationMessageScope, pending: PendingConversationMessage): Promise<boolean> {
    const targetScopeKey = scopeKey(scope)
    commandAbort?.abort()
    const controller = new AbortController()
    commandAbort = controller
    state.commandErrorStatus = null
    try {
      await gateway.postMessage(scope, { content: pending.content }, pending.idempotencyKey, controller.signal)
      if (activeScopeKey !== targetScopeKey || !state.pending.some(item => item.clientId === pending.clientId)) return false
      await refreshLatest(scope)
      if (activeScopeKey !== targetScopeKey) return false
      const committed = state.items.some(message =>
        message.sequence > pending.baselineSequence
        && message.type === 'USER_MESSAGE'
        && message.authorPrincipalId === pending.authorPrincipalId
        && message.content === pending.content,
      )
      if (!committed) {
        markFailed(pending, '消息已提交，但暂时无法从历史确认。请重试同步。')
        return false
      }
      state.pending = state.pending.filter(item => item.clientId !== pending.clientId)
      return true
    } catch (error) {
      if (isAbort(error) || activeScopeKey !== targetScopeKey) return false
      markFailed(pending, presentError(error, '消息发送失败，请重试'))
      state.commandErrorStatus = statusOf(error)
      return false
    } finally {
      if (commandAbort === controller) commandAbort = null
    }
  }

  async function refreshLatest(scope: ConversationMessageScope): Promise<void> {
    const targetScopeKey = scopeKey(scope)
    const version = ++historyVersion
    historyAbort?.abort()
    const controller = new AbortController()
    historyAbort = controller
    try {
      const page = await gateway.listMessages({ ...scope, limit: 50 }, controller.signal)
      if (version !== historyVersion || activeScopeKey !== targetScopeKey) return
      state.items = mergeMessages(state.items, page.items)
      if (state.phase === 'idle' || state.phase === 'loading' || state.phase === 'error') {
        state.nextCursor = page.nextCursor
      }
      state.phase = state.items.length === 0 ? 'empty' : 'ready'
      state.errorMessage = null
      state.errorStatus = null
    } catch (error) {
      if (isAbort(error) || activeScopeKey !== targetScopeKey) return
      state.errorStatus = statusOf(error)
      throw error
    } finally {
      if (historyAbort === controller) historyAbort = null
    }
  }

  function changeScope(scope: ConversationMessageScope): void {
    historyVersion += 1
    historyAbort?.abort()
    commandAbort?.abort()
    historyAbort = null
    commandAbort = null
    activeScope = { ...scope }
    activeScopeKey = scopeKey(scope)
    clearState()
  }

  function reset(): void {
    historyVersion += 1
    historyAbort?.abort()
    commandAbort?.abort()
    historyAbort = null
    commandAbort = null
    activeScope = null
    activeScopeKey = null
    clearState()
  }

  function clearState(): void {
    state.phase = 'idle'
    state.items = []
    state.nextCursor = null
    state.loadingOlder = false
    state.pending = []
    state.errorMessage = null
    state.errorStatus = null
    state.olderErrorMessage = null
    state.commandErrorStatus = null
  }

  return {
    state: readonly(state) as Readonly<ConversationMessageState>,
    synchronize,
    load,
    refresh: refreshLatest,
    loadOlder,
    send,
    retry,
    reset,
  }
}

export function installConversationMessageStore(
  app: App,
  gateway: ConversationMessageGateway,
): ConversationMessageStore {
  const store = createConversationMessageStore(gateway)
  app.provide(CONVERSATION_MESSAGE_STORE, store)
  return store
}

export function useConversationMessageStore(): ConversationMessageStore {
  const store = inject(CONVERSATION_MESSAGE_STORE)
  if (!store) throw new Error('CrewScope Conversation Message Store is not installed')
  return store
}

function scopeKey(scope: ConversationMessageScope): string {
  return `${scope.organizationId}:${scope.teamId}:${scope.conversationId}`
}

function canonicalMessages(messages: ConversationMessage[]): ConversationMessage[] {
  return mergeMessages([], messages)
}

function mergeMessages(current: ConversationMessage[], incoming: ConversationMessage[]): ConversationMessage[] {
  const facts = new Map(current.map(message => [message.id, message]))
  incoming.forEach(message => facts.set(message.id, message))
  return [...facts.values()].sort((left, right) => left.sequence - right.sequence)
}

function newestSequence(messages: ConversationMessage[]): number {
  return messages.reduce((latest, message) => Math.max(latest, message.sequence), 0)
}

function markFailed(pending: PendingConversationMessage, message: string): void {
  pending.status = 'failed'
  pending.errorMessage = message
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
