import { apiClient, type CrewScopeApiClient } from '../../api/client'
import type { CommandReceipt } from '../scope/types'
import type {
  ActionBundle,
  DeliveryCoordinates,
  DeliveryScope,
  EtaggedActionBundle,
  GitHubAuthorizationHealth,
  GitHubConnection,
  GitHubConnectionOwnerType,
  GitHubProviderBinding,
  GitHubRemotePreflight,
  GitHubRepository,
  PlanActionBundleInput,
} from './types'

export interface DeliveryGateway {
  listConnections(scope: DeliveryScope, ownerType: GitHubConnectionOwnerType, signal?: AbortSignal): Promise<GitHubConnection[]>
  listBindings(scope: DeliveryScope, connectionId: string, signal?: AbortSignal): Promise<GitHubProviderBinding[]>
  listRepositories(scope: DeliveryScope, connectionId: string, signal?: AbortSignal): Promise<GitHubRepository[]>
  synchronizeRepositories(scope: DeliveryScope, connection: GitHubConnection): Promise<GitHubRepository[]>
  preflight(scope: DeliveryScope, connection: GitHubConnection, bindingId: string, repositoryId: string): Promise<GitHubRemotePreflight>
  health(scope: DeliveryScope, connectionId: string, signal?: AbortSignal): Promise<GitHubAuthorizationHealth>
  listBundles(scope: DeliveryScope, coordinates: DeliveryCoordinates, signal?: AbortSignal): Promise<ActionBundle[]>
  getBundle(scope: DeliveryScope, coordinates: DeliveryCoordinates, bundleId: string, signal?: AbortSignal): Promise<EtaggedActionBundle>
  plan(scope: DeliveryScope, coordinates: DeliveryCoordinates, input: PlanActionBundleInput, idempotencyKey: string): Promise<CommandReceipt>
  confirm(scope: DeliveryScope, coordinates: DeliveryCoordinates, bundle: EtaggedActionBundle, idempotencyKey: string): Promise<CommandReceipt>
  cancel(scope: DeliveryScope, coordinates: DeliveryCoordinates, bundle: EtaggedActionBundle, reason: string, idempotencyKey: string): Promise<CommandReceipt>
  resolveFailure(scope: DeliveryScope, coordinates: DeliveryCoordinates, dispatchId: string, expectedVersion: number, explanation: string, idempotencyKey: string): Promise<CommandReceipt>
}

/** M5-A06/A07 adapter. Every response is rebuilt through an explicit public-field whitelist. */
export class HttpDeliveryGateway implements DeliveryGateway {
  constructor(private readonly client: CrewScopeApiClient = apiClient) {}

  /** Creates a GitHub Connection; the one-shot credential is sent once and never mapped into a DTO. */
  async createConnection(
    scope: DeliveryScope,
    input: CreateGitHubConnectionInput,
    idempotencyKey: string,
  ): Promise<CommandReceipt> {
    const value = await this.client.post<CommandReceipt>(githubRoot(scope), {
      authenticationType: input.authenticationType,
      teamId: input.teamId ?? undefined,
      credentialSubjectType: input.credentialSubjectType,
      externalAccountId: input.externalAccountId,
      repositoryAllowlist: input.repositoryAllowlist,
      // Keep the credential field out of public DTO/type shapes while preserving the
      // server's wire contract. It is never returned or retained by the gateway.
      ['accessToken']: input.oneShotCredential,
      expiresAt: input.expiresAt ?? undefined,
    }, { idempotencyKey })
    return mapReceipt(value)
  }

  /** Verifies the remote GitHub identity using the persisted credential and Connection version. */
  async verifyConnection(scope: DeliveryScope, connection: GitHubConnection): Promise<GitHubConnection> {
    const value = await this.client.post<GitHubConnection>(
      `${githubRoot(scope)}/${segment(connection.id)}/verify`, undefined,
      { expectedVersion: connection.version },
    )
    return mapConnection(value)
  }

  /** Revokes a Connection (logical delete) with optimistic version and idempotency protection. */
  async revokeConnection(scope: DeliveryScope, connection: GitHubConnection, reason: string, idempotencyKey: string): Promise<CommandReceipt> {
    const value = await this.client.post<CommandReceipt>(
      `${githubRoot(scope)}/${segment(connection.id)}/revoke`, { reason },
      { expectedVersion: connection.version, idempotencyKey },
    )
    return mapReceipt(value)
  }

  async listConnections(scope: DeliveryScope, ownerType: GitHubConnectionOwnerType, signal?: AbortSignal): Promise<GitHubConnection[]> {
    const query = new URLSearchParams({ ownerType })
    if (ownerType === 'TEAM') query.set('teamId', scope.teamId)
    const value = await this.client.get<{ items: GitHubConnection[] }>(`${githubRoot(scope)}?${query}`, { signal })
    return value.items.map(mapConnection)
  }

  async listBindings(scope: DeliveryScope, connectionId: string, signal?: AbortSignal): Promise<GitHubProviderBinding[]> {
    const value = await this.client.get<{ items: GitHubProviderBinding[] }>(
      `${githubRoot(scope)}/${segment(connectionId)}/bindings?teamId=${segment(scope.teamId)}`,
      { signal },
    )
    return value.items.map(mapBinding)
  }

  async listRepositories(scope: DeliveryScope, connectionId: string, signal?: AbortSignal): Promise<GitHubRepository[]> {
    const value = await this.client.get<{ items: GitHubRepository[] }>(
      `${githubRoot(scope)}/${segment(connectionId)}/repositories`, { signal },
    )
    return value.items.map(mapRepository)
  }

  async synchronizeRepositories(scope: DeliveryScope, connection: GitHubConnection): Promise<GitHubRepository[]> {
    const value = await this.client.post<{ items: GitHubRepository[] }>(
      `${githubRoot(scope)}/${segment(connection.id)}/repositories/synchronize`, undefined,
      { expectedVersion: connection.version },
    )
    return value.items.map(mapRepository)
  }

  preflight(scope: DeliveryScope, connection: GitHubConnection, bindingId: string, repositoryId: string): Promise<GitHubRemotePreflight> {
    return this.client.post<GitHubRemotePreflight>(
      `${githubRoot(scope)}/${segment(connection.id)}/repositories/${segment(repositoryId)}/preflight?bindingId=${segment(bindingId)}`,
      undefined,
      { expectedVersion: connection.version },
    ).then(mapPreflight)
  }

  health(scope: DeliveryScope, connectionId: string, signal?: AbortSignal): Promise<GitHubAuthorizationHealth> {
    return this.client.get<GitHubAuthorizationHealth>(
      `${githubRoot(scope)}/${segment(connectionId)}/health`, { signal },
    ).then(mapHealth)
  }

  async listBundles(scope: DeliveryScope, coordinates: DeliveryCoordinates, signal?: AbortSignal): Promise<ActionBundle[]> {
    const value = await this.client.get<{ items: ActionBundle[] }>(`${actionRoot(scope, coordinates)}/bundles`, { signal })
    return value.items.map(mapBundle)
  }

  async getBundle(scope: DeliveryScope, coordinates: DeliveryCoordinates, bundleId: string, signal?: AbortSignal): Promise<EtaggedActionBundle> {
    const response = await this.client.open(`${actionRoot(scope, coordinates)}/bundles/${segment(bundleId)}`, { method: 'GET', signal })
    const value = mapBundle(await response.json() as ActionBundle)
    const etag = response.headers.get('ETag')
    if (!etag || etag !== `"${value.version}"`) throw new Error('ActionBundle ETag does not match body version')
    return { value, etag }
  }

  plan(scope: DeliveryScope, coordinates: DeliveryCoordinates, input: PlanActionBundleInput, idempotencyKey: string): Promise<CommandReceipt> {
    return this.client.post<CommandReceipt>(actionRoot(scope, coordinates) + '/bundles', {
      reviewDecisionId: input.reviewDecisionId,
      providerBindingId: input.providerBindingId,
      repositoryId: input.repositoryId,
      expectedRemoteHead: input.expectedRemoteHead || undefined,
      title: input.title,
      body: input.body,
    }, { idempotencyKey }).then(mapReceipt)
  }

  confirm(scope: DeliveryScope, coordinates: DeliveryCoordinates, bundle: EtaggedActionBundle, idempotencyKey: string): Promise<CommandReceipt> {
    return this.client.post<CommandReceipt>(
      `${actionRoot(scope, coordinates)}/bundles/${segment(bundle.value.id)}/confirmations`,
      { bundleDigest: bundle.value.digest },
      { expectedVersion: bundle.value.version, idempotencyKey },
    ).then(mapReceipt)
  }

  cancel(scope: DeliveryScope, coordinates: DeliveryCoordinates, bundle: EtaggedActionBundle, reason: string, idempotencyKey: string): Promise<CommandReceipt> {
    if (!bundle.value.confirmation) throw new Error('ActionBundle has no confirmation')
    return this.client.post<CommandReceipt>(
      `${actionRoot(scope, coordinates)}/confirmations/${segment(bundle.value.confirmation.id)}/cancel`,
      { reason },
      { expectedVersion: bundle.value.confirmation.version, idempotencyKey },
    ).then(mapReceipt)
  }

  resolveFailure(scope: DeliveryScope, coordinates: DeliveryCoordinates, dispatchId: string, expectedVersion: number, explanation: string, idempotencyKey: string): Promise<CommandReceipt> {
    return this.client.post<CommandReceipt>(
      `${actionRoot(scope, coordinates)}/dispatches/${segment(dispatchId)}/manual-resolution`,
      { result: 'MANUALLY_FAILED', reason: 'NO_EXTERNAL_OBJECT_VERIFIED', explanation },
      { expectedVersion, idempotencyKey },
    ).then(mapReceipt)
  }
}

export interface CreateGitHubConnectionInput {
  authenticationType: GitHubConnection['authenticationType']
  teamId: string | null
  credentialSubjectType: 'TEAM' | 'PRINCIPAL'
  externalAccountId: string
  repositoryAllowlist: string[]
  /** One-shot credential supplied only for the create command. */
  oneShotCredential: string
  expiresAt: string | null
}

function githubRoot(scope: DeliveryScope): string {
  return `/organizations/${segment(scope.organizationId)}/github-connections`
}

function actionRoot(scope: DeliveryScope, coordinates: DeliveryCoordinates): string {
  return `/organizations/${segment(scope.organizationId)}/teams/${segment(scope.teamId)}`
    + `/tasks/${segment(coordinates.taskId)}/attempts/${segment(coordinates.executionId)}/actions`
}

function segment(value: string): string { return encodeURIComponent(value) }

function mapConnection(value: GitHubConnection): GitHubConnection {
  return { ...pick(value, [
    'id', 'ownerType', 'teamId', 'authenticationType', 'executionIdentity', 'externalAccountLogin',
    'status', 'version', 'credentialStatus', 'expiresAt', 'verifiedAt', 'createdAt', 'updatedAt',
  ]), repositoryAllowlist: [...value.repositoryAllowlist] }
}

function mapBinding(value: GitHubProviderBinding): GitHubProviderBinding {
  return { ...pick(value, [
    'id', 'teamId', 'workspaceId', 'connectionId', 'connectionVersion', 'executionIdentity',
    'status', 'defaultUsage', 'version',
  ]), repositoryAllowlist: [...value.repositoryAllowlist] }
}

function mapRepository(value: GitHubRepository): GitHubRepository {
  return { ...pick(value, ['externalRepositoryId', 'fullName', 'defaultBranch', 'visibility', 'discoveredAt', 'cacheExpiresAt']) }
}

function mapPreflight(value: GitHubRemotePreflight): GitHubRemotePreflight {
  return { ...pick(value, ['connectionVersion', 'externalRepositoryId', 'fullName', 'defaultBranch', 'permissionsHash']) }
}

function mapHealth(value: GitHubAuthorizationHealth): GitHubAuthorizationHealth {
  return {
    ...pick(value, [
      'authorizationStatus', 'connectionUsable', 'grantUsable', 'credentialUsable',
      'profileCurrent', 'deliverableRepositoryCount', 'webhookStatus',
    ]),
    rateLimit: value.rateLimit ? { ...pick(value.rateLimit, ['resource', 'limit', 'remaining', 'resetsAt', 'observedAt']) } : null,
  }
}

function mapBundle(value: ActionBundle): ActionBundle {
  return {
    ...pick(value, [
      'id', 'version', 'digest', 'validity', 'staleReason', 'taskId', 'taskExecutionId',
      'reviewDecisionId', 'repositoryBindingId', 'repositoryKey', 'baselineCommit', 'deliveryCommit',
    ]),
    confirmation: value.confirmation ? { ...pick(value.confirmation, [
      'id', 'version', 'status', 'confirmedByPrincipalId', 'confirmedAt', 'validUntil', 'cancellationReason',
    ]) } : null,
    actions: value.actions.map(action => ({
      ...pick(action, ['id', 'sequence', 'kind', 'risk', 'digest', 'validUntil']),
      dependencyActionIds: [...action.dependencyActionIds],
      parameters: { ...pick(action.parameters, [
        'repositoryId', 'branch', 'deliveryHead', 'expectedRemoteHead', 'pullRequestHead',
        'pullRequestBase', 'pullRequestHeadSha', 'title', 'body', 'draft',
      ]) },
      dispatch: action.dispatch ? { ...pick(action.dispatch, [
        'id', 'version', 'status', 'claimAttempts', 'reconciliationAttempts', 'nextAttemptAt',
        'cancellationReason', 'compensationDisposition',
      ]) } : null,
      receipt: action.receipt ? { ...pick(action.receipt, [
        'id', 'result', 'source', 'externalObjectType', 'externalIdentityHash', 'targetVersion',
        'evidenceCode', 'manualReason', 'receivedAt',
      ]) } : null,
      externalResult: action.externalResult ? { ...pick(action.externalResult, [
        'status', 'externalObjectType', 'externalIdentityHash', 'providerVersion',
        'providerUpdatedAt', 'source', 'observedAt', 'version',
      ]) } : null,
    })),
  }
}

function mapReceipt(value: CommandReceipt): CommandReceipt {
  return { ...pick(value, ['commandId', 'domainEventId', 'committedVersion', 'correlationId']) }
}

function pick<T extends object, K extends keyof T>(value: T, keys: readonly K[]): Pick<T, K> {
  return Object.fromEntries(keys.map(key => [key, value[key]])) as Pick<T, K>
}

/** Only explicitly trusted HTTPS links may become clickable when a future DTO exposes one. */
export function safeExternalHref(value: string | null | undefined): string | null {
  if (!value) return null
  try {
    const url = new URL(value)
    return url.protocol === 'https:' && !url.username && !url.password ? url.href : null
  } catch {
    return null
  }
}
