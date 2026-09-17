import type { SettingsScope } from '../settings/types'

export type SetupCapability =
  | 'PERSONAL_CONVERSATION'
  | 'TEAM_TASK'
  | 'CODING_REVIEW'
  | 'GITHUB_DRAFT_PR'
  | 'LARK_NOTIFICATIONS'
  | 'TEAM_OBSERVER'

export type SetupStatus = 'READY' | 'ACTION_REQUIRED' | 'BLOCKED' | 'UNAVAILABLE'

export interface SetupReadinessItem {
  capability: SetupCapability
  required: boolean
  status: SetupStatus
  reasonCode: string
  canConfigure: boolean
  responsibleParty: string
  actionKey: string | null
}

export interface SetupReadinessView {
  scope: SettingsScope
  snapshotVersion: string
  observedAt: string
  capabilities: SetupReadinessItem[]
  requiredReady: boolean
}

/** The four components the derived configuration health reports on. */
export type ConfigurationComponent =
  | 'AGENT_CONFIGURATION'
  | 'MODEL_CONNECTION'
  | 'CREDENTIAL'
  | 'INTEGRATION'

export interface ConfigurationHealthItem {
  component: ConfigurationComponent
  status: SetupStatus
  reasonCode: string
  responsibleParty: string
  actionKey: string | null
}

export interface ConfigurationHealthView {
  scope: SettingsScope
  observedAt: string
  overallStatus: SetupStatus
  items: ConfigurationHealthItem[]
}

/** One configuration field, never a value: prompts, secrets and provider settings stay server-side. */
export interface ConfigurationSearchHit {
  profileId: string
  revision: number
  field: string
  label: string
  route: string
}
