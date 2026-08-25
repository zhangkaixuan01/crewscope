import { inject, reactive, readonly, type App, type InjectionKey } from 'vue'
import { CrewScopeApiError } from '../../api/client'
import type { Etagged, OffsetPage, SettingsScope } from '../settings/types'
import type { ModelGateway } from './gateway'
import type {
  CreateModelConnectionInput,
  ModelCatalogEntrySummary,
  ModelConnectionCommandReceipt,
  ModelConnectionOwnerType,
  ModelConnectionSummary,
  ModelProviderSummary,
} from './types'

export type ModelResourcePhase = 'idle' | 'loading' | 'ready' | 'empty' | 'error'

export interface ModelResource<T> {
  phase: ModelResourcePhase
  value: T | null
  errorMessage: string | null
  errorStatus: number | null
}

export interface ModelPageResource<T> extends ModelResource<T[]> {
  nextOffset: number | null
  loadingMore: boolean
}

export interface ModelCommandState {
  phase: 'idle' | 'pending' | 'success' | 'error' | 'conflict'
  operation: 'create' | 'verify' | 'rotate' | 'suspend' | 'revoke' | null
  connectionId: string | null
  receipt: ModelConnectionCommandReceipt | null
  errorMessage: string | null
  errorStatus: number | null
  retryable: boolean
}

export interface ModelStoreState {
  providers: ModelPageResource<ModelProviderSummary>
  catalogs: Record<string, ModelPageResource<ModelCatalogEntrySummary>>
  connections: Record<string, ModelPageResource<ModelConnectionSummary>>
  connectionDetails: Record<string, ModelResource<Etagged<ModelConnectionSummary>>>
  command: ModelCommandState
}

export interface ModelStore {
  state: Readonly<ModelStoreState>
  activateScope(scope: SettingsScope): void
  loadProviders(more?: boolean, force?: boolean): Promise<void>
  loadCatalog(providerKey: string, more?: boolean, force?: boolean): Promise<void>
  loadConnections(ownerType: ModelConnectionOwnerType, more?: boolean, force?: boolean): Promise<void>
  loadConnection(connectionId: string, force?: boolean): Promise<void>
  createConnection(input: CreateModelConnectionInput, idempotencyKey: string): Promise<boolean>
  verifyConnection(connectionId: string, idempotencyKey: string): Promise<boolean>
  rotateCredential(connectionId: string, credentialVersion: number, apiKey: string, idempotencyKey: string): Promise<boolean>
  suspendConnection(connectionId: string, idempotencyKey: string): Promise<boolean>
  revokeConnection(connectionId: string, reason: string, idempotencyKey: string): Promise<boolean>
  invalidateConnection(connectionId?: string): void
  clearCommand(): void
  reset(): void
}

export const MODEL_STORE: InjectionKey<ModelStore> = Symbol('crewscope-model-store')

const PAGE_SIZE = 50

interface ModelRequest {
  key: string
  version: number
  controller: AbortController
  scopeKey: string | null
}

/** Scope-partitioned model state. Secret command inputs never enter this reactive graph. */
export function createModelStore(gateway: ModelGateway): ModelStore {
  const state = reactive<ModelStoreState>(initialState())
  let activeScope: SettingsScope | null = null
  let activeScopeKey: string | null = null
  let generation = 0
  const requests = new Map<string, ModelRequest>()

  function activateScope(scope: SettingsScope): void {
    const nextKey = scopeKey(scope)
    if (nextKey === activeScopeKey) return
    activeScope = { ...scope }
    activeScopeKey = nextKey
    generation += 1
    abortRequests()
    replaceState(initialState())
  }

  async function loadProviders(more = false, force = false): Promise<void> {
    const scope = requireScope()
    await loadPage(
      'providers',
      state.providers,
      more,
      force,
      (offset, signal) => gateway.listProviders(scope.organizationId, offset, PAGE_SIZE, signal),
      item => item.key,
    )
  }

  async function loadCatalog(providerKey: string, more = false, force = false): Promise<void> {
    const scope = requireScope()
    const key = `catalog:${providerKey}`
    if (!state.catalogs[providerKey]) state.catalogs[providerKey] = pageResource<ModelCatalogEntrySummary>()
    const resource = state.catalogs[providerKey]!
    await loadPage(
      key,
      resource,
      more,
      force,
      (offset, signal) => gateway.listCatalog(scope.organizationId, providerKey, offset, PAGE_SIZE, signal),
      item => `${item.id}:${item.catalogRevision}`,
      page => {
        if (page.items.some(item => item.providerKey !== providerKey)) {
          throw new Error('Model Catalog entry does not match the requested Provider')
        }
      },
    )
  }

  async function loadConnections(ownerType: ModelConnectionOwnerType, more = false, force = false): Promise<void> {
    const scope = requireScope()
    if (!state.connections[ownerType]) state.connections[ownerType] = pageResource<ModelConnectionSummary>()
    const resource = state.connections[ownerType]!
    await loadPage(
      `connections:${ownerType}`,
      resource,
      more,
      force,
      (offset, signal) => gateway.listConnections(scope, ownerType, offset, PAGE_SIZE, signal),
      item => item.id,
      page => page.items.forEach(item => {
        if (item.ownerType !== ownerType) throw new Error('Model Connection owner type does not match the query')
        assertConnectionScope(item, scope)
      }),
    )
  }

  async function loadConnection(connectionId: string, force = false): Promise<void> {
    const scope = requireScope()
    const existing = state.connectionDetails[connectionId]
    if (!force && existing?.phase === 'ready') return
    if (!existing) state.connectionDetails[connectionId] = resourceState<Etagged<ModelConnectionSummary>>()
    // Mutate the proxy retrieved from reactive state; mutating the pre-insertion raw object would
    // leave an already-mounted detail panel stuck in its loading projection.
    const resource = state.connectionDetails[connectionId]!
    const request = beginRequest(`connection:${connectionId}`)
    resource.phase = 'loading'
    resource.errorMessage = null
    resource.errorStatus = null
    try {
      const value = await gateway.getConnection(scope.organizationId, connectionId, request.controller.signal)
      if (!isCurrent(request)) return
      assertConnectionScope(value.value, scope)
      resource.value = value
      resource.phase = 'ready'
    } catch (error) {
      if (!isAbort(error) && isCurrent(request)) setError(resource, error, '暂时无法加载模型连接')
    } finally {
      finishRequest(request)
    }
  }

  async function createConnection(input: CreateModelConnectionInput, idempotencyKey: string): Promise<boolean> {
    const scope = requireScope()
    if (input.ownerType === 'TEAM' && input.teamId !== scope.teamId) {
      throw new Error('Model Connection Team does not match the active Scope')
    }
    return runCommand('create', null, async () => {
      // The API Key remains a stack-local argument and is never copied into command retry state.
      return gateway.createConnection(input, scope.organizationId, idempotencyKey)
    }, () => {
      state.connections[input.ownerType] = pageResource<ModelConnectionSummary>()
    })
  }

  async function verifyConnection(connectionId: string, idempotencyKey: string): Promise<boolean> {
    return withConnection(connectionId, 'verify', (scope, detail) =>
      gateway.verifyConnection(scope.organizationId, detail.value, detail.etag, idempotencyKey))
  }

  async function rotateCredential(
    connectionId: string,
    credentialVersion: number,
    apiKey: string,
    idempotencyKey: string,
  ): Promise<boolean> {
    return withConnection(connectionId, 'rotate', (scope, detail) =>
      gateway.rotateCredential(
        scope.organizationId,
        connectionId,
        detail.etag,
        { credentialVersion, apiKey },
        idempotencyKey,
      ))
  }

  async function suspendConnection(connectionId: string, idempotencyKey: string): Promise<boolean> {
    return withConnection(connectionId, 'suspend', (scope, detail) =>
      gateway.suspendConnection(scope.organizationId, detail.value, detail.etag, idempotencyKey))
  }

  async function revokeConnection(connectionId: string, reason: string, idempotencyKey: string): Promise<boolean> {
    return withConnection(connectionId, 'revoke', (scope, detail) =>
      gateway.revokeConnection(scope.organizationId, detail.value, detail.etag, reason, idempotencyKey))
  }

  async function withConnection(
    connectionId: string,
    operation: Exclude<ModelCommandState['operation'], 'create' | null>,
    command: (scope: SettingsScope, detail: Etagged<ModelConnectionSummary>) => Promise<ModelConnectionCommandReceipt>,
  ): Promise<boolean> {
    const scope = requireScope()
    if (state.connectionDetails[connectionId]?.phase !== 'ready') await loadConnection(connectionId)
    const detail = state.connectionDetails[connectionId]?.value
    if (!detail) return false
    return runCommand(
      operation,
      connectionId,
      () => command(scope, detail),
      () => invalidateConnection(connectionId),
    )
  }

  async function runCommand(
    operation: NonNullable<ModelCommandState['operation']>,
    connectionId: string | null,
    action: () => Promise<ModelConnectionCommandReceipt>,
    onSuccess?: () => void,
  ): Promise<boolean> {
    const commandGeneration = generation
    state.command = {
      phase: 'pending', operation, connectionId, receipt: null,
      errorMessage: null, errorStatus: null, retryable: false,
    }
    try {
      const receipt = await action()
      if (commandGeneration !== generation) return false
      onSuccess?.()
      state.command.phase = 'success'
      state.command.receipt = receipt
      return true
    } catch (error) {
      if (commandGeneration !== generation) return false
      state.command.phase = conflict(error) ? 'conflict' : 'error'
      state.command.errorMessage = presentError(error, '模型连接命令执行失败')
      state.command.errorStatus = statusOf(error)
      state.command.retryable = error instanceof CrewScopeApiError && error.envelope.retryable
      return false
    }
  }

  async function loadPage<T>(
    requestKey: string,
    resource: ModelPageResource<T>,
    more: boolean,
    force: boolean,
    load: (offset: number, signal: AbortSignal) => Promise<OffsetPage<T>>,
    identity: (value: T) => string,
    validate?: (page: OffsetPage<T>) => void,
  ): Promise<void> {
    if (more && (resource.nextOffset === null || resource.loadingMore)) return
    if (!more && !force && ['ready', 'empty'].includes(resource.phase)) return
    const offset = more ? resource.nextOffset ?? 0 : 0
    const request = beginRequest(requestKey)
    if (more) resource.loadingMore = true
    else {
      resource.phase = 'loading'
      resource.errorMessage = null
      resource.errorStatus = null
      if (force) resource.value = null
    }
    try {
      const page = await load(offset, request.controller.signal)
      if (!isCurrent(request)) return
      validate?.(page)
      resource.value = more ? merge(resource.value ?? [], page.items, identity) : page.items
      resource.nextOffset = page.nextOffset
      resource.phase = resource.value.length === 0 ? 'empty' : 'ready'
    } catch (error) {
      if (!isAbort(error) && isCurrent(request)) setError(resource, error, '暂时无法加载模型设置')
    } finally {
      if (isCurrent(request)) resource.loadingMore = false
      finishRequest(request)
    }
  }

  function invalidateConnection(connectionId?: string): void {
    if (connectionId) delete state.connectionDetails[connectionId]
    else state.connectionDetails = {}
    state.connections = {}
  }

  function clearCommand(): void {
    state.command = commandState()
  }

  function reset(): void {
    activeScope = null
    activeScopeKey = null
    generation += 1
    abortRequests()
    replaceState(initialState())
  }

  function requireScope(): SettingsScope {
    if (!activeScope) throw new Error('Model Store Scope is not active')
    return { ...activeScope }
  }

  function beginRequest(key: string): ModelRequest {
    requests.get(key)?.controller.abort()
    const request = { key, version: generation, controller: new AbortController(), scopeKey: activeScopeKey }
    requests.set(key, request)
    return request
  }

  function isCurrent(request: ModelRequest): boolean {
    return generation === request.version
      && activeScopeKey === request.scopeKey
      && requests.get(request.key) === request
  }

  function finishRequest(request: ModelRequest): void {
    if (requests.get(request.key) === request) requests.delete(request.key)
  }

  function abortRequests(): void {
    for (const request of requests.values()) request.controller.abort()
    requests.clear()
  }

  function replaceState(next: ModelStoreState): void {
    state.providers = next.providers
    state.catalogs = next.catalogs
    state.connections = next.connections
    state.connectionDetails = next.connectionDetails
    state.command = next.command
  }

  return {
    state: readonly(state) as Readonly<ModelStoreState>,
    activateScope,
    loadProviders,
    loadCatalog,
    loadConnections,
    loadConnection,
    createConnection,
    verifyConnection,
    rotateCredential,
    suspendConnection,
    revokeConnection,
    invalidateConnection,
    clearCommand,
    reset,
  }
}

export function installModelStore(app: App, gateway: ModelGateway): ModelStore {
  const store = createModelStore(gateway)
  app.provide(MODEL_STORE, store)
  return store
}

export function useModelStore(): ModelStore {
  const store = inject(MODEL_STORE)
  if (!store) throw new Error('CrewScope Model Store is not installed')
  return store
}

function initialState(): ModelStoreState {
  return {
    providers: pageResource<ModelProviderSummary>(),
    catalogs: {},
    connections: {},
    connectionDetails: {},
    command: commandState(),
  }
}

function resourceState<T>(): ModelResource<T> {
  return { phase: 'idle', value: null, errorMessage: null, errorStatus: null }
}

function pageResource<T>(): ModelPageResource<T> {
  return { ...resourceState<T[]>(), nextOffset: 0, loadingMore: false }
}

function commandState(): ModelCommandState {
  return {
    phase: 'idle', operation: null, connectionId: null, receipt: null,
    errorMessage: null, errorStatus: null, retryable: false,
  }
}

function assertConnectionScope(value: ModelConnectionSummary, scope: SettingsScope): void {
  if (value.organizationId !== scope.organizationId) throw new Error('Model Connection is outside the active Organization')
  if (value.ownerType === 'TEAM' && value.ownerId !== scope.teamId) {
    throw new Error('Model Connection is outside the active Team')
  }
}

function merge<T>(existing: T[], incoming: T[], identity: (value: T) => string): T[] {
  const known = new Set(existing.map(identity))
  return [...existing, ...incoming.filter(value => !known.has(identity(value)))]
}

function scopeKey(scope: SettingsScope): string {
  return `${scope.organizationId}:${scope.teamId}`
}

function setError(resource: ModelResource<unknown>, error: unknown, fallback: string): void {
  resource.phase = 'error'
  resource.errorMessage = presentError(error, fallback)
  resource.errorStatus = statusOf(error)
}

function presentError(error: unknown, fallback: string): string {
  return error instanceof CrewScopeApiError ? error.envelope.message : fallback
}

function statusOf(error: unknown): number | null {
  return error instanceof CrewScopeApiError ? error.status : null
}

function conflict(error: unknown): boolean {
  return error instanceof CrewScopeApiError && (error.status === 409 || error.status === 412)
}

function isAbort(error: unknown): boolean {
  return error instanceof DOMException && error.name === 'AbortError'
}
