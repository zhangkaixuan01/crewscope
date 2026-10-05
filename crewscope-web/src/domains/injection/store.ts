import { inject, reactive, readonly, type App, type InjectionKey } from 'vue'
import { CrewScopeApiError } from '../../api/client'
import type { SettingsScope } from '../settings/types'
import type { InjectionGateway } from './gateway'
import type { InjectionFeedbackInput, InjectionReference, InjectionReferences } from './types'

export type InjectionPhase = 'idle' | 'loading' | 'ready' | 'empty' | 'error'

/** One execution's evidence view, keyed `taskId:executionId` — the drawer holds several at once. */
export interface InjectionViewResource {
  phase: InjectionPhase
  value: InjectionReferences | null
  errorMessage: string | null
  errorStatus: number | null
}

/**
 * The I02c feedback command is structurally idempotent (contract §3: no Idempotency-Key, no
 * If-Match), so this slot carries a plain success message instead of a receipt envelope.
 */
export interface InjectionFeedbackState {
  phase: 'idle' | 'pending' | 'success' | 'error'
  /** The view the last command targeted (`taskId:executionId`), for scoping the banner. */
  key: string | null
  /** The stage-free quadruple that was marked, for scoping the banner to its row. */
  reference: InjectionFeedbackInput | null
  message: string | null
  errorMessage: string | null
  errorCode: string | null
  errorStatus: number | null
}

export interface InjectionStoreState {
  views: Record<string, InjectionViewResource>
  feedback: InjectionFeedbackState
}

export interface InjectionStore {
  state: Readonly<InjectionStoreState>
  activateScope(scope: SettingsScope): void
  load(taskId: string, executionId: string): Promise<void>
  submitFeedback(taskId: string, executionId: string, reference: InjectionFeedbackInput): Promise<boolean>
  clearFeedback(): void
  reset(): void
}

export const INJECTION_STORE: InjectionKey<InjectionStore> = Symbol('crewscope-injection-store')

export function viewKey(taskId: string, executionId: string): string {
  return `${taskId}:${executionId}`
}

interface ViewRequest {
  key: string
  version: number
  controller: AbortController
  scopeKey: string | null
}

/** Execution-keyed I02c evidence views with generation checks in addition to AbortSignal cancellation. */
export function createInjectionStore(gateway: InjectionGateway): InjectionStore {
  const state = reactive<InjectionStoreState>(initialState())
  let activeScope: SettingsScope | null = null
  let activeScopeKey: string | null = null
  let generation = 0
  const requests = new Map<string, ViewRequest>()

  function activateScope(scope: SettingsScope): void {
    const nextKey = scopeKey(scope)
    if (nextKey === activeScopeKey) return
    activeScope = { ...scope }
    activeScopeKey = nextKey
    generation += 1
    abortRequests()
    replaceState(initialState())
  }

  async function load(taskId: string, executionId: string): Promise<void> {
    const scope = requireScope()
    const view = viewKey(taskId, executionId)
    const current = resource(view)
    if (['ready', 'empty'].includes(current.phase)) return
    const request = beginRequest(view)
    state.views[view] = { phase: 'loading', value: null, errorMessage: null, errorStatus: null }
    try {
      const value = await gateway.list(scope, taskId, executionId, request.controller.signal)
      if (!isCurrent(request)) return
      state.views[view] = {
        phase: value.attempts.length === 0 ? 'empty' : 'ready',
        value,
        errorMessage: null,
        errorStatus: null,
      }
    } catch (error) {
      if (!isAbort(error) && isCurrent(request)) {
        state.views[view] = { phase: 'error', value: null, errorMessage: presentError(error, '暂时无法加载注入与引用证据'), errorStatus: statusOf(error) }
      }
    } finally {
      finishRequest(request)
    }
  }

  async function submitFeedback(taskId: string, executionId: string, reference: InjectionFeedbackInput): Promise<boolean> {
    const scope = requireScope()
    const commandGeneration = generation
    if (state.feedback.phase === 'pending') return false
    state.feedback = {
      phase: 'pending', key: viewKey(taskId, executionId), reference,
      message: null, errorMessage: null, errorCode: null, errorStatus: null,
    }
    const pending = state.feedback
    try {
      await gateway.submitFeedback(scope, taskId, executionId, reference)
      if (commandGeneration !== generation || state.feedback !== pending) return false
      patchNotApplicable(viewKey(taskId, executionId), reference)
      state.feedback.phase = 'success'
      state.feedback.message = '已标记为不适用'
      return true
    } catch (error) {
      if (commandGeneration !== generation || state.feedback !== pending) return false
      state.feedback.phase = 'error'
      state.feedback.errorMessage = feedbackError(error)
      state.feedback.errorCode = error instanceof CrewScopeApiError ? error.envelope.code : null
      state.feedback.errorStatus = statusOf(error)
      return false
    }
  }

  /**
   * The member's judgement is recorded per execution, so the same quadruple reads back as
   * marked in every attempt that carries it — patch them all in place (F01b replaceJob precedent).
   * A replay finds nothing left to mark and keeps the view reference (no reactive churn).
   */
  function patchNotApplicable(view: string, reference: InjectionFeedbackInput): void {
    const resource = state.views[view]
    const value = resource?.value
    if (!resource || !value) return
    const sameKey = (row: InjectionReference) =>
      row.type === reference.type && row.sourceId === reference.sourceId
        && row.version === reference.version && row.contentHash === reference.contentHash
    let changed = false
    const attempts = value.attempts.map(attempt => ({
      ...attempt,
      references: attempt.references.map(row => {
        if (!sameKey(row) || row.notApplicable) return row
        changed = true
        return { ...row, notApplicable: true }
      }),
    }))
    if (changed) state.views[view] = { ...resource, value: { ...value, attempts } }
  }

  function clearFeedback(): void {
    state.feedback = feedbackState()
  }

  function reset(): void {
    activeScope = null
    activeScopeKey = null
    generation += 1
    abortRequests()
    replaceState(initialState())
  }

  function requireScope(): SettingsScope {
    if (!activeScope) throw new Error('Injection Store Scope is not active')
    return { ...activeScope }
  }

  function resource(view: string): InjectionViewResource {
    return state.views[view] ?? viewResource()
  }

  function beginRequest(key: string): ViewRequest {
    requests.get(key)?.controller.abort()
    const request: ViewRequest = { key, version: generation, controller: new AbortController(), scopeKey: activeScopeKey }
    requests.set(key, request)
    return request
  }

  function isCurrent(request: ViewRequest): boolean {
    return generation === request.version
      && activeScopeKey === request.scopeKey
      && requests.get(request.key) === request
  }

  function finishRequest(request: ViewRequest): void {
    if (requests.get(request.key) === request) requests.delete(request.key)
  }

  function abortRequests(): void {
    for (const request of requests.values()) request.controller.abort()
    requests.clear()
  }

  function replaceState(next: InjectionStoreState): void {
    state.views = next.views
    state.feedback = next.feedback
  }

  return {
    state: readonly(state) as Readonly<InjectionStoreState>,
    activateScope,
    load,
    submitFeedback,
    clearFeedback,
    reset,
  }
}

export function installInjectionStore(app: App, gateway: InjectionGateway): InjectionStore {
  const store = createInjectionStore(gateway)
  app.provide(INJECTION_STORE, store)
  return store
}

export function useInjectionStore(): InjectionStore {
  const store = inject(INJECTION_STORE)
  if (!store) throw new Error('CrewScope Injection Store is not installed')
  return store
}

function initialState(): InjectionStoreState {
  return { views: {}, feedback: feedbackState() }
}

function viewResource(): InjectionViewResource {
  return { phase: 'idle', value: null, errorMessage: null, errorStatus: null }
}

function feedbackState(): InjectionFeedbackState {
  return { phase: 'idle', key: null, reference: null, message: null, errorMessage: null, errorCode: null, errorStatus: null }
}

/** Contract §6: 422 outside-manifest is the one feedback error worth its own wording. */
function feedbackError(error: unknown): string {
  if (error instanceof CrewScopeApiError) {
    if (error.envelope.code === 'feedback_reference_outside_manifest') {
      return '该引用不在本次执行任何 attempt 的已注入并集中，无法标记。'
    }
    return error.envelope.message
  }
  return '标记不适用失败'
}

function presentError(error: unknown, fallback: string): string {
  return error instanceof CrewScopeApiError ? error.envelope.message : fallback
}

function statusOf(error: unknown): number | null {
  return error instanceof CrewScopeApiError ? error.status : null
}

function isAbort(error: unknown): boolean {
  return error instanceof DOMException && error.name === 'AbortError'
}

function scopeKey(scope: SettingsScope): string {
  return `${scope.organizationId}:${scope.teamId}`
}
