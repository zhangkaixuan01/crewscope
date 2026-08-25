import { apiClient, type CrewScopeApiClient } from '../../api/client'
import type { CommandReceipt } from '../scope/types'
import type {
  EtaggedReview,
  ReviewCoordinates,
  ReviewDecisionInput,
  ReviewDetails,
  ReviewerExecutionResult,
  ReviewFinding,
  ReviewFindingEvidence,
  ReviewModificationRound,
  ReviewScope,
  ReviewSummary,
} from './types'

export interface ReviewGateway {
  list(scope: ReviewScope, coordinates: ReviewCoordinates, signal?: AbortSignal): Promise<ReviewSummary[]>
  get(scope: ReviewScope, coordinates: ReviewCoordinates, reviewRequestId: string, signal?: AbortSignal): Promise<EtaggedReview>
  execute(scope: ReviewScope, coordinates: ReviewCoordinates, reviewRequestId: string, expectedVersion: number, idempotencyKey: string): Promise<ReviewerExecutionResult>
  decide(scope: ReviewScope, coordinates: ReviewCoordinates, reviewRequestId: string, expectedVersion: number, input: ReviewDecisionInput, idempotencyKey: string): Promise<CommandReceipt>
  requestChanges(scope: ReviewScope, coordinates: ReviewCoordinates, reviewRequestId: string, expectedVersion: number, rationale: string, idempotencyKey: string): Promise<CommandReceipt>
}

/** M5-A05 adapter that admits only the member-safe Review projection. */
export class HttpReviewGateway implements ReviewGateway {
  constructor(private readonly client: CrewScopeApiClient = apiClient) {}

  async list(scope: ReviewScope, coordinates: ReviewCoordinates, signal?: AbortSignal): Promise<ReviewSummary[]> {
    const value = await this.client.get<{ items: ReviewSummary[] }>(root(scope, coordinates), { signal })
    return value.items.map(mapSummary)
  }

  async get(
    scope: ReviewScope,
    coordinates: ReviewCoordinates,
    reviewRequestId: string,
    signal?: AbortSignal,
  ): Promise<EtaggedReview> {
    const response = await this.client.open(`${root(scope, coordinates)}/${segment(reviewRequestId)}`, {
      method: 'GET', signal,
    })
    const value = mapDetails(await response.json() as ReviewDetails)
    const etag = response.headers.get('ETag')
    if (!etag || etag !== `"${value.version}"`) throw new Error('Review ETag does not match body version')
    return { value, etag }
  }

  async execute(
    scope: ReviewScope,
    coordinates: ReviewCoordinates,
    reviewRequestId: string,
    expectedVersion: number,
    idempotencyKey: string,
  ): Promise<ReviewerExecutionResult> {
    const value = await this.client.post<ReviewerExecutionResult>(
      `${root(scope, coordinates)}/${segment(reviewRequestId)}/execute`,
      undefined,
      { expectedVersion, idempotencyKey },
    )
    return {
      ...pick(value, [
        'reviewRequestId', 'reviewRequestVersion', 'status', 'effectiveFindingCount',
        'insertedFindingCount', 'duplicateObservationCount',
      ]),
      receipt: mapReceipt(value.receipt),
    }
  }

  decide(
    scope: ReviewScope,
    coordinates: ReviewCoordinates,
    reviewRequestId: string,
    expectedVersion: number,
    input: ReviewDecisionInput,
    idempotencyKey: string,
  ): Promise<CommandReceipt> {
    return this.client.post<CommandReceipt>(
      `${root(scope, coordinates)}/${segment(reviewRequestId)}/decisions`,
      { type: input.type, rationale: input.rationale },
      { expectedVersion, idempotencyKey },
    ).then(mapReceipt)
  }

  requestChanges(
    scope: ReviewScope,
    coordinates: ReviewCoordinates,
    reviewRequestId: string,
    expectedVersion: number,
    rationale: string,
    idempotencyKey: string,
  ): Promise<CommandReceipt> {
    return this.client.post<CommandReceipt>(
      `${root(scope, coordinates)}/${segment(reviewRequestId)}/modifications`,
      { rationale },
      { expectedVersion, idempotencyKey },
    ).then(mapReceipt)
  }
}

function root(scope: ReviewScope, coordinates: ReviewCoordinates): string {
  return `/organizations/${segment(scope.organizationId)}/teams/${segment(scope.teamId)}`
    + `/tasks/${segment(coordinates.taskId)}/attempts/${segment(coordinates.executionId)}/reviews`
}

function segment(value: string): string {
  return encodeURIComponent(value)
}

function mapSummary(value: ReviewSummary): ReviewSummary {
  return { ...pick(value, [
    'id', 'revision', 'version', 'status', 'invalidationReason', 'contextHash', 'findingCount',
    'blockerCount', 'highCount', 'latestDecisionType', 'modificationRound',
  ]) }
}

function mapDetails(value: ReviewDetails): ReviewDetails {
  return {
    ...pick(value, [
      'id', 'revision', 'version', 'status', 'invalidationReason', 'reviewerRelationship',
      'reviewerAgentProfileId', 'contextPackageId', 'contextHash', 'diffArtifactId',
      'diffArtifactHash', 'baselineCommit', 'deliveryCommit', 'testEvidenceId', 'testEvidenceHash',
    ]),
    changedPaths: [...value.changedPaths],
    findings: value.findings.map(mapFinding),
    decisions: value.decisions.map(item => ({ ...pick(item, [
      'id', 'revision', 'type', 'rationale', 'reviewerMemberId', 'eligibilityMode', 'decidedAt',
    ]) })),
    modificationRounds: value.modificationRounds.map(mapRound),
  }
}

function mapFinding(value: ReviewFinding): ReviewFinding {
  return {
    ...pick(value, [
      'id', 'severity', 'category', 'title', 'claim', 'suggestedFix', 'relationship', 'fingerprint',
    ]),
    evidence: value.evidence.map(mapEvidence),
  }
}

function mapEvidence(value: ReviewFindingEvidence): ReviewFindingEvidence {
  return { ...pick(value, ['path', 'startLine', 'endLine', 'acceptanceCriterionIndex']) }
}

function mapRound(value: ReviewModificationRound): ReviewModificationRound {
  return { ...pick(value, [
    'id', 'roundNumber', 'sourceReviewRequestId', 'triggerDecisionId', 'createdAt',
  ]) }
}

function mapReceipt(value: CommandReceipt): CommandReceipt {
  return { ...pick(value, ['commandId', 'domainEventId', 'committedVersion', 'correlationId']) }
}

function pick<T extends object, K extends keyof T>(value: T, keys: readonly K[]): Pick<T, K> {
  return Object.fromEntries(keys.map(key => [key, value[key]])) as Pick<T, K>
}
