import { CrewScopeApiError } from '../../api/client'
import {
  actionBundle,
  deliveryIds,
  etaggedActionBundle,
  githubBinding,
  githubConnection,
  githubHealth,
  githubPreflight,
  githubRepository,
} from '../../test/deliveryFixtures'
import { fixtureIds } from '../../test/scopeFixtures'
import { taskIds } from '../../test/taskFixtures'
import type { CommandReceipt } from '../scope/types'
import type { DeliveryGateway } from './gateway'
import { createDeliveryStore, deliveryBundleKey } from './store'
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

const platform = { organizationId: fixtureIds.organization, teamId: fixtureIds.teamPlatform }
const coordinates = { taskId: taskIds.first, executionId: taskIds.execution }

describe('DeliveryStore', () => {
  it('selects a usable Connection, default Binding and stable Repository before Preflight', async () => {
    const gateway = new FixtureDeliveryGateway()
    const store = createDeliveryStore(gateway)

    await store.synchronize(platform, coordinates)
    expect(store.state.selectedConnectionId).toBe(deliveryIds.connection)
    expect(store.state.selectedBindingId).toBe(deliveryIds.binding)
    expect(store.state.selectedRepositoryId).toBe(deliveryIds.repository)
    expect(store.state.preflight.phase).toBe('idle')

    expect(await store.preflightSelected()).toBe(true)
    expect(store.state.preflight.value?.permissionsHash).toBe('1'.repeat(64))
  })

  it('retries Confirmation with the original idempotency key and never adds an optimistic Dispatch', async () => {
    const gateway = new FixtureDeliveryGateway()
    const keys: string[] = []
    gateway.confirm = vi.fn(async (_scope, _coordinates, _bundle, key) => {
      keys.push(key)
      if (keys.length === 1) throw apiError(503, true)
      return receipt()
    })
    const store = createDeliveryStore(gateway)
    await store.synchronize(platform, coordinates)
    const before = selected(store).actions.map(item => item.dispatch)

    expect(await store.confirm()).toBe(false)
    expect(selected(store).actions.map(item => item.dispatch)).toEqual(before)
    expect(await store.retryCommand()).toBe(true)

    expect(keys).toHaveLength(2)
    expect(keys[0]).toBe(keys[1])
  })

  it('rejects a stale Digest through 412 and replaces it with the authoritative Bundle', async () => {
    const gateway = new FixtureDeliveryGateway()
    let current = etaggedActionBundle({
      taskId: coordinates.taskId, taskExecutionId: coordinates.executionId,
    })
    gateway.getBundle = vi.fn(async () => current)
    gateway.listBundles = vi.fn(async () => [current.value])
    gateway.confirm = vi.fn(async () => {
      current = etaggedActionBundle({
        taskId: coordinates.taskId, taskExecutionId: coordinates.executionId,
        version: 1, digest: 'f'.repeat(64), validity: 'STALE', staleReason: 'REVIEW_DECISION_CHANGED',
      })
      throw apiError(412, false)
    })
    const store = createDeliveryStore(gateway)
    await store.synchronize(platform, coordinates)

    expect(await store.confirm()).toBe(false)

    expect(store.state.command.phase).toBe('conflict')
    expect(selected(store).digest).toBe('f'.repeat(64))
    expect(selected(store).validity).toBe('STALE')
    expect(await store.retryCommand()).toBe(false)
  })

  it('preserves Push success when Draft PR fails and refreshes Webhook ExternalResult monotonically', async () => {
    const gateway = new FixtureDeliveryGateway()
    let webhookObserved = false
    gateway.getBundle = vi.fn(async () => deliveryBundle(webhookObserved))
    gateway.listBundles = vi.fn(async () => [deliveryBundle(webhookObserved).value])
    gateway.confirm = vi.fn(gateway.confirm.bind(gateway))
    const store = createDeliveryStore(gateway)
    await store.synchronize(platform, coordinates)

    expect(selected(store).actions[0]?.receipt?.result).toBe('SUCCEEDED')
    expect(selected(store).actions[1]?.receipt?.result).toBe('FAILED')
    expect(gateway.confirm).not.toHaveBeenCalled()

    webhookObserved = true
    await store.refresh()
    expect(selected(store).actions[1]?.externalResult?.status).toBe('OPEN')
    expect(selected(store).actions[1]?.externalResult?.source).toBe('WEBHOOK')
    expect(selected(store).actions[0]?.receipt?.result).toBe('SUCCEEDED')
  })

  it('drops late Connection results after a Team Scope change', async () => {
    const gateway = new FixtureDeliveryGateway()
    const slow = deferred<GitHubConnection[]>()
    gateway.listConnections = vi.fn()
      .mockImplementationOnce(() => slow.promise)
      .mockResolvedValueOnce([])
      .mockResolvedValueOnce([githubConnection({ id: '00000000-0000-0000-0000-000000009299' })])
      .mockResolvedValueOnce([])
    const store = createDeliveryStore(gateway)
    const old = store.synchronize(platform, coordinates)
    await store.synchronize({ ...platform, teamId: fixtureIds.teamSecurity }, coordinates)
    slow.resolve([githubConnection()])
    await old

    expect(store.state.connections.value?.map(item => item.id)).toEqual(['00000000-0000-0000-0000-000000009299'])
  })
})

function selected(store: ReturnType<typeof createDeliveryStore>): ActionBundle {
  return store.state.bundleDetails[deliveryBundleKey(coordinates, deliveryIds.bundle)]!.value!.value
}

function deliveryBundle(webhookObserved: boolean): EtaggedActionBundle {
  const value = actionBundle({
    taskId: coordinates.taskId, taskExecutionId: coordinates.executionId, version: 4,
  })
  value.actions[0] = {
    ...value.actions[0]!, dispatch: {
      id: deliveryIds.pushDispatch, version: 2, status: 'SUCCEEDED', claimAttempts: 1,
      reconciliationAttempts: 0, nextAttemptAt: '2026-08-25T08:00:00Z', cancellationReason: null,
      compensationDisposition: 'NONE',
    }, receipt: {
      id: crypto.randomUUID(), result: 'SUCCEEDED', source: 'WORKER', externalObjectType: 'BRANCH',
      externalIdentityHash: '2'.repeat(64), targetVersion: 'c'.repeat(40), evidenceCode: 'REMOTE_HEAD_MATCHED',
      manualReason: null, receivedAt: '2026-08-25T08:01:00Z',
    },
  }
  value.actions[1] = {
    ...value.actions[1]!, dispatch: {
      id: deliveryIds.pullRequestDispatch, version: webhookObserved ? 5 : 3,
      status: webhookObserved ? 'SUCCEEDED' : 'FAILED', claimAttempts: 1,
      reconciliationAttempts: webhookObserved ? 1 : 0, nextAttemptAt: '2026-08-25T08:02:00Z',
      cancellationReason: null, compensationDisposition: 'NONE',
    }, receipt: {
      id: crypto.randomUUID(), result: webhookObserved ? 'SUCCEEDED' : 'FAILED', source: 'WORKER',
      externalObjectType: webhookObserved ? 'PULL_REQUEST' : null,
      externalIdentityHash: webhookObserved ? '3'.repeat(64) : null, targetVersion: null,
      evidenceCode: webhookObserved ? 'DRAFT_PR_VERIFIED' : 'PROVIDER_UNAVAILABLE', manualReason: null,
      receivedAt: '2026-08-25T08:03:00Z',
    }, externalResult: webhookObserved ? {
      status: 'OPEN', externalObjectType: 'PULL_REQUEST', externalIdentityHash: '3'.repeat(64),
      providerVersion: 42, providerUpdatedAt: '2026-08-25T08:04:00Z', source: 'WEBHOOK',
      observedAt: '2026-08-25T08:04:01Z', version: 2,
    } : null,
  }
  return { value, etag: '"4"' }
}

class FixtureDeliveryGateway implements DeliveryGateway {
  async listConnections(_scope: DeliveryScope, ownerType: GitHubConnectionOwnerType): Promise<GitHubConnection[]> {
    return ownerType === 'TEAM' ? [githubConnection()] : []
  }
  async listBindings(): Promise<GitHubProviderBinding[]> { return [githubBinding()] }
  async listRepositories(): Promise<GitHubRepository[]> { return [githubRepository()] }
  async synchronizeRepositories(): Promise<GitHubRepository[]> { return [githubRepository()] }
  async preflight(): Promise<GitHubRemotePreflight> { return githubPreflight() }
  async health(): Promise<GitHubAuthorizationHealth> { return githubHealth() }
  async listBundles(): Promise<ActionBundle[]> {
    return [actionBundle({ taskId: coordinates.taskId, taskExecutionId: coordinates.executionId })]
  }
  async getBundle(): Promise<EtaggedActionBundle> {
    return etaggedActionBundle({ taskId: coordinates.taskId, taskExecutionId: coordinates.executionId })
  }
  async plan(_scope: DeliveryScope, _coordinates: DeliveryCoordinates, _input: PlanActionBundleInput, _key: string): Promise<CommandReceipt> { return receipt() }
  async confirm(_scope: DeliveryScope, _coordinates: DeliveryCoordinates, _bundle: EtaggedActionBundle, _key: string): Promise<CommandReceipt> { return receipt() }
  async cancel(): Promise<CommandReceipt> { return receipt() }
  async resolveFailure(): Promise<CommandReceipt> { return receipt() }
}

function receipt(): CommandReceipt {
  return { commandId: crypto.randomUUID(), domainEventId: crypto.randomUUID(), committedVersion: 1, correlationId: crypto.randomUUID() }
}

function apiError(status: number, retryable: boolean): CrewScopeApiError {
  return new CrewScopeApiError(status, {
    code: status === 412 ? 'bundle_digest_changed' : 'provider_unavailable',
    message: status === 412 ? 'ActionBundle Digest 已变化' : 'Provider 暂时不可用',
    correlationId: crypto.randomUUID(), retryable, currentVersion: status === 412 ? 1 : null, details: {},
  })
}

function deferred<T>(): { promise: Promise<T>, resolve: (value: T) => void } {
  let resolve!: (value: T) => void
  const promise = new Promise<T>(next => { resolve = next })
  return { promise, resolve }
}
