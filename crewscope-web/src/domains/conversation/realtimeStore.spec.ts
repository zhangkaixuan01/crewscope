import { CrewScopeApiError } from '../../api/client'
import { conversationIds } from '../../test/conversationFixtures'
import { fixtureIds } from '../../test/scopeFixtures'
import type { ConversationRealtimeGateway, RealtimeConnection, RealtimeStreamItem } from './realtimeGateway'
import { createConversationRealtimeStore } from './realtimeStore'
import type { AgentCancelResponse, ConversationMessageScope, RealtimeEventEnvelope } from './types'

const scope: ConversationMessageScope = {
  organizationId: fixtureIds.organization,
  teamId: fixtureIds.teamPlatform,
  conversationId: conversationIds.provider,
}

describe('conversation realtime store', () => {
  it('merges public token deltas once and ignores unknown disclosure surfaces', async () => {
    const gateway = new FixtureRealtimeGateway()
    const started = agui('agui-1', 'RUN_STARTED', {})
    const first = agui('agui-2', 'TEXT_MESSAGE_CONTENT', { delta: '公开' })
    gateway.invocations.push(connection('invocation-1', [
      item(started),
      item(first),
      item(first),
      item(agui('agui-secret', 'REASONING_CONTENT', { reasoning: 'internal chain' })),
      item(agui('agui-3', 'TEXT_MESSAGE_CONTENT', { delta: '回复' })),
      item(agui('agui-4', 'RUN_FINISHED', { status: 'COMPLETED' })),
    ]))
    const store = createStore(gateway)
    store.synchronize(scope)

    expect(await store.invoke(scope, '检查发布风险', fixtureIds.principal, 4)).toBe(true)

    expect(store.state.streamedContent).toBe('公开回复')
    expect(store.state.streamedContent).not.toContain('internal chain')
    expect(store.state.invocationPhase).toBe('completed')
    expect(gateway.invokeKeys).toHaveLength(1)
    store.reset()
  })

  it('preserves multiline Markdown and tabs while normalizing pasted line endings', async () => {
    const gateway = new FixtureRealtimeGateway()
    gateway.invocations.push(connection('invocation-multiline', [
      item(agui('multiline-finished', 'RUN_FINISHED', { status: 'COMPLETED' })),
    ]))
    const store = createStore(gateway)
    store.synchronize(scope)

    expect(await store.invoke(
      scope,
      '  请完成以下任务：\r\n1. 修复登录提示\r\n2. 增加测试\t证据  ',
      fixtureIds.principal,
      4,
    )).toBe(true)
    expect(gateway.invokedMessages).toEqual([
      '请完成以下任务：\n1. 修复登录提示\n2. 增加测试\t证据',
    ])
    store.reset()
  })

  it('rejects non-formatting control characters in message content', async () => {
    const gateway = new FixtureRealtimeGateway()
    const store = createStore(gateway)
    store.synchronize(scope)

    expect(await store.invoke(scope, '合法内容\u0000隐藏内容', fixtureIds.principal, 4)).toBe(false)
    expect(gateway.invokedMessages).toEqual([])
    store.reset()
  })

  it('replays the same invocation after disconnect and removes duplicate events', async () => {
    const gateway = new FixtureRealtimeGateway()
    const started = agui('replay-1', 'RUN_STARTED', {})
    const first = agui('replay-2', 'TEXT_MESSAGE_CONTENT', { delta: '第一段' })
    gateway.invocations.push(failingConnection('invocation-2', [item(started), item(first)]))
    gateway.invocations.push(connection('invocation-2', [
      item(started),
      item(first),
      item(agui('replay-3', 'TEXT_MESSAGE_CONTENT', { delta: '第二段' })),
      item(agui('replay-4', 'RUN_FINISHED', { status: 'COMPLETED' })),
    ]))
    const store = createStore(gateway)
    store.synchronize(scope)

    await store.invoke(scope, '断线恢复', fixtureIds.principal, 4)

    expect(store.state.streamedContent).toBe('第一段第二段')
    expect(gateway.invokeKeys).toHaveLength(2)
    expect(new Set(gateway.invokeKeys).size).toBe(1)
    store.reset()
  })

  it('persists the durable Cursor, deduplicates events and detects aggregate gaps', async () => {
    const gateway = new FixtureRealtimeGateway()
    const first = durable('durable-1', 'domain-1', 1)
    const gap = durable('durable-3', 'domain-3', 3)
    gateway.durableEvents = [
      { cursor: 'cursor-1', event: first },
      { cursor: 'cursor-duplicate', event: durable('durable-other-stream-id', 'domain-1', 1) },
      { cursor: 'cursor-3', event: gap },
    ]
    const storage = new MemoryStorage()
    const store = createStore(gateway, storage)

    store.synchronize(scope)
    await waitFor(() => store.state.lastCursor === 'cursor-3')

    expect(store.state.messageRefreshVersion).toBe(3)
    expect(store.state.projectionGap).toBe(true)
    expect(storage.values()).toContain('cursor-3')
    store.reset()
  })

  it('sends an explicit cancellation without treating HTTP disconnect as cancel', async () => {
    const gateway = new FixtureRealtimeGateway()
    gateway.invocations.push(hangingConnection('invocation-cancel'))
    const store = createStore(gateway)
    store.synchronize(scope)
    void store.invoke(scope, '停止前先开始', fixtureIds.principal, 4)
    await waitFor(() => store.state.invocationId === 'invocation-cancel')

    expect(await store.cancel(scope)).toBe(true)
    expect(gateway.cancelled).toEqual([{ invocationId: 'invocation-cancel', reason: 'Owner requested cancellation' }])
    expect(store.state.invocationPhase).toBe('cancelling')

    store.reset()
  })

  it('fails closed on a non-retryable invocation response', async () => {
    const gateway = new FixtureRealtimeGateway()
    gateway.invokeFailure = new CrewScopeApiError(403, envelope('agent_forbidden', '无权调用 Personal Agent', false))
    const store = createStore(gateway)
    store.synchronize(scope)

    expect(await store.invoke(scope, '越权调用', fixtureIds.principal, 4)).toBe(false)
    expect(store.state).toEqual(expect.objectContaining({ invocationPhase: 'error', errorStatus: 403, retryable: false }))
    store.reset()
  })

  it('retries an exhausted network stream with the original invocation key', async () => {
    const gateway = new FixtureRealtimeGateway()
    gateway.invocations.push(failingConnection('invocation-retry', []))
    gateway.invocations.push(failingConnection('invocation-retry', []))
    gateway.invocations.push(failingConnection('invocation-retry', []))
    gateway.invocations.push(connection('invocation-retry', [
      item(agui('retry-text', 'TEXT_MESSAGE_CONTENT', { delta: '恢复后回复' })),
      item(agui('retry-finished', 'RUN_FINISHED', { status: 'COMPLETED' })),
    ]))
    const store = createStore(gateway)
    store.synchronize(scope)

    expect(await store.invoke(scope, '需要稳定重连', fixtureIds.principal, 4)).toBe(false)
    expect(store.state).toEqual(expect.objectContaining({ invocationPhase: 'error', retryable: true }))
    expect(await store.retry(scope)).toBe(true)
    expect(new Set(gateway.invokeKeys).size).toBe(1)
    expect(store.state.streamedContent).toBe('恢复后回复')
    store.reset()
  })

  it('restores a structured clarification and resumes it with the original field keys', async () => {
    const gateway = new FixtureRealtimeGateway()
    gateway.invocations.push(connection('invocation-clarify', [
      item(agui('clarify-interrupted', 'RUN_INTERRUPTED', {
        safePrompt: 'Additional information is required to continue.',
        clarification: clarification(),
      })),
    ]))
    gateway.invocations.push(connection('invocation-clarify', [
      item(agui('resume-started', 'RUN_STARTED', {})),
      item(agui('resume-finished', 'RUN_FINISHED', { status: 'COMPLETED' })),
    ]))
    const storage = new MemoryStorage()
    const store = createStore(gateway, storage)
    store.synchronize(scope)
    expect(await store.invoke(scope, '请规划接入', fixtureIds.principal, 4)).toBe(false)
    expect(store.state.clarification?.questions[0]?.fieldKey).toBe('repository')

    expect(await store.resume(scope, { repository: 'crewscope-java' }, fixtureIds.principal, 5)).toBe(true)
    expect(gateway.resumed).toEqual([{ invocationId: 'invocation-clarify', answers: { repository: 'crewscope-java' } }])
    expect(storage.values().join(' ')).not.toContain('interruptToken')
    store.reset()
  })

  it('tracks the latest TaskIntent aggregate independently from message refreshes', async () => {
    const gateway = new FixtureRealtimeGateway()
    gateway.durableEvents = [{ cursor: 'intent-cursor', event: realtime(
      'intent-event', 'intent-domain', 'CONVERSATION', 'TASK_INTENT_PROPOSED', 1, {},
    ) }]
    gateway.durableEvents[0]!.event.aggregateType = 'TASK_INTENT'
    gateway.durableEvents[0]!.event.aggregateId = '74000000-0000-4000-8000-000000000001'
    const store = createStore(gateway)
    store.synchronize(scope)
    await waitFor(() => store.state.taskIntentRefreshVersion === 1)
    expect(store.state.latestTaskIntentId).toBe('74000000-0000-4000-8000-000000000001')
    store.reset()
  })

  it('deduplicates invocation and durable event IDs in separate stream namespaces', async () => {
    const gateway = new FixtureRealtimeGateway()
    gateway.durableEvents = [{ cursor: 'shared-id', event: durable('shared-id', 'domain-shared', 1) }]
    gateway.invocations.push(connection('invocation-shared-id', [
      item(agui('shared-id', 'TEXT_MESSAGE_CONTENT', { delta: '公开回复' })),
      item(agui('shared-finished', 'RUN_FINISHED', { status: 'COMPLETED' })),
    ]))
    const store = createStore(gateway)
    store.synchronize(scope)
    await waitFor(() => store.state.lastCursor === 'shared-id')

    expect(await store.invoke(scope, '验证分流去重', fixtureIds.principal, 4)).toBe(true)
    expect(store.state.streamedContent).toBe('公开回复')
    store.reset()
  })

  it('accepts only the first terminal event and stops consuming trailing frames', async () => {
    const gateway = new FixtureRealtimeGateway()
    gateway.invocations.push(connection('invocation-terminal', [
      item(agui('terminal-finished', 'RUN_FINISHED', { status: 'COMPLETED' })),
      item(agui('terminal-trailing-text', 'TEXT_MESSAGE_CONTENT', { delta: '不应出现' })),
      item(agui('terminal-trailing-error', 'RUN_ERROR', { safeMessage: '不应覆盖', retryable: false })),
    ]))
    const store = createStore(gateway)
    store.synchronize(scope)

    expect(await store.invoke(scope, '验证终态', fixtureIds.principal, 4)).toBe(true)
    expect(store.state.invocationPhase).toBe('completed')
    expect(store.state.streamedContent).toBe('')
    expect(store.state.errorMessage).toBeNull()
    store.reset()
  })

  it('fails closed on oversized text and malformed clarification payloads', async () => {
    const gateway = new FixtureRealtimeGateway()
    gateway.invocations.push(connection('invocation-oversized', [
      ...Array.from({ length: 6 }, (_, index) => item(agui(`oversized-${index}`, 'TEXT_MESSAGE_CONTENT', { delta: 'x'.repeat(10_000) }))),
      item(agui('oversized-finished', 'RUN_FINISHED', { status: 'COMPLETED' })),
    ]))
    gateway.invocations.push(connection('invocation-invalid-clarification', [
      item(agui('invalid-clarification', 'RUN_INTERRUPTED', { safePrompt: '继续', clarification: null })),
    ]))
    const store = createStore(gateway)
    store.synchronize(scope)

    expect(await store.invoke(scope, '验证文本边界', fixtureIds.principal, 4)).toBe(false)
    expect(store.state).toEqual(expect.objectContaining({ invocationPhase: 'error', retryable: false }))
    expect(store.state.streamedContent).toHaveLength(50_000)

    expect(await store.invoke(scope, '验证澄清边界', fixtureIds.principal, 4)).toBe(false)
    expect(store.state.errorMessage).toContain('澄清请求无效')
    store.reset()
  })

  it('retains recovery coordinates for an explicitly retryable RUN_ERROR', async () => {
    const gateway = new FixtureRealtimeGateway()
    gateway.invocations.push(connection('invocation-run-error', [
      item(agui('run-error', 'RUN_ERROR', { safeMessage: '模型暂时不可用', retryable: true })),
    ]))
    gateway.invocations.push(connection('invocation-run-error', [
      item(agui('run-error-retry-text', 'TEXT_MESSAGE_CONTENT', { delta: '恢复成功' })),
      item(agui('run-error-retry-finished', 'RUN_FINISHED', { status: 'COMPLETED' })),
    ]))
    const store = createStore(gateway)
    store.synchronize(scope)

    expect(await store.invoke(scope, '验证显式重试', fixtureIds.principal, 4)).toBe(false)
    expect(store.state.retryable).toBe(true)
    expect(await store.retry(scope)).toBe(true)
    expect(store.state.streamedContent).toBe('恢复成功')
    expect(new Set(gateway.invokeKeys).size).toBe(1)
    store.reset()
  })

  it('discards malformed browser recovery coordinates before reconnecting', async () => {
    const gateway = new FixtureRealtimeGateway()
    const storage = new MemoryStorage()
    const storageKey = `crewscope:conversation:invocation:${scope.organizationId}:${scope.teamId}:${scope.conversationId}`
    storage.setItem(storageKey, JSON.stringify({
      content: 'x'.repeat(50_001),
      authorPrincipalId: fixtureIds.principal,
      baselineSequence: -1,
      idempotencyKey: 'untrusted-key',
    }))
    const store = createStore(gateway, storage)

    store.synchronize(scope)
    await Promise.resolve()

    expect(gateway.invokeKeys).toEqual([])
    expect(storage.getItem(storageKey)).toBeNull()
    expect(store.state.invocationPhase).toBe('idle')
    store.reset()
  })

  it('normalizes CRLF in a valid browser recovery before reconnecting', async () => {
    const gateway = new FixtureRealtimeGateway()
    gateway.invocations.push(connection('invocation-recovered', [
      item(agui('recovered-finished', 'RUN_FINISHED', { status: 'COMPLETED' })),
    ]))
    const storage = new MemoryStorage()
    const storageKey = `crewscope:conversation:invocation:${scope.organizationId}:${scope.teamId}:${scope.conversationId}`
    storage.setItem(storageKey, JSON.stringify({
      content: '第一行\r\n第二行',
      authorPrincipalId: fixtureIds.principal,
      baselineSequence: 4,
      idempotencyKey: 'recovery-crlf',
    }))
    const store = createStore(gateway, storage)

    store.synchronize(scope)
    await vi.waitFor(() => {
      expect(gateway.invokedMessages).toEqual(['第一行\n第二行'])
      expect(storage.getItem(storageKey)).toBeNull()
    })

    store.reset()
  })
})

class FixtureRealtimeGateway implements ConversationRealtimeGateway {
  readonly invokeKeys: string[] = []
  readonly invokedMessages: string[] = []
  readonly cancelled: Array<{ invocationId: string; reason: string }> = []
  readonly resumed: Array<{ invocationId: string; answers: Record<string, string> }> = []
  invocations: RealtimeConnection[] = []
  durableEvents: RealtimeStreamItem[] = []
  invokeFailure: unknown = null

  async invoke(_scope: ConversationMessageScope, input: { message: string }, key: string): Promise<RealtimeConnection> {
    this.invokeKeys.push(key)
    this.invokedMessages.push(input.message)
    if (this.invokeFailure) throw this.invokeFailure
    const next = this.invocations.shift()
    if (!next) throw new Error('Missing invocation fixture')
    return next
  }

  async resume(_scope: ConversationMessageScope, invocationId: string, input: { answers: Record<string, string> }): Promise<RealtimeConnection> {
    this.resumed.push({ invocationId, answers: input.answers })
    const next = this.invocations.shift()
    if (!next) throw new Error('Missing resume fixture')
    return next
  }

  async streamEvents(_scope: ConversationMessageScope, _after?: string, signal?: AbortSignal): Promise<RealtimeConnection> {
    return { invocationId: null, replayed: false, events: waitingEvents(this.durableEvents, signal) }
  }

  async cancel(_scope: ConversationMessageScope, invocationId: string, reason: string): Promise<AgentCancelResponse> {
    this.cancelled.push({ invocationId, reason })
    return { invocationId, result: 'ACCEPTED', correlationId: 'corr-cancel' }
  }
}

function clarification() {
  return { schemaVersion: '1', summary: '需要选择仓库', questions: [{ fieldKey: 'repository', question: '使用哪个仓库？', context: null, required: true, choices: ['crewscope-java'] }] }
}

function createStore(gateway: ConversationRealtimeGateway, storage: Storage = new MemoryStorage()) {
  return createConversationRealtimeStore(gateway, {
    storage,
    reconnectDelay: async () => undefined,
    maxInvocationReconnects: 2,
  })
}

function connection(invocationId: string, events: RealtimeStreamItem[]): RealtimeConnection {
  return { invocationId, replayed: false, events: finiteEvents(events) }
}

function failingConnection(invocationId: string, events: RealtimeStreamItem[]): RealtimeConnection {
  return {
    invocationId,
    replayed: false,
    events: (async function* () {
      yield* events
      throw new CrewScopeApiError(0, envelope('network_unavailable', '连接中断', true))
    })(),
  }
}

function hangingConnection(invocationId: string): RealtimeConnection {
  return {
    invocationId,
    replayed: false,
    events: (async function* () {
      yield item(agui('cancel-1', 'RUN_STARTED', {}))
      await new Promise(() => undefined)
    })(),
  }
}

async function* finiteEvents(events: RealtimeStreamItem[]) {
  yield* events
}

async function* waitingEvents(events: RealtimeStreamItem[], signal?: AbortSignal) {
  yield* events
  await new Promise<void>((resolve) => {
    if (signal?.aborted) return resolve()
    signal?.addEventListener('abort', () => resolve(), { once: true })
  })
}

function item(event: RealtimeEventEnvelope): RealtimeStreamItem {
  return { cursor: event.eventId, event }
}

function agui(eventId: string, eventType: string, payload: Record<string, unknown>): RealtimeEventEnvelope {
  return realtime(eventId, null, 'AG_UI', eventType, null, payload)
}

function durable(eventId: string, domainEventId: string, aggregateVersion: number): RealtimeEventEnvelope {
  return realtime(eventId, domainEventId, 'CONVERSATION', 'CONVERSATION_MESSAGE_POSTED', aggregateVersion, { messageId: eventId })
}

function realtime(
  eventId: string,
  domainEventId: string | null,
  streamType: 'AG_UI' | 'CONVERSATION',
  eventType: string,
  aggregateVersion: number | null,
  payload: Record<string, unknown>,
): RealtimeEventEnvelope {
  return {
    eventId,
    domainEventId,
    streamType,
    eventType,
    schemaVersion: 'v1',
    aggregateType: aggregateVersion === null ? null : 'CONVERSATION',
    aggregateId: aggregateVersion === null ? null : conversationIds.provider,
    aggregateVersion,
    correlationId: 'corr-1',
    causationId: null,
    occurredAt: '2026-08-11T00:00:00Z',
    payload,
  }
}

function envelope(code: string, message: string, retryable: boolean) {
  return { code, message, correlationId: 'corr-error', retryable, currentVersion: null, details: {} }
}

async function waitFor(predicate: () => boolean): Promise<void> {
  for (let attempt = 0; attempt < 20; attempt += 1) {
    if (predicate()) return
    await new Promise(resolve => setTimeout(resolve, 0))
  }
  throw new Error('Timed out waiting for realtime state')
}

class MemoryStorage implements Storage {
  private readonly data = new Map<string, string>()
  get length() { return this.data.size }
  clear() { this.data.clear() }
  getItem(key: string) { return this.data.get(key) ?? null }
  key(index: number) { return [...this.data.keys()][index] ?? null }
  removeItem(key: string) { this.data.delete(key) }
  setItem(key: string, value: string) { this.data.set(key, value) }
  values() { return [...this.data.values()] }
}
