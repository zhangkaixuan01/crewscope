import type { CommandReceipt } from '../scope/types'

/** Team route shared by GitHub catalog and Action delivery requests. */
export interface DeliveryScope {
  organizationId: string
  teamId: string
}

export interface DeliveryCoordinates {
  taskId: string
  executionId: string
}

export type GitHubConnectionOwnerType = 'USER' | 'TEAM'

/**
 * Enum constants as the delivery endpoints serialise them.
 *
 * The interfaces below keep most of these fields typed `string` because the delivery gateway is a
 * pass-through adapter over the public projection; the unions live here so `delivery/labels.ts` can
 * be typed against them and an unmapped value becomes a compile error.
 */
export const actionKinds = ['PUSH_BRANCH', 'CREATE_DRAFT_PR', 'NOTIFY_COLLABORATION'] as const
export const actionRiskLevels = ['READ_ONLY', 'LOW_RISK_WRITE', 'HIGH_RISK_WRITE', 'DESTRUCTIVE'] as const
export const actionDispatchStatuses = [
  'READY',
  'RUNNING',
  'UNKNOWN',
  'RECONCILING',
  'MANUAL_REVIEW',
  'SUCCEEDED',
  'FAILED',
  'MANUALLY_SUCCEEDED',
  'MANUALLY_FAILED',
  'CANCELLED',
] as const
export const actionReceiptResults = ['SUCCEEDED', 'FAILED', 'MANUALLY_SUCCEEDED', 'MANUALLY_FAILED', 'CANCELLED'] as const
export const actionResultSources = ['WRITE_RESPONSE', 'ACTIVE_QUERY', 'WEBHOOK', 'MANUAL', 'CONTROL'] as const
/** `ExternalResultSource` is a strict subset of {@link actionResultSources}: no manual observation. */
export const externalResultSources = ['WRITE_RESPONSE', 'WEBHOOK', 'ACTIVE_QUERY'] as const
export const actionCancellationReasons = [
  'CONFIRMATION_CANCELLED',
  'MEMBER_CANCELLED',
  'DEPENDENCY_FAILED',
  'BUNDLE_EXPIRED',
  'AUTHORITY_INVALIDATED',
] as const
export const actionInvalidationReasons = [
  'EXPIRED',
  'REVIEW_CHANGED',
  'RESPONSIBILITY_CHANGED',
  'PROVIDER_AUTHORIZATION_CHANGED',
  'POLICY_CHANGED',
  'SAFETY_OVERLAY_CHANGED',
  'TARGET_PRECONDITION_CHANGED',
  'AUTHORITY_UNAVAILABLE',
] as const
export const compensationDispositions = ['NOT_REQUIRED', 'MANUAL_REVIEW_REQUIRED'] as const
export const confirmationStatuses = ['ACTIVE', 'CANCELLED'] as const
export const externalObjectTypes = ['BRANCH', 'PULL_REQUEST'] as const
export const externalObjectStatuses = ['PRESENT', 'MISSING', 'OPEN', 'CLOSED', 'MERGED'] as const
/** Derived by the bundle projection from `validUntil` and the invalidation check, not a domain enum. */
export const actionBundleValidities = ['CURRENT', 'STALE'] as const
export const githubAuthenticationTypes = ['APP_INSTALLATION', 'OAUTH_USER'] as const
/**
 * The GitHub connection projection narrows `ProviderExecutionIdentity` to the two identities a team
 * can actually deliver under, so it is its own union rather than the full provider-side enum.
 */
export const githubExecutionIdentities = ['TEAM', 'USER'] as const
/** `ConnectionStatus`, shared by the GitHub connection and its provider binding. */
export const providerConnectionStatuses = ['ACTIVE', 'SUSPENDED', 'REVOKED', 'EXPIRED'] as const
/**
 * `CredentialStatus`, shared by every provider credential the platform stores. A connection whose
 * credential is `ROTATING` is still authorized — the rotation has simply not been confirmed yet.
 */
export const providerCredentialStatuses = ['ACTIVE', 'ROTATING', 'REVOKED'] as const
export type ProviderCredentialStatus = typeof providerCredentialStatuses[number]

/**
 * A repository import job reports `failureCode` as a plain string because the worker forwards
 * whichever stable code the boundary produced: `GitHubProviderErrorCode` for authorization and
 * catalog failures, `GitHubPushErrorCode` for mirror failures, or `IMPORT_FAILED` when the job
 * itself broke. Keeping all three in one union is what lets the member be told which of the three
 * it was instead of reading a bare constant.
 */
export const githubImportFailureCodes = [
  'AUTHENTICATION_REQUIRED', 'PERMISSION_DENIED', 'RATE_LIMITED', 'RESOURCE_UNAVAILABLE',
  'CONFLICT', 'VALIDATION_FAILED', 'PROVIDER_UNAVAILABLE', 'CONNECTION_UNAVAILABLE',
  'GRANT_UNAVAILABLE', 'CREDENTIAL_UNAVAILABLE', 'IDENTITY_MISMATCH', 'REPOSITORY_BLOCKED',
  'REPOSITORY_STALE', 'DEFAULT_BRANCH_MISMATCH',
  'AUTHORITY_STALE', 'MIRROR_UNAVAILABLE', 'BASELINE_MISMATCH', 'DELIVERY_HEAD_MISMATCH',
  'REMOTE_HEAD_CONFLICT', 'NON_FAST_FORWARD', 'PROTECTED_BRANCH', 'PUSH_REJECTED', 'UNKNOWN',
  'IMPORT_FAILED',
] as const
export type GitHubImportFailureCode = typeof githubImportFailureCodes[number]

export const githubImportJobStatuses = ['REQUESTED', 'PREFLIGHTING', 'IMPORTING', 'READY', 'FAILED', 'CANCELLED'] as const
/** GitHub's own repository visibility, reported verbatim by the catalog; not a CrewScope enum. */
export const repositoryVisibilities = ['PUBLIC', 'PRIVATE', 'INTERNAL'] as const

export type ActionKind = typeof actionKinds[number]
export type ActionRiskLevel = typeof actionRiskLevels[number]
export type ActionDispatchStatus = typeof actionDispatchStatuses[number]
export type ActionReceiptResult = typeof actionReceiptResults[number]
export type ActionResultSource = typeof actionResultSources[number]
export type ExternalResultSource = typeof externalResultSources[number]
export type ActionCancellationReason = typeof actionCancellationReasons[number]
export type ActionInvalidationReason = typeof actionInvalidationReasons[number]
export type CompensationDisposition = typeof compensationDispositions[number]
export type ConfirmationStatus = typeof confirmationStatuses[number]
export type ExternalObjectType = typeof externalObjectTypes[number]
export type ExternalObjectStatus = typeof externalObjectStatuses[number]
export type ActionBundleValidity = typeof actionBundleValidities[number]
export type GitHubAuthenticationType = typeof githubAuthenticationTypes[number]
export type GitHubExecutionIdentity = typeof githubExecutionIdentities[number]
export type ProviderConnectionStatus = typeof providerConnectionStatuses[number]
export type GitHubImportJobStatus = typeof githubImportJobStatuses[number]
export type RepositoryVisibility = typeof repositoryVisibilities[number]

/** Secret-free GitHub authorization projection. */
export interface GitHubConnection {
  id: string
  ownerType: GitHubConnectionOwnerType
  teamId: string | null
  authenticationType: GitHubAuthenticationType
  executionIdentity: GitHubExecutionIdentity | null
  externalAccountLogin: string | null
  status: string
  version: number
  repositoryAllowlist: string[]
  credentialStatus: string | null
  expiresAt: string | null
  verifiedAt: string | null
  createdAt: string
  updatedAt: string
}

export interface GitHubProviderBinding {
  id: string
  teamId: string
  workspaceId: string
  connectionId: string
  connectionVersion: number
  grantId?: string | null
  grantVersion?: number
  executionIdentity: GitHubExecutionIdentity
  repositoryAllowlist: string[]
  status: string
  defaultUsage: boolean
  version: number
}

export interface GitHubRepository {
  externalRepositoryId: string
  fullName: string
  defaultBranch: string
  visibility: string
  discoveredAt: string
  cacheExpiresAt: string
}

export interface GitHubRemotePreflight {
  connectionVersion: number
  externalRepositoryId: string
  fullName: string
  defaultBranch: string
  permissionsHash: string
}

/** Durable GitHub Catalog import projection; no remote URL or Worker path is exposed. */
export interface GitHubRepositoryImportJob {
  id: string
  organizationId: string
  teamId: string
  projectId: string
  connectionId: string
  connectionVersion: number
  externalRepositoryId: string
  repositoryFullName: string
  repositoryKey: string
  defaultBranch: string
  status: GitHubImportJobStatus
  progressPercent: number
  attempt: number
  failureCode: string | null
  bindingId: string | null
  createdAt: string
  updatedAt: string
}

export interface GitHubAuthorizationHealth {
  authorizationStatus: string
  connectionUsable: boolean
  grantUsable: boolean
  credentialUsable: boolean
  profileCurrent: boolean
  deliverableRepositoryCount: number
  webhookStatus: string
  rateLimit: {
    resource: string
    limit: number
    remaining: number
    resetsAt: string
    observedAt: string
  } | null
}

export interface ActionParameter {
  repositoryId: string
  branch: string | null
  deliveryHead: string | null
  expectedRemoteHead: string | null
  pullRequestHead: string | null
  pullRequestBase: string | null
  pullRequestHeadSha: string | null
  title: string | null
  body: string | null
  draft: boolean | null
}

export interface ActionDispatch {
  id: string
  version: number
  status: string
  claimAttempts: number
  reconciliationAttempts: number
  nextAttemptAt: string
  cancellationReason: string | null
  compensationDisposition: string
}

export interface ActionReceipt {
  id: string
  result: string
  source: string
  externalObjectType: string | null
  externalIdentityHash: string | null
  targetVersion: string | null
  evidenceCode: string
  manualReason: string | null
  receivedAt: string
}

export interface ExternalResult {
  status: string
  externalObjectType: string
  externalIdentityHash: string
  providerVersion: number | null
  providerUpdatedAt: string | null
  source: string
  observedAt: string
  version: number
}

export interface PlannedAction {
  id: string
  sequence: number
  kind: ActionKind
  risk: string
  digest: string
  validUntil: string
  dependencyActionIds: string[]
  parameters: ActionParameter
  dispatch: ActionDispatch | null
  receipt: ActionReceipt | null
  externalResult: ExternalResult | null
}

export interface ActionConfirmation {
  id: string
  version: number
  status: string
  confirmedByPrincipalId: string
  confirmedAt: string
  validUntil: string
  cancellationReason: string | null
}

/** Exact, server-derived action graph shown immediately before confirmation. */
export interface ActionBundle {
  id: string
  version: number
  digest: string
  validity: ActionBundleValidity
  staleReason: string | null
  taskId: string
  taskExecutionId: string
  reviewDecisionId: string
  repositoryBindingId: string
  repositoryKey: string
  baselineCommit: string
  deliveryCommit: string
  confirmation: ActionConfirmation | null
  actions: PlannedAction[]
}

export interface EtaggedActionBundle {
  value: ActionBundle
  etag: string
}

export interface PlanActionBundleInput {
  reviewDecisionId: string
  providerBindingId: string
  repositoryId: string
  expectedRemoteHead?: string
  title: string
  body: string
}

export interface DeliveryCommandResult {
  receipt: CommandReceipt
  operation: 'plan' | 'confirm' | 'cancel' | 'manual-resolution'
}
