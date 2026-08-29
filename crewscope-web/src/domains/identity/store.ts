import {
  inject,
  reactive,
  readonly,
  type App,
  type InjectionKey,
} from 'vue'
import { AUTH_PRINCIPAL, type AuthenticatedPrincipal } from '../../app/auth'
import { CrewScopeApiError } from '../../api/client'
import type { IdentityGateway } from './gateway'
import type { AuthSession } from './types'

export type AuthPhase = 'idle' | 'restoring' | 'anonymous' | 'authenticated' | 'error'
export type AuthTransitionReason = 'restored' | 'authentication-required' | 'cross-tab-sign-out' | 'explicit-sign-out'

interface AuthState {
  phase: AuthPhase
  session: AuthSession | null
  errorCode: 'network_unavailable' | 'session_timeout' | 'session_unavailable' | null
  errorMessage: string | null
}

export interface AuthBroadcastChannel {
  postMessage(message: AuthBroadcastMessage): void
  addEventListener(type: 'message', listener: (event: MessageEvent<AuthBroadcastMessage>) => void): void
  removeEventListener(type: 'message', listener: (event: MessageEvent<AuthBroadcastMessage>) => void): void
  close(): void
}

export interface AuthBroadcastMessage {
  type: 'signed-out'
}

export interface AuthStore {
  state: Readonly<AuthState>
  principal: AuthenticatedPrincipal
  start(): void
  stop(): void
  ensureRestored(): Promise<void>
  refresh(): Promise<boolean>
  retry(): Promise<void>
  authenticationRequired(): void
  signOutLocally(broadcast?: boolean): void
  subscribe(listener: (phase: AuthPhase, reason: AuthTransitionReason) => void): () => void
}

export interface AuthStoreOptions {
  channelFactory?: () => AuthBroadcastChannel | null
  sessionTimeoutMs?: number
}

export const AUTH_STORE: InjectionKey<AuthStore> = Symbol('crewscope-auth-store')

/** Session-backed identity state. Its stable Principal object keeps existing domain Stores reference-safe. */
export function createAuthStore(gateway: IdentityGateway, options: AuthStoreOptions = {}): AuthStore {
  const state = reactive<AuthState>({ phase: 'idle', session: null, errorCode: null, errorMessage: null })
  const principal = reactive<AuthenticatedPrincipal>({
    id: '',
    displayName: '',
    role: '',
    organizationId: '',
    organization: '',
    permissions: new Set<string>(),
  }) as AuthenticatedPrincipal
  const listeners = new Set<(phase: AuthPhase, reason: AuthTransitionReason) => void>()
  const sessionTimeoutMs = options.sessionTimeoutMs ?? 10_000
  const channelFactory = options.channelFactory ?? browserChannel
  let channel: AuthBroadcastChannel | null = null
  let started = false
  let generation = 0
  let pending: Promise<void> | null = null

  const receiveBroadcast = (event: MessageEvent<AuthBroadcastMessage>) => {
    if (event.data?.type === 'signed-out') clearSession('cross-tab-sign-out', false)
  }

  function start(): void {
    if (started) return
    started = true
    channel = channelFactory()
    channel?.addEventListener('message', receiveBroadcast)
    void ensureRestored()
  }

  function stop(): void {
    if (!started) return
    started = false
    generation += 1
    pending = null
    channel?.removeEventListener('message', receiveBroadcast)
    channel?.close()
    channel = null
  }

  async function ensureRestored(): Promise<void> {
    if (state.phase === 'authenticated' || state.phase === 'anonymous') return
    if (pending) return pending
    return restore(true)
  }

  async function refresh(): Promise<boolean> {
    await restore(false)
    return state.phase === 'authenticated'
  }

  async function retry(): Promise<void> {
    await restore(true)
  }

  async function restore(blocking: boolean): Promise<void> {
    if (pending) return pending
    const requestGeneration = ++generation
    if (blocking) state.phase = 'restoring'
    state.errorCode = null
    state.errorMessage = null
    const request = timedSession(gateway, sessionTimeoutMs)
      .then(session => {
        if (requestGeneration !== generation) return
        state.session = session
        if (session.authenticated) applyPrincipal(session)
        else clearPrincipal()
        state.phase = session.authenticated ? 'authenticated' : 'anonymous'
        notify(state.phase, 'restored')
      })
      .catch(error => {
        if (requestGeneration !== generation) return
        clearPrincipal()
        state.session = null
        state.phase = 'error'
        state.errorCode = error instanceof AuthSessionTimeoutError
          ? 'session_timeout'
          : error instanceof CrewScopeApiError && error.envelope.code === 'network_unavailable'
            ? 'network_unavailable'
            : 'session_unavailable'
        state.errorMessage = error instanceof AuthSessionTimeoutError
          ? '会话检查超时，请确认网络后重新检查。'
          : '会话服务暂时不可用，请稍后重新检查。'
      })
      .finally(() => {
        if (requestGeneration === generation) pending = null
      })
    pending = request
    return request
  }

  function authenticationRequired(): void {
    clearSession('authentication-required', true)
  }

  function signOutLocally(broadcast = true): void {
    clearSession('explicit-sign-out', broadcast)
  }

  function clearSession(reason: AuthTransitionReason, broadcast: boolean): void {
    generation += 1
    pending = null
    state.session = null
    state.phase = 'anonymous'
    state.errorCode = null
    state.errorMessage = null
    clearPrincipal()
    if (broadcast) channel?.postMessage({ type: 'signed-out' })
    notify('anonymous', reason)
    // Logout rotates or removes the server Session, so every tab must obtain a fresh anonymous
    // Session and CSRF coordinate before another login or registration command.
    void restore(true)
  }

  function subscribe(listener: (phase: AuthPhase, reason: AuthTransitionReason) => void): () => void {
    listeners.add(listener)
    return () => listeners.delete(listener)
  }

  function notify(phase: AuthPhase, reason: AuthTransitionReason): void {
    for (const listener of listeners) listener(phase, reason)
  }

  function applyPrincipal(session: AuthSession): void {
    if (!session.account || !session.principal) throw new TypeError('Authenticated Session has no Principal')
    principal.id = session.principal.principalId
    principal.displayName = session.account.displayName
    principal.role = session.account.platformRole === 'OPERATOR' ? 'Operator' : 'Team Member'
    principal.organizationId = session.principal.organizationId
    principal.organization = 'CrewScope Organization'
    principal.permissions = new Set(session.permissions)
  }

  function clearPrincipal(): void {
    principal.id = ''
    principal.displayName = ''
    principal.role = ''
    principal.organizationId = ''
    principal.organization = ''
    principal.permissions = new Set<string>()
  }

  return {
    state: readonly(state) as Readonly<AuthState>,
    principal,
    start,
    stop,
    ensureRestored,
    refresh,
    retry,
    authenticationRequired,
    signOutLocally,
    subscribe,
  }
}

export function installAuthStore(app: App, store: AuthStore): void {
  app.provide(AUTH_STORE, store)
  app.provide(AUTH_PRINCIPAL, store.principal)
}

export function useAuthStore(): AuthStore {
  const store = inject(AUTH_STORE)
  if (!store) throw new Error('CrewScope Auth Store is not installed')
  return store
}

class AuthSessionTimeoutError extends Error {}

async function timedSession(gateway: IdentityGateway, timeoutMs: number): Promise<AuthSession> {
  const controller = new AbortController()
  let timedOut = false
  const timeout = window.setTimeout(() => {
    timedOut = true
    controller.abort()
  }, timeoutMs)
  try {
    return await gateway.session(controller.signal)
  } catch (error) {
    if (timedOut) throw new AuthSessionTimeoutError()
    throw error
  } finally {
    window.clearTimeout(timeout)
  }
}

function browserChannel(): AuthBroadcastChannel | null {
  return typeof BroadcastChannel === 'undefined'
    ? null
    : new BroadcastChannel('crewscope-auth') as AuthBroadcastChannel
}
