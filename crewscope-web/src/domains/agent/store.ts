import { inject, reactive, readonly, type App, type InjectionKey } from 'vue'
import { CrewScopeApiError } from '../../api/client'
import type { Etagged, OffsetPage, SettingsScope } from '../settings/types'
import type { AgentGateway } from './gateway'
import type {
  AgentCommandReceipt,
  AgentConfigurationHistoryItem,
  AgentConfigurationInput,
  AgentExecutionScope,
  AgentLifecycleTransition,
  AgentModelPreflight,
  AgentOwnershipType,
  AgentSummary,
  AgentTemplateSummary,
  ConversationAgentConfiguration,
  CreateAgentInput,
  CurrentAgentConfiguration,
  SelectableAgentModel,
} from './types'

export type AgentResourcePhase = 'idle' | 'loading' | 'ready' | 'empty' | 'error'

export interface AgentResource<T> {
  phase: AgentResourcePhase
  value: T | null
  errorMessage: string | null
  errorStatus: number | null
}

export interface AgentPageResource<T> extends AgentResource<T[]> {
  nextOffset: number | null
  loadingMore: boolean
}

export interface AgentCommandState {
  phase: 'idle' | 'pending' | 'success' | 'error' | 'conflict'
  operation: 'create' | AgentLifecycleTransition | 'configure' | 'refresh-conversation' | null
  resourceId: string | null
  receipt: AgentCommandReceipt | null
  errorMessage: string | null
  errorStatus: number | null
  retryable: boolean
}

export interface AgentStoreState {
  templates: Record<string, AgentPageResource<AgentTemplateSummary>>
  agents: AgentPageResource<AgentSummary>
  agentDetails: Record<string, AgentResource<Etagged<AgentSummary>>>
  configurationHistory: Record<string, AgentPageResource<AgentConfigurationHistoryItem>>
  currentConfigurations: Record<string, AgentResource<Etagged<CurrentAgentConfiguration>>>
  selectableModels: Record<string, AgentResource<SelectableAgentModel[]>>
  preflights: Record<string, AgentResource<AgentModelPreflight>>
  conversationConfigurations: Record<string, AgentResource<Etagged<ConversationAgentConfiguration>>>
  command: AgentCommandState
}

export interface AgentStore {
  state: Readonly<AgentStoreState>
  activateScope(scope: SettingsScope): void
  loadTemplates(ownershipType: AgentOwnershipType, more?: boolean, force?: boolean): Promise<void>
  loadAgents(more?: boolean, force?: boolean): Promise<void>
  loadAgent(profileId: string, force?: boolean): Promise<void>
  loadConfigurationHistory(profileId: string, more?: boolean, force?: boolean): Promise<void>
  loadCurrentConfiguration(profileId: string, force?: boolean): Promise<void>
  loadSelectableModels(profileId: string, executionScope: AgentExecutionScope, force?: boolean): Promise<void>
  loadPreflight(profileId: string, executionScope: AgentExecutionScope, force?: boolean): Promise<void>
  loadConversationConfiguration(conversationId: string, force?: boolean): Promise<void>
  createAgent(input: CreateAgentInput, idempotencyKey: string): Promise<boolean>
  transitionAgent(profileId: string, transition: AgentLifecycleTransition, idempotencyKey: string): Promise<boolean>
  appendConfiguration(profileId: string, input: AgentConfigurationInput, etag: string, idempotencyKey: string): Promise<boolean>
  refreshConversationConfiguration(conversationId: string, idempotencyKey: string): Promise<boolean>
  invalidateAgent(profileId?: string): void
  clearCommand(): void
  reset(): void
}

export const AGENT_STORE: InjectionKey<AgentStore> = Symbol('crewscope-agent-store')

const PAGE_SIZE = 50

interface AgentRequest {
  key: string
  version: number
  controller: AbortController
  scopeKey: string | null
}

/** Team-scoped Agent cache with generation checks in addition to AbortSignal cancellation. */
export function createAgentStore(gateway: AgentGateway): AgentStore {
  const state = reactive<AgentStoreState>(initialState())
  let activeScope: SettingsScope | null = null
  let activeScopeKey: string | null = null
  let generation = 0
  const requests = new Map<string, AgentRequest>()

  function activateScope(scope: SettingsScope): void {
    const nextKey = scopeKey(scope)
    if (nextKey === activeScopeKey) return
    activeScope = { ...scope }
    activeScopeKey = nextKey
    generation += 1
    abortRequests()
    replaceState(initialState())
  }

  async function loadTemplates(ownershipType: AgentOwnershipType, more = false, force = false): Promise<void> {
    const scope = requireScope()
    if (!state.templates[ownershipType]) state.templates[ownershipType] = pageResource<AgentTemplateSummary>()
    // Re-read through the reactive Record so async mutations notify page consumers.
    const resource = state.templates[ownershipType]!
    await loadPage(
      `templates:${ownershipType}`,
      resource,
      more,
      force,
      (offset, signal) => gateway.listTemplates(scope, ownershipType, offset, PAGE_SIZE, signal),
      item => `${item.publisherType}:${item.publisherId}:${item.key}:${item.version}`,
      page => page.items.forEach(item => assertTemplateScope(item, scope)),
    )
  }

  async function loadAgents(more = false, force = false): Promise<void> {
    const scope = requireScope()
    await loadPage(
      'agents',
      state.agents,
      more,
      force,
      (offset, signal) => gateway.listAgents(scope, offset, PAGE_SIZE, signal),
      item => item.id,
      page => page.items.forEach(item => assertAgentScope(item, scope)),
    )
  }

  async function loadAgent(profileId: string, force = false): Promise<void> {
    const scope = requireScope()
    await loadResource(
      `agent:${profileId}`,
      state.agentDetails,
      profileId,
      force,
      signal => gateway.getAgent(scope, profileId, signal),
      value => assertAgentScope(value.value, scope),
      '暂时无法加载 Agent 详情',
    )
  }

  async function loadConfigurationHistory(profileId: string, more = false, force = false): Promise<void> {
    const scope = requireScope()
    if (!state.configurationHistory[profileId]) state.configurationHistory[profileId] = pageResource<AgentConfigurationHistoryItem>()
    const resource = state.configurationHistory[profileId]!
    await loadPage(
      `configuration-history:${profileId}`,
      resource,
      more,
      force,
      (offset, signal) => gateway.listConfigurations(scope, profileId, offset, PAGE_SIZE, signal),
      item => String(item.revision),
      page => assertConfigurationSequence(page.items),
    )
  }

  async function loadCurrentConfiguration(profileId: string, force = false): Promise<void> {
    const scope = requireScope()
    await loadResource(
      `current-configuration:${profileId}`,
      state.currentConfigurations,
      profileId,
      force,
      signal => gateway.getCurrentConfiguration(scope, profileId, signal),
      value => assertConfigurationRevision(value.value, value.etag),
      '暂时无法加载 Agent 当前配置',
    )
  }

  async function loadSelectableModels(
    profileId: string,
    executionScope: AgentExecutionScope,
    force = false,
  ): Promise<void> {
    const scope = requireScope()
    const key = `${profileId}:${executionScope}`
    await loadResource(
      `selectable-models:${key}`,
      state.selectableModels,
      key,
      force,
      signal => gateway.listSelectableModels(scope, profileId, executionScope, signal),
      undefined,
      '暂时无法加载可选模型',
    )
  }

  async function loadPreflight(
    profileId: string,
    executionScope: AgentExecutionScope,
    force = false,
  ): Promise<void> {
    const scope = requireScope()
    const key = `${profileId}:${executionScope}`
    await loadResource(
      `preflight:${key}`,
      state.preflights,
      key,
      force,
      signal => gateway.preflight(scope, profileId, executionScope, signal),
      value => {
        if (value.agentProfileId !== profileId || value.executionScope !== executionScope) {
          throw new Error('Agent Preflight coordinates do not match the request')
        }
      },
      '暂时无法完成模型预检',
    )
  }

  async function loadConversationConfiguration(conversationId: string, force = false): Promise<void> {
    const scope = requireScope()
    await loadResource(
      `conversation-configuration:${conversationId}`,
      state.conversationConfigurations,
      conversationId,
      force,
      signal => gateway.getConversationConfiguration(scope, conversationId, signal),
      value => assertSessionVersion(value.value, value.etag),
      '暂时无法加载会话配置',
    )
  }

  async function createAgent(input: CreateAgentInput, idempotencyKey: string): Promise<boolean> {
    const scope = requireScope()
    return runCommand(
      'create',
      null,
      () => gateway.createAgent(scope, input, idempotencyKey),
      () => { state.agents = pageResource<AgentSummary>() },
    )
  }

  async function transitionAgent(
    profileId: string,
    transition: AgentLifecycleTransition,
    idempotencyKey: string,
  ): Promise<boolean> {
    const scope = requireScope()
    if (state.agentDetails[profileId]?.phase !== 'ready') await loadAgent(profileId)
    const detail = state.agentDetails[profileId]?.value
    if (!detail) return false
    return runCommand(
      transition,
      profileId,
      () => gateway.transitionAgent(scope, profileId, transition, detail.etag, idempotencyKey),
      () => invalidateAgentFacts(profileId),
    )
  }

  async function appendConfiguration(
    profileId: string,
    input: AgentConfigurationInput,
    etag: string,
    idempotencyKey: string,
  ): Promise<boolean> {
    const scope = requireScope()
    return runCommand(
      'configure',
      profileId,
      () => gateway.appendConfiguration(scope, profileId, input, etag, idempotencyKey),
      () => {
        // Keep the directory mounted until the editor emits its refresh event; unmounting here
        // would discard the success continuation before it can reload authoritative Profile facts.
        invalidateAgentFacts(profileId)
      },
    )
  }

  async function refreshConversationConfiguration(
    conversationId: string,
    idempotencyKey: string,
  ): Promise<boolean> {
    const scope = requireScope()
    if (state.conversationConfigurations[conversationId]?.phase !== 'ready') {
      await loadConversationConfiguration(conversationId)
    }
    const detail = state.conversationConfigurations[conversationId]?.value
    if (!detail) return false
    return runCommand(
      'refresh-conversation',
      conversationId,
      () => gateway.refreshConversationConfiguration(scope, conversationId, detail.etag, idempotencyKey),
      () => { delete state.conversationConfigurations[conversationId] },
    )
  }

  async function runCommand(
    operation: NonNullable<AgentCommandState['operation']>,
    resourceId: string | null,
    action: () => Promise<AgentCommandReceipt>,
    onSuccess?: () => void,
  ): Promise<boolean> {
    const commandGeneration = generation
    state.command = {
      phase: 'pending', operation, resourceId, receipt: null,
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
      state.command.errorMessage = presentError(error, 'Agent 设置命令执行失败')
      state.command.errorStatus = statusOf(error)
      state.command.retryable = error instanceof CrewScopeApiError && error.envelope.retryable
      return false
    }
  }

  async function loadPage<T>(
    requestKey: string,
    resource: AgentPageResource<T>,
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
      if (!isAbort(error) && isCurrent(request)) setError(resource, error, '暂时无法加载 Agent 设置')
    } finally {
      if (isCurrent(request)) resource.loadingMore = false
      finishRequest(request)
    }
  }

  async function loadResource<T>(
    requestKey: string,
    target: Record<string, AgentResource<T>>,
    resourceKey: string,
    force: boolean,
    load: (signal: AbortSignal) => Promise<T>,
    validate: ((value: T) => void) | undefined,
    fallback: string,
  ): Promise<void> {
    const existing = target[resourceKey]
    if (!force && existing?.phase === 'ready') return
    if (!existing) target[resourceKey] = resourceState<T>()
    // Vue wraps Record entries on read; mutating the original raw object after await would not trigger rendering.
    const resource = target[resourceKey]!
    const request = beginRequest(requestKey)
    resource.phase = 'loading'
    resource.errorMessage = null
    resource.errorStatus = null
    try {
      const value = await load(request.controller.signal)
      if (!isCurrent(request)) return
      validate?.(value)
      resource.value = value
      resource.phase = Array.isArray(value) && value.length === 0 ? 'empty' : 'ready'
    } catch (error) {
      if (!isAbort(error) && isCurrent(request)) setError(resource, error, fallback)
    } finally {
      finishRequest(request)
    }
  }

  function invalidateAgent(profileId?: string): void {
    state.agents = pageResource<AgentSummary>()
    if (!profileId) {
      state.agentDetails = {}
      state.configurationHistory = {}
      state.currentConfigurations = {}
      state.selectableModels = {}
      state.preflights = {}
      return
    }
    invalidateAgentFacts(profileId)
  }

  function invalidateAgentFacts(profileId: string): void {
    delete state.agentDetails[profileId]
    delete state.configurationHistory[profileId]
    delete state.currentConfigurations[profileId]
    deleteScopedResources(profileId)
  }

  function deleteScopedResources(profileId: string): void {
    for (const key of Object.keys(state.selectableModels)) if (key.startsWith(`${profileId}:`)) delete state.selectableModels[key]
    for (const key of Object.keys(state.preflights)) if (key.startsWith(`${profileId}:`)) delete state.preflights[key]
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
    if (!activeScope) throw new Error('Agent Store Scope is not active')
    return { ...activeScope }
  }

  function beginRequest(key: string): AgentRequest {
    requests.get(key)?.controller.abort()
    const request: AgentRequest = {
      key,
      version: generation,
      controller: new AbortController(),
      scopeKey: activeScopeKey,
    }
    requests.set(key, request)
    return request
  }

  function isCurrent(request: AgentRequest): boolean {
    return generation === request.version
      && activeScopeKey === request.scopeKey
      && requests.get(request.key) === request
  }

  function finishRequest(request: AgentRequest): void {
    if (requests.get(request.key) === request) requests.delete(request.key)
  }

  function abortRequests(): void {
    for (const request of requests.values()) request.controller.abort()
    requests.clear()
  }

  function replaceState(next: AgentStoreState): void {
    state.templates = next.templates
    state.agents = next.agents
    state.agentDetails = next.agentDetails
    state.configurationHistory = next.configurationHistory
    state.currentConfigurations = next.currentConfigurations
    state.selectableModels = next.selectableModels
    state.preflights = next.preflights
    state.conversationConfigurations = next.conversationConfigurations
    state.command = next.command
  }

  return {
    state: readonly(state) as Readonly<AgentStoreState>,
    activateScope,
    loadTemplates,
    loadAgents,
    loadAgent,
    loadConfigurationHistory,
    loadCurrentConfiguration,
    loadSelectableModels,
    loadPreflight,
    loadConversationConfiguration,
    createAgent,
    transitionAgent,
    appendConfiguration,
    refreshConversationConfiguration,
    invalidateAgent,
    clearCommand,
    reset,
  }
}

export function installAgentStore(app: App, gateway: AgentGateway): AgentStore {
  const store = createAgentStore(gateway)
  app.provide(AGENT_STORE, store)
  return store
}

export function useAgentStore(): AgentStore {
  const store = inject(AGENT_STORE)
  if (!store) throw new Error('CrewScope Agent Store is not installed')
  return store
}

function initialState(): AgentStoreState {
  return {
    templates: {},
    agents: pageResource<AgentSummary>(),
    agentDetails: {},
    configurationHistory: {},
    currentConfigurations: {},
    selectableModels: {},
    preflights: {},
    conversationConfigurations: {},
    command: commandState(),
  }
}

function resourceState<T>(): AgentResource<T> {
  return { phase: 'idle', value: null, errorMessage: null, errorStatus: null }
}

function pageResource<T>(): AgentPageResource<T> {
  return { ...resourceState<T[]>(), nextOffset: 0, loadingMore: false }
}

function commandState(): AgentCommandState {
  return {
    phase: 'idle', operation: null, resourceId: null, receipt: null,
    errorMessage: null, errorStatus: null, retryable: false,
  }
}

function assertTemplateScope(value: AgentTemplateSummary, scope: SettingsScope): void {
  const expected = value.publisherType === 'TEAM' ? scope.teamId : scope.organizationId
  if ((value.publisherType !== 'TEAM' && value.publisherType !== 'ORGANIZATION') || value.publisherId !== expected) {
    throw new Error('Agent Template is outside the active Scope')
  }
}

function assertAgentScope(value: AgentSummary, scope: SettingsScope): void {
  if (value.organizationId !== scope.organizationId || value.teamId !== scope.teamId) {
    throw new Error('Agent is outside the active Scope')
  }
}

function assertConfigurationSequence(items: AgentConfigurationHistoryItem[]): void {
  for (const value of items) {
    if (value.revision < 1 || (value.previousRevision !== null && value.previousRevision !== value.revision - 1)) {
      throw new Error('Agent Configuration history is not consecutive')
    }
  }
}

function assertConfigurationRevision(value: CurrentAgentConfiguration, etag: string): void {
  if (etag !== `"${value.revision}"`) throw new Error('Agent Configuration ETag does not match its Revision')
}

function assertSessionVersion(value: ConversationAgentConfiguration, etag: string): void {
  if (etag !== `"${value.runtimeSessionVersion}"`) {
    throw new Error('Conversation Configuration ETag does not match its Session Version')
  }
}

function merge<T>(existing: T[], incoming: T[], identity: (value: T) => string): T[] {
  const known = new Set(existing.map(identity))
  return [...existing, ...incoming.filter(value => !known.has(identity(value)))]
}

function scopeKey(scope: SettingsScope): string {
  return `${scope.organizationId}:${scope.teamId}`
}

function setError(resource: AgentResource<unknown>, error: unknown, fallback: string): void {
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
