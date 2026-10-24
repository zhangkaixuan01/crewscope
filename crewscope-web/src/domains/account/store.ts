import {getCurrentInstance, inject, reactive, readonly, type App, type InjectionKey} from 'vue'
import { registerStoreReset } from '../../app/storeLifecycle'
import type { AuthCsrfCoordinate } from '../identity/types'
import { HttpAccountGateway, type AccountGateway } from './gateway'
import { AccountRequestTimeoutError, presentAccountProblem, type AccountProblem } from './presentation'
import type {
  AccountPasswordChangeInput,
  AccountProfile,
  AccountProfileUpdateInput,
  AccountSessionRevocationInput,
} from './types'
import { isAbort } from '../../api/errors'
import { createRequestScope } from '../../api/requestScope'

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
  // One channel per operation family (M12b-F05): loads and commands supersede their own
  // kind; the shared timed() wrapper links the upstream request signal to its timeout.
  const requests = createRequestScope(() => 'account')

  async function load(force = false): Promise<boolean> {
    if (!force && state.phase === 'ready' && state.profile) return true
    const request = requests.begin('load')
    state.phase = 'loading'
    state.problem = null
    try {
      const response = await timed(signal => gateway.current(signal), request.signal)
      if (!request.isCurrent()) return false
      state.profile = response.value
      state.etag = response.etag
      state.phase = 'ready'
      return true
    } catch (error) {
      if (!request.isCurrent() || isAbort(error)) return false
      state.problem = presentAccountProblem(error)
      state.phase = 'error'
      return false
    } finally {
      request.finish()
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
    const request = requests.begin('command')
    state.commandPhase = 'pending'
    state.operation = operation
    state.commandProblem = null
    try {
      const result = await timed(signal => action(expectedVersion, signal), request.signal)
      if (!request.isCurrent()) return false
      apply?.(result)
      state.commandPhase = 'success'
      state.commandGeneration += 1
      return true
    } catch (error) {
      if (!request.isCurrent() || isAbort(error)) return false
      state.commandProblem = presentAccountProblem(error)
      state.commandPhase = 'error'
      state.commandGeneration += 1
      return false
    } finally {
      request.finish()
    }
  }

  /** Times an operation out, distinguishing timeout from upstream cancellation. */
  async function timed<T>(operation: (signal: AbortSignal) => Promise<T>, upstream: AbortSignal): Promise<T> {
    const linked = new AbortController()
    let timedOut = false
    const timeout = window.setTimeout(() => { timedOut = true; linked.abort() }, timeoutMs)
    const linkUpstream = () => linked.abort()
    if (upstream.aborted) linked.abort()
    else upstream.addEventListener('abort', linkUpstream, { once: true })
    try {
      return await operation(linked.signal)
    } catch (error) {
      if (timedOut) throw new AccountRequestTimeoutError()
      throw error
    } finally {
      window.clearTimeout(timeout)
      upstream.removeEventListener('abort', linkUpstream)
    }
  }

  function clearCommand(): void {
    state.commandPhase = 'idle'
    state.operation = null
    state.commandProblem = null
  }

  function reset(): void {
    requests.invalidate()
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

// M13d-P01: lazily installed on the pages that consume this domain (keeps it out of the eager
// main chunk). The instance lives on the app, so revisiting a page reuses cached state; spec
// fixtures provided through app-level provide always win and are never shadowed.
export function ensureAccountStore(app: App): AccountStore {
  const existing = app.runWithContext(() => inject(ACCOUNT_STORE, null))
  if (existing) return existing
  const store = createAccountStore(new HttpAccountGateway())
  app.provide(ACCOUNT_STORE, store)
  registerStoreReset(app, () => store.reset())
  return store
}


export function useAccountStore(): AccountStore {
  const installed = inject(ACCOUNT_STORE, null)
  if (installed) return installed
  // M13d-P01: fall back to lazy installation — a component declares its domain dependency
  // by calling use*, so no host page has to pre-install the store.
  const instance = getCurrentInstance()
  if (!instance) throw new Error('CrewScope Account Store is not installed')
  return ensureAccountStore(instance.appContext.app)
}
