import { inject, reactive, readonly, type App, type InjectionKey } from 'vue'
import { CrewScopeApiError } from '../../api/client'
import type { DeliveryGateway } from './gateway'
import type {
  ActionBundle,
  DeliveryCoordinates,
  DeliveryScope,
  EtaggedActionBundle,
  GitHubAuthorizationHealth,
  GitHubConnection,
  GitHubProviderBinding,
  GitHubRemotePreflight,
  GitHubRepository,
  PlanActionBundleInput,
  PlannedAction,
} from './types'

export type DeliveryPhase = 'idle' | 'loading' | 'ready' | 'empty' | 'error'
export type DeliveryOperation = 'plan' | 'confirm' | 'cancel' | 'manual-resolution' | 'catalog-sync'

export interface DeliveryResource<T> {
  phase: DeliveryPhase
  value: T | null
  errorMessage: string | null
  errorStatus: number | null
  errorCode: string | null
}

export interface DeliveryCommandState {
  phase: 'idle' | 'pending' | 'success' | 'error' | 'conflict'
  operation: DeliveryOperation | null
  correlationId: string | null
  errorMessage: string | null
  errorStatus: number | null
  errorCode: string | null
  retryable: boolean
}

export interface DeliveryStoreState {
  connections: DeliveryResource<GitHubConnection[]>
  bindings: Record<string, DeliveryResource<GitHubProviderBinding[]>>
  repositories: Record<string, DeliveryResource<GitHubRepository[]>>
  health: Record<string, DeliveryResource<GitHubAuthorizationHealth>>
  preflight: DeliveryResource<GitHubRemotePreflight>
  bundles: Record<string, DeliveryResource<ActionBundle[]>>
  bundleDetails: Record<string, DeliveryResource<EtaggedActionBundle>>
  selectedConnectionId: string | null
  selectedBindingId: string | null
  selectedRepositoryId: string | null
  selectedTaskId: string | null
  selectedExecutionId: string | null
  selectedBundleId: string | null
  command: DeliveryCommandState
}

export interface DeliveryStore {
  state: Readonly<DeliveryStoreState>
  synchronize(scope: DeliveryScope, coordinates: DeliveryCoordinates): Promise<void>
  loadConnections(force?: boolean): Promise<void>
  selectConnection(connectionId: string, force?: boolean): Promise<void>
  selectBinding(bindingId: string): void
  selectRepository(repositoryId: string): void
  synchronizeCatalog(): Promise<boolean>
  preflightSelected(): Promise<boolean>
  loadBundles(coordinates: DeliveryCoordinates, force?: boolean): Promise<void>
  selectBundle(coordinates: DeliveryCoordinates, bundleId: string, force?: boolean): Promise<void>
  plan(input: PlanActionBundleInput): Promise<boolean>
  confirm(): Promise<boolean>
  cancel(): Promise<boolean>
  resolveFailure(action: PlannedAction, explanation: string): Promise<boolean>
  refresh(): Promise<void>
  retryCommand(): Promise<boolean>
  clearCommand(): void
  clearSelection(): void
  reset(): void
}

export const DELIVERY_STORE: InjectionKey<DeliveryStore> = Symbol('crewscope-delivery-store')

interface ActiveRequest { scopeKey: string, generation: number, controller: AbortController }
interface PendingCommand { generation: number, operation: DeliveryOperation, run: () => Promise<{ correlationId?: string } | GitHubRepository[]> }

/** Scope-isolated state for GitHub selection and the exact ActionBundle confirmation boundary. */
export function createDeliveryStore(gateway: DeliveryGateway): DeliveryStore {
  const state = reactive<DeliveryStoreState>(initialState())
  let activeScope: DeliveryScope | null = null
  let activeScopeKey: string | null = null
  let generation = 0
  const requests = new Map<string, ActiveRequest>()
  let pendingCommand: PendingCommand | null = null

  async function synchronize(scope: DeliveryScope, coordinates: DeliveryCoordinates): Promise<void> {
    activateScope(scope)
    state.selectedTaskId = coordinates.taskId
    state.selectedExecutionId = coordinates.executionId
    await Promise.all([loadConnections(), loadBundles(coordinates)])
    if (!selectionMatches(coordinates)) return
    const connections = state.connections.value ?? []
    const current = connections.find(item => item.id === state.selectedConnectionId)
      ?? connections.find(item => item.ownerType === 'TEAM' && item.status === 'ACTIVE')
      ?? connections.find(item => item.status === 'ACTIVE')
      ?? connections[0]
    if (current) await selectConnection(current.id)
  }

  function activateScope(scope: DeliveryScope): void {
    const next = scopeKey(scope)
    if (next === activeScopeKey) return
    activeScope = { ...scope }
    activeScopeKey = next
    generation += 1
    abortRequests()
    pendingCommand = null
    replaceState(initialState())
  }

  async function loadConnections(force = false): Promise<void> {
    const scope = requireScope()
    if (!force && ['loading', 'ready', 'empty'].includes(state.connections.phase)) return
    const request = beginRequest('connections')
    beginLoad(state.connections)
    try {
      const values = (await Promise.all([
        gateway.listConnections(scope, 'TEAM', request.controller.signal),
        gateway.listConnections(scope, 'USER', request.controller.signal),
      ])).flat()
      if (!isCurrent('connections', request)) return
      state.connections.value = unique(values, item => item.id)
      state.connections.phase = values.length ? 'ready' : 'empty'
    } catch (error) {
      if (!isAbort(error) && isCurrent('connections', request)) setError(state.connections, error, '暂时无法加载 GitHub Connection')
    } finally { finishRequest('connections', request) }
  }

  async function selectConnection(connectionId: string, force = false): Promise<void> {
    const scope = requireScope()
    const connection = state.connections.value?.find(item => item.id === connectionId)
    if (!connection) return
    const changed = state.selectedConnectionId !== connectionId
    state.selectedConnectionId = connectionId
    if (changed) {
      state.selectedBindingId = null
      state.selectedRepositoryId = null
      state.preflight = idleResource()
    }
    state.bindings[connectionId] ??= idleResource<GitHubProviderBinding[]>()
    state.repositories[connectionId] ??= idleResource<GitHubRepository[]>()
    state.health[connectionId] ??= idleResource<GitHubAuthorizationHealth>()
    const bindings = state.bindings[connectionId]!
    const repositories = state.repositories[connectionId]!
    const health = state.health[connectionId]!
    await Promise.all([
      loadConnectionResource(`bindings:${connectionId}`, bindings, force, signal => gateway.listBindings(scope, connectionId, signal), '暂时无法加载 GitHub Binding'),
      loadConnectionResource(`repositories:${connectionId}`, repositories, force, signal => gateway.listRepositories(scope, connectionId, signal), '暂时无法加载 Repository Catalog'),
      loadConnectionResource(`health:${connectionId}`, health, force, signal => gateway.health(scope, connectionId, signal), '暂时无法加载授权健康'),
    ])
    if (state.selectedConnectionId !== connectionId) return
    const availableBindings = bindings.value ?? []
    const selectedBinding = availableBindings.find(item => item.id === state.selectedBindingId)
      ?? availableBindings.find(item => item.defaultUsage && item.status === 'ACTIVE')
      ?? availableBindings.find(item => item.status === 'ACTIVE')
      ?? availableBindings[0]
    state.selectedBindingId = selectedBinding?.id ?? null
    const availableRepositories = repositories.value ?? []
    const selectedRepository = availableRepositories.find(item => item.externalRepositoryId === state.selectedRepositoryId)
      ?? availableRepositories[0]
    state.selectedRepositoryId = selectedRepository?.externalRepositoryId ?? null
  }

  async function loadConnectionResource<T>(
    key: string,
    resource: DeliveryResource<T>,
    force: boolean,
    loader: (signal: AbortSignal) => Promise<T>,
    fallback: string,
  ): Promise<void> {
    if (!force && ['loading', 'ready', 'empty'].includes(resource.phase)) return
    const request = beginRequest(key)
    beginLoad(resource)
    try {
      const value = await loader(request.controller.signal)
      if (!isCurrent(key, request)) return
      resource.value = value
      resource.phase = Array.isArray(value) && !value.length ? 'empty' : 'ready'
    } catch (error) {
      if (!isAbort(error) && isCurrent(key, request)) setError(resource, error, fallback)
    } finally { finishRequest(key, request) }
  }

  function selectBinding(bindingId: string): void {
    state.selectedBindingId = bindingId
    state.preflight = idleResource()
  }

  function selectRepository(repositoryId: string): void {
    state.selectedRepositoryId = repositoryId
    state.preflight = idleResource()
  }

  async function synchronizeCatalog(): Promise<boolean> {
    const connection = selectedConnection()
    if (!connection) return false
    return runCommand({
      generation, operation: 'catalog-sync',
      run: () => gateway.synchronizeRepositories(requireScope(), connection),
    }, result => applyCatalogResult(connection.id, result))
  }

  async function preflightSelected(): Promise<boolean> {
    const scope = requireScope()
    const connection = selectedConnection()
    const bindingId = state.selectedBindingId
    const repositoryId = state.selectedRepositoryId
    if (!connection || !bindingId || !repositoryId) return false
    state.preflight = { ...idleResource(), phase: 'loading' }
    try {
      const value = await gateway.preflight(scope, connection, bindingId, repositoryId)
      if (connection.id !== state.selectedConnectionId || bindingId !== state.selectedBindingId || repositoryId !== state.selectedRepositoryId) return false
      state.preflight = { ...idleResource(), phase: 'ready', value }
      return true
    } catch (error) {
      setError(state.preflight, error, 'GitHub Remote Preflight 未通过')
      if (error instanceof CrewScopeApiError && [409, 412].includes(error.status)) await selectConnection(connection.id, true)
      return false
    }
  }

  async function loadBundles(coordinates: DeliveryCoordinates, force = false): Promise<void> {
    const scope = requireScope()
    const key = attemptKey(coordinates)
    state.bundles[key] ??= idleResource<ActionBundle[]>()
    const resource = state.bundles[key]!
    if (!force && ['loading', 'ready', 'empty'].includes(resource.phase)) return
    const request = beginRequest(`bundles:${key}`)
    beginLoad(resource)
    try {
      const values = await gateway.listBundles(scope, coordinates, request.controller.signal)
      if (!isCurrent(`bundles:${key}`, request) || !selectionMatches(coordinates)) return
      resource.value = values
      resource.phase = values.length ? 'ready' : 'empty'
      const selected = values.find(item => item.id === state.selectedBundleId) ?? values[0]
      state.selectedBundleId = selected?.id ?? null
      if (selected) await selectBundle(coordinates, selected.id, force)
    } catch (error) {
      if (!isAbort(error) && isCurrent(`bundles:${key}`, request)) setError(resource, error, '暂时无法加载 ActionBundle')
    } finally { finishRequest(`bundles:${key}`, request) }
  }

  async function selectBundle(coordinates: DeliveryCoordinates, bundleId: string, force = false): Promise<void> {
    if (!selectionMatches(coordinates)) return
    state.selectedBundleId = bundleId
    const key = bundleKey(coordinates, bundleId)
    state.bundleDetails[key] ??= idleResource<EtaggedActionBundle>()
    const resource = state.bundleDetails[key]!
    if (!force && ['loading', 'ready'].includes(resource.phase)) return
    const request = beginRequest(`bundle:${key}`)
    beginLoad(resource)
    try {
      const value = await gateway.getBundle(requireScope(), coordinates, bundleId, request.controller.signal)
      if (!isCurrent(`bundle:${key}`, request) || !selectionMatches(coordinates) || state.selectedBundleId !== bundleId) return
      if (value.value.id !== bundleId || value.value.taskId !== coordinates.taskId || value.value.taskExecutionId !== coordinates.executionId) {
        throw new Error('ActionBundle response identity mismatch')
      }
      resource.value = value
      resource.phase = 'ready'
    } catch (error) {
      if (!isAbort(error) && isCurrent(`bundle:${key}`, request)) setError(resource, error, '暂时无法加载 ActionBundle 详情')
    } finally { finishRequest(`bundle:${key}`, request) }
  }

  async function plan(input: PlanActionBundleInput): Promise<boolean> {
    const context = commandCoordinates()
    if (!context || state.preflight.phase !== 'ready') return false
    const key = crypto.randomUUID()
    return runCommand({ generation, operation: 'plan', run: () => gateway.plan(requireScope(), context, input, key) }, refresh)
  }

  async function confirm(): Promise<boolean> {
    const context = commandCoordinates()
    const bundle = selectedBundle()
    if (!context || !bundle || bundle.value.validity !== 'CURRENT' || bundle.value.confirmation) return false
    const key = crypto.randomUUID()
    return runCommand({ generation, operation: 'confirm', run: () => gateway.confirm(requireScope(), context, bundle, key) }, refresh)
  }

  async function cancel(): Promise<boolean> {
    const context = commandCoordinates()
    const bundle = selectedBundle()
    if (!context || !bundle?.value.confirmation || bundle.value.confirmation.status !== 'ACTIVE') return false
    const key = crypto.randomUUID()
    return runCommand({ generation, operation: 'cancel', run: () => gateway.cancel(requireScope(), context, bundle, 'MEMBER_CANCELLED', key) }, refresh)
  }

  async function resolveFailure(action: PlannedAction, explanation: string): Promise<boolean> {
    const context = commandCoordinates()
    if (!context || action.dispatch?.status !== 'MANUAL_REVIEW' || explanation.trim().length < 10) return false
    const key = crypto.randomUUID()
    return runCommand({
      generation, operation: 'manual-resolution',
      run: () => gateway.resolveFailure(requireScope(), context, action.dispatch!.id, action.dispatch!.version, explanation.trim(), key),
    }, refresh)
  }

  async function runCommand(command: PendingCommand, onSuccess?: (result: { correlationId?: string } | GitHubRepository[]) => Promise<void> | void): Promise<boolean> {
    if (state.command.phase === 'pending') return false
    pendingCommand = command
    state.command = { ...idleCommand(), phase: 'pending', operation: command.operation }
    try {
      const result = await command.run()
      if (command.generation !== generation || pendingCommand !== command) return false
      state.command.phase = 'success'
      state.command.correlationId = Array.isArray(result) ? null : result.correlationId ?? null
      pendingCommand = null
      await onSuccess?.(result)
      return true
    } catch (error) {
      if (command.generation !== generation || pendingCommand !== command) return false
      const api = error instanceof CrewScopeApiError ? error : null
      const conflict = api?.status === 409 || api?.status === 412
      state.command.phase = conflict ? 'conflict' : 'error'
      state.command.errorMessage = api?.envelope.message ?? '交付命令执行失败'
      state.command.errorStatus = api?.status ?? null
      state.command.errorCode = api?.envelope.code ?? null
      state.command.retryable = Boolean(api?.envelope.retryable) && !conflict
      if (!state.command.retryable) pendingCommand = null
      if (conflict) await refresh()
      return false
    }
  }

  async function retryCommand(): Promise<boolean> {
    const command = pendingCommand
    if (!command || !state.command.retryable || command.generation !== generation) return false
    state.command = idleCommand()
    return runCommand(command, command.operation === 'catalog-sync'
      ? result => applyCatalogResult(state.selectedConnectionId, result)
      : refresh)
  }

  function applyCatalogResult(connectionId: string | null, result: { correlationId?: string } | GitHubRepository[]): void {
    if (!connectionId || !Array.isArray(result)) return
    const resource = state.repositories[connectionId] ??= idleResource()
    resource.value = result
    resource.phase = result.length ? 'ready' : 'empty'
    if (!result.some(item => item.externalRepositoryId === state.selectedRepositoryId)) {
      state.selectedRepositoryId = result[0]?.externalRepositoryId ?? null
    }
    state.preflight = idleResource()
  }

  async function refresh(): Promise<void> {
    const coordinates = commandCoordinates()
    if (!coordinates) return
    await loadBundles(coordinates, true)
  }

  function selectedConnection(): GitHubConnection | null {
    return state.connections.value?.find(item => item.id === state.selectedConnectionId) ?? null
  }

  function selectedBundle(): EtaggedActionBundle | null {
    const coordinates = commandCoordinates()
    return coordinates && state.selectedBundleId
      ? state.bundleDetails[bundleKey(coordinates, state.selectedBundleId)]?.value ?? null
      : null
  }

  function commandCoordinates(): DeliveryCoordinates | null {
    return state.selectedTaskId && state.selectedExecutionId
      ? { taskId: state.selectedTaskId, executionId: state.selectedExecutionId }
      : null
  }

  function clearCommand(): void {
    if (state.command.phase === 'pending') return
    pendingCommand = null
    state.command = idleCommand()
  }

  function clearSelection(): void {
    generation += 1
    abortRequests()
    pendingCommand = null
    state.selectedTaskId = null
    state.selectedExecutionId = null
    state.selectedBundleId = null
    state.command = idleCommand()
  }

  function reset(): void {
    generation += 1
    abortRequests()
    pendingCommand = null
    activeScope = null
    activeScopeKey = null
    replaceState(initialState())
  }

  function selectionMatches(value: DeliveryCoordinates): boolean {
    return state.selectedTaskId === value.taskId && state.selectedExecutionId === value.executionId
  }

  function requireScope(): DeliveryScope {
    if (!activeScope) throw new Error('Delivery scope is not active')
    return { ...activeScope }
  }

  function beginRequest(key: string): ActiveRequest {
    requests.get(key)?.controller.abort()
    const request = { scopeKey: activeScopeKey!, generation, controller: new AbortController() }
    requests.set(key, request)
    return request
  }

  function isCurrent(key: string, request: ActiveRequest): boolean {
    return requests.get(key) === request && request.scopeKey === activeScopeKey && request.generation === generation
  }

  function finishRequest(key: string, request: ActiveRequest): void {
    if (requests.get(key) === request) requests.delete(key)
  }

  function abortRequests(): void { requests.forEach(item => item.controller.abort()); requests.clear() }
  function replaceState(value: DeliveryStoreState): void { Object.assign(state, value) }

  return {
    state: readonly(state) as Readonly<DeliveryStoreState>, synchronize, loadConnections, selectConnection,
    selectBinding, selectRepository, synchronizeCatalog, preflightSelected, loadBundles, selectBundle,
    plan, confirm, cancel, resolveFailure, refresh, retryCommand, clearCommand, clearSelection, reset,
  }
}

export function deliveryAttemptKey(value: DeliveryCoordinates): string { return attemptKey(value) }
export function deliveryBundleKey(value: DeliveryCoordinates, bundleId: string): string { return bundleKey(value, bundleId) }
function attemptKey(value: DeliveryCoordinates): string { return `${value.taskId}:${value.executionId}` }
function bundleKey(value: DeliveryCoordinates, bundleId: string): string { return `${attemptKey(value)}:${bundleId}` }
function scopeKey(value: DeliveryScope): string { return `${value.organizationId}:${value.teamId}` }

function initialState(): DeliveryStoreState {
  return {
    connections: idleResource(), bindings: {}, repositories: {}, health: {}, preflight: idleResource(),
    bundles: {}, bundleDetails: {}, selectedConnectionId: null, selectedBindingId: null,
    selectedRepositoryId: null, selectedTaskId: null, selectedExecutionId: null,
    selectedBundleId: null, command: idleCommand(),
  }
}

function idleResource<T>(): DeliveryResource<T> {
  return { phase: 'idle', value: null, errorMessage: null, errorStatus: null, errorCode: null }
}

function idleCommand(): DeliveryCommandState {
  return { phase: 'idle', operation: null, correlationId: null, errorMessage: null, errorStatus: null, errorCode: null, retryable: false }
}

function beginLoad(resource: DeliveryResource<unknown>): void {
  resource.phase = 'loading'; resource.errorMessage = null; resource.errorStatus = null; resource.errorCode = null
}

function setError(resource: DeliveryResource<unknown>, error: unknown, fallback: string): void {
  const api = error instanceof CrewScopeApiError ? error : null
  resource.phase = 'error'; resource.errorMessage = api?.envelope.message ?? fallback
  resource.errorStatus = api?.status ?? null; resource.errorCode = api?.envelope.code ?? null
}

function unique<T>(values: T[], key: (value: T) => string): T[] {
  return [...new Map(values.map(value => [key(value), value])).values()]
}

function isAbort(error: unknown): boolean { return error instanceof DOMException && error.name === 'AbortError' }

export function installDeliveryStore(app: App, gateway: DeliveryGateway): DeliveryStore {
  const store = createDeliveryStore(gateway)
  app.provide(DELIVERY_STORE, store)
  return store
}

export function useDeliveryStore(): DeliveryStore {
  const store = inject(DELIVERY_STORE)
  if (!store) throw new Error('DeliveryStore has not been installed')
  return store
}
