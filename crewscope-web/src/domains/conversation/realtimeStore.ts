import { inject, reactive, readonly, type App, type InjectionKey } from 'vue'
import { CrewScopeApiError } from '../../api/client'
import type { ConversationRealtimeGateway } from './realtimeGateway'
import type {
  ClarificationRequest,
  ConversationMessage,
  ConversationMessageScope,
  RealtimeEventEnvelope,
} from './types'

export type AgentInvocationPhase =
  | 'idle'
  | 'connecting'
  | 'running'
  | 'reconnecting'
  | 'cancelling'
  | 'interrupted'
  | 'completed'
  | 'cancelled'
  | 'error'

export type ConversationEventStreamPhase = 'idle' | 'connecting' | 'connected' | 'reconnecting' | 'error'

interface ConversationRealtimeState {
  invocationPhase: AgentInvocationPhase
  eventStreamPhase: ConversationEventStreamPhase
  invocationId: string | null
  submittedContent: string | null
  submittedAuthorPrincipalId: string | null
  submittedAt: string | null
  baselineSequence: number
  streamedContent: string
  safePrompt: string | null
  clarification: ClarificationRequest | null
  errorMessage: string | null
  errorStatus: number | null
  retryable: boolean
  lastCursor: string | null
  messageRefreshVersion: number
  taskIntentRefreshVersion: number
  latestTaskIntentId: string | null
  projectionGap: boolean
}

export interface ConversationRealtimeStore {
  state: Readonly<ConversationRealtimeState>
  synchronize(scope: ConversationMessageScope): void
  invoke(
    scope: ConversationMessageScope,
    message: string,
    authorPrincipalId: string,
    baselineSequence: number,
  ): Promise<boolean>
  retry(scope: ConversationMessageScope): Promise<boolean>
  resume(
    scope: ConversationMessageScope,
    answers: Record<string, string>,
    authorPrincipalId: string,
    baselineSequence: number,
  ): Promise<boolean>
  cancel(scope: ConversationMessageScope, reason?: string): Promise<boolean>
  reconcile(messages: ConversationMessage[]): void
  reset(): void
}

interface RealtimeStoreOptions {
  reconnectDelay?: (attempt: number, signal: AbortSignal) => Promise<void>
  storage?: Storage | null
  maxInvocationReconnects?: number
}

interface InvocationRecovery {
  kind?: 'invoke'
  content: string
  authorPrincipalId: string
  baselineSequence: number
  idempotencyKey: string
}

interface ResumeRecovery {
  kind: 'resume'
  invocationId: string
  answers: Record<string, string>
  content: string
  authorPrincipalId: string
  baselineSequence: number
  idempotencyKey: string
}

interface PendingClarification {
  invocationId: string
  safePrompt: string
  clarification: ClarificationRequest
}

type SegmentRecovery = InvocationRecovery | ResumeRecovery

export const CONVERSATION_REALTIME_STORE: InjectionKey<ConversationRealtimeStore> = Symbol('crewscope-conversation-realtime-store')

export function createConversationRealtimeStore(
  gateway: ConversationRealtimeGateway,
  options: RealtimeStoreOptions = {},
): ConversationRealtimeStore {
  const state = reactive<ConversationRealtimeState>({
    invocationPhase: 'idle',
    eventStreamPhase: 'idle',
    invocationId: null,
    submittedContent: null,
    submittedAuthorPrincipalId: null,
    submittedAt: null,
    baselineSequence: 0,
    streamedContent: '',
    safePrompt: null,
    clarification: null,
    errorMessage: null,
    errorStatus: null,
    retryable: false,
    lastCursor: null,
    messageRefreshVersion: 0,
    taskIntentRefreshVersion: 0,
    latestTaskIntentId: null,
    projectionGap: false,
  })
  const storage = options.storage === undefined ? browserStorage() : options.storage
  const reconnectDelay = options.reconnectDelay ?? defaultReconnectDelay
  const maxInvocationReconnects = options.maxInvocationReconnects ?? 3
  const seenInvocationEventIds = new Set<string>()
  const invocationEventIdOrder: string[] = []
  const seenDurableEventIds = new Set<string>()
  const durableEventIdOrder: string[] = []
  const seenDomainEventIds = new Set<string>()
  const domainEventIdOrder: string[] = []
  const aggregateVersions = new Map<string, number>()
  let activeScopeKey: string | null = null
  let invocationAbort: AbortController | null = null
  let eventAbort: AbortController | null = null
  let generation = 0

  function synchronize(scope: ConversationMessageScope): void {
    const nextKey = scopeKey(scope)
    if (nextKey === activeScopeKey) return
    changeScope(scope)
    state.latestTaskIntentId = safeGet(storage, taskIntentKey(nextKey))
    const currentGeneration = generation
    eventAbort = new AbortController()
    void consumeDurableEvents(scope, currentGeneration, eventAbort.signal)
    const recovery = readRecovery(nextKey)
    if (recovery) {
      stageInvocation(recovery.content, recovery.authorPrincipalId, recovery.baselineSequence, 'reconnecting', recovery)
      invocationAbort = new AbortController()
      void executeSegment(scope, recovery, currentGeneration, invocationAbort.signal)
    } else {
      restorePendingClarification(nextKey)
    }
  }

  async function invoke(
    scope: ConversationMessageScope,
    message: string,
    authorPrincipalId: string,
    baselineSequence: number,
  ): Promise<boolean> {
    const content = normalizeMessageContent(message)
    if (!publicMessageText(content, 1, MAX_MESSAGE_CONTENT_LENGTH)
      || activeScopeKey !== scopeKey(scope)
      || invocationUnavailable(state.invocationPhase)) return false
    const recovery: InvocationRecovery = {
      content,
      authorPrincipalId,
      baselineSequence,
      idempotencyKey: crypto.randomUUID(),
    }
    stageInvocation(content, authorPrincipalId, baselineSequence, 'connecting', recovery)
    writeRecovery(scopeKey(scope), recovery)
    invocationAbort?.abort()
    invocationAbort = new AbortController()
    return executeSegment(scope, recovery, generation, invocationAbort.signal)
  }

  async function resume(
    scope: ConversationMessageScope,
    answers: Record<string, string>,
    authorPrincipalId: string,
    baselineSequence: number,
  ): Promise<boolean> {
    if (activeScopeKey !== scopeKey(scope)
      || state.invocationPhase !== 'interrupted'
      || !state.invocationId
      || !state.clarification) return false
    const normalized = normalizeAnswers(answers, state.clarification)
    if (!normalized) return false
    const recovery: ResumeRecovery = {
      kind: 'resume',
      invocationId: state.invocationId,
      answers: normalized,
      content: clarificationMarkdown(normalized),
      authorPrincipalId,
      baselineSequence,
      idempotencyKey: crypto.randomUUID(),
    }
    stageInvocation(recovery.content, authorPrincipalId, baselineSequence, 'connecting', recovery)
    writeRecovery(scopeKey(scope), recovery)
    clearPendingClarification(scopeKey(scope))
    invocationAbort?.abort()
    invocationAbort = new AbortController()
    return executeSegment(scope, recovery, generation, invocationAbort.signal)
  }

  async function executeSegment(
    scope: ConversationMessageScope,
    recovery: SegmentRecovery,
    targetGeneration: number,
    signal: AbortSignal,
  ): Promise<boolean> {
    let reconnects = 0
    while (!signal.aborted && targetGeneration === generation) {
      try {
        if (reconnects > 0) state.invocationPhase = 'reconnecting'
        const connection = recovery.kind === 'resume'
          ? await gateway.resume(
              scope, recovery.invocationId, { answers: recovery.answers }, recovery.idempotencyKey, signal,
            )
          : await gateway.invoke(scope, { message: recovery.content }, recovery.idempotencyKey, signal)
        if (signal.aborted || targetGeneration !== generation) return false
        if (connection.invocationId) state.invocationId = connection.invocationId
        let terminal = false
        for await (const item of connection.events) {
          if (signal.aborted || targetGeneration !== generation) return false
          terminal = consumeInvocationEvent(item.event)
          if (terminal) break
        }
        if (terminal) {
          if (!(state.invocationPhase === 'error' && state.retryable)) {
            clearRecovery(scopeKey(scope))
          }
          return state.invocationPhase === 'completed'
        }
        if (reconnects >= maxInvocationReconnects) {
          failInvocation('Agent 连接已中断，请稍后重试', 0, true)
          return false
        }
      } catch (error) {
        if (isAbort(error) || signal.aborted || targetGeneration !== generation) return false
        if (!retryable(error) || reconnects >= maxInvocationReconnects) {
          failInvocation(presentError(error, 'Agent 暂时无法回复，请稍后重试'), statusOf(error), retryable(error))
          if (!retryable(error)) clearRecovery(scopeKey(scope))
          return false
        }
      }
      reconnects += 1
      state.invocationPhase = 'reconnecting'
      await reconnectDelay(reconnects, signal).catch(() => undefined)
    }
    return false
  }

  async function retry(scope: ConversationMessageScope): Promise<boolean> {
    if (activeScopeKey !== scopeKey(scope) || state.invocationPhase !== 'error' || !state.retryable) return false
    const recovery = readRecovery(scopeKey(scope))
    if (!recovery) return false
    state.invocationPhase = 'reconnecting'
    state.errorMessage = null
    state.errorStatus = null
    invocationAbort?.abort()
    invocationAbort = new AbortController()
    return executeSegment(scope, recovery, generation, invocationAbort.signal)
  }

  function consumeInvocationEvent(event: RealtimeEventEnvelope): boolean {
    if (event.streamType !== 'AG_UI'
      || !remember(event.eventId, seenInvocationEventIds, invocationEventIdOrder)) return false
    const payload = event.payload
    if (!isRecord(payload)) return false
    switch (event.eventType) {
      case 'RUN_STARTED':
        state.invocationPhase = 'running'
        return false
      case 'TEXT_MESSAGE_CONTENT': {
        const delta = typeof payload.delta === 'string' ? payload.delta : ''
        if (delta.length > MAX_STREAM_DELTA_LENGTH
          || state.streamedContent.length + delta.length > MAX_MESSAGE_CONTENT_LENGTH) {
          failInvocation('Agent 返回内容超过安全限制，请重新发起', null, false)
          return true
        }
        if (delta) state.streamedContent += delta
        state.invocationPhase = 'running'
        return false
      }
      case 'RUN_INTERRUPTED': {
        const clarification = parseClarification(payload.clarification)
        if (!clarification) {
          clearPendingClarification(scopeKeyFromActive())
          failInvocation('Agent 返回的澄清请求无效，请重新发起', null, false)
          return true
        }
        state.invocationPhase = 'interrupted'
        state.safePrompt = publicText(payload.safePrompt, 1, 1_000)
          ? payload.safePrompt.trim()
          : '需要补充信息后继续'
        state.clarification = clarification
        if (state.invocationId) {
          writePendingClarification(scopeKeyFromActive(), {
            invocationId: state.invocationId,
            safePrompt: state.safePrompt,
            clarification,
          })
        }
        return true
      }
      case 'RUN_FINISHED':
        if (payload.status !== 'COMPLETED' && payload.status !== 'CANCELED') {
          failInvocation('Agent 返回了无效的完成状态，请重新发起', null, false)
          return true
        }
        state.invocationPhase = payload.status === 'CANCELED' ? 'cancelled' : 'completed'
        clearPendingClarification(scopeKeyFromActive())
        state.messageRefreshVersion += 1
        return true
      case 'RUN_ERROR':
        clearPendingClarification(scopeKeyFromActive())
        failInvocation(
          publicText(payload.safeMessage, 1, 1_000) ? payload.safeMessage.trim() : 'Agent 暂时无法回复',
          null,
          payload.retryable === true,
        )
        return true
      default:
        // Unknown AG-UI events remain undisclosed until the frontend contract explicitly supports them.
        return false
    }
  }

  async function consumeDurableEvents(
    scope: ConversationMessageScope,
    targetGeneration: number,
    signal: AbortSignal,
  ): Promise<void> {
    let attempt = 0
    let after = readCursor(scopeKey(scope))
    state.lastCursor = after
    while (!signal.aborted && targetGeneration === generation) {
      state.eventStreamPhase = attempt === 0 ? 'connecting' : 'reconnecting'
      try {
        const connection = await gateway.streamEvents(scope, after ?? undefined, signal)
        if (signal.aborted || targetGeneration !== generation) return
        state.eventStreamPhase = 'connected'
        let receivedEvent = false
        for await (const item of connection.events) {
          if (signal.aborted || targetGeneration !== generation) return
          if (!receivedEvent) {
            attempt = 0
            receivedEvent = true
          }
          if (item.cursor) {
            after = item.cursor
            state.lastCursor = item.cursor
            writeCursor(scopeKey(scope), item.cursor)
          }
          consumeDurableEvent(item.event)
        }
      } catch (error) {
        if (isAbort(error) || signal.aborted || targetGeneration !== generation) return
        const status = statusOf(error)
        state.errorStatus = status
        if (status === 410) {
          after = null
          state.lastCursor = null
          state.projectionGap = true
          state.messageRefreshVersion += 1
          clearCursor(scopeKey(scope))
        } else if (status !== 0 && status !== null && status < 500) {
          state.eventStreamPhase = 'error'
          return
        }
      }
      attempt += 1
      state.eventStreamPhase = 'reconnecting'
      await reconnectDelay(attempt, signal).catch(() => undefined)
    }
  }

  function consumeDurableEvent(event: RealtimeEventEnvelope): void {
    if (event.streamType !== 'CONVERSATION'
      || !remember(event.eventId, seenDurableEventIds, durableEventIdOrder)) return
    if (event.domainEventId && !remember(event.domainEventId, seenDomainEventIds, domainEventIdOrder)) return
    if (event.aggregateId && event.aggregateVersion !== null) {
      const aggregateKey = `${event.aggregateType ?? 'UNKNOWN'}:${event.aggregateId}`
      const previous = aggregateVersions.get(aggregateKey)
      if (previous !== undefined && event.aggregateVersion <= previous) return
      if (previous !== undefined && event.aggregateVersion > previous + 1) {
        state.projectionGap = true
        state.messageRefreshVersion += 1
      }
      aggregateVersions.set(aggregateKey, event.aggregateVersion)
    }
    if (event.eventType === 'CONVERSATION_MESSAGE_POSTED') state.messageRefreshVersion += 1
    if (TASK_INTENT_EVENTS.has(event.eventType) && event.aggregateId) {
      state.latestTaskIntentId = event.aggregateId
      state.taskIntentRefreshVersion += 1
      safeSet(storage, taskIntentKey(scopeKeyFromActive()), event.aggregateId)
    }
  }

  async function cancel(scope: ConversationMessageScope, reason = 'Owner requested cancellation'): Promise<boolean> {
    if (activeScopeKey !== scopeKey(scope) || !state.invocationId || !invocationUnavailable(state.invocationPhase)) return false
    state.invocationPhase = 'cancelling'
    try {
      const result = await gateway.cancel(scope, state.invocationId, reason, crypto.randomUUID())
      if (result.result === 'NOT_FOUND') {
        failInvocation('当前 Agent 调用不存在或已经不可用', 404, false)
        clearRecovery(scopeKey(scope))
        return false
      }
      return true
    } catch (error) {
      if (isAbort(error)) return false
      failInvocation(presentError(error, '暂时无法取消 Agent 调用'), statusOf(error), retryable(error))
      return false
    }
  }

  function reconcile(messages: ConversationMessage[]): void {
    if (state.submittedContent && messages.some(message =>
      message.sequence > state.baselineSequence
      && message.type === 'USER_MESSAGE'
      && message.authorPrincipalId === state.submittedAuthorPrincipalId
      && message.content === state.submittedContent,
    )) {
      state.submittedContent = null
      state.submittedAuthorPrincipalId = null
      state.submittedAt = null
    }
    if (state.streamedContent && messages.some(message =>
      message.type === 'AGENT_MESSAGE' && message.content === state.streamedContent,
    )) {
      state.streamedContent = ''
    }
    if (!state.submittedContent && !state.streamedContent && state.invocationPhase === 'completed') {
      state.invocationPhase = 'idle'
      state.invocationId = null
    }
    state.projectionGap = false
  }

  function changeScope(scope: ConversationMessageScope): void {
    generation += 1
    invocationAbort?.abort()
    eventAbort?.abort()
    invocationAbort = null
    eventAbort = null
    activeScopeKey = scopeKey(scope)
    seenInvocationEventIds.clear()
    invocationEventIdOrder.length = 0
    seenDurableEventIds.clear()
    durableEventIdOrder.length = 0
    seenDomainEventIds.clear()
    domainEventIdOrder.length = 0
    aggregateVersions.clear()
    clearState()
  }

  function reset(): void {
    generation += 1
    invocationAbort?.abort()
    eventAbort?.abort()
    invocationAbort = null
    eventAbort = null
    activeScopeKey = null
    seenInvocationEventIds.clear()
    invocationEventIdOrder.length = 0
    seenDurableEventIds.clear()
    durableEventIdOrder.length = 0
    seenDomainEventIds.clear()
    domainEventIdOrder.length = 0
    aggregateVersions.clear()
    clearState()
  }

  function stageInvocation(
    content: string,
    authorPrincipalId: string,
    baselineSequence: number,
    phase: AgentInvocationPhase,
    recovery: SegmentRecovery,
  ): void {
    state.invocationPhase = phase
    state.invocationId = recovery.kind === 'resume' ? recovery.invocationId : null
    state.submittedContent = content
    state.submittedAuthorPrincipalId = authorPrincipalId
    state.submittedAt = new Date().toISOString()
    state.baselineSequence = baselineSequence
    state.streamedContent = ''
    state.safePrompt = null
    state.clarification = null
    state.errorMessage = null
    state.errorStatus = null
    state.retryable = false
  }

  function failInvocation(message: string, status: number | null, canRetry: boolean): void {
    state.invocationPhase = 'error'
    state.errorMessage = message
    state.errorStatus = status
    state.retryable = canRetry
  }

  function clearState(): void {
    state.invocationPhase = 'idle'
    state.eventStreamPhase = 'idle'
    state.invocationId = null
    state.submittedContent = null
    state.submittedAuthorPrincipalId = null
    state.submittedAt = null
    state.baselineSequence = 0
    state.streamedContent = ''
    state.safePrompt = null
    state.clarification = null
    state.errorMessage = null
    state.errorStatus = null
    state.retryable = false
    state.lastCursor = null
    state.messageRefreshVersion = 0
    state.taskIntentRefreshVersion = 0
    state.latestTaskIntentId = null
    state.projectionGap = false
  }

  function readRecovery(key: string): SegmentRecovery | null {
    const storageKey = recoveryKey(key)
    const recovery = parseRecovery(readJson<unknown>(storage, storageKey))
    if (!recovery) safeRemove(storage, storageKey)
    return recovery
  }

  function writeRecovery(key: string, recovery: SegmentRecovery): void {
    safeSet(storage, recoveryKey(key), JSON.stringify(recovery))
  }

  function clearRecovery(key: string): void {
    safeRemove(storage, recoveryKey(key))
  }

  function readCursor(key: string): string | null {
    return safeGet(storage, cursorKey(key))
  }

  function writeCursor(key: string, cursor: string): void {
    safeSet(storage, cursorKey(key), cursor)
  }

  function clearCursor(key: string): void {
    safeRemove(storage, cursorKey(key))
  }

  function restorePendingClarification(key: string): void {
    const pending = readJson<PendingClarification>(storage, clarificationKey(key))
    const clarification = pending ? parseClarification(pending.clarification) : null
    if (!pending
      || !publicText(pending.invocationId, 1, 128)
      || !publicText(pending.safePrompt, 1, 1_000)
      || !clarification) {
      safeRemove(storage, clarificationKey(key))
      return
    }
    state.invocationId = pending.invocationId
    state.safePrompt = pending.safePrompt.trim()
    state.clarification = clarification
    state.invocationPhase = 'interrupted'
  }

  function writePendingClarification(key: string, pending: PendingClarification): void {
    if (key) safeSet(storage, clarificationKey(key), JSON.stringify(pending))
  }

  function clearPendingClarification(key: string): void {
    if (key) safeRemove(storage, clarificationKey(key))
  }

  function scopeKeyFromActive(): string {
    return activeScopeKey ?? ''
  }

  return {
    state: readonly(state) as Readonly<ConversationRealtimeState>,
    synchronize,
    invoke,
    retry,
    resume,
    cancel,
    reconcile,
    reset,
  }
}

export function installConversationRealtimeStore(
  app: App,
  gateway: ConversationRealtimeGateway,
): ConversationRealtimeStore {
  const store = createConversationRealtimeStore(gateway)
  app.provide(CONVERSATION_REALTIME_STORE, store)
  return store
}

export function useConversationRealtimeStore(): ConversationRealtimeStore {
  const store = inject(CONVERSATION_REALTIME_STORE)
  if (!store) throw new Error('CrewScope Conversation Realtime Store is not installed')
  return store
}

function invocationActive(phase: AgentInvocationPhase): boolean {
  return ['connecting', 'running', 'reconnecting', 'cancelling'].includes(phase)
}

function invocationUnavailable(phase: AgentInvocationPhase): boolean {
  return invocationActive(phase) || phase === 'interrupted'
}

function scopeKey(scope: ConversationMessageScope): string {
  return `${scope.organizationId}:${scope.teamId}:${scope.conversationId}`
}

function recoveryKey(scope: string): string {
  return `crewscope:conversation:invocation:${scope}`
}

function cursorKey(scope: string): string {
  return `crewscope:conversation:events:${scope}`
}

function clarificationKey(scope: string): string {
  return `crewscope:conversation:clarification:${scope}`
}

function taskIntentKey(scope: string): string {
  return `crewscope:conversation:task-intent:${scope}`
}

const TASK_INTENT_EVENTS = new Set([
  'TASK_INTENT_PROPOSED',
  'TASK_INTENT_REVISED',
  'TASK_INTENT_REJECTED',
  'TASK_INTENT_CONFIRMED',
])

const MAX_STREAM_DELTA_LENGTH = 10_000
const MAX_MESSAGE_CONTENT_LENGTH = 50_000

function parseClarification(value: unknown): ClarificationRequest | null {
  if (!isRecord(value)
    || value.schemaVersion !== '1'
    || !publicText(value.summary, 1, 1_000)
    || !Array.isArray(value.questions)
    || value.questions.length < 1
    || value.questions.length > 10) return null
  const fieldKeys = new Set<string>()
  const questions = value.questions.map(raw => {
    if (!isRecord(raw)
      || typeof raw.fieldKey !== 'string'
      || !/^[a-z][a-z0-9_]{0,63}$/.test(raw.fieldKey)
      || fieldKeys.has(raw.fieldKey)
      || !publicText(raw.question, 1, 500)
      || (raw.context !== null && raw.context !== undefined && !publicText(raw.context, 0, 1_000))
      || typeof raw.required !== 'boolean'
      || !Array.isArray(raw.choices)
      || raw.choices.length > 5
      || !raw.choices.every(choice => publicText(choice, 1, 200))) return null
    fieldKeys.add(raw.fieldKey)
    return {
      fieldKey: raw.fieldKey,
      question: raw.question.trim(),
      context: typeof raw.context === 'string' ? raw.context.trim() : null,
      required: raw.required,
      choices: raw.choices.map(choice => (choice as string).trim()),
    }
  })
  if (questions.some(question => question === null)) return null
  return {
    schemaVersion: '1',
    summary: value.summary.trim(),
    questions: questions as ClarificationRequest['questions'],
  }
}

function publicText(value: unknown, minimum: number, maximum: number): value is string {
  return typeof value === 'string'
    && value.trim().length >= minimum
    && value.trim().length <= maximum
    && !/\p{Cc}/u.test(value)
}

/**
 * Normalizes user-authored message content while preserving its Markdown layout.
 * Browser textareas can contain either LF or CRLF depending on how text was pasted.
 */
function normalizeMessageContent(value: string): string {
  return value.replace(/\r\n?/g, '\n').trim()
}

/**
 * Accepts the formatting controls used by multiline Markdown and rejects all other
 * Unicode control characters before content reaches the invocation boundary.
 */
function publicMessageText(value: unknown, minimum: number, maximum: number): value is string {
  if (typeof value !== 'string') return false
  const trimmed = value.trim()
  return trimmed.length >= minimum
    && trimmed.length <= maximum
    && !/\p{Cc}/u.test(value.replace(/[\n\t]/g, ''))
}

function normalizeAnswers(
  answers: Record<string, string>,
  clarification: ClarificationRequest,
): Record<string, string> | null {
  const declared = new Map(clarification.questions.map(question => [question.fieldKey, question]))
  const normalized: Record<string, string> = {}
  for (const [fieldKey, rawValue] of Object.entries(answers)) {
    const question = declared.get(fieldKey)
    const value = rawValue.trim()
    if (!question || !value || value.length > 1_000) return null
    if (question.choices.length > 0 && !question.choices.includes(value)) return null
    normalized[fieldKey] = value
  }
  if (clarification.questions.some(question => question.required && !normalized[question.fieldKey])) return null
  return Object.keys(normalized).length > 0 ? normalized : null
}

function parseRecovery(value: unknown): SegmentRecovery | null {
  if (!isRecord(value)) return null
  const content = typeof value.content === 'string' ? normalizeMessageContent(value.content) : ''
  if (!publicMessageText(content, 1, MAX_MESSAGE_CONTENT_LENGTH)
    || !publicText(value.authorPrincipalId, 1, 512)
    || !Number.isSafeInteger(value.baselineSequence)
    || (value.baselineSequence as number) < 0
    || !publicText(value.idempotencyKey, 1, 512)) return null
  const common = {
    content,
    authorPrincipalId: value.authorPrincipalId.trim(),
    baselineSequence: value.baselineSequence as number,
    idempotencyKey: value.idempotencyKey.trim(),
  }
  if (value.kind === undefined || value.kind === 'invoke') {
    return { ...common, kind: value.kind }
  }
  if (value.kind !== 'resume'
    || !publicText(value.invocationId, 1, 512)
    || !isRecord(value.answers)) return null
  const entries = Object.entries(value.answers)
  if (entries.length < 1 || entries.length > 10) return null
  const answers: Record<string, string> = {}
  for (const [fieldKey, answer] of entries) {
    if (!/^[a-z][a-z0-9_]{0,63}$/.test(fieldKey) || !publicText(answer, 1, 1_000)) return null
    answers[fieldKey] = answer.trim()
  }
  return {
    ...common,
    kind: 'resume',
    invocationId: value.invocationId.trim(),
    answers,
  }
}

function clarificationMarkdown(answers: Record<string, string>): string {
  return Object.keys(answers).sort().reduce(
    (text, key) => `${text}\n- **${key}**: ${answers[key]}`,
    '澄清回答：',
  )
}

function remember(value: string, values: Set<string>, order: string[]): boolean {
  if (values.has(value)) return false
  values.add(value)
  order.push(value)
  if (order.length > 2_048) values.delete(order.shift()!)
  return true
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function isAbort(error: unknown): boolean {
  return error instanceof DOMException && error.name === 'AbortError'
}

function statusOf(error: unknown): number | null {
  return error instanceof CrewScopeApiError ? error.status : null
}

function retryable(error: unknown): boolean {
  return error instanceof CrewScopeApiError ? error.envelope.retryable : true
}

function presentError(error: unknown, fallback: string): string {
  return error instanceof CrewScopeApiError ? error.envelope.message : fallback
}

function browserStorage(): Storage | null {
  try {
    return typeof sessionStorage === 'undefined' ? null : sessionStorage
  } catch {
    return null
  }
}

function safeGet(storage: Storage | null, key: string): string | null {
  try {
    return storage?.getItem(key) ?? null
  } catch {
    return null
  }
}

function safeSet(storage: Storage | null, key: string, value: string): void {
  try {
    storage?.setItem(key, value)
  } catch {
    // Recovery storage is best-effort; server idempotency remains authoritative.
  }
}

function safeRemove(storage: Storage | null, key: string): void {
  try {
    storage?.removeItem(key)
  } catch {
    // Ignore unavailable browser storage.
  }
}

function readJson<T>(storage: Storage | null, key: string): T | null {
  const value = safeGet(storage, key)
  if (!value) return null
  try {
    return JSON.parse(value) as T
  } catch {
    safeRemove(storage, key)
    return null
  }
}

function defaultReconnectDelay(attempt: number, signal: AbortSignal): Promise<void> {
  const timeout = Math.min(4_000, 300 * 2 ** Math.min(attempt - 1, 4))
  return new Promise((resolve, reject) => {
    const onAbort = () => {
      clearTimeout(timer)
      reject(new DOMException('Aborted', 'AbortError'))
    }
    const timer = setTimeout(() => {
      signal.removeEventListener('abort', onAbort)
      resolve()
    }, timeout)
    signal.addEventListener('abort', onAbort, { once: true })
  })
}
