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

/** Secret-free GitHub authorization projection. */
export interface GitHubConnection {
  id: string
  ownerType: GitHubConnectionOwnerType
  teamId: string | null
  authenticationType: 'APP_INSTALLATION' | 'OAUTH_USER'
  executionIdentity: 'TEAM' | 'USER' | null
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
  executionIdentity: 'TEAM' | 'USER'
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
  kind: 'PUSH_BRANCH' | 'CREATE_DRAFT_PR'
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
  validity: 'CURRENT' | 'STALE'
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
