import { inject, reactive, readonly, type App, type DeepReadonly, type InjectionKey } from 'vue'
import { CrewScopeApiError } from '../../api/client'
import type { TeamObserverGateway } from './gateway'
import type { TeamObserverEvidence, TeamObserverScope, TeamObserverSession, TeamSummary } from './types'

export type TeamObserverPhase = 'idle' | 'creating-session' | 'connecting' | 'running' | 'reconnecting' | 'completed' | 'cancelling' | 'cancelled' | 'error'

export interface TeamObserverState {
  phase: TeamObserverPhase
  session: TeamObserverSession | null
  invocationId: string | null
  instruction: string
  summary: TeamSummary | null
  lastSequence: number
  errorMessage: string | null
  errorStatus: number | null
  retryable: boolean
}

export interface TeamObserverStore {
  state: DeepReadonly<TeamObserverState>
  activateScope(scope: TeamObserverScope): void
  invoke(instruction: string, maxItemsPerSection?: number): Promise<boolean>
  retry(): Promise<boolean>
  cancel(): Promise<boolean>
  refreshSummary(): Promise<boolean>
  resolveEvidence(evidenceIndex: number): Promise<TeamObserverEvidence | null>
  reset(): void
}

export const TEAM_OBSERVER_STORE: InjectionKey<TeamObserverStore> = Symbol('crewscope-team-observer-store')
const MAX_RECONNECTS = 3

/** Scope-isolated state machine; reconnecting always resumes the retained invocation. */
export function createTeamObserverStore(gateway: TeamObserverGateway): TeamObserverStore {
  const state = reactive<TeamObserverState>(initialState())
  let activeScope: TeamObserverScope | null = null
  let activeKey: string | null = null
  let generation = 0
  let controller: AbortController | null = null
  const auxiliaryControllers = new Set<AbortController>()

  function activateScope(scope: TeamObserverScope): void {
    const key = scopeKey(scope)
    if (key === activeKey) return
    controller?.abort()
    abortAuxiliaryRequests()
    generation += 1
    activeScope = { ...scope }
    activeKey = key
    replace(initialState())
  }

  async function invoke(rawInstruction: string, maxItemsPerSection = 10): Promise<boolean> {
    const instruction = rawInstruction.trim()
    if (!activeScope || instruction.length < 1 || instruction.length > 4_000 || maxItemsPerSection < 1 || maxItemsPerSection > 50 || busy()) return false
    const targetGeneration = generation
    const scope = { ...activeScope }
    abortAuxiliaryRequests()
    state.instruction = instruction
    state.summary = null
    state.invocationId = null
    state.lastSequence = -1
    state.errorMessage = null
    state.errorStatus = null
    state.retryable = false
    controller?.abort()
    const invocationController = new AbortController()
    controller = invocationController
    try {
      if (!state.session) {
        state.phase = 'creating-session'
        const session = await gateway.createSession(scope, invocationController.signal)
        if (targetGeneration !== generation || invocationController.signal.aborted) return false
        state.session = session
      }
      if (targetGeneration !== generation || invocationController.signal.aborted) return false
      state.phase = 'connecting'
      const connection = await gateway.invoke(scope, state.session.sessionId, instruction, maxItemsPerSection, invocationController.signal)
      if (targetGeneration !== generation || invocationController.signal.aborted) return false
      state.invocationId = connection.invocationId
      return consume(connection.events, targetGeneration, invocationController.signal)
    } catch (error) {
      return fail(error, targetGeneration)
    }
  }

  async function consume(events: AsyncIterable<import('./types').TeamObserverEvent>, targetGeneration: number, signal: AbortSignal, allowResume = true): Promise<boolean> {
    try {
      for await (const event of events) {
        if (signal.aborted || targetGeneration !== generation || event.sequence <= state.lastSequence) continue
        state.lastSequence = event.sequence
        if (event.type === 'STARTED') state.phase = 'running'
        else if (event.type === 'SUMMARY_COMPLETED') {
          if (!event.summary) throw new TypeError('Team Observer completed without a summary')
          state.summary = event.summary
          state.phase = 'completed'
          return true
        } else if (event.type === 'CANCELLED') {
          state.phase = 'cancelled'
          return false
        } else if (event.type === 'FAILED') {
          state.phase = 'error'
          state.errorMessage = 'Team Observer 暂时无法生成摘要，请稍后重试'
          state.retryable = false
          return false
        }
      }
      if (allowResume && !signal.aborted && targetGeneration === generation && state.invocationId) return resumeLoop(targetGeneration, signal)
      return false
    } catch (error) {
      if (isAbort(error) || signal.aborted || targetGeneration !== generation) return false
      return allowResume ? resumeLoop(targetGeneration, signal) : false
    }
  }

  async function resumeLoop(targetGeneration: number, originalSignal: AbortSignal): Promise<boolean> {
    if (!activeScope || !state.session || !state.invocationId || originalSignal.aborted) return false
    for (let attempt = 1; attempt <= MAX_RECONNECTS; attempt += 1) {
      state.phase = 'reconnecting'
      try {
        const connection = await gateway.resume(activeScope, state.session.sessionId, state.invocationId, originalSignal)
        if (connection.invocationId !== state.invocationId) throw new TypeError('Team Observer resume changed invocation')
        const completed = await consume(connection.events, targetGeneration, originalSignal, false)
        if (['completed', 'cancelled'].includes(state.phase)) return completed
      } catch (error) {
        if (isAbort(error) || originalSignal.aborted || targetGeneration !== generation) return false
        if (!transportRetryable(error)) return fail(error, targetGeneration)
      }
    }
    state.phase = 'error'
    state.errorMessage = 'Team Observer 连接已中断，可以重新连接同一次调用'
    state.retryable = true
    return false
  }

  async function retry(): Promise<boolean> {
    if (!activeScope || !state.session || !state.invocationId || state.phase !== 'error' || !state.retryable) return false
    abortAuxiliaryRequests()
    controller?.abort()
    const retryController = new AbortController()
    controller = retryController
    return resumeLoop(generation, retryController.signal)
  }

  async function cancel(): Promise<boolean> {
    if (!activeScope || !state.session || !state.invocationId || !busy()) return false
    const targetGeneration = generation
    const scope = { ...activeScope }
    const sessionId = state.session.sessionId
    const invocationId = state.invocationId
    const requestController = beginAuxiliaryRequest()
    state.phase = 'cancelling'
    try {
      const response = await gateway.cancel(scope, sessionId, invocationId, requestController.signal)
      if (!coordinatesCurrent(targetGeneration, scope, sessionId, invocationId) || requestController.signal.aborted) return false
      if (response.invocationId !== invocationId || !response.cancelled) throw new TypeError('Team Observer cancellation was not accepted')
      state.phase = 'cancelled'
      controller?.abort()
      return true
    } catch (error) {
      if (requestController.signal.aborted || !coordinatesCurrent(targetGeneration, scope, sessionId, invocationId)) return false
      return fail(error, targetGeneration)
    } finally {
      finishAuxiliaryRequest(requestController)
    }
  }

  async function refreshSummary(): Promise<boolean> {
    if (!activeScope || !state.session || !state.invocationId) return false
    const targetGeneration = generation
    const scope = { ...activeScope }
    const sessionId = state.session.sessionId
    const invocationId = state.invocationId
    const requestController = beginAuxiliaryRequest()
    try {
      const summary = await gateway.summary(scope, sessionId, invocationId, requestController.signal)
      if (!coordinatesCurrent(targetGeneration, scope, sessionId, invocationId) || requestController.signal.aborted) return false
      state.summary = summary
      state.phase = 'completed'
      return true
    } catch (error) {
      if (requestController.signal.aborted || !coordinatesCurrent(targetGeneration, scope, sessionId, invocationId)) return false
      return fail(error, targetGeneration)
    } finally {
      finishAuxiliaryRequest(requestController)
    }
  }

  async function resolveEvidence(evidenceIndex: number): Promise<TeamObserverEvidence | null> {
    if (!activeScope || !state.session || !state.invocationId || !Number.isInteger(evidenceIndex) || evidenceIndex < 0) return null
    const targetGeneration = generation
    const scope = { ...activeScope }
    const sessionId = state.session.sessionId
    const invocationId = state.invocationId
    const requestController = beginAuxiliaryRequest()
    try {
      const selected = summaryEntries(state.summary).find(entry => entry.evidenceIndex === evidenceIndex)
      if (!selected) throw new TypeError('Evidence does not belong to the current Team Observer summary')
      const evidence = await gateway.evidence(scope, sessionId, invocationId, evidenceIndex, requestController.signal)
      if (!coordinatesCurrent(targetGeneration, scope, sessionId, invocationId) || requestController.signal.aborted) return null
      const current = summaryEntries(state.summary).find(entry => entry.evidenceIndex === evidenceIndex)
      if (!current || current.section !== selected.section || current.dataScope !== selected.dataScope || current.summary !== selected.summary) return null
      if (evidence.section !== selected.section || evidence.dataScope !== selected.dataScope || evidence.summary !== selected.summary) {
        throw new TypeError('Evidence no longer matches the current Team Observer summary')
      }
      return evidence
    } catch (error) {
      if (!requestController.signal.aborted && coordinatesCurrent(targetGeneration, scope, sessionId, invocationId)) {
        fail(error, targetGeneration, '暂时无法打开这条证据')
      }
      return null
    } finally {
      finishAuxiliaryRequest(requestController)
    }
  }

  function beginAuxiliaryRequest(): AbortController {
    const requestController = new AbortController()
    auxiliaryControllers.add(requestController)
    return requestController
  }

  function finishAuxiliaryRequest(requestController: AbortController): void {
    auxiliaryControllers.delete(requestController)
  }

  function abortAuxiliaryRequests(): void {
    auxiliaryControllers.forEach(requestController => requestController.abort())
    auxiliaryControllers.clear()
  }

  function coordinatesCurrent(
    targetGeneration: number,
    scope: TeamObserverScope,
    sessionId: string,
    invocationId: string,
  ): boolean {
    return targetGeneration === generation
      && activeScope != null
      && scopeKey(activeScope) === scopeKey(scope)
      && state.session?.sessionId === sessionId
      && state.invocationId === invocationId
  }

  function fail(error: unknown, targetGeneration: number, fallback = 'Team Observer 暂时不可用'): false {
    if (isAbort(error) || targetGeneration !== generation) return false
    state.phase = 'error'
    state.errorStatus = error instanceof CrewScopeApiError ? error.status : null
    state.retryable = Boolean(state.invocationId && transportRetryable(error))
    // Provider or prompt contents never cross this stable presentation boundary.
    state.errorMessage = state.errorStatus === 403 ? '当前成员无权使用 Team Observer' : fallback
    return false
  }

  function reset(): void {
    controller?.abort()
    abortAuxiliaryRequests()
    generation += 1
    activeScope = null
    activeKey = null
    replace(initialState())
  }

  function busy(): boolean { return ['creating-session', 'connecting', 'running', 'reconnecting', 'cancelling'].includes(state.phase) }
  function replace(next: TeamObserverState): void { Object.assign(state, next) }
  return { state: readonly(state), activateScope, invoke, retry, cancel, refreshSummary, resolveEvidence, reset }
}

export function installTeamObserverStore(app: App, gateway: TeamObserverGateway): TeamObserverStore {
  const store = createTeamObserverStore(gateway)
  app.provide(TEAM_OBSERVER_STORE, store)
  return store
}

export function useTeamObserverStore(): TeamObserverStore {
  const store = inject(TEAM_OBSERVER_STORE)
  if (!store) throw new Error('Team Observer store is not installed')
  return store
}

function initialState(): TeamObserverState {
  return { phase: 'idle', session: null, invocationId: null, instruction: '', summary: null, lastSequence: -1, errorMessage: null, errorStatus: null, retryable: false }
}
function scopeKey(scope: TeamObserverScope): string { return `${scope.organizationId}:${scope.teamId}` }
function isAbort(error: unknown): boolean { return error instanceof DOMException && error.name === 'AbortError' }
function transportRetryable(error: unknown): boolean {
  return error instanceof CrewScopeApiError
    ? error.status === 0 || error.status >= 500
    : error instanceof DOMException && error.name !== 'AbortError'
}
function summaryEntries(summary: TeamSummary | null) {
  return summary ? [...summary.progress, ...summary.blockers, ...summary.reviewBacklog, ...summary.pendingConfirmations, ...summary.anomalies] : []
}
