export interface TeamObserverScope {
  organizationId: string
  teamId: string
}

export interface TeamObserverSession {
  sessionId: string
  observerProfileId: string
  mode: 'READ_ONLY'
  createdAt: string
}

export const teamObserverEventTypes = ['STARTED', 'SUMMARY_COMPLETED', 'CANCELLED', 'FAILED'] as const
export type TeamObserverEventType = typeof teamObserverEventTypes[number]

export const teamSummarySections = ['progress', 'blockers', 'reviewBacklog', 'pendingConfirmations', 'anomalies'] as const
export type TeamSummarySection = typeof teamSummarySections[number]

/**
 * `TeamSummaryDataScope`. Every observer summary line has to say which read scope produced it —
 * that provenance is what makes a model-written sentence auditable — so it is shown, and therefore
 * has to be readable rather than a constant.
 */
export const teamSummaryDataScopes = [
  'TEAM_ACTIVITY', 'TEAM_INBOX_SUMMARY', 'WORK_ITEM_SUMMARY', 'TASK_SUMMARY', 'ARTIFACT_SUMMARY',
] as const
export type TeamSummaryDataScope = typeof teamSummaryDataScopes[number]

export interface TeamSummaryEntry {
  section: string
  dataScope: string
  summary: string
  evidenceIndex: number
}

export interface TeamSummary {
  observerProfileId: string
  generatedAt: string
  progress: TeamSummaryEntry[]
  blockers: TeamSummaryEntry[]
  reviewBacklog: TeamSummaryEntry[]
  pendingConfirmations: TeamSummaryEntry[]
  anomalies: TeamSummaryEntry[]
}

export interface TeamObserverEvent {
  invocationId: string
  sequence: number
  occurredAt: string
  type: TeamObserverEventType
  summary: TeamSummary | null
  errorCode: string | null
}

export interface TeamObserverEvidence {
  evidenceIndex: number
  section: string
  dataScope: string
  summary: string
  path: string
  navigationPath: string
  authorized: true
}

export interface TeamObserverCancelResponse {
  invocationId: string
  cancelled: boolean
}
