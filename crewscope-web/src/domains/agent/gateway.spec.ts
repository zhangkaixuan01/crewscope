import { CrewScopeApiClient } from '../../api/client'
import { fixtureIds } from '../../test/scopeFixtures'
import { HttpAgentGateway } from './gateway'

const scope = { organizationId: fixtureIds.organization, teamId: fixtureIds.teamPlatform }
const profileId = '00000000-0000-0000-0000-000000006101'
const conversationId = '00000000-0000-0000-0000-000000006201'

describe('HttpAgentGateway', () => {
  it('whitelists Template, Agent and Configuration history pages with bounded offsets', async () => {
    const fetcher = vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input)
      if (url.includes('/agent-templates')) return json({ items: [templatePayload({ systemPrompt: 'private prompt' })] })
      if (url.includes('/configurations?')) return json({ items: [historyPayload({ toolPayload: 'private tool' })] })
      return json({ items: [agentPayload({ credentialId: 'private credential' })] })
    })
    const gateway = gatewayWith(fetcher)

    const templates = await gateway.listTemplates(scope, 'USER', 2, 1)
    const agents = await gateway.listAgents(scope, 3, 1)
    const history = await gateway.listConfigurations(scope, profileId, 4, 1)

    expect(templates.nextOffset).toBe(3)
    expect(agents.nextOffset).toBe(4)
    expect(history.nextOffset).toBe(5)
    expect(String(fetcher.mock.calls[0]?.[0])).toContain('ownershipType=USER')
    expect(String(fetcher.mock.calls[2]?.[0])).toContain('offset=4&limit=1')
    expect(JSON.stringify({ templates, agents, history })).not.toContain('private prompt')
    expect(JSON.stringify({ templates, agents, history })).not.toContain('private tool')
    expect(JSON.stringify({ templates, agents, history })).not.toContain('private credential')
  })

  it('retains strong ETags for Agent, Configuration and Conversation version boundaries', async () => {
    const fetcher = vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input)
      if (url.includes('/configurations/current')) return json(currentPayload({ endpoint: 'private' }), 200, { ETag: '"3"' })
      if (url.includes('/agent-configuration')) return json(conversationPayload({ agentStateReference: 'private' }), 200, { ETag: '"8"' })
      return json(agentPayload({ principalInternalVersion: 9 }), 200, { ETag: '"5"' })
    })
    const gateway = gatewayWith(fetcher)

    const agent = await gateway.getAgent(scope, profileId)
    const configuration = await gateway.getCurrentConfiguration(scope, profileId)
    const conversation = await gateway.getConversationConfiguration(scope, conversationId)

    expect(agent.etag).toBe('"5"')
    expect(configuration.etag).toBe('"3"')
    expect(conversation.etag).toBe('"8"')
    expect(JSON.stringify({ agent, configuration, conversation })).not.toContain('private')
  })

  it('whitelists selectable Model and Preflight evidence without provider internals', async () => {
    const fetcher = vi.fn(async (input: RequestInfo | URL) => String(input).includes('/model-preflight')
      ? json(preflightPayload({ endpoint: 'https://private', credentialId: 'private' }))
      : json({ items: [selectablePayload({ adapterKey: 'private-adapter' })] }))
    const gateway = gatewayWith(fetcher)

    const selectable = await gateway.listSelectableModels(scope, profileId, 'TEAM')
    const preflight = await gateway.preflight(scope, profileId, 'TEAM')

    expect(selectable[0]?.modelId).toBe('deepseek-v4-flash')
    expect(preflight.configurationRevision).toBe(3)
    expect(JSON.stringify({ selectable, preflight })).not.toContain('private')
  })

  it('forwards exact ETag and Idempotency-Key metadata for configuration and safe refresh commands', async () => {
    const fetcher = vi.fn(async (_input: RequestInfo | URL, _init?: RequestInit) => json({
      commandId: crypto.randomUUID(), domainEventId: crypto.randomUUID(), committedVersion: 4,
      correlationId: crypto.randomUUID(), internalPayload: 'private',
    }, 202))
    const gateway = gatewayWith(fetcher)

    const receipt = await gateway.appendConfiguration(scope, profileId, {
      personalModelBinding: null,
      teamModelBinding: { kind: 'INHERIT_TEAM_DEFAULT', primary: null, fallback: null },
      supplementalInstructions: null,
      approvedSkillKeys: ['coding'],
      memoryPolicy: null,
      budgetPolicy: null,
      generateOptions: { temperature: 0 },
    }, '"3"', 'configuration-key')
    await gateway.refreshConversationConfiguration(scope, conversationId, '"8"', 'refresh-key')

    const configHeaders = new Headers(fetcher.mock.calls[0]?.[1]?.headers)
    const refreshHeaders = new Headers(fetcher.mock.calls[1]?.[1]?.headers)
    expect(configHeaders.get('If-Match')).toBe('"3"')
    expect(configHeaders.get('Idempotency-Key')).toBe('configuration-key')
    expect(refreshHeaders.get('If-Match')).toBe('"8"')
    expect(refreshHeaders.get('Idempotency-Key')).toBe('refresh-key')
    expect(receipt).not.toHaveProperty('internalPayload')
  })

  it('rejects missing or weak ETags before a versioned resource reaches Store', async () => {
    const gateway = gatewayWith(vi.fn(async () => json(agentPayload(), 200, { ETag: 'W/"5"' })))
    await expect(gateway.getAgent(scope, profileId)).rejects.toThrow('strong ETag')
  })
})

function gatewayWith(fetcher: ReturnType<typeof vi.fn>): HttpAgentGateway {
  return new HttpAgentGateway(new CrewScopeApiClient('/api/v1', fetcher as unknown as typeof fetch))
}

function templatePayload(extra: Record<string, unknown> = {}) {
  return {
    publisherType: 'ORGANIZATION', publisherId: scope.organizationId, key: 'coding', version: 1,
    runtimeRole: 'SPECIALIST', allowedOwnershipTypes: ['USER', 'TEAM'],
    allowedExecutionScopes: ['PERSONAL', 'TEAM'], declaredCapabilities: ['coding'],
    requiredModelCapabilities: ['TOOLS'], approvedSkillKeys: ['coding-baseline'], memberConfigurableSlots: ['SUPPLEMENTAL_INSTRUCTIONS'],
    administratorConfigurableSlots: ['BUDGET_POLICY'], creatable: true, platformManaged: false,
    contentHash: 'a'.repeat(64), status: 'ACTIVE',
    lifecycleVersion: 1, ...extra,
  }
}

function agentPayload(extra: Record<string, unknown> = {}) {
  return {
    id: profileId, principalId: 'principal-1', displayName: '我的 Coding Agent', principalStatus: 'ACTIVE',
    organizationId: scope.organizationId, teamId: scope.teamId, workspaceId: fixtureIds.workspacePlatform,
    ownershipType: 'USER', ownerMemberId: fixtureIds.memberOwner, runtimeRole: 'SPECIALIST',
    templateKey: 'coding', templateVersion: 1, defaultProfile: false, status: 'ACTIVE',
    currentConfigurationRevision: 3, currentConfigurationHash: 'b'.repeat(64),
    createdAt: '2026-08-23T01:00:00Z', updatedAt: '2026-08-24T01:00:00Z', version: 5,
    ...extra,
  }
}

function selectionPayload() {
  return {
    connectionId: 'connection-1', providerKey: 'deepseek', catalogEntryId: 'catalog-1',
    modelId: 'deepseek-v4-flash', catalogRevision: 2,
  }
}

function bindingPayload(executionScope = 'TEAM') {
  return { executionScope, kind: 'DIRECT', primary: selectionPayload(), fallback: null }
}

function historyPayload(extra: Record<string, unknown> = {}) {
  return {
    revision: 3, previousRevision: 2, templateKey: 'coding', templateVersion: 1,
    templateContentHash: 'a'.repeat(64), personalBinding: null, teamBinding: bindingPayload(),
    configurationHash: 'b'.repeat(64), createdAt: '2026-08-24T01:00:00Z',
    createdBy: fixtureIds.principal, ...extra,
  }
}

function currentPayload(extra: Record<string, unknown> = {}) {
  return {
    revision: 3, previousRevision: 2, templateKey: 'coding', templateVersion: 1,
    templateContentHash: 'a'.repeat(64), personalBinding: null, teamBinding: bindingPayload(),
    supplementalInstructions: null, approvedSkillKeys: ['coding'], memoryPolicy: null, budgetPolicy: null,
    generateOptions: {
      temperature: '0', topP: '1', maximumOutputTokens: 4096, reasoningMode: 'DISABLED',
      cacheEnabled: true, parallelToolCalls: false, seed: null, maximumAttempts: 2,
    },
    policyPackId: 'policy-1', policyPackVersion: 1, configurationHash: 'b'.repeat(64),
    createdAt: '2026-08-24T01:00:00Z', ...extra,
  }
}

function selectablePayload(extra: Record<string, unknown> = {}) {
  return {
    connectionId: 'connection-1', connectionOwnerType: 'TEAM', connectionOwnerId: scope.teamId,
    providerKey: 'deepseek', providerDisplayName: 'DeepSeek', catalogEntryId: 'catalog-1',
    modelId: 'deepseek-v4-flash', catalogRevision: 2, modelDisplayName: 'DeepSeek V4 Flash',
    region: 'cn', contextWindowTokens: 128_000, maximumOutputTokens: 16_384,
    capabilities: ['TOOLS'], price: {
      inputPerMillionTokens: '0.1', outputPerMillionTokens: '0.2',
      cachedInputPerMillionTokens: '0.02', currencyCode: 'USD',
    },
    ...extra,
  }
}

function resolvedSelectionPayload() {
  return {
    role: 'PRIMARY', providerKey: 'deepseek', connectionId: 'connection-1',
    connectionOwnerType: 'TEAM', connectionOwnerId: scope.teamId, region: 'cn',
    catalogEntryId: 'catalog-1', modelId: 'deepseek-v4-flash', catalogRevision: 2,
    modelRevision: 'DeepSeek-V4-Flash-0731', priceRevision: 1,
    price: { inputPerMillionTokens: '0.1', outputPerMillionTokens: '0.2', cachedInputPerMillionTokens: null, currencyCode: 'USD' },
  }
}

function preflightPayload(extra: Record<string, unknown> = {}) {
  return {
    agentProfileId: profileId, agentProfileVersion: 5, configurationRevision: 3,
    configurationHash: 'b'.repeat(64), executionScope: 'TEAM', bindingSource: 'DIRECT',
    modelDefault: null, primary: resolvedSelectionPayload(), fallback: null,
    resolutionHash: 'c'.repeat(64), ...extra,
  }
}

function conversationPayload(extra: Record<string, unknown> = {}) {
  return {
    runtimeSessionId: 'session-1', runtimeSessionVersion: 8, agentProfileId: profileId,
    pinnedConfigurationRevision: 2, pinnedConfigurationHash: 'd'.repeat(64),
    currentConfigurationRevision: 3, currentConfigurationHash: 'b'.repeat(64),
    refreshRequired: true, ...extra,
  }
}

function json(body: unknown, status = 200, headers: Record<string, string> = {}): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json', ...headers },
  })
}
