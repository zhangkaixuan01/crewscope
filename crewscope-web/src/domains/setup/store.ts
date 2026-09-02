import { inject, reactive, readonly, type App, type InjectionKey } from 'vue'
import { CrewScopeApiError } from '../../api/client'
import type { SettingsScope } from '../settings/types'
import type { SetupGateway } from './gateway'
import type { SetupReadinessView } from './types'

export type SetupPhase = 'idle' | 'loading' | 'ready' | 'error' | 'offline'
export interface SetupStoreState {
  phase: SetupPhase
  scope: SettingsScope | null
  readiness: SetupReadinessView | null
  errorMessage: string | null
  errorRetryable: boolean
}
export interface SetupStore {
  state: Readonly<SetupStoreState>
  activateScope(scope: SettingsScope): void
  load(force?: boolean): Promise<void>
  reset(): void
}
export const SETUP_STORE: InjectionKey<SetupStore> = Symbol('crewscope-setup-store')

/** Team-partitioned readiness projection; stale requests cannot overwrite a switched Team. */
export function createSetupStore(gateway: SetupGateway): SetupStore {
  const state = reactive<SetupStoreState>({ phase: 'idle', scope: null, readiness: null, errorMessage: null, errorRetryable: false })
  let generation = 0
  let request: Promise<void> | null = null

  function activateScope(scope: SettingsScope): void {
    const key = `${scope.organizationId}:${scope.teamId}`
    const current = state.scope && `${state.scope.organizationId}:${state.scope.teamId}`
    if (key === current) return
    generation += 1
    state.scope = { ...scope }
    state.readiness = null
    state.errorMessage = null
    state.errorRetryable = false
    state.phase = 'idle'
    request = null
  }

  async function load(force = false): Promise<void> {
    const scope = state.scope
    if (!scope) return
    if (request && !force) return request
    const currentGeneration = generation
    state.phase = 'loading'
    state.errorMessage = null
    const pending = gateway.getReadiness(scope).then(value => {
      if (currentGeneration !== generation) return
      state.readiness = value
      state.phase = 'ready'
    }).catch(error => {
      if (currentGeneration !== generation) return
      state.phase = error instanceof CrewScopeApiError && error.status === 0 ? 'offline' : 'error'
      state.errorMessage = error instanceof CrewScopeApiError ? error.envelope.message : 'Setup Center 暂时不可用，请稍后重试。'
      state.errorRetryable = !(error instanceof CrewScopeApiError) || error.envelope.retryable || error.status === 0
    }).finally(() => { if (request === pending) request = null })
    request = pending
    return pending
  }

  function reset(): void {
    generation += 1
    request = null
    state.phase = 'idle'; state.scope = null; state.readiness = null; state.errorMessage = null; state.errorRetryable = false
  }

  return { state: readonly(state) as Readonly<SetupStoreState>, activateScope, load, reset }
}

export function installSetupStore(app: App, gateway: SetupGateway): SetupStore {
  const store = createSetupStore(gateway)
  app.provide(SETUP_STORE, store)
  return store
}
export function useSetupStore(): SetupStore { const store = inject(SETUP_STORE); if (!store) throw new Error('CrewScope Setup Store is not installed'); return store }
