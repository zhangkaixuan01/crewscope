import { inject, reactive, readonly, type App, type InjectionKey } from 'vue'
import { CrewScopeApiError } from '../../api/client'
import type { ReviewGateway } from './gateway'
import type {
  EtaggedReview,
  ReviewCoordinates,
  ReviewDecisionInput,
  ReviewerExecutionResult,
  ReviewScope,
  ReviewSummary,
} from './types'

export type ReviewPhase = 'idle' | 'loading' | 'ready' | 'empty' | 'error'
export type ReviewOperation = 'execute' | 'decision' | 'modification'

export interface ReviewResource<T> {
  phase: ReviewPhase
  value: T | null
  errorMessage: string | null
  errorStatus: number | null
  errorCode: string | null
}

export interface ReviewCommandState {
  phase: 'idle' | 'pending' | 'success' | 'error' | 'conflict'
  operation: ReviewOperation | null
  reviewRequestId: string | null
  receiptCorrelationId: string | null
  execution: ReviewerExecutionResult | null
  errorMessage: string | null
  errorStatus: number | null
  errorCode: string | null
  errorDetails: Record<string, unknown>
  retryable: boolean
}

export interface ReviewStoreState {
  lists: Record<string, ReviewResource<ReviewSummary[]>>
  details: Record<string, ReviewResource<EtaggedReview>>
  selectedTaskId: string | null
  selectedExecutionId: string | null
  selectedReviewRequestId: string | null
  command: ReviewCommandState
}

export interface ReviewStore {
  state: Readonly<ReviewStoreState>
  activateScope(scope: ReviewScope): void
  synchronize(scope: ReviewScope, coordinates: ReviewCoordinates, reviewRequestId?: string | null): Promise<void>
  load(coordinates: ReviewCoordinates, force?: boolean): Promise<void>
  select(coordinates: ReviewCoordinates, reviewRequestId: string, force?: boolean): Promise<void>
  execute(): Promise<boolean>
  decide(input: ReviewDecisionInput): Promise<boolean>
  requestChanges(rationale: string): Promise<boolean>
  retryCommand(): Promise<boolean>
  clearCommand(): void
  invalidateAttempt(coordinates: ReviewCoordinates): void
  clearSelection(): void
  reset(): void
}

export const REVIEW_STORE: InjectionKey<ReviewStore> = Symbol('crewscope-review-store')

interface ActiveRequest {
  version: number
  scopeKey: string
  controller: AbortController
}

interface PendingCommand {
  generation: number
  operation: ReviewOperation
  reviewRequestId: string
  idempotencyKey: string
  run: () => Promise<ReviewerExecutionResult | { correlationId: string }>
}

/** Team-scoped Review state; server responses never update a later Team or attempt selection. */
export function createReviewStore(gateway: ReviewGateway): ReviewStore {
  const state = reactive<ReviewStoreState>(initialState())
  let activeScope: ReviewScope | null = null
  let activeScopeKey: string | null = null
  let generation = 0
  const requests = new Map<string, ActiveRequest>()
  let pendingCommand: PendingCommand | null = null

  function activateScope(scope: ReviewScope): void {
    const next = scopeKey(scope)
    if (activeScopeKey === next) return
    activeScope = { ...scope }
    activeScopeKey = next
    generation += 1
    abortRequests()
    pendingCommand = null
    replaceState(initialState())
  }

  async function synchronize(
    scope: ReviewScope,
    coordinates: ReviewCoordinates,
    reviewRequestId?: string | null,
  ): Promise<void> {
    activateScope(scope)
    const expectedGeneration = generation
    state.selectedTaskId = coordinates.taskId
    state.selectedExecutionId = coordinates.executionId
    await load(coordinates)
    if (expectedGeneration !== generation || !selectionMatches(coordinates)) return
    const values = state.lists[attemptKey(coordinates)]?.value ?? []
    const selected = reviewRequestId
      ?? [...values].sort((left, right) => right.revision - left.revision)[0]?.id
      ?? null
    state.selectedReviewRequestId = selected
    if (selected) await select(coordinates, selected)
  }

  async function load(coordinates: ReviewCoordinates, force = false): Promise<void> {
    const scope = requireScope()
    const key = attemptKey(coordinates)
    state.lists[key] ??= idleResource<ReviewSummary[]>()
    // Read the entry back through Vue's reactive record. Mutating the raw object returned by
    // `??=` would update data but would not notify the Workbench render effect.
    const resource = state.lists[key]!
    // Several route facts converge during drawer restoration. Reuse the in-flight read so
    // equivalent synchronizations cannot continuously abort one another.
    if (!force && ['loading', 'ready', 'empty'].includes(resource.phase)) return
    const request = beginRequest(`list:${key}`)
    resource.phase = 'loading'
    resource.errorMessage = null
    resource.errorStatus = null
    resource.errorCode = null
    try {
      const values = await gateway.list(scope, coordinates, request.controller.signal)
      if (!isCurrent(request)) return
      resource.value = [...values].sort((left, right) => right.revision - left.revision)
      resource.phase = values.length ? 'ready' : 'empty'
    } catch (error) {
      if (!isAbort(error) && isCurrent(request)) setError(resource, error, '暂时无法加载 Review 历史')
    } finally {
      finishRequest(`list:${key}`, request)
    }
  }

  async function select(
    coordinates: ReviewCoordinates,
    reviewRequestId: string,
    force = false,
  ): Promise<void> {
    const scope = requireScope()
    if (!selectionMatches(coordinates)) return
    state.selectedReviewRequestId = reviewRequestId
    const key = detailKey(coordinates, reviewRequestId)
    state.details[key] ??= idleResource<EtaggedReview>()
    const resource = state.details[key]!
    // Deep-link, Coding and Task watchers may select the same Review concurrently.
    // Keep one authoritative request in flight instead of creating an abort loop.
    if (!force && ['loading', 'ready'].includes(resource.phase)) return
    const request = beginRequest(`detail:${key}`)
    resource.phase = 'loading'
    resource.errorMessage = null
    resource.errorStatus = null
    resource.errorCode = null
    try {
      const value = await gateway.get(scope, coordinates, reviewRequestId, request.controller.signal)
      if (!isCurrent(request) || !selectionMatches(coordinates)) return
      if (value.value.id !== reviewRequestId) throw new Error('Review response identity mismatch')
      resource.value = value
      resource.phase = 'ready'
    } catch (error) {
      if (!isAbort(error) && isCurrent(request)) setError(resource, error, '暂时无法加载 Review 详情')
    } finally {
      finishRequest(`detail:${key}`, request)
    }
  }

  async function execute(): Promise<boolean> {
    const context = commandContext()
    if (!context) return false
    const key = crypto.randomUUID()
    return runCommand({
      generation,
      operation: 'execute',
      reviewRequestId: context.review.value.id,
      idempotencyKey: key,
      run: () => gateway.execute(
        context.scope, context.coordinates, context.review.value.id,
        context.review.value.version, key,
      ),
    })
  }

  async function decide(input: ReviewDecisionInput): Promise<boolean> {
    const context = commandContext()
    if (!context) return false
    const key = crypto.randomUUID()
    return runCommand({
      generation,
      operation: 'decision',
      reviewRequestId: context.review.value.id,
      idempotencyKey: key,
      run: () => gateway.decide(
        context.scope, context.coordinates, context.review.value.id,
        context.review.value.version, input, key,
      ),
    })
  }

  async function requestChanges(rationale: string): Promise<boolean> {
    const context = commandContext()
    if (!context) return false
    const key = crypto.randomUUID()
    return runCommand({
      generation,
      operation: 'modification',
      reviewRequestId: context.review.value.id,
      idempotencyKey: key,
      run: () => gateway.requestChanges(
        context.scope, context.coordinates, context.review.value.id,
        context.review.value.version, rationale, key,
      ),
    })
  }

  async function runCommand(command: PendingCommand): Promise<boolean> {
    if (state.command.phase === 'pending') return false
    pendingCommand = command
    state.command = {
      ...idleCommand(), phase: 'pending', operation: command.operation,
      reviewRequestId: command.reviewRequestId,
    }
    try {
      const result = await command.run()
      if (command.generation !== generation || pendingCommand !== command) return false
      state.command.phase = 'success'
      if ('reviewRequestId' in result) {
        state.command.execution = result
        state.command.receiptCorrelationId = result.receipt.correlationId
      } else {
        state.command.receiptCorrelationId = result.correlationId
      }
      pendingCommand = null
      await refreshSelected(command.reviewRequestId)
      return true
    } catch (error) {
      if (command.generation !== generation || pendingCommand !== command) return false
      const api = error instanceof CrewScopeApiError ? error : null
      const isConflict = api?.status === 409 || api?.status === 412
      state.command.phase = isConflict ? 'conflict' : 'error'
      state.command.errorMessage = api?.envelope.message ?? 'Review 命令执行失败'
      state.command.errorStatus = api?.status ?? null
      state.command.errorCode = api?.envelope.code ?? null
      state.command.errorDetails = { ...(api?.envelope.details ?? {}) }
      state.command.retryable = Boolean(api?.envelope.retryable) && !isConflict
      if (!state.command.retryable) pendingCommand = null
      if (isConflict) await refreshSelected(command.reviewRequestId)
      return false
    }
  }

  async function retryCommand(): Promise<boolean> {
    const command = pendingCommand
    if (!command || !state.command.retryable || command.generation !== generation) return false
    state.command.phase = 'idle'
    return runCommand(command)
  }

  async function refreshSelected(reviewRequestId: string): Promise<void> {
    const coordinates = selectedCoordinates()
    if (!coordinates) return
    invalidateAttempt(coordinates)
    state.selectedReviewRequestId = reviewRequestId
    await Promise.all([
      load(coordinates, true),
      select(coordinates, reviewRequestId, true),
    ])
  }

  function invalidateAttempt(coordinates: ReviewCoordinates): void {
    const prefix = `${attemptKey(coordinates)}:`
    delete state.lists[attemptKey(coordinates)]
    for (const key of Object.keys(state.details)) {
      if (key.startsWith(prefix)) delete state.details[key]
    }
  }

  function clearCommand(): void {
    if (state.command.phase === 'pending') return
    pendingCommand = null
    state.command = idleCommand()
  }

  function clearSelection(): void {
    state.selectedTaskId = null
    state.selectedExecutionId = null
    state.selectedReviewRequestId = null
    generation += 1
    abortRequests()
    pendingCommand = null
    state.command = idleCommand()
  }

  function reset(): void {
    generation += 1
    abortRequests()
    pendingCommand = null
    activeScope = null
    activeScopeKey = null
    replaceState(initialState())
  }

  function commandContext(): {
    scope: ReviewScope
    coordinates: ReviewCoordinates
    review: EtaggedReview
  } | null {
    const scope = requireScope()
    const coordinates = selectedCoordinates()
    const id = state.selectedReviewRequestId
    if (!coordinates || !id) return null
    const review = state.details[detailKey(coordinates, id)]?.value
    return review ? { scope, coordinates, review } : null
  }

  function selectedCoordinates(): ReviewCoordinates | null {
    return state.selectedTaskId && state.selectedExecutionId
      ? { taskId: state.selectedTaskId, executionId: state.selectedExecutionId }
      : null
  }

  function selectionMatches(coordinates: ReviewCoordinates): boolean {
    return state.selectedTaskId === coordinates.taskId
      && state.selectedExecutionId === coordinates.executionId
  }

  function requireScope(): ReviewScope {
    if (!activeScope) throw new Error('Review scope is not active')
    return { ...activeScope }
  }

  function beginRequest(key: string): ActiveRequest {
    requests.get(key)?.controller.abort()
    const request: ActiveRequest = {
      version: (requests.get(key)?.version ?? 0) + 1,
      scopeKey: activeScopeKey!,
      controller: new AbortController(),
    }
    requests.set(key, request)
    return request
  }

  function isCurrent(request: ActiveRequest): boolean {
    return activeScopeKey === request.scopeKey
      && [...requests.values()].some(current => current === request)
  }

  function finishRequest(key: string, request: ActiveRequest): void {
    if (requests.get(key) === request) requests.delete(key)
  }

  function abortRequests(): void {
    requests.forEach(request => request.controller.abort())
    requests.clear()
  }

  function replaceState(value: ReviewStoreState): void {
    Object.assign(state, value)
  }

  return {
    state: readonly(state) as Readonly<ReviewStoreState>, activateScope, synchronize, load, select, execute, decide,
    requestChanges, retryCommand, clearCommand, invalidateAttempt, clearSelection, reset,
  }
}

export function reviewAttemptKey(coordinates: ReviewCoordinates): string {
  return attemptKey(coordinates)
}

export function reviewDetailKey(coordinates: ReviewCoordinates, reviewRequestId: string): string {
  return detailKey(coordinates, reviewRequestId)
}

function attemptKey(coordinates: ReviewCoordinates): string {
  return `${coordinates.taskId}:${coordinates.executionId}`
}

function detailKey(coordinates: ReviewCoordinates, reviewRequestId: string): string {
  return `${attemptKey(coordinates)}:${reviewRequestId}`
}

function scopeKey(scope: ReviewScope): string {
  return `${scope.organizationId}:${scope.teamId}`
}

function initialState(): ReviewStoreState {
  return {
    lists: {}, details: {}, selectedTaskId: null, selectedExecutionId: null,
    selectedReviewRequestId: null, command: idleCommand(),
  }
}

function idleResource<T>(): ReviewResource<T> {
  return { phase: 'idle', value: null, errorMessage: null, errorStatus: null, errorCode: null }
}

function idleCommand(): ReviewCommandState {
  return {
    phase: 'idle', operation: null, reviewRequestId: null, receiptCorrelationId: null,
    execution: null, errorMessage: null, errorStatus: null, errorCode: null,
    errorDetails: {}, retryable: false,
  }
}

function setError(resource: ReviewResource<unknown>, error: unknown, fallback: string): void {
  const api = error instanceof CrewScopeApiError ? error : null
  resource.phase = 'error'
  resource.errorMessage = api?.envelope.message ?? fallback
  resource.errorStatus = api?.status ?? null
  resource.errorCode = api?.envelope.code ?? null
}

function isAbort(error: unknown): boolean {
  return error instanceof DOMException && error.name === 'AbortError'
}

export function installReviewStore(app: App, gateway: ReviewGateway): ReviewStore {
  const store = createReviewStore(gateway)
  app.provide(REVIEW_STORE, store)
  return store
}

export function useReviewStore(): ReviewStore {
  const store = inject(REVIEW_STORE)
  if (!store) throw new Error('ReviewStore has not been installed')
  return store
}
