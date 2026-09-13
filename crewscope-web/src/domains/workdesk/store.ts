import { inject, reactive, readonly, type App, type InjectionKey } from 'vue'
import { CrewScopeApiError } from '../../api/client'
import type { WorkDeskFilter, WorkDeskScope, WorkDeskSummary } from './types'
import type { WorkDeskGateway } from './gateway'

export type WorkDeskPhase = 'idle' | 'loading' | 'ready' | 'empty' | 'error' | 'offline'
export interface WorkDeskStoreState { phase: WorkDeskPhase; scope: WorkDeskScope | null; summary: WorkDeskSummary | null; errorMessage: string | null }
export interface WorkDeskStore {
  state: Readonly<WorkDeskStoreState>
  activateScope(scope: WorkDeskScope): void
  load(filter?: WorkDeskFilter, force?: boolean): Promise<void>
  reset(): void
}
export const WORKDESK_STORE: InjectionKey<WorkDeskStore> = Symbol('crewscope-workdesk-store')

/** Scope-isolated WorkDesk read model; stale responses are ignored after a Team switch. */
export function createWorkDeskStore(gateway: WorkDeskGateway): WorkDeskStore {
  const state = reactive<WorkDeskStoreState>({ phase: 'idle', scope: null, summary: null, errorMessage: null })
  let generation = 0
  let request: Promise<void> | null = null
  let filterKey = ''
  function activateScope(scope: WorkDeskScope): void {
    const key = `${scope.organizationId}:${scope.teamId}`
    const current = state.scope && `${state.scope.organizationId}:${state.scope.teamId}`
    if (key === current) return
    generation += 1; request = null; filterKey = ''
    state.scope = { ...scope }; state.summary = null; state.errorMessage = null; state.phase = 'idle'
  }
  async function load(filter: WorkDeskFilter = {}, force = false): Promise<void> {
    const scope = state.scope
    if (!scope) return
    const key = JSON.stringify(filter)
    if (request && !force && key === filterKey) return request
    filterKey = key
    const currentGeneration = generation
    state.phase = 'loading'; state.errorMessage = null
    const pending = gateway.get(scope, filter).then(summary => {
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
  function reset(): void { generation += 1; request = null; filterKey = ''; state.phase = 'idle'; state.scope = null; state.summary = null; state.errorMessage = null }
  return { state: readonly(state) as Readonly<WorkDeskStoreState>, activateScope, load, reset }
}
export function installWorkDeskStore(app: App, gateway: WorkDeskGateway): WorkDeskStore { const store = createWorkDeskStore(gateway); app.provide(WORKDESK_STORE, store); return store }
export function useWorkDeskStore(): WorkDeskStore { const store = inject(WORKDESK_STORE); if (!store) throw new Error('CrewScope WorkDesk Store is not installed'); return store }
