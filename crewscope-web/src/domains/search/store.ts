import { registerStoreReset } from '../../app/storeLifecycle'
import {getCurrentInstance, inject, reactive, readonly, type App, type InjectionKey} from 'vue'
import { CrewScopeApiError } from '../../api/client'
import { HttpSearchGateway, type SearchGateway } from './gateway'
import type { SearchFilter, SearchResultPage, SearchScope } from './types'
export type SearchPhase = 'idle' | 'loading' | 'ready' | 'empty' | 'error' | 'offline'
export interface SearchStoreState { phase: SearchPhase; scope: SearchScope | null; result: SearchResultPage | null; errorMessage: string | null }
export interface SearchStore { state: Readonly<SearchStoreState>; activateScope(scope: SearchScope): void; search(filter: SearchFilter): Promise<void>; reset(): void }
export const SEARCH_STORE: InjectionKey<SearchStore> = Symbol('crewscope-search-store')
/** R20: the palette owns its own session; opening or clearing it never touches the search page's. */
export const PALETTE_SEARCH_STORE: InjectionKey<SearchStore> = Symbol('crewscope-palette-search-store')
/** Scope-isolated search state; a response from an old Team cannot replace current results. */
export function createSearchStore(gateway: SearchGateway): SearchStore {
  const state = reactive<SearchStoreState>({ phase: 'idle', scope: null, result: null, errorMessage: null }); let generation = 0; let request: Promise<void> | null = null; let activeQueryKey = ''
  function activateScope(scope: SearchScope): void { const key = `${scope.organizationId}:${scope.teamId}`; const current = state.scope && `${state.scope.organizationId}:${state.scope.teamId}`; if (key === current) return; generation += 1; request = null; activeQueryKey = ''; state.scope = { ...scope }; state.result = null; state.errorMessage = null; state.phase = 'idle' }
  async function search(filter: SearchFilter): Promise<void> {
    const scope = state.scope; const text = filter.text.trim()
    if (!scope || !text) { generation += 1; request = null; activeQueryKey = ''; state.phase = 'idle'; state.result = null; state.errorMessage = null; return }
    const currentGeneration = generation; const append = Boolean(filter.after)
    const queryKey = JSON.stringify({ text, projectId: filter.projectId ?? null, types: filter.types ?? null })
    if (append && queryKey !== activeQueryKey) return
    if (!append) activeQueryKey = queryKey
    // Keep the current result visible while a cursor page is loading.
    if (!append) state.phase = 'loading'
    state.errorMessage = null
    const pending = gateway.search(scope, filter).then(result => {
      if (currentGeneration !== generation || activeQueryKey !== queryKey || request !== pending) return
      if (append && state.result && activeQueryKey === queryKey) {
        // Cursor pages are appended only when they belong to the same active query.
        state.result = { items: [...state.result.items, ...result.items], nextCursor: result.nextCursor }
      } else state.result = result
      state.phase = state.result.items.length ? 'ready' : 'empty'
    }).catch(error => {
      if (currentGeneration !== generation || activeQueryKey !== queryKey || request !== pending) return
      state.phase = error instanceof CrewScopeApiError && error.status === 0 ? 'offline' : 'error'
      state.errorMessage = error instanceof CrewScopeApiError ? error.envelope.message : '搜索服务暂时不可用，请稍后重试。'
    }).finally(() => { if (request === pending) request = null })
    request = pending; return pending
  }
  function reset(): void { generation += 1; request = null; activeQueryKey = ''; state.scope = null; state.result = null; state.errorMessage = null; state.phase = 'idle' }
  return { state: readonly(state) as Readonly<SearchStoreState>, activateScope, search, reset }
}
// M13d-P01: lazily installed on the pages that consume this domain (keeps it out of the eager
// main chunk). The instance lives on the app, so revisiting a page reuses cached state; spec
// fixtures provided through app-level provide always win and are never shadowed.
export function ensureSearchStore(app: App): SearchStore {
  const existing = app.runWithContext(() => inject(SEARCH_STORE, null))
  if (existing) return existing
  const store = createSearchStore(new HttpSearchGateway())
  app.provide(SEARCH_STORE, store)
  registerStoreReset(app, () => store.reset())
  return store
}
export function installPaletteSearchStore(app: App, gateway: SearchGateway): SearchStore {
  const store = createSearchStore(gateway)
  app.provide(PALETTE_SEARCH_STORE, store)
  return store
}
export function useSearchStore(): SearchStore {
  const installed = inject(SEARCH_STORE, null)
  if (installed) return installed
  // M13d-P01: fall back to lazy installation — a component declares its domain dependency
  // by calling use*, so no host page has to pre-install the store.
  const instance = getCurrentInstance()
  if (!instance) throw new Error('CrewScope Search Store is not installed')
  return ensureSearchStore(instance.appContext.app)
}
