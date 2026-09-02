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
