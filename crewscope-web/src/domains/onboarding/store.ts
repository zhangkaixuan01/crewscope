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
  let generation = 0
  let controller: AbortController | null = null
  let retryKey: string | null = null
  let retryName: string | null = null

  async function load(): Promise<boolean> {
    // A new route visit derives completion from current server facts, not a previous page receipt.
    state.receipt = null
    clearRetry()
    const requestGeneration = begin('loading')
    try {
      const status = await timed(signal => gateway.status(signal), statusTimeoutMs)
      if (requestGeneration !== generation) return false
      applyStatus(status)
      return status.state === 'COMPLETE'
    } catch (error) {
      if (requestGeneration !== generation || isAbort(error)) return false
      fail(presentOnboardingProblem(error))
      return false
    } finally {
      finish(requestGeneration)
    }
  }

  async function createFirstTeam(name: string, csrf: AuthCsrfCoordinate): Promise<boolean> {
    const normalizedName = name.trim()
    if (retryName !== normalizedName || !retryKey) {
      retryName = normalizedName
      retryKey = crypto.randomUUID()
    }
    const requestGeneration = begin('submitting')
    try {
      const receipt = await timed(
        signal => gateway.createFirstTeam({
          name: normalizedName,
          csrf,
          idempotencyKey: retryKey!,
        }, signal),
        createTimeoutMs,
      )
      if (requestGeneration !== generation) return false
      state.receipt = receipt
      state.phase = 'verifying'
      return await verify(requestGeneration)
    } catch (error) {
      if (requestGeneration !== generation || isAbort(error)) return false
      if (error instanceof CrewScopeApiError && error.envelope.code === 'onboarding_already_complete') {
        state.phase = 'verifying'
        return await verify(requestGeneration)
      }
      if (!keepsIdempotencyKey(error)) clearRetry()
      fail(presentOnboardingProblem(error))
      return false
    } finally {
      finish(requestGeneration)
    }
  }

  async function retry(csrf: AuthCsrfCoordinate): Promise<boolean> {
    if (state.receipt) {
      const requestGeneration = begin('verifying')
      try {
        return await verify(requestGeneration)
      } finally {
        finish(requestGeneration)
      }
    }
    if (retryName && retryKey) return createFirstTeam(retryName, csrf)
    return load()
  }

  async function verify(requestGeneration: number): Promise<boolean> {
    try {
      const status = await timed(signal => gateway.status(signal), statusTimeoutMs)
      if (requestGeneration !== generation) return false
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
      if (requestGeneration !== generation || isAbort(error)) return false
      fail(presentOnboardingProblem(error))
      return false
    }
  }

  function begin(phase: OnboardingPhase): number {
    generation += 1
    controller?.abort()
    controller = new AbortController()
    state.phase = phase
    state.problem = null
    return generation
  }

  function finish(requestGeneration: number): void {
    if (requestGeneration === generation) controller = null
  }

  async function timed<T>(
    operation: (signal: AbortSignal) => Promise<T>,
    timeoutMs: number,
  ): Promise<T> {
    // begin() owns one controller for the whole logical operation. Reusing it across
    // command and verification prevents an abandoned controller from surviving a retry.
    const activeController = controller ?? new AbortController()
    controller = activeController
    let timedOut = false
    const timeout = window.setTimeout(() => {
      timedOut = true
      activeController.abort()
    }, timeoutMs)
    try {
      return await operation(activeController.signal)
    } catch (error) {
      if (timedOut) throw new OnboardingRequestTimeoutError()
      throw error
    } finally {
      window.clearTimeout(timeout)
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
    generation += 1
    controller?.abort()
    controller = null
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

function isAbort(error: unknown): boolean {
  return error instanceof DOMException && error.name === 'AbortError'
}
