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
