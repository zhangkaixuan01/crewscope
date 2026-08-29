import { inject, reactive, readonly, type App, type InjectionKey } from 'vue'
import type { AuthCsrfCoordinate } from '../identity/types'
import type { AccountGateway } from './gateway'
import { AccountRequestTimeoutError, presentAccountProblem, type AccountProblem } from './presentation'
import type {
  AccountPasswordChangeInput,
  AccountProfile,
  AccountProfileUpdateInput,
  AccountSessionRevocationInput,
} from './types'

export type AccountPhase = 'idle' | 'loading' | 'ready' | 'error'
export type AccountOperation = 'profile' | 'password' | 'sessions'

export interface AccountStoreState {
  phase: AccountPhase
  profile: AccountProfile | null
  etag: number | null
  problem: AccountProblem | null
  commandPhase: 'idle' | 'pending' | 'success' | 'error'
  operation: AccountOperation | null
  commandProblem: AccountProblem | null
  commandGeneration: number
}

export interface AccountStore {
  state: Readonly<AccountStoreState>
  load(force?: boolean): Promise<boolean>
  updateProfile(input: AccountProfileUpdateInput, csrf: AuthCsrfCoordinate): Promise<boolean>
  changePassword(input: AccountPasswordChangeInput, csrf: AuthCsrfCoordinate): Promise<boolean>
  revokeAllSessions(input: AccountSessionRevocationInput, csrf: AuthCsrfCoordinate): Promise<boolean>
  clearCommand(): void
  reset(): void
}

export interface AccountStoreOptions {
  timeoutMs?: number
}

export const ACCOUNT_STORE: InjectionKey<AccountStore> = Symbol('crewscope-account-store')

/** Keeps account projections reactive while passwords remain one-way method arguments. */
export function createAccountStore(gateway: AccountGateway, options: AccountStoreOptions = {}): AccountStore {
  const state = reactive<AccountStoreState>({
    phase: 'idle', profile: null, etag: null, problem: null,
    commandPhase: 'idle', operation: null, commandProblem: null, commandGeneration: 0,
  })
  const timeoutMs = options.timeoutMs ?? 15_000
  let generation = 0
  let controller: AbortController | null = null

  async function load(force = false): Promise<boolean> {
    if (!force && state.phase === 'ready' && state.profile) return true
    const requestGeneration = begin()
    state.phase = 'loading'
    state.problem = null
    try {
      const response = await timed(signal => gateway.current(signal))
      if (requestGeneration !== generation) return false
      state.profile = response.value
      state.etag = response.etag
      state.phase = 'ready'
      return true
    } catch (error) {
      if (requestGeneration !== generation || isAbort(error)) return false
      state.problem = presentAccountProblem(error)
      state.phase = 'error'
      return false
    } finally {
      finish(requestGeneration)
    }
  }

  async function updateProfile(input: AccountProfileUpdateInput, csrf: AuthCsrfCoordinate): Promise<boolean> {
    return command('profile', (expectedVersion, signal) => gateway.updateProfile(
      input, { csrf, expectedVersion }, signal,
    ), response => {
      state.profile = response.value
      state.etag = response.etag
      state.phase = 'ready'
    })
  }

  async function changePassword(input: AccountPasswordChangeInput, csrf: AuthCsrfCoordinate): Promise<boolean> {
    return command('password', (expectedVersion, signal) => gateway.changePassword(
      input, { csrf, expectedVersion }, signal,
    ))
  }

  async function revokeAllSessions(input: AccountSessionRevocationInput, csrf: AuthCsrfCoordinate): Promise<boolean> {
    return command('sessions', (expectedVersion, signal) => gateway.revokeAllSessions(
      input, { csrf, expectedVersion }, signal,
    ))
  }

  async function command<T>(
    operation: AccountOperation,
    action: (expectedVersion: number, signal: AbortSignal) => Promise<T>,
    apply?: (value: T) => void,
  ): Promise<boolean> {
    if (state.etag === null) return false
    const expectedVersion = state.etag
    const requestGeneration = begin()
    state.commandPhase = 'pending'
    state.operation = operation
    state.commandProblem = null
    try {
      const result = await timed(signal => action(expectedVersion, signal))
      if (requestGeneration !== generation) return false
      apply?.(result)
      state.commandPhase = 'success'
      state.commandGeneration += 1
      return true
    } catch (error) {
      if (requestGeneration !== generation || isAbort(error)) return false
      state.commandProblem = presentAccountProblem(error)
      state.commandPhase = 'error'
      state.commandGeneration += 1
      return false
    } finally {
      finish(requestGeneration)
    }
  }

  function begin(): number {
    generation += 1
    controller?.abort()
    controller = new AbortController()
    return generation
  }

  async function timed<T>(operation: (signal: AbortSignal) => Promise<T>): Promise<T> {
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
      if (timedOut) throw new AccountRequestTimeoutError()
      throw error
    } finally {
      window.clearTimeout(timeout)
    }
  }

  function finish(requestGeneration: number): void {
    if (requestGeneration === generation) controller = null
  }

  function clearCommand(): void {
    state.commandPhase = 'idle'
    state.operation = null
    state.commandProblem = null
  }

  function reset(): void {
    generation += 1
    controller?.abort()
    controller = null
    state.phase = 'idle'
    state.profile = null
    state.etag = null
    state.problem = null
    state.commandGeneration = 0
    clearCommand()
  }

  return {
    state: readonly(state) as Readonly<AccountStoreState>,
    load, updateProfile, changePassword, revokeAllSessions, clearCommand, reset,
  }
}

export function installAccountStore(app: App, store: AccountStore): AccountStore {
  app.provide(ACCOUNT_STORE, store)
  return store
}

export function useAccountStore(): AccountStore {
  const store = inject(ACCOUNT_STORE)
  if (!store) throw new Error('CrewScope Account Store is not installed')
  return store
}

function isAbort(error: unknown): boolean {
  return error instanceof DOMException && error.name === 'AbortError'
}
