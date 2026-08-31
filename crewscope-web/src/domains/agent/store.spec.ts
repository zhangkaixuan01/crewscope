import { fixtureIds } from '../../test/scopeFixtures'
import type { Etagged, OffsetPage, SettingsScope } from '../settings/types'
import type { AgentGateway } from './gateway'
import { createAgentStore } from './store'
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

const platformScope = { organizationId: fixtureIds.organization, teamId: fixtureIds.teamPlatform }
const securityScope = { organizationId: fixtureIds.organization, teamId: fixtureIds.teamSecurity }
const profileId = '00000000-0000-0000-0000-000000006101'
const conversationId = '00000000-0000-0000-0000-000000006201'

describe('AgentStore', () => {
  it('rejects a late Agent page after the selected Team changes', async () => {
    const gateway = new FixtureAgentGateway()
    const first = deferred<OffsetPage<AgentSummary>>()
    gateway.listAgents = vi.fn()
      .mockImplementationOnce(async () => first.promise)
      .mockImplementationOnce(async () => ({ items: [agent(securityScope, 'security-agent')], nextOffset: null }))
    const store = createAgentStore(gateway)

    store.activateScope(platformScope)
    const slow = store.loadAgents()
    store.activateScope(securityScope)
    await store.loadAgents()
    first.resolve({ items: [agent(platformScope, 'platform-agent')], nextOffset: null })
    await slow

    expect(store.state.agents.value?.map(item => item.displayName)).toEqual(['security-agent'])
    expect(store.state.currentConfigurations).toEqual({})
  })

  it('continues offset history, de-duplicates revisions and keeps exact current Revision ETag', async () => {
    const gateway = new FixtureAgentGateway()
    const offsets: number[] = []
    gateway.listConfigurations = vi.fn(async (_scope, _profile, offset = 0) => {
      offsets.push(offset)
      return offset === 0
        ? { items: [history(2, 1)], nextOffset: 50 }
        : { items: [history(2, 1), history(3, 2)], nextOffset: null }
    })
    const store = createAgentStore(gateway)
    store.activateScope(platformScope)

    await store.loadConfigurationHistory(profileId)
    await store.loadConfigurationHistory(profileId, true)
    await store.loadCurrentConfiguration(profileId)

    expect(offsets).toEqual([0, 50])
    expect(store.state.configurationHistory[profileId]?.value?.map(item => item.revision)).toEqual([2, 3])
    expect(store.state.currentConfigurations[profileId]?.value?.etag).toBe('"3"')
    expect(store.state.currentConfigurations[profileId]?.value?.value.configurationHash).toBe('b'.repeat(64))
  })

  it('fails a Configuration closed when its ETag does not match the body Revision', async () => {
    const gateway = new FixtureAgentGateway()
    gateway.getCurrentConfiguration = vi.fn(async () => ({ value: currentConfiguration(), etag: '"2"' }))
    const store = createAgentStore(gateway)
    store.activateScope(platformScope)

    await store.loadCurrentConfiguration(profileId)

    expect(store.state.currentConfigurations[profileId]?.phase).toBe('error')
    expect(store.state.currentConfigurations[profileId]?.value).toBeNull()
  })

  it('uses the caller-held Configuration ETag and invalidates all derived model facts after append', async () => {
    const gateway = new FixtureAgentGateway()
    const store = createAgentStore(gateway)
    store.activateScope(platformScope)
    await store.loadAgent(profileId)
    await store.loadCurrentConfiguration(profileId)
    await store.loadSelectableModels(profileId, 'TEAM')
    await store.loadPreflight(profileId, 'TEAM')

    const result = await store.appendConfiguration(profileId, configurationInput(), '"3"', 'configuration-key')

    expect(result).toBe(true)
    expect(gateway.seenEtag).toBe('"3"')
    expect(store.state.currentConfigurations[profileId]).toBeUndefined()
    expect(store.state.selectableModels[`${profileId}:TEAM`]).toBeUndefined()
    expect(store.state.preflights[`${profileId}:TEAM`]).toBeUndefined()
    expect(store.state.agentDetails[profileId]).toBeUndefined()
  })

  it('uses the Runtime Session ETag for safe-point refresh and never constructs a new pin locally', async () => {
    const gateway = new FixtureAgentGateway()
    const store = createAgentStore(gateway)
    store.activateScope(platformScope)

    await store.loadConversationConfiguration(conversationId)
    const before = store.state.conversationConfigurations[conversationId]?.value?.value
    const result = await store.refreshConversationConfiguration(conversationId, 'refresh-key')

    expect(before?.pinnedConfigurationRevision).toBe(2)
    expect(result).toBe(true)
    expect(gateway.seenEtag).toBe('"8"')
    expect(store.state.conversationConfigurations[conversationId]).toBeUndefined()
  })
})

class FixtureAgentGateway implements AgentGateway {
  seenEtag: string | null = null

  async listTemplates(
    scope: SettingsScope,
    _ownershipType: AgentOwnershipType,
  ): Promise<OffsetPage<AgentTemplateSummary>> {
    return { items: [template(scope)], nextOffset: null }
  }

  async listAgents(scope: SettingsScope): Promise<OffsetPage<AgentSummary>> {
    return { items: [agent(scope, 'Coding Agent')], nextOffset: null }
  }

  async getAgent(scope: SettingsScope): Promise<Etagged<AgentSummary>> {
    return { value: agent(scope, 'Coding Agent'), etag: '"5"' }
  }

  async listConfigurations(
    _scope: SettingsScope,
    _profileId: string,
    _offset?: number,
    _limit?: number,
    _signal?: AbortSignal,
  ): Promise<OffsetPage<AgentConfigurationHistoryItem>> {
    return { items: [history(3, 2)], nextOffset: null }
  }

  async getCurrentConfiguration(): Promise<Etagged<CurrentAgentConfiguration>> {
    return { value: currentConfiguration(), etag: '"3"' }
  }

  async listSelectableModels(): Promise<SelectableAgentModel[]> {
    return [selectableModel()]
  }

  async preflight(
    _scope: SettingsScope,
    requestedProfileId: string,
    executionScope: AgentExecutionScope,
  ): Promise<AgentModelPreflight> {
    return preflight(requestedProfileId, executionScope)
  }

  async getConversationConfiguration(): Promise<Etagged<ConversationAgentConfiguration>> {
    return { value: conversationConfiguration(), etag: '"8"' }
  }

  async createAgent(_scope: SettingsScope, _input: CreateAgentInput): Promise<AgentCommandReceipt> {
    return receipt()
  }

  async transitionAgent(
    _scope: SettingsScope,
    _profileId: string,
    _transition: AgentLifecycleTransition,
    etag: string,
  ): Promise<AgentCommandReceipt> {
    this.seenEtag = etag
    return receipt()
  }

  async appendConfiguration(
    _scope: SettingsScope,
    _profileId: string,
    _input: AgentConfigurationInput,
    etag: string,
  ): Promise<AgentCommandReceipt> {
    this.seenEtag = etag
    return receipt()
  }

  async refreshConversationConfiguration(
    _scope: SettingsScope,
    _conversationId: string,
    etag: string,
  ): Promise<AgentCommandReceipt> {
    this.seenEtag = etag
    return receipt()
  }
}

function template(scope: SettingsScope): AgentTemplateSummary {
  return {
    publisherType: 'ORGANIZATION', publisherId: scope.organizationId, key: 'coding', version: 1,
    runtimeRole: 'SPECIALIST', allowedOwnershipTypes: ['USER'], allowedExecutionScopes: ['PERSONAL', 'TEAM'],
    declaredCapabilities: ['coding'], requiredModelCapabilities: ['TOOLS'], approvedSkillKeys: ['coding-baseline'],
    memberConfigurableSlots: ['SUPPLEMENTAL_INSTRUCTIONS'], administratorConfigurableSlots: [],
    creatable: true, platformManaged: false,
    contentHash: 'a'.repeat(64), status: 'ACTIVE', lifecycleVersion: 1,
  }
}

function agent(scope: SettingsScope, displayName: string): AgentSummary {
  return {
    id: profileId, principalId: 'principal-1', displayName, principalStatus: 'ACTIVE',
    organizationId: scope.organizationId, teamId: scope.teamId, workspaceId: fixtureIds.workspacePlatform,
    ownershipType: 'USER', ownerMemberId: fixtureIds.memberOwner, runtimeRole: 'SPECIALIST',
    templateKey: 'coding', templateVersion: 1, defaultProfile: false, status: 'ACTIVE',
    currentConfigurationRevision: 3, currentConfigurationHash: 'b'.repeat(64),
    createdAt: '2026-08-23T01:00:00Z', updatedAt: '2026-08-24T01:00:00Z', version: 5,
  }
}

function history(revision: number, previousRevision: number | null): AgentConfigurationHistoryItem {
  return {
    revision, previousRevision, templateKey: 'coding', templateVersion: 1,
    templateContentHash: 'a'.repeat(64), personalBinding: null, teamBinding: null,
    configurationHash: `${revision}`.repeat(64).slice(0, 64), createdAt: '2026-08-24T01:00:00Z',
    createdBy: fixtureIds.principal,
  }
}

function currentConfiguration(): CurrentAgentConfiguration {
  return {
    revision: 3, previousRevision: 2, templateKey: 'coding', templateVersion: 1,
    templateContentHash: 'a'.repeat(64), personalBinding: null, teamBinding: null,
    supplementalInstructions: null, approvedSkillKeys: ['coding'], memoryPolicy: null, budgetPolicy: null,
    generateOptions: {
      temperature: '0', topP: '1', maximumOutputTokens: 4096, reasoningMode: 'DISABLED',
      cacheEnabled: true, parallelToolCalls: false, seed: null, maximumAttempts: 2,
    },
    policyPackId: 'policy-1', policyPackVersion: 1, configurationHash: 'b'.repeat(64),
    createdAt: '2026-08-24T01:00:00Z',
  }
}

function selectableModel(): SelectableAgentModel {
  return {
    connectionId: 'connection-1', connectionOwnerType: 'TEAM', connectionOwnerId: fixtureIds.teamPlatform,
    providerKey: 'deepseek', providerDisplayName: 'DeepSeek', catalogEntryId: 'catalog-1',
    modelId: 'deepseek-v4-flash', catalogRevision: 2, modelDisplayName: 'DeepSeek V4 Flash',
    region: 'cn', contextWindowTokens: 128_000, maximumOutputTokens: 16_384, capabilities: ['TOOLS'],
    price: { inputPerMillionTokens: '0.1', outputPerMillionTokens: '0.2', cachedInputPerMillionTokens: null, currencyCode: 'USD' },
  }
}

function preflight(requestedProfileId: string, executionScope: AgentExecutionScope): AgentModelPreflight {
  return {
    agentProfileId: requestedProfileId, agentProfileVersion: 5, configurationRevision: 3,
    configurationHash: 'b'.repeat(64), executionScope, bindingSource: 'DIRECT', modelDefault: null,
    primary: {
      role: 'PRIMARY', providerKey: 'deepseek', connectionId: 'connection-1', connectionOwnerType: 'TEAM',
      connectionOwnerId: fixtureIds.teamPlatform, region: 'cn', catalogEntryId: 'catalog-1',
      modelId: 'deepseek-v4-flash', catalogRevision: 2, modelRevision: 'revision-1', priceRevision: 1,
      price: { inputPerMillionTokens: '0.1', outputPerMillionTokens: '0.2', cachedInputPerMillionTokens: null, currencyCode: 'USD' },
    },
    fallback: null, resolutionHash: 'c'.repeat(64),
  }
}

function conversationConfiguration(): ConversationAgentConfiguration {
  return {
    runtimeSessionId: 'session-1', runtimeSessionVersion: 8, agentProfileId: profileId,
    pinnedConfigurationRevision: 2, pinnedConfigurationHash: 'd'.repeat(64),
    currentConfigurationRevision: 3, currentConfigurationHash: 'b'.repeat(64), refreshRequired: true,
  }
}

function configurationInput(): AgentConfigurationInput {
  return {
    personalModelBinding: null, teamModelBinding: null, supplementalInstructions: null,
    approvedSkillKeys: ['coding'], memoryPolicy: null, budgetPolicy: null,
    generateOptions: { temperature: 0, topP: 1 },
  }
}

function receipt(): AgentCommandReceipt {
  return {
    commandId: crypto.randomUUID(), domainEventId: crypto.randomUUID(),
    committedVersion: 4, correlationId: crypto.randomUUID(),
  }
}

function deferred<T>(): { promise: Promise<T>, resolve: (value: T) => void } {
  let resolve!: (value: T) => void
  const promise = new Promise<T>(complete => { resolve = complete })
  return { promise, resolve }
}
