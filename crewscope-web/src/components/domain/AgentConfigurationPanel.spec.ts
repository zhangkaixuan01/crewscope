import { flushPromises, mount } from '@vue/test-utils'
import { reactive } from 'vue'
import { AGENT_STORE, type AgentStore, type AgentStoreState } from '../../domains/agent/store'
import type { AgentConfigurationInput, AgentSummary, AgentTemplateSummary, SelectableAgentModel } from '../../domains/agent/types'
import AgentConfigurationPanel from './AgentConfigurationPanel.vue'

describe('AgentConfigurationPanel', () => {
  it('creates Revision 1 with If-Match zero and a whitelisted PERSONAL binding', async () => {
    const { store, appendConfiguration } = fixtureStore('PERSONAL')
    const wrapper = mount(AgentConfigurationPanel, {
      props: { agent: agent(null), template: template('PERSONAL'), canConfigure: true, selectedRevision: null },
      global: { provide: { [AGENT_STORE as symbol]: store } },
    })
    await flushPromises()
    const selects = wrapper.findAll('.binding-editor select')
    await selects[0]!.setValue(modelKey(model('primary-model', 'connection-primary', 'catalog-primary')))
    await wrapper.get('textarea').setValue('只补充公开且受控的执行偏好')
    await wrapper.get('.skill-picker input').setValue(true)
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(appendConfiguration).toHaveBeenCalledOnce()
    const [profileId, input, etag, idempotencyKey] = appendConfiguration.mock.calls[0]!
    expect(profileId).toBe(agentId)
    expect(etag).toBe('"0"')
    expect(idempotencyKey).toMatch(/^[0-9a-f-]{36}$/)
    expect(input).toMatchObject({
      personalModelBinding: {
        kind: 'DIRECT',
        primary: { connectionId: 'connection-primary', catalogEntryId: 'catalog-primary', catalogRevision: 1 },
        fallback: null,
      },
      teamModelBinding: null,
      supplementalInstructions: '只补充公开且受控的执行偏好',
      approvedSkillKeys: ['coding-baseline'],
      memoryPolicy: null,
      budgetPolicy: null,
    })
    expect(JSON.stringify(input)).not.toMatch(/apiKey|credential|systemPrompt|toolPayload/)
  })

  it('supports explicitly selected TEAM default inheritance without exposing USER connection facts', async () => {
    const { store, appendConfiguration } = fixtureStore('TEAM')
    const wrapper = mount(AgentConfigurationPanel, {
      props: { agent: agent(null, 'TEAM'), template: template('TEAM'), canConfigure: true, selectedRevision: null },
      global: { provide: { [AGENT_STORE as symbol]: store } },
    })
    await flushPromises()
    expect(wrapper.text()).toContain('继承已发布的 Team/Organization 默认')
    expect(wrapper.text()).not.toContain('USER secret connection')
    await wrapper.get('.binding-mode select').setValue('INHERIT_TEAM_DEFAULT')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(appendConfiguration.mock.calls[0]?.[1]).toMatchObject({
      personalModelBinding: null,
      teamModelBinding: { kind: 'INHERIT_TEAM_DEFAULT', primary: null, fallback: null },
    })
  })

  it('keeps historical revisions immutable and explains pinned execution semantics', async () => {
    const { store } = fixtureStore('PERSONAL', true)
    const wrapper = mount(AgentConfigurationPanel, {
      props: { agent: agent(2), template: template('PERSONAL'), canConfigure: true, selectedRevision: 1 },
      global: { provide: { [AGENT_STORE as symbol]: store } },
    })
    await flushPromises()
    expect(wrapper.text()).toContain('历史版本不可编辑')
    expect(wrapper.text()).toContain('Conversation、Task 和 Retry')
    expect(wrapper.find('form').exists()).toBe(false)
  })

  it('requires an explicit second activation before a lifecycle command', async () => {
    const { store, transitionAgent } = fixtureStore('PERSONAL')
    const wrapper = mount(AgentConfigurationPanel, {
      props: { agent: agent(null), template: template('PERSONAL'), canConfigure: true, selectedRevision: null },
      global: { provide: { [AGENT_STORE as symbol]: store } },
    })
    await flushPromises()
    const disable = wrapper.findAll('button').find(button => button.text() === '禁用')!
    await disable.trigger('click')
    expect(transitionAgent).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('确认禁用')
    await wrapper.findAll('button').find(button => button.text() === '确认禁用')!.trigger('click')
    await flushPromises()
    expect(transitionAgent).toHaveBeenCalledWith(agentId, 'disable', expect.any(String))
  })

  it('configures a platform-managed Team Observer without exposing generic lifecycle actions', async () => {
    const { store, appendConfiguration, transitionAgent } = fixtureStore('TEAM')
    const observer = {
      ...agent(null, 'TEAM'),
      displayName: 'Team Observer',
      runtimeRole: 'TEAM_COORDINATOR',
      templateKey: 'team-observer',
      templateVersion: 1,
      status: 'DISABLED',
      principalStatus: 'DISABLED',
    }
    const wrapper = mount(AgentConfigurationPanel, {
      props: {
        agent: observer,
        template: {
          ...template('TEAM'), key: 'team-observer', version: 1,
          platformManaged: true, creatable: false,
          memberConfigurableSlots: [], administratorConfigurableSlots: ['MODEL_BINDING', 'BUDGET'],
        },
        canConfigure: true,
        selectedRevision: null,
      },
      global: { provide: { [AGENT_STORE as symbol]: store } },
    })
    await flushPromises()

    expect(wrapper.text()).toContain('平台托管 Team Observer')
    expect(wrapper.text()).not.toContain('Template 元数据不可用')
    expect(wrapper.find('form').exists()).toBe(true)
    expect(wrapper.text()).toContain('继承已发布的 Team/Organization 默认')
    const modelSelects = wrapper.findAll('.binding-editor select')
    await modelSelects[1]!.setValue(modelKey(model('primary-model', 'connection-primary', 'catalog-primary')))
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(appendConfiguration).toHaveBeenCalledOnce()
    expect(appendConfiguration.mock.calls[0]?.[1]).toMatchObject({
      personalModelBinding: null,
      teamModelBinding: {
        kind: 'DIRECT',
        primary: { connectionId: 'connection-primary', catalogEntryId: 'catalog-primary', catalogRevision: 1 },
        fallback: null,
      },
    })
    expect(wrapper.text()).toContain('不支持重复创建或通用归档')
    expect(wrapper.text()).not.toContain('确认归档')
    expect(transitionAgent).not.toHaveBeenCalled()
  })

  it('shows a visible Team Agent as read-only before requiring manager-only template metadata', async () => {
    const { store } = fixtureStore('TEAM')
    const wrapper = mount(AgentConfigurationPanel, {
      props: { agent: agent(null, 'TEAM'), template: null, canConfigure: false, selectedRevision: null },
      global: { provide: { [AGENT_STORE as symbol]: store } },
    })
    await flushPromises()

    expect(wrapper.text()).toContain('只读 Agent')
    expect(wrapper.text()).not.toContain('Template 元数据不可用')
  })
})

const agentId = '00000000-0000-0000-0000-000000005102'

function fixtureStore(scope: 'PERSONAL' | 'TEAM', withHistory = false) {
  const selectable = model('primary-model', 'connection-primary', 'catalog-primary')
  const state = reactive<AgentStoreState>({
    templates: {},
    agents: { phase: 'ready', value: [], errorMessage: null, errorStatus: null, nextOffset: null, loadingMore: false },
    agentDetails: {},
    configurationHistory: {
      [agentId]: {
        phase: 'ready', value: withHistory ? [{
          revision: 1, previousRevision: null, templateKey: 'coding-specialist', templateVersion: 3,
          templateContentHash: 'a'.repeat(64), personalBinding: null, teamBinding: null,
          configurationHash: 'b'.repeat(64), createdAt: '2026-08-24T01:00:00Z', createdBy: 'member-1',
        }] : [], errorMessage: null, errorStatus: null, nextOffset: null, loadingMore: false,
      },
    },
    currentConfigurations: {},
    selectableModels: {
      [`${agentId}:${scope}`]: { phase: 'ready', value: [selectable], errorMessage: null, errorStatus: null },
    },
    preflights: {}, conversationConfigurations: {},
    command: { phase: 'idle', operation: null, resourceId: null, receipt: null, errorMessage: null, errorStatus: null, retryable: false },
  })
  const appendConfiguration = vi.fn(async (
    _profileId: string,
    _input: AgentConfigurationInput,
    _etag: string,
    _idempotencyKey: string,
  ) => true)
  const transitionAgent = vi.fn(async (
    _profileId: string,
    _transition: 'activate' | 'disable' | 'archive',
    _idempotencyKey: string,
  ) => true)
  const store = {
    state,
    activateScope: vi.fn(), loadTemplates: vi.fn(), loadAgents: vi.fn(), loadAgent: vi.fn(),
    loadConfigurationHistory: vi.fn(), loadCurrentConfiguration: vi.fn(), loadSelectableModels: vi.fn(),
    loadPreflight: vi.fn(), loadConversationConfiguration: vi.fn(), createAgent: vi.fn(),
    transitionAgent, appendConfiguration, refreshConversationConfiguration: vi.fn(),
    invalidateAgent: vi.fn(), clearCommand: vi.fn(), reset: vi.fn(),
  } as unknown as AgentStore
  return { store, appendConfiguration, transitionAgent }
}

function agent(revision: number | null, ownershipType: AgentSummary['ownershipType'] = 'USER'): AgentSummary {
  return {
    id: agentId, principalId: 'principal-agent', displayName: '我的 Coding Agent', principalStatus: 'ACTIVE',
    organizationId: 'organization-1', teamId: 'team-1', workspaceId: 'workspace-1', ownershipType,
    ownerMemberId: ownershipType === 'USER' ? 'member-1' : null, runtimeRole: 'CODING',
    templateKey: 'coding-specialist', templateVersion: 3, defaultProfile: false, status: 'ACTIVE',
    currentConfigurationRevision: revision, currentConfigurationHash: revision ? 'b'.repeat(64) : null,
    createdAt: '2026-08-24T01:00:00Z', updatedAt: '2026-08-25T01:00:00Z', version: 2,
  }
}

function template(scope: 'PERSONAL' | 'TEAM'): AgentTemplateSummary {
  return {
    publisherType: 'ORGANIZATION', publisherId: 'organization-1', key: 'coding-specialist', version: 3,
    runtimeRole: 'CODING', allowedOwnershipTypes: [scope === 'PERSONAL' ? 'USER' : 'TEAM'],
    allowedExecutionScopes: [scope], declaredCapabilities: ['coding'], requiredModelCapabilities: ['TOOLS'],
    approvedSkillKeys: ['coding-baseline'],
    memberConfigurableSlots: ['MODEL_BINDING', 'SUPPLEMENTAL_INSTRUCTIONS', 'APPROVED_SKILLS', 'OUTPUT_PREFERENCE'],
    administratorConfigurableSlots: [], creatable: true, platformManaged: false,
    contentHash: 'a'.repeat(64), status: 'ACTIVE', lifecycleVersion: 1,
  }
}

function model(modelId: string, connectionId: string, catalogEntryId: string): SelectableAgentModel {
  return {
    connectionId, connectionOwnerType: 'TEAM', connectionOwnerId: 'team-1', providerKey: 'deepseek',
    providerDisplayName: 'DeepSeek', catalogEntryId, modelId, catalogRevision: 1,
    modelDisplayName: modelId, region: 'cn', contextWindowTokens: 128000, maximumOutputTokens: 16000,
    capabilities: ['TOOLS'], price: {
      inputPerMillionTokens: '0.1', outputPerMillionTokens: '0.2',
      cachedInputPerMillionTokens: null, currencyCode: 'USD',
    },
  }
}

function modelKey(value: SelectableAgentModel): string {
  return `${value.connectionId}:${value.catalogEntryId}:${value.catalogRevision}`
}
