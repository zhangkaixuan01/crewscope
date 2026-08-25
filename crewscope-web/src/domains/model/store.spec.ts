import { fixtureIds } from '../../test/scopeFixtures'
import { nextTick, watch } from 'vue'
import type { Etagged, OffsetPage, SettingsScope } from '../settings/types'
import type { ModelGateway } from './gateway'
import { createModelStore } from './store'
import type {
  CreateModelConnectionInput,
  ModelCatalogEntrySummary,
  ModelConnectionCommandReceipt,
  ModelConnectionOwnerType,
  ModelConnectionSummary,
  ModelProviderSummary,
  RotateModelCredentialInput,
} from './types'

const platformScope = { organizationId: fixtureIds.organization, teamId: fixtureIds.teamPlatform }
const securityScope = { organizationId: fixtureIds.organization, teamId: fixtureIds.teamSecurity }
const connectionId = '00000000-0000-0000-0000-000000005101'

describe('ModelStore', () => {
  it('publishes dynamic Connection detail phases through the reactive proxy', async () => {
    const gateway = new FixtureModelGateway()
    const detail = deferred<Etagged<ModelConnectionSummary>>()
    gateway.getConnection = vi.fn(async () => detail.promise)
    const store = createModelStore(gateway)
    store.activateScope(platformScope)
    const phases: Array<string | undefined> = []
    const stop = watch(() => store.state.connectionDetails[connectionId]?.phase, phase => phases.push(phase))

    const request = store.loadConnection(connectionId)
    await nextTick()
    detail.resolve({ value: connection(connectionId), etag: '"4"' })
    await request
    await nextTick()

    expect(phases).toContain('loading')
    expect(phases.at(-1)).toBe('ready')
    stop()
  })

  it('isolates a late response after the selected Team Scope changes', async () => {
    const gateway = new FixtureModelGateway()
    const first = deferred<OffsetPage<ModelProviderSummary>>()
    gateway.listProviders = vi.fn(async () => first.promise)
      .mockImplementationOnce(async () => first.promise)
      .mockImplementationOnce(async () => ({ items: [provider('security')], nextOffset: null }))
    const store = createModelStore(gateway)

    store.activateScope(platformScope)
    const slow = store.loadProviders()
    store.activateScope(securityScope)
    await store.loadProviders()
    first.resolve({ items: [provider('platform')], nextOffset: null })
    await slow

    expect(store.state.providers.value?.map(item => item.key)).toEqual(['security'])
    expect(store.state.connections).toEqual({})
  })

  it('continues bounded offset pages and removes overlap without inventing a Cursor', async () => {
    const gateway = new FixtureModelGateway()
    const offsets: number[] = []
    gateway.listConnections = vi.fn(async (_scope, _owner, offset = 0) => {
      offsets.push(offset)
      return offset === 0
        ? { items: [connection('connection-1')], nextOffset: 50 }
        : { items: [connection('connection-1'), connection('connection-2')], nextOffset: null }
    })
    const store = createModelStore(gateway)
    store.activateScope(platformScope)

    await store.loadConnections('TEAM')
    await store.loadConnections('TEAM', true)

    expect(offsets).toEqual([0, 50])
    expect(store.state.connections.TEAM?.value?.map(item => item.id)).toEqual(['connection-1', 'connection-2'])
    expect(store.state.connections.TEAM?.nextOffset).toBeNull()
  })

  it('never retains an API Key in reactive state or a retry closure', async () => {
    const gateway = new FixtureModelGateway()
    const store = createModelStore(gateway)
    store.activateScope(platformScope)

    const result = await store.createConnection({
      providerKey: 'deepseek', ownerType: 'USER', teamId: null, region: 'cn',
      apiKey: 'one-way-secret', credentialExpiresAt: null,
    }, 'stable-key')

    expect(result).toBe(true)
    expect(gateway.seenApiKey).toBe('one-way-secret')
    expect(JSON.stringify(store.state)).not.toContain('one-way-secret')
    expect(JSON.stringify(store.state.command)).not.toContain('stable-key')
    expect(store.state.command.phase).toBe('success')
  })

  it('uses the loaded strong ETag for a credential rotation and invalidates stale facts', async () => {
    const gateway = new FixtureModelGateway()
    const store = createModelStore(gateway)
    store.activateScope(platformScope)

    await store.loadConnection(connectionId)
    const result = await store.rotateCredential(connectionId, 2, 'rotated-secret', 'rotate-key')

    expect(result).toBe(true)
    expect(gateway.seenEtag).toBe('"4"')
    expect(store.state.connectionDetails[connectionId]).toBeUndefined()
    expect(store.state.connections).toEqual({})
    expect(JSON.stringify(store.state)).not.toContain('rotated-secret')
  })

  it('fails a cross-Team Connection page closed before it enters browser cache', async () => {
    const gateway = new FixtureModelGateway()
    gateway.listConnections = vi.fn(async () => ({
      items: [{ ...connection(connectionId), ownerId: fixtureIds.teamSecurity }],
      nextOffset: null,
    }))
    const store = createModelStore(gateway)
    store.activateScope(platformScope)

    await store.loadConnections('TEAM')

    expect(store.state.connections.TEAM?.phase).toBe('error')
    expect(store.state.connections.TEAM?.value).toBeNull()
  })

  it('ignores a completed command from the previous Scope without invalidating new caches', async () => {
    const gateway = new FixtureModelGateway()
    const command = deferred<ModelConnectionCommandReceipt>()
    gateway.createConnection = vi.fn(async () => command.promise)
    gateway.listConnections = vi.fn(async (_scope, ownerType) => ({
      items: [{
        ...connection('security-user-connection'),
        ownerType,
        ownerId: fixtureIds.principal,
      }],
      nextOffset: null,
    }))
    const store = createModelStore(gateway)
    store.activateScope(platformScope)

    const oldCommand = store.createConnection({
      providerKey: 'deepseek', ownerType: 'USER', teamId: null, region: 'cn',
      apiKey: 'one-way-secret', credentialExpiresAt: null,
    }, 'old-key')
    store.activateScope(securityScope)
    await store.loadConnections('USER')
    command.resolve(receipt())

    expect(await oldCommand).toBe(false)
    expect(store.state.connections.USER?.phase).toBe('ready')
    expect(store.state.connections.USER?.value?.[0]?.id).toBe('security-user-connection')
    expect(store.state.command.phase).toBe('idle')
  })
})

class FixtureModelGateway implements ModelGateway {
  seenApiKey: string | null = null
  seenEtag: string | null = null

  async listProviders(): Promise<OffsetPage<ModelProviderSummary>> {
    return { items: [provider('deepseek')], nextOffset: null }
  }

  async listCatalog(): Promise<OffsetPage<ModelCatalogEntrySummary>> {
    return { items: [], nextOffset: null }
  }

  async listConnections(
    _scope: SettingsScope,
    _ownerType: ModelConnectionOwnerType,
  ): Promise<OffsetPage<ModelConnectionSummary>> {
    return { items: [connection(connectionId)], nextOffset: null }
  }

  async getConnection(): Promise<Etagged<ModelConnectionSummary>> {
    return { value: connection(connectionId), etag: '"4"' }
  }

  async createConnection(input: CreateModelConnectionInput): Promise<ModelConnectionCommandReceipt> {
    this.seenApiKey = input.apiKey
    return receipt()
  }

  async verifyConnection(
    _organizationId: string,
    _connection: ModelConnectionSummary,
    etag: string,
  ): Promise<ModelConnectionCommandReceipt> {
    this.seenEtag = etag
    return receipt()
  }

  async rotateCredential(
    _organizationId: string,
    _connectionId: string,
    etag: string,
    input: RotateModelCredentialInput,
  ): Promise<ModelConnectionCommandReceipt> {
    this.seenEtag = etag
    this.seenApiKey = input.apiKey
    return receipt()
  }

  async suspendConnection(
    _organizationId: string,
    _connection: ModelConnectionSummary,
    etag: string,
  ): Promise<ModelConnectionCommandReceipt> {
    this.seenEtag = etag
    return receipt()
  }

  async revokeConnection(
    _organizationId: string,
    _connection: ModelConnectionSummary,
    etag: string,
  ): Promise<ModelConnectionCommandReceipt> {
    this.seenEtag = etag
    return receipt()
  }
}

function provider(key: string): ModelProviderSummary {
  return {
    key, displayName: key, availableRegions: ['cn'], retentionMode: 'NONE',
    maximumRetentionSeconds: null, trainingUsagePolicy: 'DISABLED', status: 'ACTIVE', version: 1,
  }
}

function connection(id: string): ModelConnectionSummary {
  return {
    id, organizationId: fixtureIds.organization, providerKey: 'deepseek', ownerType: 'TEAM',
    ownerId: fixtureIds.teamPlatform, region: 'cn', billingSubjectType: 'TEAM',
    billingSubjectId: fixtureIds.teamPlatform, credentialVersion: 2, status: 'ACTIVE',
    healthStatus: 'HEALTHY', healthFailureCode: null, checkedAt: '2026-08-24T01:00:00Z',
    lastHealthyAt: '2026-08-24T01:00:00Z', consecutiveFailures: 0, revocationReason: null,
    createdAt: '2026-08-23T01:00:00Z', updatedAt: '2026-08-24T01:00:00Z', version: 4,
  }
}

function receipt(): ModelConnectionCommandReceipt {
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
