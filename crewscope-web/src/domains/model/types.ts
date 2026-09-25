import type { CommandReceipt } from '../scope/types'
import type { ModelConnectionOwnerType } from '../settings/route'

export type { ModelConnectionOwnerType }

/**
 * Model registry enums exactly as the server serialises them.
 *
 * These lists were reverse-engineered wrong once already: the UI shipped a retention map keyed
 * `ZERO_RETENTION/LIMITED_RETENTION/STANDARD` and a training map keyed `NO_TRAINING/ALLOWED`, none
 * of which the server ever sends, so every provider row rendered a raw Java constant. Typing the
 * label maps against these unions makes that class of drift a compile error.
 */
export const modelConnectionStatuses = ['ACTIVE', 'SUSPENDED', 'REVOKED'] as const
export const modelConnectionHealthStatuses = ['UNKNOWN', 'HEALTHY', 'UNHEALTHY'] as const
export const modelDataRetentionModes = ['NONE', 'TIME_BOUND', 'PROVIDER_MANAGED'] as const
export const modelTrainingUsagePolicies = ['PROHIBITED', 'EXPLICIT_OPT_IN', 'PROVIDER_DEFAULT'] as const
export const modelRegistryStatuses = ['ACTIVE', 'DISABLED', 'ARCHIVED'] as const
/**
 * `ModelSubjectType`, the billing subject a connection charges against. It is deliberately not
 * `ModelConnectionOwnerType`: a USER-owned connection bills a `PRINCIPAL`, and a TEAM-owned one may
 * bill either its own `TEAM` or the `ORGANIZATION` above it.
 */
export const modelSubjectTypes = ['PRINCIPAL', 'TEAM', 'ORGANIZATION'] as const
/** `ModelConnectionHealthFailureCode` — the stable failure vocabulary, never the Provider's own error. */
export const modelConnectionHealthFailureCodes = [
  'AUTHENTICATION_FAILED', 'ENDPOINT_UNREACHABLE', 'TIMEOUT',
  'RATE_LIMITED', 'PROVIDER_REJECTED', 'POLICY_REJECTED',
] as const
/** `ModelConnectionRevocationReason` — required whenever a connection reaches its terminal state. */
export const modelConnectionRevocationReasons = [
  'OWNER_REQUESTED', 'CREDENTIAL_REVOKED', 'PROVIDER_DISABLED', 'POLICY_REVOKED', 'SECURITY_INCIDENT',
] as const

export type ModelConnectionStatus = typeof modelConnectionStatuses[number]
export type ModelConnectionHealthStatus = typeof modelConnectionHealthStatuses[number]
export type ModelDataRetentionMode = typeof modelDataRetentionModes[number]
export type ModelTrainingUsagePolicy = typeof modelTrainingUsagePolicies[number]
export type ModelRegistryStatus = typeof modelRegistryStatuses[number]
export type ModelSubjectType = typeof modelSubjectTypes[number]
export type ModelConnectionHealthFailureCode = typeof modelConnectionHealthFailureCodes[number]
export type ModelConnectionRevocationReason = typeof modelConnectionRevocationReasons[number]

export interface ModelProviderSummary {
  key: string
  displayName: string
  availableRegions: string[]
  retentionMode: string
  maximumRetentionSeconds: number | null
  trainingUsagePolicy: string
  status: string
  version: number
}

export interface ModelPriceSummary {
  revision: number
  effectiveFrom: string
  inputPerMillionTokens: string
  outputPerMillionTokens: string
  cachedInputPerMillionTokens: string | null
  currencyCode: string
}

export interface ModelCatalogEntrySummary {
  id: string
  providerKey: string
  modelId: string
  catalogRevision: number
  modelRevision: string
  displayName: string
  contextWindowTokens: number
  maximumOutputTokens: number
  capabilities: string[]
  availableRegions: string[]
  status: string
  version: number
  effectivePrice: ModelPriceSummary | null
}

/** Public Connection projection. Credential identities, endpoints and secrets are deliberately absent. */
export interface ModelConnectionSummary {
  id: string
  organizationId: string
  providerKey: string
  ownerType: ModelConnectionOwnerType
  ownerId: string
  region: string
  billingSubjectType: string
  billingSubjectId: string
  credentialVersion: number
  status: string
  healthStatus: string
  healthFailureCode: string | null
  checkedAt: string | null
  lastHealthyAt: string | null
  consecutiveFailures: number
  revocationReason: string | null
  createdAt: string
  updatedAt: string
  version: number
}

/** One-way secret input. Callers must not place this value in Store or persistent browser state. */
export interface CreateModelConnectionInput {
  providerKey: string
  ownerType: ModelConnectionOwnerType
  teamId: string | null
  region: string
  apiKey: string
  credentialExpiresAt: string | null
}

export interface RotateModelCredentialInput {
  credentialVersion: number
  apiKey: string
}

export type ModelConnectionCommandReceipt = CommandReceipt
export type ModelConnectionTransition = 'verify' | 'suspend' | 'activate' | 'revoke'
