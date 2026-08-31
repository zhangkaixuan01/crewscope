import { apiClient, type CrewScopeApiClient } from '../../api/client'
import type { Etagged, OffsetPage, SettingsScope } from '../settings/types'
import type {
  AgentCommandReceipt,
  AgentConfigurationHistoryItem,
  AgentConfigurationInput,
  AgentExecutionScope,
  AgentLifecycleTransition,
  AgentModelBindingSummary,
  AgentModelPreflight,
  AgentModelSelectionSummary,
  AgentOwnershipType,
  AgentSummary,
  AgentTemplateSummary,
  ConversationAgentConfiguration,
  CreateAgentInput,
  CurrentAgentConfiguration,
  ResolvedModelSelectionSummary,
  SelectableAgentModel,
  SelectableModelPrice,
} from './types'

export interface AgentGateway {
  listTemplates(scope: SettingsScope, ownershipType: AgentOwnershipType, offset?: number, limit?: number, signal?: AbortSignal): Promise<OffsetPage<AgentTemplateSummary>>
  listAgents(scope: SettingsScope, offset?: number, limit?: number, signal?: AbortSignal): Promise<OffsetPage<AgentSummary>>
  getAgent(scope: SettingsScope, profileId: string, signal?: AbortSignal): Promise<Etagged<AgentSummary>>
  listConfigurations(scope: SettingsScope, profileId: string, offset?: number, limit?: number, signal?: AbortSignal): Promise<OffsetPage<AgentConfigurationHistoryItem>>
  getCurrentConfiguration(scope: SettingsScope, profileId: string, signal?: AbortSignal): Promise<Etagged<CurrentAgentConfiguration>>
  listSelectableModels(scope: SettingsScope, profileId: string, executionScope: AgentExecutionScope, signal?: AbortSignal): Promise<SelectableAgentModel[]>
  preflight(scope: SettingsScope, profileId: string, executionScope: AgentExecutionScope, signal?: AbortSignal): Promise<AgentModelPreflight>
  getConversationConfiguration(scope: SettingsScope, conversationId: string, signal?: AbortSignal): Promise<Etagged<ConversationAgentConfiguration>>
  createAgent(scope: SettingsScope, input: CreateAgentInput, idempotencyKey: string): Promise<AgentCommandReceipt>
  transitionAgent(scope: SettingsScope, profileId: string, transition: AgentLifecycleTransition, etag: string, idempotencyKey: string): Promise<AgentCommandReceipt>
  appendConfiguration(scope: SettingsScope, profileId: string, input: AgentConfigurationInput, etag: string, idempotencyKey: string): Promise<AgentCommandReceipt>
  refreshConversationConfiguration(scope: SettingsScope, conversationId: string, etag: string, idempotencyKey: string): Promise<AgentCommandReceipt>
}

/** A02-A03 HTTP adapter that admits only public Agent and Configuration fields. */
export class HttpAgentGateway implements AgentGateway {
  constructor(private readonly client: CrewScopeApiClient = apiClient) {}

  async listTemplates(
    scope: SettingsScope,
    ownershipType: AgentOwnershipType,
    offset = 0,
    limit = 50,
    signal?: AbortSignal,
  ): Promise<OffsetPage<AgentTemplateSummary>> {
    const search = offsetSearch(offset, limit)
    search.set('ownershipType', ownershipType)
    const value = await this.client.get<{ items: AgentTemplateSummary[] }>(
      `${teamRoot(scope)}/agent-templates?${search}`,
      { signal },
    )
    return offsetPage(value.items.map(mapTemplate), offset, limit)
  }

  async listAgents(
    scope: SettingsScope,
    offset = 0,
    limit = 50,
    signal?: AbortSignal,
  ): Promise<OffsetPage<AgentSummary>> {
    const value = await this.client.get<{ items: AgentSummary[] }>(
      `${agentRoot(scope)}?${offsetSearch(offset, limit)}`,
      { signal },
    )
    return offsetPage(value.items.map(mapAgent), offset, limit)
  }

  async getAgent(
    scope: SettingsScope,
    profileId: string,
    signal?: AbortSignal,
  ): Promise<Etagged<AgentSummary>> {
    const response = await this.client.open(`${agentRoot(scope)}/${segment(profileId)}`, { method: 'GET', signal })
    return { value: mapAgent(await response.json() as AgentSummary), etag: requireStrongEtag(response, 'Agent') }
  }

  async listConfigurations(
    scope: SettingsScope,
    profileId: string,
    offset = 0,
    limit = 50,
    signal?: AbortSignal,
  ): Promise<OffsetPage<AgentConfigurationHistoryItem>> {
    const value = await this.client.get<{ items: AgentConfigurationHistoryItem[] }>(
      `${agentRoot(scope)}/${segment(profileId)}/configurations?${offsetSearch(offset, limit)}`,
      { signal },
    )
    return offsetPage(value.items.map(mapConfigurationHistory), offset, limit)
  }

  async getCurrentConfiguration(
    scope: SettingsScope,
    profileId: string,
    signal?: AbortSignal,
  ): Promise<Etagged<CurrentAgentConfiguration>> {
    const response = await this.client.open(
      `${profileRoot(scope, profileId)}/configurations/current`,
      { method: 'GET', signal },
    )
    return {
      value: mapCurrentConfiguration(await response.json() as CurrentAgentConfiguration),
      etag: requireStrongEtag(response, 'Agent Configuration'),
    }
  }

  async listSelectableModels(
    scope: SettingsScope,
    profileId: string,
    executionScope: AgentExecutionScope,
    signal?: AbortSignal,
  ): Promise<SelectableAgentModel[]> {
    const value = await this.client.get<{ items: SelectableAgentModel[] }>(
      `${profileRoot(scope, profileId)}/model-catalog?executionScope=${executionScope}`,
      { signal },
    )
    return value.items.map(mapSelectableModel)
  }

  async preflight(
    scope: SettingsScope,
    profileId: string,
    executionScope: AgentExecutionScope,
    signal?: AbortSignal,
  ): Promise<AgentModelPreflight> {
    const value = await this.client.post<AgentModelPreflight>(
      `${profileRoot(scope, profileId)}/model-preflight`,
      { executionScope },
      { signal },
    )
    return mapPreflight(value)
  }

  async getConversationConfiguration(
    scope: SettingsScope,
    conversationId: string,
    signal?: AbortSignal,
  ): Promise<Etagged<ConversationAgentConfiguration>> {
    const response = await this.client.open(
      `${conversationRoot(scope)}/${segment(conversationId)}/agent-configuration`,
      { method: 'GET', signal },
    )
    return {
      value: mapConversationConfiguration(await response.json() as ConversationAgentConfiguration),
      etag: requireStrongEtag(response, 'Conversation Configuration'),
    }
  }

  async createAgent(
    scope: SettingsScope,
    input: CreateAgentInput,
    idempotencyKey: string,
  ): Promise<AgentCommandReceipt> {
    const value = await this.client.post<AgentCommandReceipt>(agentRoot(scope), input, { idempotencyKey })
    return mapReceipt(value)
  }

  async transitionAgent(
    scope: SettingsScope,
    profileId: string,
    transition: AgentLifecycleTransition,
    etag: string,
    idempotencyKey: string,
  ): Promise<AgentCommandReceipt> {
    const value = await this.client.post<AgentCommandReceipt>(
      `${agentRoot(scope)}/${segment(profileId)}/${transition}`,
      undefined,
      { expectedVersion: etagVersion(etag), idempotencyKey },
    )
    return mapReceipt(value)
  }

  async appendConfiguration(
    scope: SettingsScope,
    profileId: string,
    input: AgentConfigurationInput,
    etag: string,
    idempotencyKey: string,
  ): Promise<AgentCommandReceipt> {
    const value = await this.client.post<AgentCommandReceipt>(
      `${profileRoot(scope, profileId)}/configurations`,
      configurationBody(input),
      { expectedVersion: etagVersion(etag), idempotencyKey },
    )
    return mapReceipt(value)
  }

  async refreshConversationConfiguration(
    scope: SettingsScope,
    conversationId: string,
    etag: string,
    idempotencyKey: string,
  ): Promise<AgentCommandReceipt> {
    const value = await this.client.post<AgentCommandReceipt>(
      `${conversationRoot(scope)}/${segment(conversationId)}/agent-configuration-refresh`,
      undefined,
      { expectedVersion: etagVersion(etag), idempotencyKey },
    )
    return mapReceipt(value)
  }
}

function teamRoot(scope: SettingsScope): string {
  return `/organizations/${segment(scope.organizationId)}/teams/${segment(scope.teamId)}`
}

function agentRoot(scope: SettingsScope): string {
  return `${teamRoot(scope)}/agent-profiles`
}

function profileRoot(scope: SettingsScope, profileId: string): string {
  return `${agentRoot(scope)}/${segment(profileId)}`
}

function conversationRoot(scope: SettingsScope): string {
  return `${teamRoot(scope)}/conversations`
}

function offsetSearch(offset: number, limit: number): URLSearchParams {
  return new URLSearchParams({ offset: String(offset), limit: String(limit) })
}

function offsetPage<T>(items: T[], offset: number, limit: number): OffsetPage<T> {
  return { items, nextOffset: items.length === limit ? offset + items.length : null }
}

function segment(value: string): string {
  return encodeURIComponent(value)
}

function mapTemplate(value: AgentTemplateSummary): AgentTemplateSummary {
  return {
    ...pick(value, [
      'publisherType', 'publisherId', 'key', 'version', 'runtimeRole', 'contentHash', 'status',
      'lifecycleVersion', 'creatable', 'platformManaged',
    ]),
    allowedOwnershipTypes: [...value.allowedOwnershipTypes],
    allowedExecutionScopes: [...value.allowedExecutionScopes],
    declaredCapabilities: [...value.declaredCapabilities],
    requiredModelCapabilities: [...value.requiredModelCapabilities],
    approvedSkillKeys: [...value.approvedSkillKeys],
    memberConfigurableSlots: [...value.memberConfigurableSlots],
    administratorConfigurableSlots: [...value.administratorConfigurableSlots],
  }
}

function mapAgent(value: AgentSummary): AgentSummary {
  return {
    ...pick(value, [
      'id', 'principalId', 'displayName', 'principalStatus', 'organizationId', 'teamId',
      'workspaceId', 'ownerMemberId', 'runtimeRole', 'templateKey', 'templateVersion',
      'defaultProfile', 'status', 'currentConfigurationRevision', 'currentConfigurationHash',
      'createdAt', 'updatedAt', 'version',
    ]),
    ownershipType: ownershipType(value.ownershipType),
  }
}

function mapConfigurationHistory(value: AgentConfigurationHistoryItem): AgentConfigurationHistoryItem {
  return {
    ...pick(value, [
      'revision', 'previousRevision', 'templateKey', 'templateVersion', 'templateContentHash',
      'configurationHash', 'createdAt', 'createdBy',
    ]),
    personalBinding: value.personalBinding ? mapBinding(value.personalBinding) : null,
    teamBinding: value.teamBinding ? mapBinding(value.teamBinding) : null,
  }
}

function mapCurrentConfiguration(value: CurrentAgentConfiguration): CurrentAgentConfiguration {
  return {
    ...pick(value, [
      'revision', 'previousRevision', 'templateKey', 'templateVersion', 'templateContentHash',
      'supplementalInstructions', 'policyPackId', 'policyPackVersion', 'configurationHash', 'createdAt',
    ]),
    personalBinding: value.personalBinding ? mapBinding(value.personalBinding) : null,
    teamBinding: value.teamBinding ? mapBinding(value.teamBinding) : null,
    approvedSkillKeys: [...value.approvedSkillKeys],
    memoryPolicy: value.memoryPolicy ? { ...pick(value.memoryPolicy, ['id', 'version']) } : null,
    budgetPolicy: value.budgetPolicy ? { ...pick(value.budgetPolicy, ['id', 'version']) } : null,
    generateOptions: { ...pick(value.generateOptions, [
      'temperature', 'topP', 'maximumOutputTokens', 'reasoningMode', 'cacheEnabled',
      'parallelToolCalls', 'seed', 'maximumAttempts',
    ]) },
  }
}

function mapBinding(value: AgentModelBindingSummary): AgentModelBindingSummary {
  return {
    executionScope: executionScope(value.executionScope),
    kind: value.kind,
    primary: value.primary ? mapSelection(value.primary) : null,
    fallback: value.fallback ? mapSelection(value.fallback) : null,
  }
}

function mapSelection(value: AgentModelSelectionSummary): AgentModelSelectionSummary {
  return { ...pick(value, ['connectionId', 'providerKey', 'catalogEntryId', 'modelId', 'catalogRevision']) }
}

function mapSelectableModel(value: SelectableAgentModel): SelectableAgentModel {
  return {
    ...pick(value, [
      'connectionId', 'connectionOwnerType', 'connectionOwnerId', 'providerKey', 'providerDisplayName',
      'catalogEntryId', 'modelId', 'catalogRevision', 'modelDisplayName', 'region',
      'contextWindowTokens', 'maximumOutputTokens',
    ]),
    capabilities: [...value.capabilities],
    price: mapPrice(value.price),
  }
}

function mapPreflight(value: AgentModelPreflight): AgentModelPreflight {
  return {
    ...pick(value, [
      'agentProfileId', 'agentProfileVersion', 'configurationRevision', 'configurationHash',
      'bindingSource', 'resolutionHash',
    ]),
    executionScope: executionScope(value.executionScope),
    modelDefault: value.modelDefault ? { ...pick(value.modelDefault, [
      'source', 'scopeType', 'scopeId', 'revision', 'contentHash',
    ]) } : null,
    primary: mapResolvedSelection(value.primary),
    fallback: value.fallback ? mapResolvedSelection(value.fallback) : null,
  }
}

function mapResolvedSelection(value: ResolvedModelSelectionSummary): ResolvedModelSelectionSummary {
  return {
    ...pick(value, [
      'role', 'providerKey', 'connectionId', 'connectionOwnerType', 'connectionOwnerId', 'region',
      'catalogEntryId', 'modelId', 'catalogRevision', 'modelRevision', 'priceRevision',
    ]),
    price: mapPrice(value.price),
  }
}

function mapPrice(value: SelectableModelPrice): SelectableModelPrice {
  return { ...pick(value, [
    'inputPerMillionTokens', 'outputPerMillionTokens', 'cachedInputPerMillionTokens', 'currencyCode',
  ]) }
}

function mapConversationConfiguration(value: ConversationAgentConfiguration): ConversationAgentConfiguration {
  return { ...pick(value, [
    'runtimeSessionId', 'runtimeSessionVersion', 'agentProfileId', 'pinnedConfigurationRevision',
    'pinnedConfigurationHash', 'currentConfigurationRevision', 'currentConfigurationHash',
    'refreshRequired',
  ]) }
}

function configurationBody(value: AgentConfigurationInput): AgentConfigurationInput {
  return {
    personalModelBinding: value.personalModelBinding ? bindingBody(value.personalModelBinding) : null,
    teamModelBinding: value.teamModelBinding ? bindingBody(value.teamModelBinding) : null,
    supplementalInstructions: value.supplementalInstructions,
    approvedSkillKeys: [...value.approvedSkillKeys],
    memoryPolicy: value.memoryPolicy ? { ...value.memoryPolicy } : null,
    budgetPolicy: value.budgetPolicy ? { ...value.budgetPolicy } : null,
    generateOptions: value.generateOptions ? { ...value.generateOptions } : null,
  }
}

function bindingBody(value: AgentConfigurationInput['personalModelBinding']) {
  if (!value) return null
  return {
    kind: value.kind,
    primary: value.primary ? { ...value.primary } : null,
    fallback: value.fallback ? { ...value.fallback } : null,
  }
}

function mapReceipt(value: AgentCommandReceipt): AgentCommandReceipt {
  return { ...pick(value, ['commandId', 'domainEventId', 'committedVersion', 'correlationId']) }
}

function ownershipType(value: string): AgentOwnershipType {
  if (value === 'USER' || value === 'TEAM' || value === 'ORGANIZATION') return value
  throw new TypeError('Agent ownership type is invalid')
}

function executionScope(value: string): AgentExecutionScope {
  if (value === 'PERSONAL' || value === 'TEAM') return value
  throw new TypeError('Agent execution scope is invalid')
}

function requireStrongEtag(response: Response, resource: string): string {
  const etag = response.headers.get('ETag')
  if (!etag || etag.startsWith('W/') || !/^"[^"]+"$/.test(etag)) {
    throw new TypeError(`${resource} strong ETag is missing`)
  }
  return etag
}

function etagVersion(etag: string): number {
  if (!/^"\d+"$/.test(etag)) throw new TypeError('Agent response ETag is invalid')
  const version = Number(etag.slice(1, -1))
  if (!Number.isSafeInteger(version)) throw new TypeError('Agent response ETag is invalid')
  return version
}

function pick<T extends object, K extends keyof T>(value: T, keys: readonly K[]): Pick<T, K> {
  return Object.fromEntries(keys.map(key => [key, value[key]])) as Pick<T, K>
}
