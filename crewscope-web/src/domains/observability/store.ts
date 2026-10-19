import { inject, reactive, readonly, type App, type DeepReadonly, type InjectionKey } from 'vue'
import { CrewScopeApiError } from '../../api/client'
import type { ObservabilityGateway } from './gateway'
import {
  isObservabilityMonth,
  type ObservabilityCostMonth,
  type ObservabilityCostMonthDetail,
  type ObservabilityQualityMonth,
  type ObservabilityScope,
} from './types'
import { isAbort } from '../../api/errors'
import { createRequestScope } from '../../api/requestScope'

export type ObservabilityPhase = 'idle' | 'loading' | 'ready' | 'empty' | 'error'

export interface ObservabilityMonthsResource {
  phase: ObservabilityPhase
  value: ObservabilityCostMonth[] | null
  errorMessage: string | null
  errorStatus: number | null
  nextAfter: string | null
  loadingMore: boolean
}

export interface ObservabilityMonthResource<T> {
  phase: ObservabilityPhase
  month: string | null
  value: T | null
  errorMessage: string | null
  errorStatus: number | null
}

export interface ObservabilityStoreState {
  months: ObservabilityMonthsResource
  detail: ObservabilityMonthResource<ObservabilityCostMonthDetail>
  quality: ObservabilityMonthResource<ObservabilityQualityMonth>
}

export interface ObservabilityStore {
  state: DeepReadonly<ObservabilityStoreState>
  activateScope(scope: ObservabilityScope): void
  loadMonths(more?: boolean, force?: boolean): Promise<void>
  /** Loads one month's detail and quality in one pass; the listing stays untouched. */
  loadMonth(month: string): Promise<void>
  reset(): void
}

export const OBSERVABILITY_STORE: InjectionKey<ObservabilityStore> = Symbol('crewscope-observability-store')

const PAGE_SIZE = 12

/**
 * Team-scoped read store for the cost and quality surface. The three resources are independent
 * so one failing month read never blanks the month navigation; `loadMonth` targets one month
 * and is safe to call for any month the listing shows.
 */
export function createObservabilityStore(gateway: ObservabilityGateway): ObservabilityStore {
  const state = reactive<ObservabilityStoreState>(initialState())
  let activeScope: ObservabilityScope | null = null
  let activeScopeKey: string | null = null
  // Scope switches advance the epoch (voiding captures) and change the coordinate (voiding
  // stragglers that miss the abort) — the generation + scopeKey pair this replaces.
  const requests = createRequestScope(() => activeScopeKey ?? '')

  function activateScope(scope: ObservabilityScope): void {
    const nextKey = scopeKey(scope)
    if (nextKey === activeScopeKey) return
    activeScope = { ...scope }
    activeScopeKey = nextKey
    requests.invalidate()
    replaceState(initialState())
  }

  async function loadMonths(more = false, force = false): Promise<void> {
    const scope = requireScope()
    if (more && (state.months.nextAfter === null || state.months.loadingMore)) return
    if (!more && !force && ['ready', 'empty'].includes(state.months.phase)) return
    const after = more ? state.months.nextAfter : null
    const request = requests.begin('months')
    if (more) state.months.loadingMore = true
    else {
      state.months.phase = 'loading'
      state.months.errorMessage = null
      state.months.errorStatus = null
      if (force) state.months.value = null
    }
    try {
      const page = await gateway.listCostMonths(scope, after, PAGE_SIZE, request.signal)
      if (!request.isCurrent()) return
      state.months.value = more ? mergeMonths(state.months.value, page.months) : page.months
      state.months.nextAfter = page.nextAfter
      state.months.phase = (state.months.value ?? []).length === 0 ? 'empty' : 'ready'
    } catch (error) {
      if (!isAbort(error) && request.isCurrent()) setError(state.months, error, '暂时无法加载用量月份')
    } finally {
      if (request.isCurrent()) state.months.loadingMore = false
      request.finish()
    }
  }

  async function loadMonth(month: string): Promise<void> {
    const scope = requireScope()
    if (!isObservabilityMonth(month)) return
    const detailRequest = requests.begin('detail')
    const qualityRequest = requests.begin('quality')
    for (const resource of [state.detail, state.quality]) {
      resource.phase = 'loading'
      resource.month = month
      resource.errorMessage = null
      resource.errorStatus = null
    }
    state.detail.value = null
    state.quality.value = null
    const [detailOutcome, qualityOutcome] = await Promise.allSettled([
      gateway.costMonth(scope, month, detailRequest.signal),
      gateway.qualityMonth(scope, month, qualityRequest.signal),
    ])
    if (detailOutcome.status === 'fulfilled' && detailRequest.isCurrent()) {
      state.detail.value = detailOutcome.value
      state.detail.phase = detailOutcome.value.rows.length === 0 ? 'empty' : 'ready'
    } else if (detailOutcome.status === 'rejected' && !isAbort(detailOutcome.reason) && detailRequest.isCurrent()) {
      setError(state.detail, detailOutcome.reason, '暂时无法加载当月成本明细')
    }
    if (qualityOutcome.status === 'fulfilled' && qualityRequest.isCurrent()) {
      state.quality.value = qualityOutcome.value
      // Quality reads are structurally full: an all-zero month is a real answer, not empty.
      state.quality.phase = 'ready'
    } else if (qualityOutcome.status === 'rejected' && !isAbort(qualityOutcome.reason) && qualityRequest.isCurrent()) {
      setError(state.quality, qualityOutcome.reason, '暂时无法加载当月质量统计')
    }
    detailRequest.finish()
    qualityRequest.finish()
  }

  function reset(): void {
    activeScope = null
    activeScopeKey = null
    requests.invalidate()
    replaceState(initialState())
  }

  function requireScope(): ObservabilityScope {
    if (!activeScope) throw new Error('Observability Store Scope is not active')
    return { ...activeScope }
  }

  function replaceState(next: ObservabilityStoreState): void {
    state.months = next.months
    state.detail = next.detail
    state.quality = next.quality
  }

  return {
    state: readonly(state),
    activateScope,
    loadMonths,
    loadMonth,
    reset,
  }
}

export function installObservabilityStore(app: App, gateway: ObservabilityGateway): ObservabilityStore {
  const store = createObservabilityStore(gateway)
  app.provide(OBSERVABILITY_STORE, store)
  return store
}

export function useObservabilityStore(): ObservabilityStore {
  const store = inject(OBSERVABILITY_STORE)
  if (!store) throw new Error('CrewScope Observability Store is not installed')
  return store
}

function initialState(): ObservabilityStoreState {
  return {
    months: { phase: 'idle', value: null, errorMessage: null, errorStatus: null, nextAfter: null, loadingMore: false },
    detail: { phase: 'idle', month: null, value: null, errorMessage: null, errorStatus: null },
    quality: { phase: 'idle', month: null, value: null, errorMessage: null, errorStatus: null },
  }
}

/** Months are descending and unique per page, but a forced refresh may re-fetch the tail. */
function mergeMonths(existing: ObservabilityCostMonth[] | null, incoming: ObservabilityCostMonth[]): ObservabilityCostMonth[] {
  const known = new Set((existing ?? []).map(item => item.month))
  return [...(existing ?? []), ...incoming.filter(item => !known.has(item.month))]
}

function scopeKey(scope: ObservabilityScope): string {
  return `${scope.organizationId}:${scope.teamId}`
}

function setError(resource: { phase: ObservabilityPhase, errorMessage: string | null, errorStatus: number | null }, error: unknown, fallback: string): void {
  resource.phase = 'error'
  resource.errorMessage = error instanceof CrewScopeApiError ? error.envelope.message : fallback
  resource.errorStatus = error instanceof CrewScopeApiError ? error.status : null
}
