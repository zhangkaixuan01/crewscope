import { fixtureIds } from '../../test/scopeFixtures'
import { flushPromises } from '@vue/test-utils'
import { FixtureTaskGateway, fixtureTasks, taskIds } from '../../test/taskFixtures'
import type { TaskGateway } from './gateway'
import { createTaskStore } from './store'
import { CrewScopeApiError } from '../../api/client'
import type { TaskCommandReceipt, TaskDetails, TaskEventItem, TaskPage } from './types'

const platformScope = { organizationId: fixtureIds.organization, teamId: fixtureIds.teamPlatform }
const securityScope = { organizationId: fixtureIds.organization, teamId: fixtureIds.teamSecurity }

describe('TaskStore', () => {
  it('paginates with the server Cursor and de-duplicates Task identities', async () => {
    const gateway = new FixtureTaskGateway()
    gateway.pageSize = 1
    const store = createTaskStore(gateway)

    await store.load(platformScope, fixtureIds.projectCrewScope)
    expect(store.state.items.map(item => item.id)).toEqual([taskIds.first])
    expect(store.state.nextCursor).toBe('1')

    await store.loadMore()
    expect(store.state.items.map(item => item.id)).toEqual([taskIds.first, taskIds.second])
    expect(gateway.listCalls[1]?.after).toBe('1')
    expect(store.state.nextCursor).toBeNull()
  })

  it('discards a slow collection response after the Team Scope changes', async () => {
    const gateway = new FixtureTaskGateway()
    const first = deferred<TaskPage>()
    const original = gateway.listTasks.bind(gateway)
    gateway.listTasks = query => query.teamId === fixtureIds.teamPlatform
      ? first.promise
      : original(query)
    const store = createTaskStore(gateway)

    const slowLoad = store.load(platformScope, fixtureIds.projectCrewScope)
    await store.load(securityScope, fixtureIds.projectRuntime)
    first.resolve({ items: fixtureTasks[fixtureIds.teamPlatform]!, nextCursor: null })
    await slowLoad

    expect(store.state.items.map(item => item.id)).toEqual([taskIds.security])
    expect(store.state.items.every(item => item.projectId === fixtureIds.projectRuntime)).toBe(true)
  })

  it('restores a Task deep link only inside its selected Team and WorkProject', async () => {
    const store = createTaskStore(new FixtureTaskGateway())

    await store.synchronize(platformScope, { projectId: fixtureIds.projectCrewScope, taskId: taskIds.first })

    expect(store.state.selectedTaskId).toBe(taskIds.first)
    expect(store.state.detailPhase).toBe('ready')
    expect(store.state.details?.projectId).toBe(fixtureIds.projectCrewScope)
    expect(store.state.attempts[0]?.id).toBe(taskIds.execution)

    await store.synchronize(securityScope, { projectId: fixtureIds.projectRuntime })
    expect(store.state.selectedTaskId).toBeNull()
    expect(store.state.details).toBeNull()
    expect(store.state.runtimeFacts).toEqual({})
    expect(store.state.events).toEqual({})
  })

  it('rejects a Task detail whose server project does not match the active collection Scope', async () => {
    const gateway = new FixtureTaskGateway()
    const store = createTaskStore(gateway)
    await store.load(platformScope, fixtureIds.projectRuntime)

    await store.select(platformScope, taskIds.first)

    expect(store.state.detailPhase).toBe('error')
    expect(store.state.details).toBeNull()
  })

  it('caches Runtime, event and association resources and invalidates them explicitly', async () => {
    const gateway = new FixtureTaskGateway()
    const runtimeSpy = vi.spyOn(gateway, 'getRuntimeFacts')
    const healthSpy = vi.spyOn(gateway, 'getRuntimeHealth')
    const store = createTaskStore(gateway)
    await store.load(platformScope, fixtureIds.projectCrewScope)

    await store.loadRuntimeFacts(taskIds.first, taskIds.execution)
    await store.loadRuntimeFacts(taskIds.first, taskIds.execution)
    await store.loadRuntimeHealth()
    await store.loadRuntimeHealth()
    await store.loadEvents(taskIds.first)
    await store.loadEvents(taskIds.first)
    await store.loadByWorkItem(fixtureIds.projectCrewScope, taskIds.workItem)
    await store.loadByWorkItem(fixtureIds.projectCrewScope, taskIds.workItem)
    await store.loadAssociations(taskIds.first)
    await store.loadAssociations(taskIds.first)

    expect(runtimeSpy).toHaveBeenCalledTimes(1)
    expect(healthSpy).toHaveBeenCalledTimes(1)
    expect(store.state.runtimeHealth.default?.value?.staleWorkerCount).toBe(1)
    expect(gateway.eventCalls).toHaveLength(1)
    expect(gateway.associationCalls).toEqual([
      `work-item:first`,
      `task:${taskIds.first}`,
    ])

    await store.loadEvents(taskIds.first, true)
    expect(gateway.eventCalls[1]?.after).toBe('cursor-1')
    expect(store.state.events[taskIds.first]?.value?.items.map(item => item.event.eventId)).toEqual(['event-1', 'event-2'])

    store.invalidateAssociations(`work-item:${fixtureIds.projectCrewScope}:${taskIds.workItem}`)
    await store.loadByWorkItem(fixtureIds.projectCrewScope, taskIds.workItem)
    expect(gateway.associationCalls.filter(call => call.startsWith('work-item:'))).toHaveLength(2)

    store.invalidateTask(taskIds.first)
    expect(store.state.phase).toBe('idle')
    expect(store.state.items).toEqual([])
    expect(store.state.runtimeFacts[`${taskIds.first}:${taskIds.execution}`]).toBeUndefined()
    expect(store.state.events[taskIds.first]).toBeUndefined()
    expect(store.state.taskAssociations[taskIds.first]).toBeUndefined()
  })

  it('caches Runtime facts per attempt and discards fleet health when Team Scope changes', async () => {
    const gateway = new FixtureTaskGateway()
    const store = createTaskStore(gateway)
    await store.load(platformScope, fixtureIds.projectCrewScope)

    await store.loadRuntimeFacts(taskIds.first, taskIds.execution)
    await store.loadRuntimeFacts(taskIds.first, taskIds.previousExecution)
    await store.loadRuntimeHealth()

    expect(gateway.runtimeCalls).toEqual([taskIds.execution, taskIds.previousExecution])
    expect(store.state.runtimeFacts[`${taskIds.first}:${taskIds.execution}`]?.value?.execution.id).toBe(taskIds.execution)
    expect(store.state.runtimeFacts[`${taskIds.first}:${taskIds.previousExecution}`]?.value?.execution.id).toBe(taskIds.previousExecution)

    await store.load(securityScope, fixtureIds.projectRuntime)
    expect(store.state.runtimeFacts).toEqual({})
    expect(store.state.runtimeHealth).toEqual({})
  })

  it('does not let an older deep-link response replace a newer Task selection', async () => {
    const gateway = new FixtureTaskGateway()
    const originalGet = gateway.getTask.bind(gateway)
    const first = deferred<Awaited<ReturnType<TaskGateway['getTask']>>>()
    gateway.getTask = (scope, taskId, signal) => taskId === taskIds.first
      ? first.promise
      : originalGet(scope, taskId, signal)
    const store = createTaskStore(gateway)
    await store.load(platformScope, fixtureIds.projectCrewScope)

    const slowSelection = store.select(platformScope, taskIds.first)
    await store.select(platformScope, taskIds.second)
    first.resolve(await originalGet(platformScope, taskIds.first))
    await slowSelection

    expect(store.state.selectedTaskId).toBe(taskIds.second)
    expect(store.state.details?.id).toBe(taskIds.second)
  })

  it('reuses the exact idempotency key on retry and refreshes the server-authored Task identity', async () => {
    const gateway = new FixtureTaskGateway()
    const originalCreate = gateway.createTask.bind(gateway)
    const keys: string[] = []
    let attempts = 0
    gateway.createTask = async (command, key) => {
      keys.push(key)
      attempts += 1
      if (attempts === 1) throw new CrewScopeApiError(0, {
        code: 'network_unavailable', message: '网络中断', correlationId: 'offline', retryable: true, currentVersion: null, details: {},
      })
      return originalCreate(command, key)
    }
    const store = createTaskStore(gateway)
    await store.load(platformScope, fixtureIds.projectCrewScope)
    const command = {
      scope: platformScope,
      projectId: fixtureIds.projectCrewScope,
      workItemId: taskIds.workItem,
      expectedVersion: 3,
      input: {
        objective: '验证幂等委托', acceptanceCriteria: ['只创建一个 Task'],
        executorAgentProfileId: crypto.randomUUID(), conversationSource: null, providerBindingIds: [],
      },
    }

    await expect(store.createTask(command)).rejects.toMatchObject({ status: 0 })
    expect(store.state.createPhase).toBe('error')
    const createdTaskId = await store.retryCreate()

    expect(keys).toHaveLength(2)
    expect(keys[1]).toBe(keys[0])
    expect(createdTaskId).toBe(store.state.createdTaskId)
    expect(store.state.items.some(item => item.id === createdTaskId)).toBe(true)
    expect(gateway.createCalls).toHaveLength(1)
  })

  it('discards a Task creation receipt when the Team Scope changes before it arrives', async () => {
    const gateway = new FixtureTaskGateway()
    const accepted = deferred<TaskCommandReceipt>()
    gateway.createTask = vi.fn(async () => accepted.promise)
    const store = createTaskStore(gateway)
    await store.load(platformScope, fixtureIds.projectCrewScope)

    const creation = store.createTask({
      scope: platformScope,
      projectId: fixtureIds.projectCrewScope,
      workItemId: taskIds.workItem,
      expectedVersion: 3,
      input: {
        objective: '跨 Scope 委托回执', acceptanceCriteria: ['不污染新 Team'],
        executorAgentProfileId: crypto.randomUUID(), conversationSource: null, providerBindingIds: [],
      },
    })
    await flushPromises()
    await store.load(securityScope, fixtureIds.projectRuntime)
    accepted.resolve(receipt())

    await expect(creation).resolves.toBeNull()
    expect(store.state.items.map(item => item.id)).toEqual([taskIds.security])
    expect(store.state.createPhase).toBe('idle')
    expect(store.state.createdTaskId).toBeNull()
    expect(gateway.associationCalls).toEqual([])
  })

  it('retains sanitized forbidden statuses for the page boundary and clears them across Scope', async () => {
    const gateway = new FixtureTaskGateway()
    gateway.createTask = async () => { throw forbiddenError('task_create_forbidden') }
    const store = createTaskStore(gateway)
    await store.load(platformScope, fixtureIds.projectCrewScope)

    await expect(store.createTask({
      scope: platformScope,
      projectId: fixtureIds.projectCrewScope,
      workItemId: taskIds.workItem,
      expectedVersion: 3,
      input: {
        objective: '无权委托', acceptanceCriteria: ['必须拒绝'], executorAgentProfileId: crypto.randomUUID(),
        conversationSource: null, providerBindingIds: [],
      },
    })).rejects.toMatchObject({ status: 403 })
    expect(store.state.createErrorStatus).toBe(403)

    await store.select(platformScope, taskIds.first)
    gateway.commandTask = async () => { throw forbiddenError('task_command_forbidden') }
    await expect(store.commandTask({
      scope: platformScope, taskId: taskIds.first, executionId: taskIds.execution,
      expectedVersion: 2, operation: 'CANCEL', reason: '无权控制',
    })).rejects.toMatchObject({ status: 403 })
    expect(store.state.commandErrorStatus).toBe(403)

    await store.load(securityScope, fixtureIds.projectRuntime)
    expect(store.state.createErrorStatus).toBeNull()
    expect(store.state.commandErrorStatus).toBeNull()
  })

  it('maintains independent resumable streams, de-duplicates replay and cancels removed Tasks', async () => {
    const gateway = new FixtureTaskGateway()
    const streams = new Map<string, ControlledTaskStream>()
    gateway.streamEvents = vi.fn(async (_scope, taskId, _after, signal) => {
      const stream = controlledTaskStream(signal)
      streams.set(taskId, stream)
      return { events: stream.events }
    })
    const store = createTaskStore(gateway, { storage: sessionStorage, reconnectDelay: async () => undefined })
    store.activateScope(platformScope)

    store.synchronizeLiveTasks([taskIds.first, taskIds.second])
    await flushPromises()
    expect(store.state.liveTasks[taskIds.first]?.phase).toBe('connected')
    expect(store.state.liveTasks[taskIds.second]?.phase).toBe('connected')

    const event = liveEvent(taskIds.first, 'event-live-1', 'domain-live-1', 'cursor-live-1')
    streams.get(taskIds.first)!.emit(event)
    await flushPromises()
    expect(store.state.liveRefreshVersion).toBe(1)
    expect(store.state.liveUpdatedTaskId).toBe(taskIds.first)

    streams.get(taskIds.first)!.emit(event)
    streams.get(taskIds.first)!.emit(liveEvent(taskIds.first, 'event-live-replay', 'domain-live-1', 'cursor-live-2'))
    await flushPromises()
    expect(store.state.liveRefreshVersion).toBe(1)

    store.synchronizeLiveTasks([taskIds.second])
    expect(streams.get(taskIds.first)!.aborted()).toBe(true)
    expect(store.state.liveTasks[taskIds.first]).toBeUndefined()
    store.stopLiveTasks()
  })

  it('continues from loaded history and appends only new SSE facts to the Timeline cache', async () => {
    const gateway = new FixtureTaskGateway()
    let stream: ControlledTaskStream | null = null
    const afterValues: Array<string | undefined> = []
    gateway.streamEvents = vi.fn(async (_scope, _taskId, after, signal) => {
      afterValues.push(after)
      stream = controlledTaskStream(signal)
      return { events: stream.events }
    })
    sessionStorage.setItem(
      `crewscope:task-cursor:${fixtureIds.organization}:${fixtureIds.teamPlatform}:${taskIds.first}`,
      'stale-browser-cursor',
    )
    const store = createTaskStore(gateway, { storage: sessionStorage, reconnectDelay: async () => undefined })
    store.activateScope(platformScope)
    await store.loadEvents(taskIds.first)

    store.synchronizeLiveTasks([taskIds.first])
    await flushPromises()
    expect(afterValues).toEqual(['cursor-1'])

    ;(stream as ControlledTaskStream | null)?.emit(liveEvent(taskIds.first, 'replayed-id', 'domain-1', 'cursor-replay'))
    ;(stream as ControlledTaskStream | null)?.emit(liveEvent(taskIds.first, 'event-live-2', 'domain-live-2', 'cursor-live-2'))
    await flushPromises()

    expect(store.state.events[taskIds.first]?.value?.items.map(item => item.event.eventId)).toEqual(['event-1', 'event-live-2'])
    expect(store.state.events[taskIds.first]?.value?.nextCursor).toBe('cursor-live-2')
    expect(store.state.liveRefreshVersion).toBe(1)
    store.stopLiveTasks()
  })

  it('does not reopen SSE after terminal history is fully caught up', async () => {
    const gateway = new FixtureTaskGateway()
    const original = gateway.listEvents.bind(gateway)
    gateway.listEvents = async (...args) => ({ ...(await original(...args)), hasMore: false, taskTerminal: true })
    gateway.streamEvents = vi.fn(gateway.streamEvents.bind(gateway))
    const store = createTaskStore(gateway)
    store.activateScope(platformScope)

    await store.loadEvents(taskIds.first)
    store.synchronizeLiveTasks([taskIds.first])
    await flushPromises()

    expect(gateway.streamEvents).not.toHaveBeenCalled()
    expect(store.state.liveTasks[taskIds.first]).toBeUndefined()
  })

  it('drops an expired Cursor, requests a fact refresh and reconnects from the current projection', async () => {
    const gateway = new FixtureTaskGateway()
    const afterValues: Array<string | undefined> = []
    let live: ControlledTaskStream | null = null
    let calls = 0
    gateway.streamEvents = vi.fn(async (_scope, _taskId, after, signal) => {
      afterValues.push(after)
      calls += 1
      if (calls === 1) throw new CrewScopeApiError(410, {
        code: 'cursor_expired', message: 'Cursor 已过期', correlationId: 'cursor-410', retryable: true, currentVersion: null, details: {},
      })
      live = controlledTaskStream(signal)
      return { events: live.events }
    })
    sessionStorage.setItem(`crewscope:task-cursor:${fixtureIds.organization}:${fixtureIds.teamPlatform}:${taskIds.first}`, 'expired-cursor')
    const store = createTaskStore(gateway, { storage: sessionStorage, reconnectDelay: async () => undefined })
    store.activateScope(platformScope)

    store.synchronizeLiveTasks([taskIds.first])
    await flushPromises()

    expect(afterValues).toEqual(['expired-cursor', undefined])
    expect(store.state.liveRefreshVersion).toBe(1)
    expect(store.state.liveTasks[taskIds.first]?.projectionGap).toBe(true)
    expect(store.state.liveTasks[taskIds.first]?.phase).toBe('connected')
    store.stopLiveTasks()
    expect(live).not.toBeNull()
    expect((live as ControlledTaskStream | null)?.aborted()).toBe(true)
  })

  it('force-refreshes Conversation associations while retaining stale cards during loading', async () => {
    const gateway = new FixtureTaskGateway()
    const store = createTaskStore(gateway)
    store.activateScope(platformScope)
    await store.loadByConversation(taskIds.conversation)
    const first = store.state.associationPages[`conversation:${taskIds.conversation}`]?.value?.items[0]?.task.id

    await store.loadByConversation(taskIds.conversation, false, true)

    expect(first).toBe(taskIds.first)
    expect(gateway.associationCalls.filter(call => call.startsWith('conversation:'))).toHaveLength(2)
  })

  it('keeps server facts unchanged while a command is pending and ignores duplicate submission', async () => {
    const gateway = new FixtureTaskGateway()
    const original = gateway.commandTask.bind(gateway)
    const accepted = deferred<TaskCommandReceipt>()
    gateway.commandTask = vi.fn(async (command, key) => {
      await accepted.promise
      return original(command, key)
    })
    const store = createTaskStore(gateway)
    await store.synchronize(platformScope, { projectId: fixtureIds.projectCrewScope, taskId: taskIds.first })
    const command = {
      scope: platformScope, taskId: taskIds.first, executionId: taskIds.execution,
      expectedVersion: 2, operation: 'PAUSE' as const, reason: '等待审查',
    }

    const first = store.commandTask(command)
    await flushPromises()
    const duplicate = store.commandTask(command)
    expect(store.state.commandPending).toBe('PAUSE')
    expect(store.state.attempts[0]?.status).toBe('RUNNING')
    expect(gateway.commandTask).toHaveBeenCalledTimes(1)

    accepted.resolve(receipt())
    await Promise.all([first, duplicate])
    expect(store.state.commandPending).toBeNull()
    expect(store.state.attempts[0]?.status).toBe('PAUSE_REQUESTED')
  })

  it('does not let a Task command refresh switch the Store back to an earlier Team Scope', async () => {
    const gateway = new FixtureTaskGateway()
    const originalGet = gateway.getTask.bind(gateway)
    const refreshing = deferred<TaskDetails>()
    const store = createTaskStore(gateway)
    await store.synchronize(platformScope, { projectId: fixtureIds.projectCrewScope, taskId: taskIds.first })
    gateway.getTask = vi.fn((scope, taskId, signal) => scope.teamId === fixtureIds.teamPlatform
      ? refreshing.promise
      : originalGet(scope, taskId, signal))

    const command = store.commandTask({
      scope: platformScope, taskId: taskIds.first, executionId: taskIds.execution,
      expectedVersion: 2, operation: 'PAUSE', reason: '等待审查',
    })
    await flushPromises()
    await store.load(securityScope, fixtureIds.projectRuntime)
    refreshing.resolve(await originalGet(platformScope, taskIds.first))
    await command

    expect(store.state.items.map(item => item.id)).toEqual([taskIds.security])
    expect(store.state.selectedTaskId).toBeNull()
    expect(store.state.commandPending).toBeNull()
    expect(gateway.runtimeCalls).toEqual([])
    expect(gateway.associationCalls).toEqual([])
  })

  it('retries a transport failure with the exact Task command idempotency key', async () => {
    const gateway = new FixtureTaskGateway()
    const original = gateway.commandTask.bind(gateway)
    const keys: string[] = []
    let attempts = 0
    gateway.commandTask = async (command, key) => {
      keys.push(key)
      attempts += 1
      if (attempts === 1) throw new CrewScopeApiError(0, {
        code: 'network_unavailable', message: '网络中断', correlationId: 'offline', retryable: true,
        currentVersion: null, details: {},
      })
      return original(command, key)
    }
    const store = createTaskStore(gateway)
    await store.synchronize(platformScope, { projectId: fixtureIds.projectCrewScope, taskId: taskIds.first })

    await expect(store.commandTask({
      scope: platformScope, taskId: taskIds.first, executionId: taskIds.execution,
      expectedVersion: 2, operation: 'CANCEL', reason: '停止这次执行',
    })).rejects.toMatchObject({ status: 0 })
    expect(store.state.commandRetryable).toBe(true)
    expect(store.state.commandErrorMessage).toBe('网络中断')

    await store.retryTaskCommand()
    expect(keys).toHaveLength(2)
    expect(keys[1]).toBe(keys[0])
    expect(store.state.details?.status).toBe('CANCELLED')
    expect(store.state.attempts[0]?.status).toBe('CANCELLED')
  })

  it('refreshes authoritative Task facts after 409 or 412 instead of applying an optimistic state', async () => {
    for (const status of [409, 412]) {
      const gateway = new FixtureTaskGateway()
      gateway.commandTask = async () => {
        const task = gateway.tasks[fixtureIds.teamPlatform]![0]!
        task.currentExecutionStatus = 'CANCELLED'
        task.status = 'CANCELLED'
        task.version = 4
        throw new CrewScopeApiError(status, {
          code: status === 409 ? 'optimistic_lock_conflict' : 'precondition_failed',
          message: '版本已变化', correlationId: `conflict-${status}`, retryable: false,
          currentVersion: 5, details: {},
        })
      }
      const store = createTaskStore(gateway)
      await store.synchronize(platformScope, { projectId: fixtureIds.projectCrewScope, taskId: taskIds.first })

      await expect(store.commandTask({
        scope: platformScope, taskId: taskIds.first, executionId: taskIds.execution,
        expectedVersion: 2, operation: 'PAUSE', reason: '等待审查',
      })).rejects.toMatchObject({ status })

      expect(store.state.commandVersionConflict).toEqual({ operation: 'PAUSE', attemptedVersion: 2, currentVersion: 5 })
      expect(store.state.commandErrorMessage).toContain('最新服务端事实已刷新')
      expect(store.state.details?.status).toBe('CANCELLED')
      expect(store.state.attempts[0]?.status).toBe('CANCELLED')
      expect(store.state.commandRetryable).toBe(false)
    }
  })

  it('selects the server-created successor attempt after Retry refresh', async () => {
    const gateway = new FixtureTaskGateway()
    const failed = gateway.tasks[fixtureIds.teamPlatform]![0]!
    failed.status = 'FAILED'
    failed.currentExecutionStatus = 'FAILED'
    const store = createTaskStore(gateway)
    await store.synchronize(platformScope, { projectId: fixtureIds.projectCrewScope, taskId: taskIds.first })

    await store.commandTask({
      scope: platformScope, taskId: taskIds.first, executionId: taskIds.execution,
      expectedVersion: 2, operation: 'RETRY',
    })

    expect(store.state.details?.currentExecutionId).not.toBe(taskIds.execution)
    expect(store.state.attempts[0]?.attempt).toBe(2)
    expect(store.state.attempts[0]?.status).toBe('READY')
    expect(store.state.attempts.some(item => item.status === 'FAILED')).toBe(true)
  })
})

function deferred<T>(): { promise: Promise<T>, resolve: (value: T) => void } {
  let resolve!: (value: T) => void
  const promise = new Promise<T>(resolver => { resolve = resolver })
  return { promise, resolve }
}

function receipt(): TaskCommandReceipt {
  return {
    commandId: crypto.randomUUID(), domainEventId: crypto.randomUUID(),
    committedVersion: 3, correlationId: crypto.randomUUID(),
  }
}

function forbiddenError(code: string): CrewScopeApiError {
  return new CrewScopeApiError(403, {
    code, message: '无权操作 Task', correlationId: crypto.randomUUID(),
    retryable: false, currentVersion: null, details: {},
  })
}

interface ControlledTaskStream {
  events: AsyncIterable<TaskEventItem>
  emit(item: TaskEventItem): void
  aborted(): boolean
}

function controlledTaskStream(signal?: AbortSignal): ControlledTaskStream {
  const values: TaskEventItem[] = []
  let wake: (() => void) | null = null
  let stopped = signal?.aborted ?? false
  signal?.addEventListener('abort', () => {
    stopped = true
    wake?.()
  }, { once: true })
  return {
    events: {
      async *[Symbol.asyncIterator]() {
        while (!stopped) {
          if (values.length === 0) await new Promise<void>(resolve => { wake = resolve })
          wake = null
          if (stopped) return
          const value = values.shift()
          if (value) yield value
        }
      },
    },
    emit(item) {
      values.push(item)
      wake?.()
    },
    aborted: () => stopped,
  }
}

function liveEvent(taskId: string, eventId: string, domainEventId: string, cursor: string): TaskEventItem {
  return {
    cursor,
    context: { taskId, taskExecutionId: taskIds.execution, stepExecutionId: null, agentRunId: null, executionLeaseId: null },
    projectionGap: false,
    event: {
      eventId, domainEventId, streamType: 'TASK', eventType: 'TASK_STATUS_CHANGED', schemaVersion: '1',
      aggregateType: 'Task', aggregateId: taskId, aggregateVersion: 2, correlationId: 'correlation',
      causationId: null, occurredAt: '2026-08-15T12:00:00Z', payload: { status: 'WAITING' },
    },
  }
}
