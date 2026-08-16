import { inject, reactive, readonly, type App, type InjectionKey } from 'vue'
import { CrewScopeApiError } from '../../api/client'
import type { TaskGateway } from './gateway'
import type {
  CreateTaskCommand,
  MemberTaskCommand,
  MemberTaskCommandOperation,
  RuntimeFleetSummary,
  TaskAssociationPage,
  TaskAssociations,
  TaskDetails,
  TaskEventItem,
  TaskEventPage,
  TaskExecution,
  TaskListQuery,
  TaskRuntimeFacts,
  TaskScope,
  TaskStatus,
  TaskSummary,
  TaskCommandVersionConflict,
} from './types'

export type TaskPhase = 'idle' | 'loading' | 'ready' | 'empty' | 'error'
export type TaskLivePhase = 'connecting' | 'connected' | 'reconnecting' | 'error'

export interface TaskLiveState {
  phase: TaskLivePhase
  errorMessage: string | null
  projectionGap: boolean
}

export interface CachedResource<T> {
  phase: TaskPhase
  value: T | null
  errorMessage: string | null
  errorStatus: number | null
}

interface TaskState {
  phase: TaskPhase
  items: TaskSummary[]
  nextCursor: string | null
  loadingMore: boolean
  errorMessage: string | null
  errorStatus: number | null
  selectedTaskId: string | null
  detailPhase: TaskPhase
  details: TaskDetails | null
  attempts: TaskExecution[]
  detailErrorMessage: string | null
  detailErrorStatus: number | null
  runtimeFacts: Record<string, CachedResource<TaskRuntimeFacts>>
  runtimeHealth: Record<string, CachedResource<RuntimeFleetSummary>>
  events: Record<string, CachedResource<TaskEventPage>>
  associationPages: Record<string, CachedResource<TaskAssociationPage>>
  taskAssociations: Record<string, CachedResource<TaskAssociations>>
  liveTasks: Record<string, TaskLiveState>
  liveRefreshVersion: number
  liveUpdatedTaskId: string | null
  createPhase: 'idle' | 'submitting' | 'error' | 'success'
  createErrorMessage: string | null
  createErrorStatus: number | null
  createRetryable: boolean
  createdTaskId: string | null
  commandPending: MemberTaskCommandOperation | null
  commandErrorMessage: string | null
  commandErrorStatus: number | null
  commandRetryable: boolean
  commandVersionConflict: TaskCommandVersionConflict | null
}

export interface TaskStore {
  state: Readonly<TaskState>
  activateScope(scope: TaskScope): void
  synchronize(scope: TaskScope, options?: { projectId?: string, status?: TaskStatus, ownerPrincipalId?: string, taskId?: string | null }): Promise<void>
  load(scope: TaskScope, projectId?: string, status?: TaskStatus, ownerPrincipalId?: string, force?: boolean): Promise<void>
  loadMore(): Promise<void>
  select(scope: TaskScope, taskId: string, force?: boolean): Promise<void>
  clearSelection(): void
  loadRuntimeFacts(taskId: string, executionId: string, force?: boolean): Promise<void>
  loadRuntimeHealth(force?: boolean): Promise<void>
  loadEvents(taskId: string, more?: boolean): Promise<void>
  loadByWorkItem(projectId: string, workItemId: string, more?: boolean): Promise<void>
  loadByConversation(conversationId: string, more?: boolean, force?: boolean): Promise<void>
  loadAssociations(taskId: string, more?: boolean): Promise<void>
  synchronizeLiveTasks(taskIds: string[]): void
  stopLiveTasks(): void
  createTask(command: CreateTaskCommand): Promise<string | null>
  retryCreate(): Promise<string | null>
  clearCreate(): void
  commandTask(command: MemberTaskCommand): Promise<void>
  retryTaskCommand(): Promise<void>
  clearTaskCommand(): void
  invalidateTask(taskId: string): void
  invalidateAssociations(resourceKey?: string): void
  reset(): void
}

export const TASK_STORE: InjectionKey<TaskStore> = Symbol('crewscope-task-store')

interface TaskStoreOptions {
  reconnectDelay?: (attempt: number, signal: AbortSignal) => Promise<void>
  storage?: Storage | null
}

export function createTaskStore(gateway: TaskGateway, options: TaskStoreOptions = {}): TaskStore {
  const state = reactive<TaskState>({
    phase: 'idle',
    items: [],
    nextCursor: null,
    loadingMore: false,
    errorMessage: null,
    errorStatus: null,
    selectedTaskId: null,
    detailPhase: 'idle',
    details: null,
    attempts: [],
    detailErrorMessage: null,
    detailErrorStatus: null,
    runtimeFacts: {},
    runtimeHealth: {},
    events: {},
    associationPages: {},
    taskAssociations: {},
    liveTasks: {},
    liveRefreshVersion: 0,
    liveUpdatedTaskId: null,
    createPhase: 'idle',
    createErrorMessage: null,
    createErrorStatus: null,
    createRetryable: false,
    createdTaskId: null,
    commandPending: null,
    commandErrorMessage: null,
    commandErrorStatus: null,
    commandRetryable: false,
    commandVersionConflict: null,
  })

  let activeScope: TaskScope | null = null
  let activeScopeKey: string | null = null
  let activeQuery: Pick<TaskListQuery, 'projectId' | 'status' | 'ownerPrincipalId'> = {}
  let activeQueryKey: string | null = null
  let synchronizationVersion = 0
  let collectionVersion = 0
  let detailVersion = 0
  let collectionAbort: AbortController | null = null
  let detailAbort: AbortController | null = null
  const resourceVersions = new Map<string, number>()
  const resourceAborts = new Map<string, AbortController>()
  const liveControllers = new Map<string, AbortController>()
  const seenLiveEventIds = new Map<string, Set<string>>()
  const seenLiveDomainEventIds = new Map<string, Set<string>>()
  const liveEventOrders = new Map<string, string[]>()
  const liveDomainEventOrders = new Map<string, string[]>()
  const reconnectDelay = options.reconnectDelay ?? defaultReconnectDelay
  const storage = options.storage === undefined ? browserStorage() : options.storage
  let liveGeneration = 0
  let pendingCreate: { command: CreateTaskCommand, idempotencyKey: string } | null = null
  let createGeneration = 0
  let pendingTaskCommand: { command: MemberTaskCommand, idempotencyKey: string } | null = null
  let commandGeneration = 0

  function activateScope(scope: TaskScope): void {
    if (activeScopeKey !== scopeKey(scope)) changeScope(scope)
  }

  async function synchronize(
    scope: TaskScope,
    options: { projectId?: string, status?: TaskStatus, ownerPrincipalId?: string, taskId?: string | null } = {},
  ): Promise<void> {
    const version = ++synchronizationVersion
    const nextScopeKey = scopeKey(scope)
    const nextQueryKey = queryKey(scope, options.projectId, options.status, options.ownerPrincipalId)
    if (activeScopeKey !== nextScopeKey || activeQueryKey !== nextQueryKey || state.phase === 'idle') {
      await load(scope, options.projectId, options.status, options.ownerPrincipalId, activeQueryKey !== nextQueryKey)
    }
    if (version !== synchronizationVersion || activeScopeKey !== nextScopeKey || activeQueryKey !== nextQueryKey) return
    if (options.taskId) await select(scope, options.taskId)
    else clearSelection()
  }

  async function load(
    scope: TaskScope,
    projectId?: string,
    status?: TaskStatus,
    ownerPrincipalId?: string,
    force = false,
  ): Promise<void> {
    const nextScopeKey = scopeKey(scope)
    const nextQueryKey = queryKey(scope, projectId, status, ownerPrincipalId)
    if (activeScopeKey !== nextScopeKey) changeScope(scope)
    if (!force && activeQueryKey === nextQueryKey && ['ready', 'empty'].includes(state.phase)) return
    activeQuery = { projectId, status, ownerPrincipalId }
    activeQueryKey = nextQueryKey
    const version = ++collectionVersion
    collectionAbort?.abort()
    const controller = new AbortController()
    collectionAbort = controller
    state.phase = 'loading'
    state.items = []
    state.nextCursor = null
    state.loadingMore = false
    state.errorMessage = null
    state.errorStatus = null
    try {
      const page = await gateway.listTasks(
        { ...scope, projectId, status, ownerPrincipalId, limit: 50 },
        controller.signal,
      )
      if (!collectionCurrent(version, nextScopeKey, nextQueryKey)) return
      state.items = page.items
      state.nextCursor = page.nextCursor
      state.phase = page.items.length === 0 ? 'empty' : 'ready'
    } catch (error) {
      if (isAbort(error) || !collectionCurrent(version, nextScopeKey, nextQueryKey)) return
      state.phase = 'error'
      state.errorMessage = presentError(error, '暂时无法加载 Task，请稍后重试')
      state.errorStatus = statusOf(error)
    } finally {
      if (collectionAbort === controller) collectionAbort = null
    }
  }

  async function loadMore(): Promise<void> {
    if (!activeScope || !activeQueryKey || !state.nextCursor || state.loadingMore) return
    const scope = { ...activeScope }
    const cursor = state.nextCursor
    const version = collectionVersion
    const currentScopeKey = activeScopeKey
    const currentQueryKey = activeQueryKey
    collectionAbort?.abort()
    const controller = new AbortController()
    collectionAbort = controller
    state.loadingMore = true
    state.errorMessage = null
    try {
      const page = await gateway.listTasks(
        { ...scope, ...activeQuery, after: cursor, limit: 50 },
        controller.signal,
      )
      if (!collectionCurrent(version, currentScopeKey!, currentQueryKey)) return
      const known = new Set(state.items.map(item => item.id))
      state.items.push(...page.items.filter(item => !known.has(item.id)))
      state.nextCursor = page.nextCursor
      state.phase = state.items.length === 0 ? 'empty' : 'ready'
    } catch (error) {
      if (!isAbort(error) && collectionCurrent(version, currentScopeKey!, currentQueryKey)) {
        state.errorMessage = presentError(error, '暂时无法加载更多 Task，请稍后重试')
        state.errorStatus = statusOf(error)
      }
    } finally {
      if (collectionAbort === controller) collectionAbort = null
      if (collectionCurrent(version, currentScopeKey!, currentQueryKey)) state.loadingMore = false
    }
  }

  async function select(scope: TaskScope, taskId: string, force = false): Promise<void> {
    const nextScopeKey = scopeKey(scope)
    if (activeScopeKey !== nextScopeKey) changeScope(scope)
    if (!force && state.selectedTaskId === taskId && state.detailPhase === 'ready') return
    if (state.selectedTaskId !== taskId) clearTaskCommand()
    const version = ++detailVersion
    detailAbort?.abort()
    const controller = new AbortController()
    detailAbort = controller
    const retainStaleDetails = state.selectedTaskId === taskId && state.details?.id === taskId
    state.selectedTaskId = taskId
    state.detailPhase = 'loading'
    if (!retainStaleDetails) {
      state.details = null
      state.attempts = []
    }
    state.detailErrorMessage = null
    state.detailErrorStatus = null
    try {
      const [details, attempts] = await Promise.all([
        gateway.getTask(scope, taskId, controller.signal),
        gateway.listAttempts(scope, taskId, controller.signal),
      ])
      if (!detailCurrent(version, nextScopeKey, taskId)) return
      if (activeQuery.projectId && details.projectId !== activeQuery.projectId) {
        throw new Error('Task does not belong to the selected WorkProject')
      }
      state.details = details
      state.attempts = attempts
      state.detailPhase = 'ready'
      upsertSummary(details, attempts)
    } catch (error) {
      if (isAbort(error) || !detailCurrent(version, nextScopeKey, taskId)) return
      state.detailPhase = 'error'
      state.detailErrorMessage = presentError(error, '暂时无法加载 Task 详情，请稍后重试')
      state.detailErrorStatus = statusOf(error)
    } finally {
      if (detailAbort === controller) detailAbort = null
    }
  }

  function clearSelection(): void {
    detailVersion += 1
    detailAbort?.abort()
    detailAbort = null
    state.selectedTaskId = null
    state.detailPhase = 'idle'
    state.details = null
    state.attempts = []
    state.detailErrorMessage = null
    state.detailErrorStatus = null
    clearTaskCommand()
  }

  async function loadRuntimeFacts(taskId: string, executionId: string, force = false): Promise<void> {
    const scope = requireScope()
    const key = `${taskId}:${executionId}`
    await loadCached(
      `runtime:${key}`,
      state.runtimeFacts,
      key,
      force,
      signal => gateway.getRuntimeFacts(scope, taskId, executionId, signal),
      '暂时无法加载 Runtime 事实',
    )
  }

  async function loadRuntimeHealth(force = false): Promise<void> {
    const scope = requireScope()
    await loadCached(
      'runtime-health:default',
      state.runtimeHealth,
      'default',
      force,
      signal => gateway.getRuntimeHealth(scope, signal),
      '暂时无法加载 Runtime 健康摘要',
    )
  }

  async function loadEvents(taskId: string, more = false): Promise<void> {
    const scope = requireScope()
    const current = state.events[taskId]?.value
    const after = more ? current?.nextCursor ?? undefined : undefined
    if (more && !after) return
    await loadCached(
      `events:${taskId}`,
      state.events,
      taskId,
      false,
      signal => gateway.listEvents(scope, taskId, after, 50, signal),
      '暂时无法加载 Task 事件',
      more ? mergeEventPages : undefined,
    )
  }

  async function loadByWorkItem(projectId: string, workItemId: string, more = false): Promise<void> {
    const scope = requireScope()
    const key = `work-item:${projectId}:${workItemId}`
    const after = more ? state.associationPages[key]?.value?.nextCursor ?? undefined : undefined
    if (more && !after) return
    await loadCached(
      `association:${key}`,
      state.associationPages,
      key,
      false,
      signal => gateway.listByWorkItem(scope, projectId, workItemId, after, 50, signal),
      '暂时无法加载 WorkItem 关联 Task',
      more ? mergeAssociationPages : undefined,
    )
  }

  async function loadByConversation(conversationId: string, more = false, force = false): Promise<void> {
    const scope = requireScope()
    const key = `conversation:${conversationId}`
    const after = more ? state.associationPages[key]?.value?.nextCursor ?? undefined : undefined
    if (more && !after) return
    await loadCached(
      `association:${key}`,
      state.associationPages,
      key,
      force,
      signal => gateway.listByConversation(scope, conversationId, after, 50, signal),
      '暂时无法加载 Conversation 关联 Task',
      more ? mergeAssociationPages : undefined,
    )
  }

  function synchronizeLiveTasks(taskIds: string[]): void {
    const scope = requireScope()
    const desired = new Set(taskIds)
    for (const [taskId, controller] of liveControllers) {
      if (desired.has(taskId)) continue
      controller.abort()
      liveControllers.delete(taskId)
      delete state.liveTasks[taskId]
      clearLiveMemory(taskId)
    }
    for (const taskId of desired) {
      if (liveControllers.has(taskId)) continue
      const eventPage = state.events[taskId]?.value
      if (eventPage?.taskTerminal && !eventPage.hasMore) continue
      seedLiveMemory(taskId)
      const controller = new AbortController()
      liveControllers.set(taskId, controller)
      state.liveTasks[taskId] = { phase: 'connecting', errorMessage: null, projectionGap: false }
      void consumeLiveTask(scope, taskId, liveGeneration, controller)
    }
  }

  async function consumeLiveTask(
    scope: TaskScope,
    taskId: string,
    targetGeneration: number,
    controller: AbortController,
  ): Promise<void> {
    let attempt = 0
    // A loaded durable history page is a stronger starting point than a browser-stored Cursor.
    // Conversation cards do not load history, so they continue to resume from sessionStorage.
    const loadedEvents = state.events[taskId]
    let after = loadedEvents?.value && ['ready', 'empty', 'error'].includes(loadedEvents.phase)
      ? loadedEvents.value.nextCursor
      : safeGet(storage, liveCursorKey(scope, taskId))
    while (liveCurrent(taskId, targetGeneration, controller)) {
      setLiveState(taskId, attempt === 0 ? 'connecting' : 'reconnecting', null)
      try {
        const connection = await gateway.streamEvents(scope, taskId, after ?? undefined, controller.signal)
        if (!liveCurrent(taskId, targetGeneration, controller)) return
        setLiveState(taskId, 'connected', null)
        let received = false
        for await (const item of connection.events) {
          if (!liveCurrent(taskId, targetGeneration, controller)) return
          after = item.cursor
          safeSet(storage, liveCursorKey(scope, taskId), item.cursor)
          if (!rememberLive(taskId, item.event.eventId, item.event.domainEventId)) continue
          received = true
          attempt = 0
          if (item.projectionGap) state.liveTasks[taskId]!.projectionGap = true
          appendLiveEvent(taskId, item)
          // Task status and Runtime state remain server-authored facts. Events update the
          // append-only Timeline immediately and separately request an authoritative refresh.
          state.liveUpdatedTaskId = taskId
          state.liveRefreshVersion += 1
        }
        if (received) attempt = 0
        const eventPage = state.events[taskId]?.value
        if (eventPage?.taskTerminal && !eventPage.hasMore) {
          finishLiveTask(taskId, controller)
          return
        }
      } catch (error) {
        if (isAbort(error) || !liveCurrent(taskId, targetGeneration, controller)) return
        const status = statusOf(error)
        if (status === 410) {
          after = null
          safeRemove(storage, liveCursorKey(scope, taskId))
          state.liveTasks[taskId]!.projectionGap = true
          state.liveUpdatedTaskId = taskId
          state.liveRefreshVersion += 1
        } else if (status !== null && status !== 0 && status < 500) {
          setLiveState(taskId, 'error', presentError(error, 'Task 实时状态不可用'))
          return
        } else {
          setLiveState(taskId, 'reconnecting', presentError(error, 'Task 实时连接中断'))
        }
      }
      attempt += 1
      if (!liveCurrent(taskId, targetGeneration, controller)) return
      setLiveState(taskId, 'reconnecting', state.liveTasks[taskId]?.errorMessage ?? null)
      await reconnectDelay(attempt, controller.signal).catch(() => undefined)
    }
  }

  function stopLiveTasks(): void {
    liveGeneration += 1
    for (const controller of liveControllers.values()) controller.abort()
    liveControllers.clear()
    seenLiveEventIds.clear()
    seenLiveDomainEventIds.clear()
    liveEventOrders.clear()
    liveDomainEventOrders.clear()
    state.liveTasks = {}
    state.liveUpdatedTaskId = null
  }

  function finishLiveTask(taskId: string, controller: AbortController): void {
    if (liveControllers.get(taskId) !== controller) return
    liveControllers.delete(taskId)
    delete state.liveTasks[taskId]
    clearLiveMemory(taskId)
  }

  function liveCurrent(taskId: string, targetGeneration: number, controller: AbortController): boolean {
    return targetGeneration === liveGeneration
      && liveControllers.get(taskId) === controller
      && !controller.signal.aborted
  }

  function setLiveState(taskId: string, phase: TaskLivePhase, errorMessage: string | null): void {
    const current = state.liveTasks[taskId]
    if (!current) return
    current.phase = phase
    current.errorMessage = errorMessage
  }

  function rememberLive(taskId: string, eventId: string, domainEventId: string | null): boolean {
    const eventIds = seenLiveEventIds.get(taskId) ?? new Set<string>()
    const eventOrder = liveEventOrders.get(taskId) ?? []
    seenLiveEventIds.set(taskId, eventIds)
    liveEventOrders.set(taskId, eventOrder)
    if (!rememberBounded(eventId, eventIds, eventOrder)) return false
    if (!domainEventId) return true
    const domainIds = seenLiveDomainEventIds.get(taskId) ?? new Set<string>()
    const domainOrder = liveDomainEventOrders.get(taskId) ?? []
    seenLiveDomainEventIds.set(taskId, domainIds)
    liveDomainEventOrders.set(taskId, domainOrder)
    return rememberBounded(domainEventId, domainIds, domainOrder)
  }

  function clearLiveMemory(taskId: string): void {
    seenLiveEventIds.delete(taskId)
    seenLiveDomainEventIds.delete(taskId)
    liveEventOrders.delete(taskId)
    liveDomainEventOrders.delete(taskId)
  }

  function seedLiveMemory(taskId: string): void {
    clearLiveMemory(taskId)
    for (const item of state.events[taskId]?.value?.items ?? []) {
      rememberLive(taskId, item.event.eventId, item.event.domainEventId)
    }
  }

  function appendLiveEvent(taskId: string, item: TaskEventItem): void {
    const resource = state.events[taskId]
    if (!resource?.value) return
    const page = resource.value
    if (page.items.some(existing => existing.event.eventId === item.event.eventId
      || Boolean(item.event.domainEventId && existing.event.domainEventId === item.event.domainEventId))) return
    page.items.push(item)
    page.nextCursor = item.cursor
    // Once SSE has taken over from the history Cursor, every following durable item arrives on
    // the same stream. A rotation simply reconnects from this updated Cursor.
    page.hasMore = false
    resource.phase = 'ready'
    resource.errorMessage = null
    resource.errorStatus = null
  }

  async function loadAssociations(taskId: string, more = false): Promise<void> {
    const scope = requireScope()
    const after = more ? state.taskAssociations[taskId]?.value?.conversations.nextCursor ?? undefined : undefined
    if (more && !after) return
    await loadCached(
      `task-association:${taskId}`,
      state.taskAssociations,
      taskId,
      false,
      signal => gateway.getAssociations(scope, taskId, after, 50, signal),
      '暂时无法加载 Task 关联对象',
      more ? mergeTaskAssociations : undefined,
    )
  }

  async function createTask(command: CreateTaskCommand): Promise<string | null> {
    if (state.createPhase === 'submitting') return null
    createGeneration += 1
    const idempotencyKey = crypto.randomUUID()
    pendingCreate = { command: structuredClone(command), idempotencyKey }
    return executeCreate()
  }

  async function retryCreate(): Promise<string | null> {
    if (!pendingCreate) throw new Error('No Task creation is available for retry')
    return executeCreate()
  }

  async function executeCreate(): Promise<string | null> {
    const pending = pendingCreate
    if (!pending || state.createPhase === 'submitting') return null
    const generation = createGeneration
    const expectedScopeKey = scopeKey(pending.command.scope)
    if (activeScopeKey !== expectedScopeKey) {
      throw new Error('Task creation Scope is no longer selected')
    }
    state.createPhase = 'submitting'
    state.createErrorMessage = null
    state.createErrorStatus = null
    state.createRetryable = false
    state.createdTaskId = null
    const associationKey = `work-item:${pending.command.projectId}:${pending.command.workItemId}`
    const before = new Set(
      state.associationPages[associationKey]?.value?.items.map(item => item.task.id) ?? [],
    )
    try {
      await gateway.createTask(pending.command, pending.idempotencyKey)
    } catch (error) {
      if (!createCurrent(generation, expectedScopeKey, pending)) return null
      state.createPhase = 'error'
      state.createErrorMessage = presentError(error, '暂时无法创建 Task，请使用相同请求重试')
      state.createErrorStatus = statusOf(error)
      state.createRetryable = error instanceof CrewScopeApiError && error.envelope.retryable
      if (!state.createRetryable) pendingCreate = null
      throw error
    }

    if (!createCurrent(generation, expectedScopeKey, pending)) return null

    // The command receipt intentionally contains no client-trusted Task identity. Recover the
    // new identity from the authorized association query, then refresh the active collection.
    delete state.associationPages[associationKey]
    await Promise.allSettled([
      loadByWorkItem(pending.command.projectId, pending.command.workItemId),
      activeScope
        ? load(
            activeScope,
            activeQuery.projectId,
            activeQuery.status,
            activeQuery.ownerPrincipalId,
            true,
          )
        : Promise.resolve(),
    ])
    if (!createCurrent(generation, expectedScopeKey, pending)) return null
    const associated = state.associationPages[associationKey]?.value?.items ?? []
    const created = associated.find(item => !before.has(item.task.id))
      ?? associated.find(item => item.task.objective === pending.command.input.objective)
      ?? null
    state.createdTaskId = created?.task.id ?? null
    state.createPhase = 'success'
    state.createErrorMessage = null
    state.createErrorStatus = null
    state.createRetryable = false
    pendingCreate = null
    return state.createdTaskId
  }

  function clearCreate(): void {
    if (state.createPhase === 'submitting') return
    invalidateCreate()
    state.createPhase = 'idle'
    state.createErrorMessage = null
    state.createErrorStatus = null
    state.createRetryable = false
    state.createdTaskId = null
  }

  function invalidateCreate(): void {
    createGeneration += 1
    pendingCreate = null
  }

  function createCurrent(
    generation: number,
    expectedScopeKey: string,
    pending: { command: CreateTaskCommand, idempotencyKey: string },
  ): boolean {
    return generation === createGeneration
      && activeScopeKey === expectedScopeKey
      && pendingCreate === pending
  }

  async function commandTask(command: MemberTaskCommand): Promise<void> {
    if (state.commandPending) return
    if (activeScopeKey !== scopeKey(command.scope)
      || state.selectedTaskId !== command.taskId
      || state.details?.currentExecutionId !== command.executionId) {
      throw new Error('Task command no longer targets the selected current attempt')
    }
    pendingTaskCommand = { command: structuredClone(command), idempotencyKey: crypto.randomUUID() }
    await executeTaskCommand()
  }

  async function retryTaskCommand(): Promise<void> {
    if (!pendingTaskCommand || !state.commandRetryable) {
      throw new Error('No retryable Task command is available')
    }
    await executeTaskCommand()
  }

  async function executeTaskCommand(): Promise<void> {
    const pending = pendingTaskCommand
    if (!pending || state.commandPending) return
    const generation = commandGeneration
    const expectedScopeKey = scopeKey(pending.command.scope)
    state.commandPending = pending.command.operation
    state.commandErrorMessage = null
    state.commandErrorStatus = null
    state.commandRetryable = false
    state.commandVersionConflict = null
    try {
      await gateway.commandTask(pending.command, pending.idempotencyKey)
    } catch (error) {
      if (!taskCommandCurrent(generation, expectedScopeKey, pending.command.taskId)) throw error
      if (isTaskCommandConflict(error)) {
        pendingTaskCommand = null
        state.commandVersionConflict = {
          operation: pending.command.operation,
          attemptedVersion: pending.command.expectedVersion,
          currentVersion: error.envelope.currentVersion,
        }
        state.commandErrorMessage = 'Task 已被其他执行者更新，最新服务端事实已刷新，请确认后重试'
        state.commandErrorStatus = statusOf(error)
        await refreshAfterTaskCommand(pending.command, generation, expectedScopeKey)
      } else {
        state.commandErrorMessage = presentError(error, '暂时无法提交 Task 控制命令，请稍后重试')
        state.commandErrorStatus = statusOf(error)
        state.commandRetryable = error instanceof CrewScopeApiError && error.envelope.retryable
        if (!state.commandRetryable) pendingTaskCommand = null
      }
      if (taskCommandCurrent(generation, expectedScopeKey, pending.command.taskId)) {
        state.commandPending = null
      }
      throw error
    }

    if (!taskCommandCurrent(generation, expectedScopeKey, pending.command.taskId)) return
    pendingTaskCommand = null
    await refreshAfterTaskCommand(pending.command, generation, expectedScopeKey)
    if (!taskCommandCurrent(generation, expectedScopeKey, pending.command.taskId)) return
    state.commandPending = null
    state.commandErrorMessage = state.detailPhase === 'error'
      ? '命令已受理，但最新 Task 事实暂时无法恢复，请刷新详情'
      : null
    state.commandErrorStatus = state.detailPhase === 'error' ? state.detailErrorStatus : null
    state.commandRetryable = false
    state.commandVersionConflict = null
  }

  async function refreshAfterTaskCommand(
    command: MemberTaskCommand,
    generation: number,
    expectedScopeKey: string,
  ): Promise<void> {
    if (!taskCommandCurrent(generation, expectedScopeKey, command.taskId)) return
    const scope = { ...command.scope }
    clearTaskResourceFacts(command.taskId)
    await select(scope, command.taskId, true)
    if (!taskCommandCurrent(generation, expectedScopeKey, command.taskId)) return
    const currentExecutionId = state.details?.currentExecutionId
    await Promise.allSettled([
      currentExecutionId ? loadRuntimeFacts(command.taskId, currentExecutionId, true) : Promise.resolve(),
      loadAssociations(command.taskId),
      activeQueryKey
        ? load(scope, activeQuery.projectId, activeQuery.status, activeQuery.ownerPrincipalId, true)
        : Promise.resolve(),
    ])
  }

  function clearTaskResourceFacts(taskId: string): void {
    for (const key of Object.keys(state.runtimeFacts)) {
      if (key.startsWith(`${taskId}:`)) delete state.runtimeFacts[key]
    }
    // Task Event history is append-only evidence and remains useful while command facts refresh.
    delete state.taskAssociations[taskId]
    for (const [key, resource] of Object.entries(state.associationPages)) {
      if (resource.value?.items.some(item => item.task.id === taskId)) delete state.associationPages[key]
    }
  }

  function clearTaskCommand(): void {
    commandGeneration += 1
    pendingTaskCommand = null
    state.commandPending = null
    state.commandErrorMessage = null
    state.commandErrorStatus = null
    state.commandRetryable = false
    state.commandVersionConflict = null
  }

  function taskCommandCurrent(generation: number, expectedScopeKey: string, taskId: string): boolean {
    return generation === commandGeneration
      && activeScopeKey === expectedScopeKey
      && state.selectedTaskId === taskId
  }

  async function loadCached<T>(
    requestKey: string,
    cache: Record<string, CachedResource<T>>,
    cacheKey: string,
    force: boolean,
    request: (signal: AbortSignal) => Promise<T>,
    fallback: string,
    merge?: (current: T, incoming: T) => T,
  ): Promise<void> {
    const existing = cache[cacheKey]
    if (!force && !merge && existing && ['ready', 'empty'].includes(existing.phase)) return
    const version = (resourceVersions.get(requestKey) ?? 0) + 1
    resourceVersions.set(requestKey, version)
    resourceAborts.get(requestKey)?.abort()
    const controller = new AbortController()
    resourceAborts.set(requestKey, controller)
    const scopeAtStart = activeScopeKey
    cache[cacheKey] = { phase: 'loading', value: existing?.value ?? null, errorMessage: null, errorStatus: null }
    try {
      const incoming = await request(controller.signal)
      if (!resourceCurrent(requestKey, version, scopeAtStart, controller)) return
      const value = merge && existing?.value ? merge(existing.value, incoming) : incoming
      cache[cacheKey] = { phase: isEmpty(value) ? 'empty' : 'ready', value, errorMessage: null, errorStatus: null }
    } catch (error) {
      if (isAbort(error) || !resourceCurrent(requestKey, version, scopeAtStart, controller)) return
      cache[cacheKey] = {
        phase: 'error',
        value: existing?.value ?? null,
        errorMessage: presentError(error, fallback),
        errorStatus: statusOf(error),
      }
    } finally {
      if (resourceAborts.get(requestKey) === controller) resourceAborts.delete(requestKey)
    }
  }

  function invalidateTask(taskId: string): void {
    if (state.selectedTaskId === taskId) clearSelection()
    // A list summary contains current-attempt facts, so invalidating one Task makes the whole
    // Cursor window stale. Mark the collection idle instead of leaving a misleading ready page.
    collectionVersion += 1
    collectionAbort?.abort()
    collectionAbort = null
    activeQueryKey = null
    state.phase = 'idle'
    state.items = []
    state.nextCursor = null
    state.loadingMore = false
    for (const key of Object.keys(state.runtimeFacts)) {
      if (key.startsWith(`${taskId}:`)) delete state.runtimeFacts[key]
    }
    delete state.events[taskId]
    delete state.taskAssociations[taskId]
  }

  function invalidateAssociations(resourceKey?: string): void {
    if (resourceKey) {
      delete state.associationPages[resourceKey]
      delete state.taskAssociations[resourceKey]
      return
    }
    state.associationPages = {}
    state.taskAssociations = {}
    state.createPhase = 'idle'
    state.createErrorMessage = null
    state.createErrorStatus = null
    state.createRetryable = false
    state.createdTaskId = null
    pendingCreate = null
  }

  function changeScope(scope: TaskScope): void {
    cancelAll()
    activeScope = { ...scope }
    activeScopeKey = scopeKey(scope)
    activeQuery = {}
    activeQueryKey = null
    clearState()
  }

  function reset(): void {
    synchronizationVersion += 1
    cancelAll()
    activeScope = null
    activeScopeKey = null
    activeQuery = {}
    activeQueryKey = null
    clearState()
  }

  function cancelAll(): void {
    collectionVersion += 1
    detailVersion += 1
    collectionAbort?.abort()
    detailAbort?.abort()
    collectionAbort = null
    detailAbort = null
    for (const controller of resourceAborts.values()) controller.abort()
    resourceAborts.clear()
    resourceVersions.clear()
    stopLiveTasks()
    invalidateCreate()
    clearTaskCommand()
  }

  function clearState(): void {
    state.phase = 'idle'
    state.items = []
    state.nextCursor = null
    state.loadingMore = false
    state.errorMessage = null
    state.errorStatus = null
    state.selectedTaskId = null
    state.detailPhase = 'idle'
    state.details = null
    state.attempts = []
    state.detailErrorMessage = null
    state.detailErrorStatus = null
    state.runtimeFacts = {}
    state.runtimeHealth = {}
    state.events = {}
    state.associationPages = {}
    state.taskAssociations = {}
    state.liveTasks = {}
    state.liveRefreshVersion = 0
    state.liveUpdatedTaskId = null
    state.createPhase = 'idle'
    state.createErrorMessage = null
    state.createErrorStatus = null
    state.createRetryable = false
    state.createdTaskId = null
    state.commandPending = null
    state.commandErrorMessage = null
    state.commandErrorStatus = null
    state.commandRetryable = false
    state.commandVersionConflict = null
  }

  function collectionCurrent(version: number, expectedScope: string, expectedQuery: string): boolean {
    return version === collectionVersion && activeScopeKey === expectedScope && activeQueryKey === expectedQuery
  }

  function detailCurrent(version: number, expectedScope: string, taskId: string): boolean {
    return version === detailVersion && activeScopeKey === expectedScope && state.selectedTaskId === taskId
  }

  function resourceCurrent(
    key: string,
    version: number,
    expectedScope: string | null,
    controller: AbortController,
  ): boolean {
    return resourceVersions.get(key) === version
      && activeScopeKey === expectedScope
      && resourceAborts.get(key) === controller
  }

  function requireScope(): TaskScope {
    if (!activeScope) throw new Error('Task Scope is not selected')
    return { ...activeScope }
  }

  function upsertSummary(details: TaskDetails, attempts: TaskExecution[]): void {
    const current = attempts.find(item => item.id === details.currentExecutionId) ?? null
    const summary: TaskSummary = {
      id: details.id,
      workspaceId: details.workspaceId,
      projectId: details.projectId,
      workItemId: details.workItemId,
      objective: details.objective,
      acceptanceCriteria: details.acceptanceCriteria,
      status: details.status,
      currentExecutionId: details.currentExecutionId,
      currentAttempt: current?.attempt ?? null,
      currentExecutionStatus: current?.status ?? null,
      currentWaitingReason: current?.waiting?.reason ?? null,
      ownerPrincipalId: details.responsibilitySnapshot.find(item => item.role === 'OWNER')?.principalId ?? null,
      version: details.version,
      createdAt: details.audit.createdAt,
      updatedAt: details.audit.updatedAt,
    }
    const index = state.items.findIndex(item => item.id === details.id)
    if (index >= 0) state.items[index] = summary
    else state.items.unshift(summary)
  }

  return {
    state: readonly(state) as Readonly<TaskState>,
    activateScope,
    synchronize,
    load,
    loadMore,
    select,
    clearSelection,
    loadRuntimeFacts,
    loadRuntimeHealth,
    loadEvents,
    loadByWorkItem,
    loadByConversation,
    loadAssociations,
    synchronizeLiveTasks,
    stopLiveTasks,
    createTask,
    retryCreate,
    clearCreate,
    commandTask,
    retryTaskCommand,
    clearTaskCommand,
    invalidateTask,
    invalidateAssociations,
    reset,
  }
}

function defaultReconnectDelay(attempt: number, signal: AbortSignal): Promise<void> {
  const delay = Math.min(1_000 * 2 ** Math.min(attempt - 1, 4), 15_000)
  return new Promise((resolve, reject) => {
    const timeout = window.setTimeout(resolve, delay)
    signal.addEventListener('abort', () => {
      window.clearTimeout(timeout)
      reject(new DOMException('Aborted', 'AbortError'))
    }, { once: true })
  })
}

function browserStorage(): Storage | null {
  try {
    return typeof sessionStorage === 'undefined' ? null : sessionStorage
  } catch {
    return null
  }
}

function liveCursorKey(scope: TaskScope, taskId: string): string {
  return `crewscope:task-cursor:${scope.organizationId}:${scope.teamId}:${taskId}`
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
    // Storage is an optimization; an in-memory Cursor still keeps this connection consistent.
  }
}

function safeRemove(storage: Storage | null, key: string): void {
  try {
    storage?.removeItem(key)
  } catch {
    // A 410 response remains recoverable even when browser storage is unavailable.
  }
}

function rememberBounded(value: string, seen: Set<string>, order: string[]): boolean {
  if (seen.has(value)) return false
  seen.add(value)
  order.push(value)
  if (order.length > 500) {
    const oldest = order.shift()
    if (oldest) seen.delete(oldest)
  }
  return true
}

export function installTaskStore(app: App, gateway: TaskGateway): TaskStore {
  const store = createTaskStore(gateway)
  app.provide(TASK_STORE, store)
  return store
}

export function useTaskStore(): TaskStore {
  const store = inject(TASK_STORE)
  if (!store) throw new Error('CrewScope Task Store is not installed')
  return store
}

function scopeKey(scope: TaskScope): string {
  return `${scope.organizationId}:${scope.teamId}`
}

function queryKey(
  scope: TaskScope,
  projectId?: string,
  status?: TaskStatus,
  ownerPrincipalId?: string,
): string {
  return `${scopeKey(scope)}:${projectId ?? 'ALL'}:${status ?? 'ALL'}:${ownerPrincipalId ?? 'ALL'}`
}

function mergeEventPages(current: TaskEventPage, incoming: TaskEventPage): TaskEventPage {
  return {
    items: deduplicateTaskEvents([...current.items, ...incoming.items]),
    hasMore: incoming.hasMore,
    taskTerminal: incoming.taskTerminal,
    nextCursor: incoming.nextCursor,
  }
}

function deduplicateTaskEvents(items: TaskEventItem[]): TaskEventItem[] {
  const eventIds = new Set<string>()
  const domainEventIds = new Set<string>()
  return items.filter(item => {
    if (eventIds.has(item.event.eventId)) return false
    if (item.event.domainEventId && domainEventIds.has(item.event.domainEventId)) return false
    eventIds.add(item.event.eventId)
    if (item.event.domainEventId) domainEventIds.add(item.event.domainEventId)
    return true
  })
}

function mergeAssociationPages(current: TaskAssociationPage, incoming: TaskAssociationPage): TaskAssociationPage {
  const known = new Set(current.items.map(item => item.task.id))
  return {
    items: [...current.items, ...incoming.items.filter(item => !known.has(item.task.id))],
    nextCursor: incoming.nextCursor,
  }
}

function mergeTaskAssociations(current: TaskAssociations, incoming: TaskAssociations): TaskAssociations {
  const known = new Set(current.conversations.items.map(item => item.id))
  return {
    task: incoming.task,
    workItem: incoming.workItem,
    conversations: {
      items: [
        ...current.conversations.items,
        ...incoming.conversations.items.filter(item => !known.has(item.id)),
      ],
      nextCursor: incoming.conversations.nextCursor,
    },
  }
}

function isEmpty(value: unknown): boolean {
  if (Array.isArray(value)) return value.length === 0
  if (value && typeof value === 'object' && 'items' in value) {
    return Array.isArray((value as { items: unknown }).items)
      && (value as { items: unknown[] }).items.length === 0
  }
  return false
}

function isAbort(error: unknown): boolean {
  return error instanceof DOMException && error.name === 'AbortError'
}

function statusOf(error: unknown): number | null {
  return error instanceof CrewScopeApiError ? error.status : null
}

function isTaskCommandConflict(error: unknown): error is CrewScopeApiError {
  return error instanceof CrewScopeApiError && (error.status === 409 || error.status === 412)
}

function presentError(error: unknown, fallback: string): string {
  return error instanceof CrewScopeApiError ? error.envelope.message : fallback
}
