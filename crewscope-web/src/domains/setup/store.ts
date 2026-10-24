import {getCurrentInstance, inject, reactive, readonly, type App, type InjectionKey} from 'vue'
import { registerStoreReset } from '../../app/storeLifecycle'
import { CrewScopeApiError } from '../../api/client'
import type { SettingsScope } from '../settings/types'
import { HttpSetupGateway, type SetupGateway } from './gateway'
import type { ConfigurationHealthView, ConfigurationSearchHit, SetupReadinessView } from './types'

export type SetupPhase = 'idle' | 'loading' | 'ready' | 'error' | 'offline'
/** A projection the active gateway does not implement at all. */
export type SetupUnavailablePhase = SetupPhase | 'unavailable'
/** Search additionally separates "not a Team member" from a transient failure. */
export type SetupSearchPhase = SetupUnavailablePhase | 'forbidden'
export interface SetupStoreState {
  phase: SetupPhase
  scope: SettingsScope | null
  readiness: SetupReadinessView | null
  errorMessage: string | null
  errorRetryable: boolean
  healthPhase: SetupUnavailablePhase
  health: ConfigurationHealthView | null
  healthErrorMessage: string | null
  searchPhase: SetupSearchPhase
  searchQuery: string
  searchResults: ConfigurationSearchHit[]
  searchErrorMessage: string | null
}
export interface SetupStore {
  state: Readonly<SetupStoreState>
  activateScope(scope: SettingsScope): void
  load(force?: boolean): Promise<void>
  loadHealth(force?: boolean): Promise<void>
  search(query: string): Promise<void>
  clearSearch(): void
  reset(): void
}
export const SETUP_STORE: InjectionKey<SetupStore> = Symbol('crewscope-setup-store')

/** Team-partitioned readiness projection; stale requests cannot overwrite a switched Team. */
export function createSetupStore(gateway: SetupGateway): SetupStore {
  const state = reactive<SetupStoreState>({
    phase: 'idle', scope: null, readiness: null, errorMessage: null, errorRetryable: false,
    healthPhase: 'idle', health: null, healthErrorMessage: null,
    searchPhase: 'idle', searchQuery: '', searchResults: [], searchErrorMessage: null,
  })
  let generation = 0
  let request: Promise<void> | null = null
  let healthRequest: Promise<void> | null = null
  let searchGeneration = 0
  let searchRequest: Promise<void> | null = null

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
    resetHealth()
    clearSearch()
  }

  function resetHealth(): void {
    state.healthPhase = 'idle'
    state.health = null
    state.healthErrorMessage = null
    healthRequest = null
  }

  /*
   * Search results are scoped to the query and the Team that asked for them: the counter below is
   * what makes a slow response for an abandoned query or a switched Team unable to land.
   */
  function clearSearch(): void {
    searchGeneration += 1
    searchRequest = null
    state.searchPhase = 'idle'
    state.searchQuery = ''
    state.searchResults = []
    state.searchErrorMessage = null
  }

  async function load(force = false): Promise<void> {
    const scope = state.scope
    if (!scope) return
    if (request && !force) return request
    const currentGeneration = generation
    // M13d-A15: readiness refreshes stale-while-ready — the caller only gets the loading
    // phase when there is nothing to show yet.
    if (state.phase !== 'ready') state.phase = 'loading'
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

  async function loadHealth(force = false): Promise<void> {
    const scope = state.scope
    if (!scope) return
    if (healthRequest && !force) return healthRequest
    const requestHealth = gateway.getConfigurationHealth
    if (!requestHealth) {
      state.healthPhase = 'unavailable'
      state.health = null
      return
    }
    const currentGeneration = generation
    state.healthPhase = 'loading'
    state.healthErrorMessage = null
    const pending = requestHealth.call(gateway, scope).then(value => {
      if (currentGeneration !== generation) return
      state.health = value
      state.healthPhase = 'ready'
    }).catch(error => {
      if (currentGeneration !== generation) return
      state.health = null
      state.healthPhase = error instanceof CrewScopeApiError && error.status === 0 ? 'offline' : 'error'
      state.healthErrorMessage = error instanceof CrewScopeApiError
        ? error.envelope.message : '配置健康暂时不可用，请稍后重试。'
    }).finally(() => { if (healthRequest === pending) healthRequest = null })
    healthRequest = pending
    return pending
  }

  async function search(query: string): Promise<void> {
    const scope = state.scope
    const term = query.trim()
    searchGeneration += 1
    const currentSearch = searchGeneration
    state.searchQuery = term
    state.searchErrorMessage = null
    if (!scope || !term) {
      state.searchPhase = 'idle'
      state.searchResults = []
      return
    }
    const find = gateway.searchConfiguration
    /*
     * The deployment without this projection reports that fact instead of an empty result list; an
     * empty list would read as "no field matched", which is a different statement.
     */
    if (!find) {
      state.searchPhase = 'unavailable'
      state.searchResults = []
      return
    }
    state.searchPhase = 'loading'
    state.searchResults = []
    const pending = find.call(gateway, scope, term).then(hits => {
      if (currentSearch !== searchGeneration) return
      state.searchResults = [...hits]
      state.searchPhase = 'ready'
    }).catch(error => {
      if (currentSearch !== searchGeneration) return
      state.searchResults = []
      const status = error instanceof CrewScopeApiError ? error.status : null
      state.searchPhase = status === 0 ? 'offline' : status === 403 ? 'forbidden' : 'error'
      state.searchErrorMessage = error instanceof CrewScopeApiError
        ? error.envelope.message : '配置搜索暂时不可用，请稍后重试。'
    })
    return pending
  }

  function reset(): void {
    generation += 1
    request = null
    state.phase = 'idle'; state.scope = null; state.readiness = null; state.errorMessage = null; state.errorRetryable = false
    resetHealth()
    clearSearch()
  }

  return {
    state: readonly(state) as Readonly<SetupStoreState>,
    activateScope, load, loadHealth, search, clearSearch, reset,
  }
}

// M13d-P01: lazily installed on the pages that consume this domain (keeps it out of the eager
// main chunk). The instance lives on the app, so revisiting a page reuses cached state; spec
// fixtures provided through app-level provide always win and are never shadowed.
export function ensureSetupStore(app: App): SetupStore {
  const existing = app.runWithContext(() => inject(SETUP_STORE, null))
  if (existing) return existing
  const store = createSetupStore(new HttpSetupGateway())
  app.provide(SETUP_STORE, store)
  registerStoreReset(app, () => store.reset())
  return store
}

export function useSetupStore(): SetupStore {
  const installed = inject(SETUP_STORE, null)
  if (installed) return installed
  // M13d-P01: fall back to lazy installation — a component declares its domain dependency
  // by calling use*, so no host page has to pre-install the store.
  const instance = getCurrentInstance()
  if (!instance) throw new Error('CrewScope Setup Store is not installed')
  return ensureSetupStore(instance.appContext.app)
}
