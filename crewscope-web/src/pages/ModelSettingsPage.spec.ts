import { flushPromises, mount } from '@vue/test-utils'
import { nextTick } from 'vue'
import { createMemoryHistory } from 'vue-router'
import { AUTH_PRINCIPAL, permissions, type AuthenticatedPrincipal } from '../app/auth'
import { createCrewScopeRouter } from '../app/router'
import type { Etagged, OffsetPage, SettingsScope } from '../domains/settings/types'
import type { ModelGateway } from '../domains/model/gateway'
import { createModelStore, MODEL_STORE } from '../domains/model/store'
import type {
  CreateModelConnectionInput, ModelCatalogEntrySummary, ModelConnectionCommandReceipt,
  ModelConnectionOwnerType, ModelConnectionSummary, ModelProviderSummary, RotateModelCredentialInput,
} from '../domains/model/types'
import { createScopeStore, SCOPE_STORE } from '../domains/scope/store'
import { FixtureScopeGateway, fixtureIds } from '../test/scopeFixtures'
import ModelSettingsPage from './ModelSettingsPage.vue'
import ModelConnectionDetail from '../components/domain/ModelConnectionDetail.vue'

const teamConnectionId = '00000000-0000-0000-0000-000000005201'

const manager: AuthenticatedPrincipal = {
  id: fixtureIds.principal, displayName: 'Provider Manager', role: 'Team Owner',
  organizationId: fixtureIds.organization, organization: 'Test Organization',
  permissions: new Set(Object.values(permissions)),
}

describe('ModelSettingsPage', () => {
  it('renders versioned catalog, price, owner-scoped Connection and safe health facts', async () => {
    const { wrapper, store } = await mountPage(manager, `ownerType=TEAM&connection=${teamConnectionId}&provider=deepseek`)

    expect(store.state.connectionDetails[teamConnectionId]?.phase).toBe('ready')
    expect(wrapper.findComponent(ModelConnectionDetail).props('resource')?.phase).toBe('ready')
    expect((wrapper.findComponent(ModelConnectionDetail).vm as unknown as { resourceState: { phase: string } }).resourceState.phase).toBe('ready')
    expect(wrapper.text()).toContain('Provider 与模型目录')
    expect(wrapper.text()).toContain('DeepSeek V4 Flash')
    expect(wrapper.text()).toContain('USD 0.1')
    expect(wrapper.text()).toContain('团队连接')
    expect(wrapper.text()).toContain('Credential Version')
    expect(wrapper.text()).toContain('Command Receipt')
    expect(wrapper.text()).not.toContain('private-endpoint')
    expect(wrapper.text()).not.toContain('one-way-secret')
  })

  it('lets a normal member view Team health while hiding management actions', async () => {
    const member = { ...manager, role: 'Member', permissions: new Set([permissions.scopeRead]) }
    const { wrapper } = await mountPage(member, `ownerType=TEAM&connection=${teamConnectionId}&provider=deepseek`)

    expect(wrapper.text()).toContain('需要 Provider Manager 权限')
    expect(wrapper.findAll('button').some(button => button.text().includes('轮换凭证'))).toBe(false)
    expect(wrapper.findAll('button').some(button => button.text().includes('组织连接'))).toBe(false)
  })

  it('submits a Key once without retaining it in Model Store or URL', async () => {
    const { wrapper, gateway, store, router } = await mountPage(manager, 'ownerType=USER&provider=deepseek')
    await wrapper.findAll('button').find(button => button.text().includes('创建连接'))!.trigger('click')
    await wrapper.get<HTMLInputElement>('input[type="password"]').setValue('one-way-secret')
    await wrapper.get('.credential-dialog').trigger('submit')
    await flushPromises()

    expect(gateway.seenApiKey).toBe('one-way-secret')
    expect(JSON.stringify(store.state)).not.toContain('one-way-secret')
    expect(router.currentRoute.value.fullPath).not.toContain('one-way-secret')
    expect(wrapper.find('.credential-dialog').exists()).toBe(false)
  })

  it('reuses the same lifecycle idempotency key only while command coordinates remain unchanged', async () => {
    const { wrapper, gateway } = await mountPage(manager, `ownerType=TEAM&connection=${teamConnectionId}&provider=deepseek`)
    gateway.failNextVerify = true
    const verify = () => wrapper.findAll('button').find(button => button.text().includes('验证健康'))!

    await verify().trigger('click')
    await flushPromises()
    await verify().trigger('click')
    await flushPromises()

    expect(gateway.verifyKeys).toHaveLength(2)
    expect(gateway.verifyKeys[0]).toBe(gateway.verifyKeys[1])
  })
})

async function mountPage(principal: AuthenticatedPrincipal, query: string) {
  const router = createCrewScopeRouter(createMemoryHistory(), principal)
  const scopeStore = createScopeStore(new FixtureScopeGateway(), principal)
  await scopeStore.synchronize(fixtureIds.teamPlatform, fixtureIds.projectCrewScope)
  const gateway = new FixtureModelGateway()
  const store = createModelStore(gateway)
  await router.push(`/settings/models?team=${fixtureIds.teamPlatform}&${query}`)
  await router.isReady()
  const wrapper = mount(ModelSettingsPage, {
    global: {
      plugins: [router],
      provide: {
        [AUTH_PRINCIPAL as symbol]: principal,
        [SCOPE_STORE as symbol]: scopeStore,
        [MODEL_STORE as symbol]: store,
      },
      stubs: { AppShell: { template: '<main><slot name="actions"/><slot/></main>' } },
    },
  })
  await flushPromises()
  await flushPromises()
  const connectionId = router.currentRoute.value.query.connection
  if (typeof connectionId === 'string') {
    await vi.waitFor(() => expect(store.state.connectionDetails[connectionId]?.phase).toBe('ready'))
  }
  await nextTick()
  return { wrapper, gateway, store, router }
}

class FixtureModelGateway implements ModelGateway {
  seenApiKey: string | null = null
  failNextVerify = false
  verifyKeys: string[] = []
  private userConnections: ModelConnectionSummary[] = [connection('user-connection', 'USER')]

  async listProviders(): Promise<OffsetPage<ModelProviderSummary>> {
    return { items: [provider()], nextOffset: null }
  }

  async listCatalog(): Promise<OffsetPage<ModelCatalogEntrySummary>> {
    return { items: [catalog()], nextOffset: null }
  }

  async listConnections(_scope: SettingsScope, ownerType: ModelConnectionOwnerType): Promise<OffsetPage<ModelConnectionSummary>> {
    return { items: ownerType === 'USER' ? [...this.userConnections] : ownerType === 'TEAM' ? [connection(teamConnectionId, 'TEAM')] : [], nextOffset: null }
  }

  async getConnection(_organizationId: string, connectionId: string): Promise<Etagged<ModelConnectionSummary>> {
    const value = connectionId === teamConnectionId
      ? connection(teamConnectionId, 'TEAM')
      : this.userConnections.find(item => item.id === connectionId) ?? connection(connectionId, 'USER')
    return { value, etag: `"${value.version}"` }
  }

  async createConnection(input: CreateModelConnectionInput): Promise<ModelConnectionCommandReceipt> {
    this.seenApiKey = input.apiKey
    this.userConnections.push(connection('created-connection', input.ownerType))
    return receipt()
  }

  async verifyConnection(
    _organizationId: string,
    _connection: ModelConnectionSummary,
    _etag: string,
    idempotencyKey: string,
  ): Promise<ModelConnectionCommandReceipt> {
    this.verifyKeys.push(idempotencyKey)
    if (this.failNextVerify) {
      this.failNextVerify = false
      throw new Error('transient provider failure')
    }
    return receipt()
  }
  async rotateCredential(_organizationId: string, _connectionId: string, _etag: string, input: RotateModelCredentialInput): Promise<ModelConnectionCommandReceipt> { this.seenApiKey = input.apiKey; return receipt() }
  async suspendConnection(): Promise<ModelConnectionCommandReceipt> { return receipt() }
  async revokeConnection(): Promise<ModelConnectionCommandReceipt> { return receipt() }
}

function provider(): ModelProviderSummary {
  return {
    key: 'deepseek', displayName: 'DeepSeek', availableRegions: ['cn'], retentionMode: 'NONE',
    maximumRetentionSeconds: null, trainingUsagePolicy: 'DISABLED', status: 'ACTIVE', version: 2,
  }
}

function catalog(): ModelCatalogEntrySummary {
  return {
    id: 'catalog-1', providerKey: 'deepseek', modelId: 'deepseek-v4-flash', catalogRevision: 4,
    modelRevision: 'DeepSeek-V4-Flash', displayName: 'DeepSeek V4 Flash', contextWindowTokens: 128000,
    maximumOutputTokens: 120000, capabilities: ['TOOLS', 'STRUCTURED_OUTPUT'], availableRegions: ['cn'],
    status: 'ACTIVE', version: 2, effectivePrice: {
      revision: 2, effectiveFrom: '2026-08-01T00:00:00Z', inputPerMillionTokens: '0.1',
      outputPerMillionTokens: '0.2', cachedInputPerMillionTokens: '0.02', currencyCode: 'USD',
    },
  }
}

function connection(id: string, ownerType: ModelConnectionOwnerType): ModelConnectionSummary {
  return {
    id, organizationId: fixtureIds.organization, providerKey: 'deepseek', ownerType,
    ownerId: ownerType === 'USER' ? fixtureIds.principal : ownerType === 'TEAM' ? fixtureIds.teamPlatform : fixtureIds.organization,
    region: 'cn', billingSubjectType: ownerType, billingSubjectId: fixtureIds.teamPlatform,
    credentialVersion: 2, status: 'ACTIVE', healthStatus: 'HEALTHY', healthFailureCode: null,
    checkedAt: '2026-08-25T01:00:00Z', lastHealthyAt: '2026-08-25T01:00:00Z', consecutiveFailures: 0,
    revocationReason: null, createdAt: '2026-08-24T01:00:00Z', updatedAt: '2026-08-25T01:00:00Z', version: 4,
  }
}

function receipt(): ModelConnectionCommandReceipt {
  return { commandId: crypto.randomUUID(), domainEventId: crypto.randomUUID(), committedVersion: 5, correlationId: crypto.randomUUID() }
}
