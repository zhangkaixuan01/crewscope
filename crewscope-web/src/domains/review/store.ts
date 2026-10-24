import { registerStoreReset } from '../../app/storeLifecycle'
import { createCommandGateway } from '../../api/commandGateway'
import { secureId } from '../../api/secureId'
import {getCurrentInstance, inject, reactive, readonly, type App, type InjectionKey} from 'vue'
import { CrewScopeApiError } from '../../api/client'
import { HttpReviewGateway, type ReviewGateway } from './gateway'
import type {
  EtaggedReview,
  ReviewCoordinates,
  ReviewDecisionInput,
  ReviewLineComment,
  ReviewerExecutionResult,
  ReviewScope,
  ReviewSummary,
} from './types'
import { isAbort } from '../../api/errors'
import { createRequestScope } from '../../api/requestScope'

export type ReviewPhase = 'idle' | 'loading' | 'ready' | 'empty' | 'error'
export type ReviewOperation = 'execute' | 'decision' | 'modification' | 'create'

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
  comments: Record<string, ReviewResource<ReviewLineComment[]>>
  selectedTaskId: string | null
  selectedExecutionId: string | null
  selectedReviewRequestId: string | null
  command: ReviewCommandState
}

export interface ReviewStore {
  state: Readonly<ReviewStoreState>
  activateScope(scope: ReviewScope): void
  createReview(): Promise<boolean>
  synchronize(scope: ReviewScope, coordinates: ReviewCoordinates, reviewRequestId?: string | null): Promise<void>
  load(coordinates: ReviewCoordinates, force?: boolean): Promise<void>
  select(coordinates: ReviewCoordinates, reviewRequestId: string, force?: boolean): Promise<void>
  execute(): Promise<boolean>
  decide(input: ReviewDecisionInput): Promise<boolean>
  requestChanges(rationale: string): Promise<boolean>
  loadComments(coordinates: ReviewCoordinates, reviewRequestId: string, force?: boolean): Promise<void>
  addComment(input: { filePath: string; side: 'OLD' | 'NEW'; lineNumber: number; hunkHeader: string; lineContentHash: string; diffGeneration: number; content: string }): Promise<ReviewLineComment | null>
  retryCommand(): Promise<boolean>
  clearCommand(): void
  invalidateAttempt(coordinates: ReviewCoordinates): void
  clearSelection(): void
  reset(): void
}

export const REVIEW_STORE: InjectionKey<ReviewStore> = Symbol('crewscope-review-store')

interface PendingCommand {
  isCurrent: () => boolean
  operation: ReviewOperation
  reviewRequestId: string
  idempotencyKey: string
  run: () => Promise<ReviewerExecutionResult | { correlationId: string }>
}

/** Team-scoped Review state; server responses never update a later Team or attempt selection. */
export function createReviewStore(gateway: ReviewGateway): ReviewStore {
  const commandIntents = createCommandGateway(gateway, { execute: 4, decide: 5, requestChanges: 5, addComment: 4, create: 2 })
  gateway = commandIntents.gateway
  const state = reactive<ReviewStoreState>(initialState())
  let activeScope: ReviewScope | null = null
  let activeScopeKey: string | null = null
  // Scope switches and selection clears advance the epoch (voiding captures) — the
  // generation counter + ActiveRequest map this replaces.
  const requests = createRequestScope(() => activeScopeKey ?? '')
  let pendingCommand: PendingCommand | null = null

  function activateScope(scope: ReviewScope): void {
    const next = scopeKey(scope)
    if (activeScopeKey === next) return
    activeScope = { ...scope }
    activeScopeKey = next
    requests.invalidate()
    pendingCommand = null
    replaceState(initialState())
  }

  async function synchronize(
    scope: ReviewScope,
    coordinates: ReviewCoordinates,
    reviewRequestId?: string | null,
  ): Promise<void> {
    activateScope(scope)
    const owner = requests.capture()
    state.selectedTaskId = coordinates.taskId
    state.selectedExecutionId = coordinates.executionId
    await load(coordinates)
    if (!owner.isCurrent() || !selectionMatches(coordinates)) return
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
    const request = requests.begin(`list:${key}`)
    resource.phase = 'loading'
    resource.errorMessage = null
    resource.errorStatus = null
    resource.errorCode = null
    try {
      const values = await gateway.list(scope, coordinates, request.signal)
      if (!request.isCurrent()) return
      resource.value = [...values].sort((left, right) => right.revision - left.revision)
      resource.phase = values.length ? 'ready' : 'empty'
    } catch (error) {
      if (!isAbort(error) && request.isCurrent()) setError(resource, error, '暂时无法加载 Review 历史')
    } finally {
      request.finish()
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
    const request = requests.begin(`detail:${key}`)
    resource.phase = 'loading'
    resource.errorMessage = null
    resource.errorStatus = null
    resource.errorCode = null
    try {
      const value = await gateway.get(scope, coordinates, reviewRequestId, request.signal)
      if (!request.isCurrent() || !selectionMatches(coordinates)) return
      if (value.value.id !== reviewRequestId) throw new Error('Review response identity mismatch')
      resource.value = value
      resource.phase = 'ready'
      await loadComments(coordinates, reviewRequestId)
    } catch (error) {
      if (!isAbort(error) && request.isCurrent()) setError(resource, error, '暂时无法加载 Review 详情')
    } finally {
      request.finish()
    }
  }

  async function loadComments(coordinates: ReviewCoordinates, reviewRequestId: string, force = false): Promise<void> {
    const scope = requireScope()
    const key = detailKey(coordinates, reviewRequestId)
    state.comments[key] ??= idleResource<ReviewLineComment[]>()
    const resource = state.comments[key]!
    if (!force && ['loading', 'ready', 'empty'].includes(resource.phase)) return
    const request = requests.begin(`comments:${key}`)
    resource.phase = 'loading'; resource.errorMessage = null
    try {
      const values = await gateway.listComments?.(scope, coordinates, reviewRequestId, request.signal) ?? []
      if (!request.isCurrent()) return
      resource.value = values; resource.phase = values.length ? 'ready' : 'empty'
    } catch (error) { if (!isAbort(error) && request.isCurrent()) setError(resource, error, '暂时无法加载行级评论') }
    finally { request.finish() }
  }

  async function addComment(input: { filePath: string; side: 'OLD' | 'NEW'; lineNumber: number; hunkHeader: string; lineContentHash: string; diffGeneration: number; content: string }): Promise<ReviewLineComment | null> {
    const scope = requireScope(); const coordinates = selectedCoordinates(); const reviewRequestId = state.selectedReviewRequestId
    if (!coordinates || !reviewRequestId || !gateway.addComment) return null
    const owner = requests.capture()
    const value = await gateway.addComment(scope, coordinates, reviewRequestId, input, secureId())
    if (!owner.isCurrent() || !selectionMatches(coordinates) || state.selectedReviewRequestId !== reviewRequestId) return null
    const key = detailKey(coordinates, reviewRequestId)
    state.comments[key] ??= idleResource<ReviewLineComment[]>()
    state.comments[key]!.value = [...(state.comments[key]!.value ?? []), value]
    state.comments[key]!.phase = 'ready'
    return value
  }

  /**
   * M9b-Q02 review-gate path: requests the platform to open a Review on the selected attempt,
   * resolving the advisory Reviewer automatically when no snapshot id is supplied.
   */
  async function createReview(): Promise<boolean> {
    const scope = requireScope()
    const coordinates = selectedCoordinates()
    if (!coordinates || !gateway.create) return false
    if (state.command.phase === 'pending') return false
    const owner = requests.capture()
    state.command = { ...idleCommand(), phase: 'pending', operation: 'create' }
    try {
      const receipt = await gateway.create(scope, coordinates, secureId())
      if (!owner.isCurrent()) return false
      state.command.phase = 'success'
      state.command.receiptCorrelationId = receipt.correlationId
      invalidateAttempt(coordinates)
      await load(coordinates, true)
      return true
    } catch (error) {
      if (!owner.isCurrent()) return false
      const api = error instanceof CrewScopeApiError ? error : null
      const isConflict = api?.status === 409 || api?.status === 412
      state.command.phase = isConflict ? 'conflict' : 'error'
      state.command.errorMessage = api?.envelope.message ?? '无法发起审查'
      state.command.errorStatus = api?.status ?? null
      state.command.errorCode = api?.envelope.code ?? null
      state.command.errorDetails = { ...(api?.envelope.details ?? {}) }
      state.command.retryable = !isConflict
      return false
    }
  }

  async function execute(): Promise<boolean> {
    const context = commandContext()
    if (!context) return false
    const key = secureId()
    const owner = requests.capture()
    return runCommand({
      isCurrent: owner.isCurrent,
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
    const key = secureId()
    const owner = requests.capture()
    return runCommand({
      isCurrent: owner.isCurrent,
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
    const key = secureId()
    const owner = requests.capture()
    return runCommand({
      isCurrent: owner.isCurrent,
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
      if (!command.isCurrent() || pendingCommand !== command) return false
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
      if (!command.isCurrent() || pendingCommand !== command) return false
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
    if (!command || !state.command.retryable || !command.isCurrent()) return false
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
    for (const key of Object.keys(state.comments)) {
      if (key.startsWith(prefix)) delete state.comments[key]
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
    requests.invalidate()
    pendingCommand = null
    state.command = idleCommand()
  }

  function reset(): void {
    commandIntents.clear()
    requests.invalidate()
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

  function replaceState(value: ReviewStoreState): void {
    Object.assign(state, value)
  }

  return {
    state: readonly(state) as Readonly<ReviewStoreState>, activateScope, synchronize, load, select, createReview, execute, decide,
    requestChanges, loadComments, addComment, retryCommand, clearCommand, invalidateAttempt, clearSelection, reset,
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
    lists: {}, details: {}, comments: {}, selectedTaskId: null, selectedExecutionId: null,
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


// M13d-P01: lazily installed on the pages that consume this domain (keeps it out of the eager
// main chunk). The instance lives on the app, so revisiting a page reuses cached state; spec
// fixtures provided through app-level provide always win and are never shadowed.
export function ensureReviewStore(app: App): ReviewStore {
  const existing = app.runWithContext(() => inject(REVIEW_STORE, null))
  if (existing) return existing
  const store = createReviewStore(new HttpReviewGateway())
  app.provide(REVIEW_STORE, store)
  registerStoreReset(app, () => store.reset())
  return store
}


export function useReviewStore(): ReviewStore {
  const installed = inject(REVIEW_STORE, null)
  if (installed) return installed
  // M13d-P01: fall back to lazy installation — a component declares its domain dependency
  // by calling use*, so no host page has to pre-install the store.
  const instance = getCurrentInstance()
  if (!instance) throw new Error('ReviewStore has not been installed')
  return ensureReviewStore(instance.appContext.app)
}
