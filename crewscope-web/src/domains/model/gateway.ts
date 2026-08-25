import { apiClient, type CrewScopeApiClient } from '../../api/client'
import type { Etagged, OffsetPage, SettingsScope } from '../settings/types'
import type {
  CreateModelConnectionInput,
  ModelCatalogEntrySummary,
  ModelConnectionCommandReceipt,
  ModelConnectionOwnerType,
  ModelConnectionSummary,
  ModelConnectionTransition,
  ModelPriceSummary,
  ModelProviderSummary,
  RotateModelCredentialInput,
} from './types'

export interface ModelGateway {
  listProviders(organizationId: string, offset?: number, limit?: number, signal?: AbortSignal): Promise<OffsetPage<ModelProviderSummary>>
  listCatalog(organizationId: string, providerKey: string, offset?: number, limit?: number, signal?: AbortSignal): Promise<OffsetPage<ModelCatalogEntrySummary>>
  listConnections(scope: SettingsScope, ownerType: ModelConnectionOwnerType, offset?: number, limit?: number, signal?: AbortSignal): Promise<OffsetPage<ModelConnectionSummary>>
  getConnection(organizationId: string, connectionId: string, signal?: AbortSignal): Promise<Etagged<ModelConnectionSummary>>
  createConnection(input: CreateModelConnectionInput, organizationId: string, idempotencyKey: string): Promise<ModelConnectionCommandReceipt>
  verifyConnection(organizationId: string, connection: ModelConnectionSummary, etag: string, idempotencyKey: string): Promise<ModelConnectionCommandReceipt>
  rotateCredential(organizationId: string, connectionId: string, etag: string, input: RotateModelCredentialInput, idempotencyKey: string): Promise<ModelConnectionCommandReceipt>
  suspendConnection(organizationId: string, connection: ModelConnectionSummary, etag: string, idempotencyKey: string): Promise<ModelConnectionCommandReceipt>
  revokeConnection(organizationId: string, connection: ModelConnectionSummary, etag: string, reason: string, idempotencyKey: string): Promise<ModelConnectionCommandReceipt>
}

/** A01 HTTP adapter with a second explicit browser disclosure whitelist. */
export class HttpModelGateway implements ModelGateway {
  constructor(private readonly client: CrewScopeApiClient = apiClient) {}

  async listProviders(
    organizationId: string,
    offset = 0,
    limit = 50,
    signal?: AbortSignal,
  ): Promise<OffsetPage<ModelProviderSummary>> {
    const value = await this.client.get<{ items: ModelProviderSummary[] }>(
      `${providerRoot(organizationId)}?${offsetSearch(offset, limit)}`,
      { signal },
    )
    const items = value.items.map(mapProvider)
    return offsetPage(items, offset, limit)
  }

  async listCatalog(
    organizationId: string,
    providerKey: string,
    offset = 0,
    limit = 50,
    signal?: AbortSignal,
  ): Promise<OffsetPage<ModelCatalogEntrySummary>> {
    const value = await this.client.get<{ items: ModelCatalogEntrySummary[] }>(
      `${providerRoot(organizationId)}/${segment(providerKey)}/catalog?${offsetSearch(offset, limit)}`,
      { signal },
    )
    const items = value.items.map(mapCatalogEntry)
    return offsetPage(items, offset, limit)
  }

  async listConnections(
    scope: SettingsScope,
    ownerType: ModelConnectionOwnerType,
    offset = 0,
    limit = 50,
    signal?: AbortSignal,
  ): Promise<OffsetPage<ModelConnectionSummary>> {
    const search = offsetSearch(offset, limit)
    search.set('ownerType', ownerType)
    if (ownerType === 'TEAM') search.set('teamId', scope.teamId)
    const value = await this.client.get<{ items: ModelConnectionSummary[] }>(
      `${connectionRoot(scope.organizationId)}?${search}`,
      { signal },
    )
    const items = value.items.map(mapConnection)
    return offsetPage(items, offset, limit)
  }

  async getConnection(
    organizationId: string,
    connectionId: string,
    signal?: AbortSignal,
  ): Promise<Etagged<ModelConnectionSummary>> {
    const response = await this.client.open(
      `${connectionRoot(organizationId)}/${segment(connectionId)}`,
      { method: 'GET', signal },
    )
    return {
      value: mapConnection(await response.json() as ModelConnectionSummary),
      etag: requireStrongEtag(response),
    }
  }

  async createConnection(
    input: CreateModelConnectionInput,
    organizationId: string,
    idempotencyKey: string,
  ): Promise<ModelConnectionCommandReceipt> {
    const value = await this.client.post<ModelConnectionCommandReceipt>(connectionRoot(organizationId), {
      providerKey: input.providerKey,
      ownerType: input.ownerType,
      teamId: input.teamId,
      region: input.region,
      apiKey: input.apiKey,
      credentialExpiresAt: input.credentialExpiresAt,
    }, { idempotencyKey })
    return mapReceipt(value)
  }

  async verifyConnection(
    organizationId: string,
    connection: ModelConnectionSummary,
    etag: string,
    idempotencyKey: string,
  ): Promise<ModelConnectionCommandReceipt> {
    return this.transition(organizationId, connection, etag, 'verify', idempotencyKey)
  }

  async rotateCredential(
    organizationId: string,
    connectionId: string,
    etag: string,
    input: RotateModelCredentialInput,
    idempotencyKey: string,
  ): Promise<ModelConnectionCommandReceipt> {
    const value = await this.client.post<ModelConnectionCommandReceipt>(
      `${connectionRoot(organizationId)}/${segment(connectionId)}/rotate`,
      { credentialVersion: input.credentialVersion, apiKey: input.apiKey },
      { expectedVersion: etagVersion(etag), idempotencyKey },
    )
    return mapReceipt(value)
  }

  async suspendConnection(
    organizationId: string,
    connection: ModelConnectionSummary,
    etag: string,
    idempotencyKey: string,
  ): Promise<ModelConnectionCommandReceipt> {
    return this.transition(organizationId, connection, etag, 'suspend', idempotencyKey)
  }

  async revokeConnection(
    organizationId: string,
    connection: ModelConnectionSummary,
    etag: string,
    reason: string,
    idempotencyKey: string,
  ): Promise<ModelConnectionCommandReceipt> {
    const value = await this.client.post<ModelConnectionCommandReceipt>(
      `${connectionRoot(organizationId)}/${segment(connection.id)}/revoke`,
      { credentialVersion: connection.credentialVersion, reason },
      { expectedVersion: etagVersion(etag), idempotencyKey },
    )
    return mapReceipt(value)
  }

  private async transition(
    organizationId: string,
    connection: ModelConnectionSummary,
    etag: string,
    operation: ModelConnectionTransition,
    idempotencyKey: string,
  ): Promise<ModelConnectionCommandReceipt> {
    const value = await this.client.post<ModelConnectionCommandReceipt>(
      `${connectionRoot(organizationId)}/${segment(connection.id)}/${operation}`,
      { credentialVersion: connection.credentialVersion },
      { expectedVersion: etagVersion(etag), idempotencyKey },
    )
    return mapReceipt(value)
  }
}

function providerRoot(organizationId: string): string {
  return `/organizations/${segment(organizationId)}/model-providers`
}

function connectionRoot(organizationId: string): string {
  return `/organizations/${segment(organizationId)}/model-connections`
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

function mapProvider(value: ModelProviderSummary): ModelProviderSummary {
  return {
    ...pick(value, [
      'key', 'displayName', 'retentionMode', 'maximumRetentionSeconds', 'trainingUsagePolicy',
      'status', 'version',
    ]),
    availableRegions: [...value.availableRegions],
  }
}

function mapCatalogEntry(value: ModelCatalogEntrySummary): ModelCatalogEntrySummary {
  return {
    ...pick(value, [
      'id', 'providerKey', 'modelId', 'catalogRevision', 'modelRevision', 'displayName',
      'contextWindowTokens', 'maximumOutputTokens', 'status', 'version',
    ]),
    capabilities: [...value.capabilities],
    availableRegions: [...value.availableRegions],
    effectivePrice: value.effectivePrice ? mapPrice(value.effectivePrice) : null,
  }
}

function mapPrice(value: ModelPriceSummary): ModelPriceSummary {
  return { ...pick(value, [
    'revision', 'effectiveFrom', 'inputPerMillionTokens', 'outputPerMillionTokens',
    'cachedInputPerMillionTokens', 'currencyCode',
  ]) }
}

function mapConnection(value: ModelConnectionSummary): ModelConnectionSummary {
  const ownerType = connectionOwnerType(value.ownerType)
  return {
    ...pick(value, [
      'id', 'organizationId', 'providerKey', 'ownerId', 'region', 'billingSubjectType',
      'billingSubjectId', 'credentialVersion', 'status', 'healthStatus', 'healthFailureCode',
      'checkedAt', 'lastHealthyAt', 'consecutiveFailures', 'revocationReason', 'createdAt',
      'updatedAt', 'version',
    ]),
    ownerType,
  }
}

function connectionOwnerType(value: string): ModelConnectionOwnerType {
  if (value === 'USER' || value === 'TEAM' || value === 'ORGANIZATION') return value
  throw new TypeError('Model Connection owner type is invalid')
}

function mapReceipt(value: ModelConnectionCommandReceipt): ModelConnectionCommandReceipt {
  return { ...pick(value, ['commandId', 'domainEventId', 'committedVersion', 'correlationId']) }
}

function requireStrongEtag(response: Response): string {
  const etag = response.headers.get('ETag')
  if (!etag || etag.startsWith('W/') || !/^"[^"]+"$/.test(etag)) {
    throw new TypeError('Model response strong ETag is missing')
  }
  return etag
}

function etagVersion(etag: string): number {
  if (!/^"\d+"$/.test(etag)) throw new TypeError('Model response ETag is invalid')
  const version = Number(etag.slice(1, -1))
  if (!Number.isSafeInteger(version)) throw new TypeError('Model response ETag is invalid')
  return version
}

function pick<T extends object, K extends keyof T>(value: T, keys: readonly K[]): Pick<T, K> {
  return Object.fromEntries(keys.map(key => [key, value[key]])) as Pick<T, K>
}
