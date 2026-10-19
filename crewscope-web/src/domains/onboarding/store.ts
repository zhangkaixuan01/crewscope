import { secureId } from '../../api/secureId'
import { inject, reactive, readonly, type App, type InjectionKey } from 'vue'
import { CrewScopeApiError } from '../../api/client'
import type { AuthCsrfCoordinate } from '../identity/types'
import type { OnboardingGateway } from './gateway'
import {
  OnboardingRequestTimeoutError,
  onboardingNotConvergedProblem,
  presentOnboardingProblem,
  type OnboardingProblem,
} from './presentation'
import type { OnboardingReceipt, OnboardingStatus } from './types'
import { isAbort } from '../../api/errors'
import { createRequestScope } from '../../api/requestScope'

export type OnboardingPhase = 'idle' | 'loading' | 'required' | 'submitting' | 'verifying' | 'complete' | 'error'

interface OnboardingStoreState {
  phase: OnboardingPhase
  status: OnboardingStatus | null
  receipt: OnboardingReceipt | null
  problem: OnboardingProblem | null
  errorGeneration: number
}

export interface OnboardingStore {
  state: Readonly<OnboardingStoreState>
  load(): Promise<boolean>
  createFirstTeam(name: string, csrf: AuthCsrfCoordinate): Promise<boolean>
  retry(csrf: AuthCsrfCoordinate): Promise<boolean>
  reset(): void
}

export interface OnboardingStoreOptions {
  statusTimeoutMs?: number
  createTimeoutMs?: number
}

export const ONBOARDING_STORE: InjectionKey<OnboardingStore> = Symbol('crewscope-onboarding-store')

/** Keeps idempotency and uncertain command recovery outside reactive browser state. */
export function createOnboardingStore(
  gateway: OnboardingGateway,
  options: OnboardingStoreOptions = {},
): OnboardingStore {
  const state = reactive<OnboardingStoreState>({
    phase: 'idle',
    status: null,
    receipt: null,
    problem: null,
    errorGeneration: 0,
  })
  const statusTimeoutMs = options.statusTimeoutMs ?? 10_000
  const createTimeoutMs = options.createTimeoutMs ?? 20_000
  // One channel (M12b-F05): every phase entry supersedes the previous logical operation —
  // the generation counter + controller pair this replaces.
  const requests = createRequestScope(() => 'onboarding')
  let retryKey: string | null = null
  let retryName: string | null = null
  const createKeys = new Map<string, string>()
  const uncertainCreates = new Set<string>()

  async function load(): Promise<boolean> {
    // A new route visit derives completion from current server facts, not a previous page receipt.
    state.receipt = null
    clearRetry()
    const request = begin('loading')
    try {
      const status = await timed(signal => gateway.status(signal), request.signal, statusTimeoutMs)
      if (!request.isCurrent()) return false
      applyStatus(status)
      return status.state === 'COMPLETE'
    } catch (error) {
      if (!request.isCurrent() || isAbort(error)) return false
      fail(presentOnboardingProblem(error))
      return false
    } finally {
      request.finish()
    }
  }

  async function createFirstTeam(name: string, csrf: AuthCsrfCoordinate): Promise<boolean> {
    if (state.phase === 'submitting' || state.phase === 'verifying') return false
    const normalizedName = name.trim()
    if (!createKeys.has(normalizedName) && createKeys.size >= 100) {
      fail(presentOnboardingProblem(new Error('待确认操作过多，请先核实原团队创建结果。')))
      return false
    }
    if (retryName !== normalizedName || !retryKey) {
      retryName = normalizedName
      retryKey = createKeys.get(normalizedName) ?? secureId()
      createKeys.set(normalizedName, retryKey)
    }
    const request = begin('submitting')
    try {
      const receipt = await timed(
        signal => gateway.createFirstTeam({
          name: normalizedName,
          csrf,
          idempotencyKey: retryKey!,
        }, signal),
        request.signal,
        createTimeoutMs,
      )
      if (!request.isCurrent()) return false
      state.receipt = receipt
      state.phase = 'verifying'
      return await verify(request)
    } catch (error) {
      if (!request.isCurrent() || isAbort(error)) return false
      if (error instanceof CrewScopeApiError && error.envelope.code === 'onboarding_already_complete') {
        state.phase = 'verifying'
        return await verify(request)
      }
      if (keepsIdempotencyKey(error)) uncertainCreates.add(normalizedName)
      else if (!uncertainCreates.has(normalizedName)) {
        createKeys.delete(normalizedName)
        clearRetry()
      }
      fail(presentOnboardingProblem(error))
      return false
    } finally {
      request.finish()
    }
  }

  async function retry(csrf: AuthCsrfCoordinate): Promise<boolean> {
    if (state.receipt) {
      const request = begin('verifying')
      try {
        return await verify(request)
      } finally {
        request.finish()
      }
    }
    if (retryName && retryKey) return createFirstTeam(retryName, csrf)
    return load()
  }

  async function verify(request: { isCurrent(): boolean, signal: AbortSignal }): Promise<boolean> {
    try {
      const status = await timed(signal => gateway.status(signal), request.signal, statusTimeoutMs)
      if (!request.isCurrent()) return false
      state.status = status
      if (status.state === 'COMPLETE') {
        state.phase = 'complete'
        state.problem = null
        clearRetry()
        return true
      }
      fail(onboardingNotConvergedProblem())
      return false
    } catch (error) {
      if (!request.isCurrent() || isAbort(error)) return false
      fail(presentOnboardingProblem(error))
      return false
    }
  }

  function begin(phase: OnboardingPhase) {
    const request = requests.begin('onboarding')
    state.phase = phase
    state.problem = null
    return request
  }

  /** Times an operation out, distinguishing timeout from upstream cancellation. */
  async function timed<T>(
    operation: (signal: AbortSignal) => Promise<T>,
    upstream: AbortSignal,
    timeoutMs: number,
  ): Promise<T> {
    // One request signal spans the whole logical operation. Linking it to the timeout prevents
    // an abandoned controller from surviving a retry.
    const linked = new AbortController()
    let timedOut = false
    const timeout = window.setTimeout(() => { timedOut = true; linked.abort() }, timeoutMs)
    const linkUpstream = () => linked.abort()
    if (upstream.aborted) linked.abort()
    else upstream.addEventListener('abort', linkUpstream, { once: true })
    try {
      return await operation(linked.signal)
    } catch (error) {
      if (timedOut) throw new OnboardingRequestTimeoutError()
      throw error
    } finally {
      window.clearTimeout(timeout)
      upstream.removeEventListener('abort', linkUpstream)
    }
  }

  function applyStatus(status: OnboardingStatus): void {
    state.status = status
    state.problem = null
    state.phase = status.state === 'COMPLETE' ? 'complete' : 'required'
  }

  function fail(problem: OnboardingProblem): void {
    state.problem = problem
    state.phase = 'error'
    state.errorGeneration += 1
  }

  function clearRetry(): void {
    retryKey = null
    retryName = null
  }

  function reset(): void {
    createKeys.clear()
    uncertainCreates.clear()
    requests.invalidate()
    clearRetry()
    state.phase = 'idle'
    state.status = null
    state.receipt = null
    state.problem = null
    state.errorGeneration = 0
  }

  return { state: readonly(state) as Readonly<OnboardingStoreState>, load, createFirstTeam, retry, reset }
}

export function installOnboardingStore(app: App, store: OnboardingStore): void {
  app.provide(ONBOARDING_STORE, store)
}

export function useOnboardingStore(): OnboardingStore {
  const store = inject(ONBOARDING_STORE)
  if (!store) throw new Error('CrewScope Onboarding Store is not installed')
  return store
}

function keepsIdempotencyKey(error: unknown): boolean {
  if (error instanceof OnboardingRequestTimeoutError) return true
  return error instanceof CrewScopeApiError
    && ['network_unavailable', 'onboarding_unavailable', 'csrf_rejected'].includes(error.envelope.code)
}

