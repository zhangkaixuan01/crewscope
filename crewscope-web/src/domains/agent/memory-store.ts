import {getCurrentInstance, inject, reactive, readonly, type App, type InjectionKey} from 'vue'
import { registerStoreReset } from '../../app/storeLifecycle'
import { CrewScopeApiError } from '../../api/client'
import type { SettingsScope } from '../settings/types'
import { HttpAgentGateway, type AgentGateway } from './gateway'
import type { AgentMemoryView } from './types'
import { isAbort, statusOf, presentError } from '../../api/errors'
import { createRequestScope } from '../../api/requestScope'

export type AgentMemoryPhase = 'idle' | 'loading' | 'ready' | 'error'

/** One profile's own memory view — the settings panel may hold several profiles at once. */
export interface AgentMemoryResource {
  phase: AgentMemoryPhase
  value: AgentMemoryView | null
  errorMessage: string | null
  errorStatus: number | null
}

/**
 * The I02a clear is structurally idempotent (contract §4: no Idempotency-Key, no If-Match),
 * so this slot carries the synchronous receipt wording instead of a command envelope.
 */
export interface AgentMemoryClearState {
  phase: 'idle' | 'pending' | 'success' | 'error'
  profileId: string | null
  message: string | null
  errorMessage: string | null
  errorCode: string | null
  errorStatus: number | null
}

export interface AgentMemoryStoreState {
  views: Record<string, AgentMemoryResource>
  clear: AgentMemoryClearState
}

export interface AgentMemoryStore {
  state: Readonly<AgentMemoryStoreState>
  activateScope(scope: SettingsScope): void
  load(profileId: string, force?: boolean): Promise<void>
  clearMemory(profileId: string): Promise<boolean>
  clearCommand(): void
  reset(): void
}

export const AGENT_MEMORY_STORE: InjectionKey<AgentMemoryStore> = Symbol('crewscope-agent-memory-store')

/** Same-domain second store (F01b precedent): consumes only the I02a memory face. */
export type AgentMemoryGateway = Pick<AgentGateway, 'getMemory' | 'clearMemory'>

/** Profile-keyed I02a memory views with generation checks in addition to AbortSignal cancellation. */
export function createAgentMemoryStore(gateway: AgentMemoryGateway): AgentMemoryStore {
  const state = reactive<AgentMemoryStoreState>(initialState())
  let activeScope: SettingsScope | null = null
  let activeScopeKey: string | null = null
  // Scope switches advance the epoch (voiding captures) and change the coordinate (voiding
  // stragglers that miss the abort) — the generation + scopeKey pair this replaces.
  const requests = createRequestScope(() => activeScopeKey ?? '')

  function activateScope(scope: SettingsScope): void {
    const nextKey = scopeKey(scope)
    if (nextKey === activeScopeKey) return
    activeScope = { ...scope }
    activeScopeKey = nextKey
    requests.invalidate()
    replaceState(initialState())
  }

  async function load(profileId: string, force = false): Promise<void> {
    const scope = requireScope()
    const current = state.views[profileId]
    if (!force && current && current.phase === 'ready') return
    const request = requests.begin(profileId)
    state.views[profileId] = { phase: 'loading', value: null, errorMessage: null, errorStatus: null }
    try {
      const value = await gateway.getMemory(scope, profileId, request.signal)
      if (!request.isCurrent()) return
      state.views[profileId] = { phase: 'ready', value, errorMessage: null, errorStatus: null }
    } catch (error) {
      if (!isAbort(error) && request.isCurrent()) {
        state.views[profileId] = { phase: 'error', value: null, errorMessage: presentError(error, '暂时无法加载辅助记忆'), errorStatus: statusOf(error) }
      }
    } finally {
      request.finish()
    }
  }

  async function clearMemory(profileId: string): Promise<boolean> {
    const scope = requireScope()
    const commandOwner = requests.capture()
    if (state.clear.phase === 'pending') return false
    state.clear = { phase: 'pending', profileId, message: null, errorMessage: null, errorCode: null, errorStatus: null }
    const pending = state.clear
    try {
      const clearance = await gateway.clearMemory(scope, profileId)
      if (!commandOwner.isCurrent() || state.clear !== pending) return false
      state.clear.phase = 'success'
      state.clear.message = `已清除 ${clearance.clearedCount} 条记忆 · 清空代际 ${clearance.clearanceGeneration}`
      // The receipt is the fact, but the entry table must not survive it — re-read the view.
      state.views[profileId] = { phase: 'loading', value: null, errorMessage: null, errorStatus: null }
      await load(profileId, true)
      return true
    } catch (error) {
      if (!commandOwner.isCurrent() || state.clear !== pending) return false
      state.clear.phase = 'error'
      state.clear.errorMessage = presentError(error, '清除辅助记忆失败')
      state.clear.errorCode = error instanceof CrewScopeApiError ? error.envelope.code : null
      state.clear.errorStatus = statusOf(error)
      return false
    }
  }

  function clearCommand(): void {
    state.clear = clearState()
  }

  function reset(): void {
    activeScope = null
    activeScopeKey = null
    requests.invalidate()
    replaceState(initialState())
  }

  function requireScope(): SettingsScope {
    if (!activeScope) throw new Error('Agent Memory Store Scope is not active')
    return { ...activeScope }
  }

  function replaceState(next: AgentMemoryStoreState): void {
    state.views = next.views
    state.clear = next.clear
  }

  return {
    state: readonly(state) as Readonly<AgentMemoryStoreState>,
    activateScope,
    load,
    clearMemory,
    clearCommand,
    reset,
  }
}

// M13d-P01: lazily installed on the pages that consume this domain (keeps it out of the eager
// main chunk). The instance lives on the app, so revisiting a page reuses cached state; spec
// fixtures provided through app-level provide always win and are never shadowed.
export function ensureAgentMemoryStore(app: App): AgentMemoryStore {
  const existing = app.runWithContext(() => inject(AGENT_MEMORY_STORE, null))
  if (existing) return existing
  const store = createAgentMemoryStore(new HttpAgentGateway())
  app.provide(AGENT_MEMORY_STORE, store)
  registerStoreReset(app, () => store.reset())
  return store
}


export function useAgentMemoryStore(): AgentMemoryStore {
  const installed = inject(AGENT_MEMORY_STORE, null)
  if (installed) return installed
  // M13d-P01: fall back to lazy installation — a component declares its domain dependency
  // by calling use*, so no host page has to pre-install the store.
  const instance = getCurrentInstance()
  if (!instance) throw new Error('CrewScope Agent Memory Store is not installed')
  return ensureAgentMemoryStore(instance.appContext.app)
}

function initialState(): AgentMemoryStoreState {
  return { views: {}, clear: clearState() }
}

function clearState(): AgentMemoryClearState {
  return { phase: 'idle', profileId: null, message: null, errorMessage: null, errorCode: null, errorStatus: null }
}




function scopeKey(scope: SettingsScope): string {
  return `${scope.organizationId}:${scope.teamId}`
}
