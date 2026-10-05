import { inject, reactive, readonly, type App, type InjectionKey } from 'vue'
import { CrewScopeApiError } from '../../api/client'
import type { SettingsScope } from '../settings/types'
import type { AgentGateway } from './gateway'
import type { AgentMemoryView } from './types'

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

interface MemoryRequest {
  key: string
  version: number
  controller: AbortController
  scopeKey: string | null
}

/** Profile-keyed I02a memory views with generation checks in addition to AbortSignal cancellation. */
export function createAgentMemoryStore(gateway: AgentMemoryGateway): AgentMemoryStore {
  const state = reactive<AgentMemoryStoreState>(initialState())
  let activeScope: SettingsScope | null = null
  let activeScopeKey: string | null = null
  let generation = 0
  const requests = new Map<string, MemoryRequest>()

  function activateScope(scope: SettingsScope): void {
    const nextKey = scopeKey(scope)
    if (nextKey === activeScopeKey) return
    activeScope = { ...scope }
    activeScopeKey = nextKey
    generation += 1
    abortRequests()
    replaceState(initialState())
  }

  async function load(profileId: string, force = false): Promise<void> {
    const scope = requireScope()
    const current = state.views[profileId]
    if (!force && current && current.phase === 'ready') return
    const request = beginRequest(profileId)
    state.views[profileId] = { phase: 'loading', value: null, errorMessage: null, errorStatus: null }
    try {
      const value = await gateway.getMemory(scope, profileId, request.controller.signal)
      if (!isCurrent(request)) return
      state.views[profileId] = { phase: 'ready', value, errorMessage: null, errorStatus: null }
    } catch (error) {
      if (!isAbort(error) && isCurrent(request)) {
        state.views[profileId] = { phase: 'error', value: null, errorMessage: presentError(error, '暂时无法加载辅助记忆'), errorStatus: statusOf(error) }
      }
    } finally {
      finishRequest(request)
    }
  }

  async function clearMemory(profileId: string): Promise<boolean> {
    const scope = requireScope()
    const commandGeneration = generation
    if (state.clear.phase === 'pending') return false
    state.clear = { phase: 'pending', profileId, message: null, errorMessage: null, errorCode: null, errorStatus: null }
    const pending = state.clear
    try {
      const clearance = await gateway.clearMemory(scope, profileId)
      if (commandGeneration !== generation || state.clear !== pending) return false
      state.clear.phase = 'success'
      state.clear.message = `已清除 ${clearance.clearedCount} 条记忆 · 清空代际 ${clearance.clearanceGeneration}`
      // The receipt is the fact, but the entry table must not survive it — re-read the view.
      state.views[profileId] = { phase: 'loading', value: null, errorMessage: null, errorStatus: null }
      await load(profileId, true)
      return true
    } catch (error) {
      if (commandGeneration !== generation || state.clear !== pending) return false
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
    generation += 1
    abortRequests()
    replaceState(initialState())
  }

  function requireScope(): SettingsScope {
    if (!activeScope) throw new Error('Agent Memory Store Scope is not active')
    return { ...activeScope }
  }

  function beginRequest(key: string): MemoryRequest {
    requests.get(key)?.controller.abort()
    const request: MemoryRequest = { key, version: generation, controller: new AbortController(), scopeKey: activeScopeKey }
    requests.set(key, request)
    return request
  }

  function isCurrent(request: MemoryRequest): boolean {
    return generation === request.version
      && activeScopeKey === request.scopeKey
      && requests.get(request.key) === request
  }

  function finishRequest(request: MemoryRequest): void {
    if (requests.get(request.key) === request) requests.delete(request.key)
  }

  function abortRequests(): void {
    for (const request of requests.values()) request.controller.abort()
    requests.clear()
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

export function installAgentMemoryStore(app: App, gateway: AgentMemoryGateway): AgentMemoryStore {
  const store = createAgentMemoryStore(gateway)
  app.provide(AGENT_MEMORY_STORE, store)
  return store
}

export function useAgentMemoryStore(): AgentMemoryStore {
  const store = inject(AGENT_MEMORY_STORE)
  if (!store) throw new Error('CrewScope Agent Memory Store is not installed')
  return store
}

function initialState(): AgentMemoryStoreState {
  return { views: {}, clear: clearState() }
}

function clearState(): AgentMemoryClearState {
  return { phase: 'idle', profileId: null, message: null, errorMessage: null, errorCode: null, errorStatus: null }
}

function presentError(error: unknown, fallback: string): string {
  return error instanceof CrewScopeApiError ? error.envelope.message : fallback
}

function statusOf(error: unknown): number | null {
  return error instanceof CrewScopeApiError ? error.status : null
}

function isAbort(error: unknown): boolean {
  return error instanceof DOMException && error.name === 'AbortError'
}

function scopeKey(scope: SettingsScope): string {
  return `${scope.organizationId}:${scope.teamId}`
}
