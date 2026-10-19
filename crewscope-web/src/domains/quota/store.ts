import { inject, reactive, readonly, type App, type InjectionKey } from 'vue'
import { CrewScopeApiError } from '../../api/client'
import { createCommandGateway } from '../../api/commandGateway'
import { createRequestScope } from '../../api/requestScope'
import type { QuotaGateway } from './gateway'
import type { OrganizationQuotaConfiguration, QuotaCommandReceipt, QuotaUsageView } from './types'
import { isQuotaMonth, quotaMonthsBack } from './types'

/**
 * M12-F02 quota store. Resources are org-grain like the organization store: a 403 maps
 * to the 'forbidden' phase so the page renders the role-gated notice, and every command
 * outcome (202 receipt or 409) forces a configuration re-read so the next edit starts
 * from the persisted version.
 */
/** The phases a load can settle into; configuration additionally rests at 'idle'. */
export type QuotaLoadPhase = 'loading' | 'ready' | 'error' | 'forbidden'
export type QuotaResourcePhase = 'idle' | QuotaLoadPhase

export interface QuotaResource<T> {
  phase: QuotaResourcePhase
  value: T | null
  errorMessage: string | null
}

export interface QuotaCommandState {
  phase: 'idle' | 'pending' | 'success' | 'error'
  receipt: QuotaCommandReceipt | null
  errorMessage: string | null
  errorStatus: number | null
  errorCode: string | null
}

export interface QuotaMonthEntry {
  phase: 'loading' | 'ready' | 'error'
  value: QuotaUsageView | null
  errorMessage: string | null
}

/**
 * The usage window has no dedicated backend endpoint (contract deviation #1): the first
 * parameterless call anchors the server's current reporting month and the five previous
 * months fan out in parallel. Months outside the window load on demand when navigated to.
 */
export interface QuotaUsageState {
  phase: 'bootstrap' | 'loading' | 'ready' | 'error' | 'forbidden'
  errorMessage: string | null
  anchorMonth: string | null
  windowMonths: string[]
  months: Record<string, QuotaMonthEntry>
}

export interface QuotaStoreState {
  configuration: QuotaResource<OrganizationQuotaConfiguration>
  command: QuotaCommandState
  usage: QuotaUsageState
}

export interface QuotaStore {
  state: Readonly<QuotaStoreState>
  loadConfiguration(organizationId: string, force?: boolean): Promise<void>
  updateQuota(
    organizationId: string,
    input: { monthlyTaskIntakeLimit: number | null, monthlyTokenBudget: number | null },
    expectedVersion: number,
    idempotencyKey: string,
  ): Promise<boolean>
  loadUsageWindow(organizationId: string, force?: boolean): Promise<void>
  /** Loads one navigated month on demand; future months never dispatch (server 400). */
  loadUsageMonth(organizationId: string, month: string): Promise<void>
  clearCommand(): void
  reset(): void
}

export const QUOTA_STORE: InjectionKey<QuotaStore> = Symbol('crewscope-quota-store')

/** How many months the trend card shows (anchor month plus the five before it). */
const USAGE_WINDOW_SIZE = 6

export function createQuotaStore(gateway: QuotaGateway): QuotaStore {
  // The number is the idempotencyKey parameter index of the gateway method — the
  // commandInventory gate verifies it against the interface signature by AST.
  const commandIntents = createCommandGateway(gateway, { updateQuota: 3 })
  gateway = commandIntents.gateway
  const state = reactive<QuotaStoreState>(initialState())
  // Two independent request scopes (M12b-F05): configuration and usage own their
  // invalidation separately, exactly as the two generations they replace did.
  const configurationRequests = createRequestScope(() => 'quota-configuration')
  // One scope for the whole usage surface: a window reload invalidates every in-flight
  // month (window or on-demand); a single on-demand month never voids another —
  // months run as per-month channels, the anchor ride-along uses a plain capture.
  const usageRequests = createRequestScope(() => 'quota-usage')

  async function loadConfiguration(organizationId: string, force = false): Promise<void> {
    if (!force && state.configuration.phase === 'ready') return
    const request = configurationRequests.begin('configuration')
    state.configuration.phase = 'loading'
    state.configuration.errorMessage = null
    try {
      const value = await gateway.quota(organizationId, request.signal)
      if (!request.isCurrent()) return
      state.configuration.phase = 'ready'
      state.configuration.value = value
    } catch (error) {
      if (!request.isCurrent()) return
      state.configuration.phase = phaseOf(error)
      state.configuration.errorMessage = messageOf(error)
    } finally {
      request.finish()
    }
  }

  async function updateQuota(
    organizationId: string,
    input: { monthlyTaskIntakeLimit: number | null, monthlyTokenBudget: number | null },
    expectedVersion: number,
    idempotencyKey: string,
  ): Promise<boolean> {
    state.command.phase = 'pending'
    state.command.errorMessage = null
    state.command.errorStatus = null
    state.command.errorCode = null
    try {
      const receipt = await gateway.updateQuota(organizationId, input, expectedVersion, idempotencyKey)
      state.command.phase = 'success'
      state.command.receipt = receipt
      // A 202 receipt means acceptance, not a moved read model — re-read for the next edit.
      await loadConfiguration(organizationId, true)
      return true
    } catch (error) {
      state.command.phase = 'error'
      state.command.errorMessage = messageOf(error)
      state.command.errorStatus = error instanceof CrewScopeApiError ? error.status : null
      state.command.errorCode = error instanceof CrewScopeApiError ? error.envelope.code : null
      if (error instanceof CrewScopeApiError && error.status === 409) {
        await loadConfiguration(organizationId, true)
      }
      return false
    }
  }

  async function loadUsageWindow(organizationId: string, force = false): Promise<void> {
    if (!force && state.usage.phase === 'ready') return
    // Window reload voids every in-flight month request (the generation it replaces);
    // the anchor owner captures only after the invalidation epoch has advanced.
    usageRequests.invalidate()
    const owner = usageRequests.capture()
    state.usage.phase = 'loading'
    state.usage.errorMessage = null
    let anchor: QuotaUsageView
    try {
      // Parameterless: the server names its current reporting month (single source).
      anchor = await gateway.usage(organizationId, null)
    } catch (error) {
      if (!owner.isCurrent()) return
      state.usage.phase = phaseOf(error)
      state.usage.errorMessage = messageOf(error)
      return
    }
    if (!owner.isCurrent()) return
    const windowMonths = quotaMonthsBack(anchor.month, USAGE_WINDOW_SIZE)
    state.usage.anchorMonth = anchor.month
    state.usage.windowMonths = windowMonths
    state.usage.months = Object.fromEntries(windowMonths.map(month => [month, monthEntry()]))
    state.usage.months[anchor.month] = { phase: 'ready', value: anchor, errorMessage: null }
    state.usage.phase = 'ready'
    // The five previous months fan out independently; one month's failure degrades that
    // month's entry (the trend card skips it) without failing the anchored window.
    await Promise.all(windowMonths
      .filter(month => month !== anchor.month)
      .map(month => loadUsageMonth(organizationId, month)))
  }

  async function loadUsageMonth(organizationId: string, month: string): Promise<void> {
    // Future months are a guaranteed 400 (contract §4): the store refuses to dispatch.
    // Before the anchor resolves the ceiling is unknown, so on-demand loads wait for it —
    // a deep-linked month re-triggers from the page once the anchor names the ceiling.
    if (!isQuotaMonth(month)) return
    if (state.usage.anchorMonth === null) return
    if (state.usage.months[month]?.phase === 'ready') return
    if (month > state.usage.anchorMonth) return
    const request = usageRequests.begin(month)
    state.usage.months[month] = { phase: 'loading', value: null, errorMessage: null }
    try {
      const value = await gateway.usage(organizationId, month, request.signal)
      if (!request.isCurrent()) return
      state.usage.months[month] = { phase: 'ready', value, errorMessage: null }
    } catch (error) {
      if (!request.isCurrent()) return
      state.usage.months[month] = { phase: 'error', value: null, errorMessage: messageOf(error) }
    } finally {
      request.finish()
    }
  }

  function clearCommand(): void {
    state.command = commandState()
  }

  function reset(): void {
    configurationRequests.invalidate()
    usageRequests.invalidate()
    commandIntents.clear()
    Object.assign(state, initialState())
  }

  return {
    state: readonly(state) as Readonly<QuotaStoreState>,
    loadConfiguration,
    updateQuota,
    loadUsageWindow,
    loadUsageMonth,
    clearCommand,
    reset,
  }
}

function initialState(): QuotaStoreState {
  return {
    configuration: resource(),
    command: commandState(),
    usage: usageState(),
  }
}

function resource<T>(): QuotaResource<T> {
  return { phase: 'idle', value: null, errorMessage: null }
}

function commandState(): QuotaCommandState {
  return { phase: 'idle', receipt: null, errorMessage: null, errorStatus: null, errorCode: null }
}

function usageState(): QuotaUsageState {
  return { phase: 'bootstrap', errorMessage: null, anchorMonth: null, windowMonths: [], months: {} }
}

function monthEntry(): QuotaMonthEntry {
  return { phase: 'loading', value: null, errorMessage: null }
}

function messageOf(error: unknown): string {
  if (error instanceof CrewScopeApiError) return error.envelope.message
  return '暂时无法完成该操作，请稍后重试。'
}

/** A 403 is a role fact, not a retriable failure — the page renders the gated notice. */
function phaseOf(error: unknown): QuotaLoadPhase {
  return error instanceof CrewScopeApiError && error.status === 403 ? 'forbidden' : 'error'
}

export function installQuotaStore(app: App, gateway: QuotaGateway): QuotaStore {
  const store = createQuotaStore(gateway)
  app.provide(QUOTA_STORE, store)
  return store
}

export function useQuotaStore(): QuotaStore {
  const store = inject(QUOTA_STORE)
  if (!store) throw new Error('CrewScope Quota Store is not installed')
  return store
}
