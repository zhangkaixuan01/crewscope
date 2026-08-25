import { CrewScopeApiError } from '../../api/client'
import { fixtureIds } from '../../test/scopeFixtures'
import { etaggedReview, reviewDetails, reviewIds, reviewSummary } from '../../test/reviewFixtures'
import { taskIds } from '../../test/taskFixtures'
import type { CommandReceipt } from '../scope/types'
import type { ReviewGateway } from './gateway'
import { createReviewStore, reviewAttemptKey, reviewDetailKey } from './store'
import type {
  EtaggedReview,
  ReviewCoordinates,
  ReviewDecisionInput,
  ReviewerExecutionResult,
  ReviewScope,
  ReviewSummary,
} from './types'

const platform = { organizationId: fixtureIds.organization, teamId: fixtureIds.teamPlatform }
const security = { organizationId: fixtureIds.organization, teamId: fixtureIds.teamSecurity }
const coordinates = { taskId: taskIds.first, executionId: taskIds.execution }

describe('ReviewStore', () => {
  it('isolates a late Review response after Team Scope changes', async () => {
    const gateway = new FixtureReviewGateway()
    const slow = deferred<ReviewSummary[]>()
    gateway.list = vi.fn()
      .mockImplementationOnce(() => slow.promise)
      .mockResolvedValueOnce([reviewSummary({ id: reviewIds.previousRequest, revision: 3 })])
    const store = createReviewStore(gateway)

    store.activateScope(platform)
    const old = store.synchronize(platform, coordinates)
    store.activateScope(security)
    await store.synchronize(security, coordinates)
    slow.resolve([reviewSummary({ revision: 1 })])
    await old

    expect(store.state.lists[reviewAttemptKey(coordinates)]?.value?.map(item => item.id))
      .toEqual([reviewIds.previousRequest])
    expect(store.state.selectedReviewRequestId).toBe(reviewIds.previousRequest)
  })

  it('selects the newest Review revision and validates the exact detail identity', async () => {
    const gateway = new FixtureReviewGateway()
    gateway.list = vi.fn(async () => [
      reviewSummary({ id: reviewIds.previousRequest, revision: 1 }),
      reviewSummary({ id: reviewIds.request, revision: 2 }),
    ])
    const store = createReviewStore(gateway)

    await store.synchronize(platform, coordinates)

    expect(store.state.selectedReviewRequestId).toBe(reviewIds.request)
    expect(store.state.details[reviewDetailKey(coordinates, reviewIds.request)]?.value?.etag).toBe('"4"')
  })

  it('retries the exact Reviewer command with the original idempotency key and no optimistic Finding', async () => {
    const gateway = new FixtureReviewGateway()
    const keys: string[] = []
    gateway.execute = vi.fn(async (_scope, _coordinates, _id, _version, key) => {
      keys.push(key)
      if (keys.length === 1) throw apiError(503, true)
      return executionResult()
    })
    const store = createReviewStore(gateway)
    await store.synchronize(platform, coordinates)
    const before = store.state.details[reviewDetailKey(coordinates, reviewIds.request)]?.value?.value.findings.length

    expect(await store.execute()).toBe(false)
    expect(store.state.command.retryable).toBe(true)
    expect(store.state.details[reviewDetailKey(coordinates, reviewIds.request)]?.value?.value.findings.length).toBe(before)
    expect(await store.retryCommand()).toBe(true)

    expect(keys).toHaveLength(2)
    expect(keys[0]).toBe(keys[1])
    expect(store.state.command.execution?.effectiveFindingCount).toBe(1)
  })

  it('refreshes the authoritative Review after an ETag conflict and drops the stale command', async () => {
    const gateway = new FixtureReviewGateway()
    let version = 4
    gateway.get = vi.fn(async () => etaggedReview({ version }))
    gateway.decide = vi.fn(async () => {
      version = 5
      throw apiError(412, false)
    })
    const store = createReviewStore(gateway)
    await store.synchronize(platform, coordinates)

    expect(await store.decide({ type: 'APPROVED', rationale: '证据完整' })).toBe(false)

    expect(store.state.command.phase).toBe('conflict')
    expect(store.state.command.retryable).toBe(false)
    expect(store.state.details[reviewDetailKey(coordinates, reviewIds.request)]?.value?.value.version).toBe(5)
    expect(await store.retryCommand()).toBe(false)
  })
})

class FixtureReviewGateway implements ReviewGateway {
  async list(_scope: ReviewScope, _coordinates: ReviewCoordinates): Promise<ReviewSummary[]> {
    return [reviewSummary()]
  }

  async get(
    _scope: ReviewScope,
    _coordinates: ReviewCoordinates,
    reviewRequestId: string,
  ): Promise<EtaggedReview> {
    return etaggedReview({ id: reviewRequestId })
  }

  async execute(
    _scope: ReviewScope,
    _coordinates: ReviewCoordinates,
    _reviewRequestId: string,
    _expectedVersion: number,
    _idempotencyKey: string,
  ): Promise<ReviewerExecutionResult> {
    return executionResult()
  }

  async decide(
    _scope: ReviewScope,
    _coordinates: ReviewCoordinates,
    _reviewRequestId: string,
    _expectedVersion: number,
    _input: ReviewDecisionInput,
    _idempotencyKey: string,
  ): Promise<CommandReceipt> {
    return receipt()
  }

  async requestChanges(): Promise<CommandReceipt> {
    return receipt()
  }
}

function executionResult(): ReviewerExecutionResult {
  return {
    receipt: receipt(), reviewRequestId: reviewIds.request, reviewRequestVersion: 5,
    status: 'COMPLETED', effectiveFindingCount: 1, insertedFindingCount: 1,
    duplicateObservationCount: 0,
  }
}

function receipt(): CommandReceipt {
  return {
    commandId: crypto.randomUUID(), domainEventId: crypto.randomUUID(),
    committedVersion: 5, correlationId: crypto.randomUUID(),
  }
}

function apiError(status: number, retryable: boolean): CrewScopeApiError {
  return new CrewScopeApiError(status, {
    code: status === 412 ? 'version_conflict' : 'reviewer_unavailable',
    message: status === 412 ? 'Review 已发生变化' : 'Reviewer 暂时不可用',
    correlationId: crypto.randomUUID(), retryable, currentVersion: status === 412 ? 5 : null,
    details: {},
  })
}

function deferred<T>(): { promise: Promise<T>, resolve: (value: T) => void } {
  let resolve!: (value: T) => void
  const promise = new Promise<T>(next => { resolve = next })
  return { promise, resolve }
}
