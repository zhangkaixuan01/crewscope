import { inject, reactive, readonly, type App, type InjectionKey } from 'vue'
import { CrewScopeApiError } from '../../api/client'
import type { WorkDeskFilter, WorkDeskScope, WorkDeskSummary } from './types'
import type { WorkDeskGateway } from './gateway'

export type WorkDeskPhase = 'idle' | 'loading' | 'ready' | 'empty' | 'error' | 'offline'
export interface WorkDeskStoreState {
  phase: WorkDeskPhase
  scope: WorkDeskScope | null
  summary: WorkDeskSummary | null
  errorMessage: string | null
  /** The section whose continuation page is in flight, if any; one at a time by design. */
  sectionLoadingKey: string | null
  /** Why the last continuation failed, so a section can explain itself without tearing down the page. */
  sectionErrorMessage: string | null
}
export interface WorkDeskStore {
  state: Readonly<WorkDeskStoreState>
  activateScope(scope: WorkDeskScope): void
  load(filter?: WorkDeskFilter, force?: boolean): Promise<void>
  /**
   * Continues one section from its cursor through the per-section endpoint, merging rows by id.
   *
   * Capability for the WorkDesk surfaces to adopt; no surface calls it yet. A failure here is a
   * section-level fact: the already-loaded page stays as it is, and the error names the section.
   */
  loadMore(sectionKey: string): Promise<void>
  reset(): void
}
export const WORKDESK_STORE: InjectionKey<WorkDeskStore> = Symbol('crewscope-workdesk-store')

/** First-screen page size per section, sent explicitly so the server default never silently changes it. */
const SECTION_PAGE_SIZE = 50

/** Scope-isolated WorkDesk read model; stale responses are ignored after a Team switch. */
export function createWorkDeskStore(gateway: WorkDeskGateway): WorkDeskStore {
  const state = reactive<WorkDeskStoreState>({ phase: 'idle', scope: null, summary: null, errorMessage: null, sectionLoadingKey: null, sectionErrorMessage: null })
  let generation = 0
  let request: Promise<void> | null = null
  let filterKey = ''
  let activeFilter: WorkDeskFilter = {}
  function activateScope(scope: WorkDeskScope): void {
    const key = `${scope.organizationId}:${scope.teamId}`
    const current = state.scope && `${state.scope.organizationId}:${state.scope.teamId}`
    if (key === current) return
    generation += 1; request = null; filterKey = ''; activeFilter = {}
    state.scope = { ...scope }; state.summary = null; state.errorMessage = null
    state.sectionLoadingKey = null; state.sectionErrorMessage = null; state.phase = 'idle'
  }
  async function load(filter: WorkDeskFilter = {}, force = false): Promise<void> {
    const scope = state.scope
    if (!scope) return
    const key = JSON.stringify(filter)
    if (request && !force && key === filterKey) return request
    filterKey = key
    activeFilter = { ...filter }
    const currentGeneration = generation
    state.phase = 'loading'; state.errorMessage = null
    state.sectionLoadingKey = null; state.sectionErrorMessage = null
    const pending = gateway.get(scope, filter, SECTION_PAGE_SIZE).then(summary => {
      if (currentGeneration !== generation) return
      state.summary = summary; state.phase = summary.sections.some(section => section.items.length > 0) ? 'ready' : 'empty'
    }).catch(error => {
      if (currentGeneration !== generation) return
      state.phase = error instanceof CrewScopeApiError && error.status === 0 ? 'offline' : 'error'
      state.errorMessage = error instanceof CrewScopeApiError ? error.envelope.message : '个人工作台暂时不可用，请稍后重试。'
    }).finally(() => { if (request === pending) request = null })
    request = pending
    return pending
  }
  async function loadMore(sectionKey: string): Promise<void> {
    const scope = state.scope
    const summary = state.summary
    if (!scope || !summary || state.sectionLoadingKey) return
    const section = summary.sections.find(candidate => candidate.key === sectionKey)
    if (!section || !section.nextCursor) return
    const currentGeneration = generation
    const filter = { ...activeFilter }
    state.sectionLoadingKey = sectionKey
    state.sectionErrorMessage = null
    try {
      const page = await gateway.getSection(scope, sectionKey, filter, section.nextCursor, SECTION_PAGE_SIZE)
      if (currentGeneration !== generation) return
      // Rows merge by objectId: a row the reload in between already brought back must not appear twice.
      const known = new Set(section.items.map(item => item.objectId))
      const incoming = page.items.filter(item => !known.has(item.objectId))
      state.summary = {
        ...summary,
        sections: summary.sections.map(candidate => candidate.key === sectionKey
          ? { ...candidate, items: [...candidate.items, ...incoming], nextCursor: page.nextCursor, total: page.total, truncated: page.truncated }
          : candidate),
      }
      state.phase = state.summary.sections.some(candidate => candidate.items.length > 0) ? 'ready' : 'empty'
    } catch (error) {
      if (currentGeneration === generation) {
        state.sectionErrorMessage = error instanceof CrewScopeApiError ? error.envelope.message : '暂时无法加载该分组的更多内容，请稍后重试。'
      }
    } finally {
      if (currentGeneration === generation) state.sectionLoadingKey = null
    }
  }
  function reset(): void {
    generation += 1; request = null; filterKey = ''; activeFilter = {}
    state.phase = 'idle'; state.scope = null; state.summary = null; state.errorMessage = null
    state.sectionLoadingKey = null; state.sectionErrorMessage = null
  }
  return { state: readonly(state) as Readonly<WorkDeskStoreState>, activateScope, load, loadMore, reset }
}
export function installWorkDeskStore(app: App, gateway: WorkDeskGateway): WorkDeskStore { const store = createWorkDeskStore(gateway); app.provide(WORKDESK_STORE, store); return store }
export function useWorkDeskStore(): WorkDeskStore { const store = inject(WORKDESK_STORE); if (!store) throw new Error('CrewScope WorkDesk Store is not installed'); return store }
