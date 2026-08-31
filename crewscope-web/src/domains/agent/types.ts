import type { CommandReceipt } from '../scope/types'

export type AgentOwnershipType = 'USER' | 'TEAM' | 'ORGANIZATION'
export type AgentExecutionScope = 'PERSONAL' | 'TEAM'
export type AgentLifecycleTransition = 'activate' | 'disable' | 'archive'

export interface AgentTemplateSummary {
  publisherType: string
  publisherId: string
  key: string
  version: number
  runtimeRole: string
  allowedOwnershipTypes: string[]
  allowedExecutionScopes: string[]
  declaredCapabilities: string[]
  requiredModelCapabilities: string[]
  approvedSkillKeys: string[]
  memberConfigurableSlots: string[]
  administratorConfigurableSlots: string[]
  creatable: boolean
  platformManaged: boolean
  contentHash: string
  status: string
  lifecycleVersion: number
}

export interface AgentSummary {
  id: string
  principalId: string
  displayName: string
  principalStatus: string
  organizationId: string
  teamId: string | null
  workspaceId: string
  ownershipType: AgentOwnershipType
  ownerMemberId: string | null
  runtimeRole: string
  templateKey: string
  templateVersion: number
  defaultProfile: boolean
  status: string
  currentConfigurationRevision: number | null
  currentConfigurationHash: string | null
  createdAt: string
  updatedAt: string
  version: number
}

export interface AgentModelSelectionSummary {
  connectionId: string
  providerKey: string
  catalogEntryId: string
  modelId: string
  catalogRevision: number
}

export interface AgentModelBindingSummary {
  executionScope: AgentExecutionScope
  kind: string
  primary: AgentModelSelectionSummary | null
  fallback: AgentModelSelectionSummary | null
}

export interface AgentConfigurationHistoryItem {
  revision: number
  previousRevision: number | null
  templateKey: string
  templateVersion: number
  templateContentHash: string
  personalBinding: AgentModelBindingSummary | null
  teamBinding: AgentModelBindingSummary | null
  configurationHash: string
  createdAt: string
  createdBy: string
}

export interface AgentPolicyReference {
  id: string
  version: number
}

export interface AgentGenerateOptions {
  temperature: string | null
  topP: string | null
  maximumOutputTokens: number | null
  reasoningMode: string
  cacheEnabled: boolean
  parallelToolCalls: boolean
  seed: number | null
  maximumAttempts: number
}

export interface CurrentAgentConfiguration {
  revision: number
  previousRevision: number | null
  templateKey: string
  templateVersion: number
  templateContentHash: string
  personalBinding: AgentModelBindingSummary | null
  teamBinding: AgentModelBindingSummary | null
  supplementalInstructions: string | null
  approvedSkillKeys: string[]
  memoryPolicy: AgentPolicyReference | null
  budgetPolicy: AgentPolicyReference | null
  generateOptions: AgentGenerateOptions
  policyPackId: string
  policyPackVersion: number
  configurationHash: string
  createdAt: string
}

export interface SelectableModelPrice {
  inputPerMillionTokens: string
  outputPerMillionTokens: string
  cachedInputPerMillionTokens: string | null
  currencyCode: string
}

export interface SelectableAgentModel {
  connectionId: string
  connectionOwnerType: string
  connectionOwnerId: string
  providerKey: string
  providerDisplayName: string
  catalogEntryId: string
  modelId: string
  catalogRevision: number
  modelDisplayName: string
  region: string
  contextWindowTokens: number
  maximumOutputTokens: number
  capabilities: string[]
  price: SelectableModelPrice
}

export interface ResolvedModelDefaultSummary {
  source: string
  scopeType: string
  scopeId: string
  revision: number
  contentHash: string
}

export interface ResolvedModelSelectionSummary {
  role: string
  providerKey: string
  connectionId: string
  connectionOwnerType: string
  connectionOwnerId: string
  region: string
  catalogEntryId: string
  modelId: string
  catalogRevision: number
  modelRevision: string
  priceRevision: number
  price: SelectableModelPrice
}

export interface AgentModelPreflight {
  agentProfileId: string
  agentProfileVersion: number
  configurationRevision: number
  configurationHash: string
  executionScope: AgentExecutionScope
  bindingSource: string
  modelDefault: ResolvedModelDefaultSummary | null
  primary: ResolvedModelSelectionSummary
  fallback: ResolvedModelSelectionSummary | null
  resolutionHash: string
}

export interface ConversationAgentConfiguration {
  runtimeSessionId: string
  runtimeSessionVersion: number
  agentProfileId: string
  pinnedConfigurationRevision: number | null
  pinnedConfigurationHash: string | null
  currentConfigurationRevision: number
  currentConfigurationHash: string
  refreshRequired: boolean
}

export interface CreateAgentInput {
  publisherType: string
  templateKey: string
  templateVersion: number
  ownershipType: AgentOwnershipType
  displayName: string
}

export interface AgentModelSelectionInput {
  connectionId: string
  catalogEntryId: string
  catalogRevision: number
}

export interface AgentModelBindingInput {
  kind: string
  primary: AgentModelSelectionInput | null
  fallback: AgentModelSelectionInput | null
}

export interface AgentGenerateOptionsInput {
  temperature?: number | null
  topP?: number | null
  maximumOutputTokens?: number | null
  reasoningMode?: string | null
  cacheEnabled?: boolean | null
  parallelToolCalls?: boolean | null
  seed?: number | null
  maximumAttempts?: number | null
}

export interface AgentConfigurationInput {
  personalModelBinding: AgentModelBindingInput | null
  teamModelBinding: AgentModelBindingInput | null
  supplementalInstructions: string | null
  approvedSkillKeys: string[]
  memoryPolicy: AgentPolicyReference | null
  budgetPolicy: AgentPolicyReference | null
  generateOptions: AgentGenerateOptionsInput | null
}

export type AgentCommandReceipt = CommandReceipt
