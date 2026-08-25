import type { CommandReceipt } from '../scope/types'
import type { ModelConnectionOwnerType } from '../settings/route'

export type { ModelConnectionOwnerType }

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
export type ModelConnectionTransition = 'verify' | 'suspend' | 'revoke'
