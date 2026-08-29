import type { AuthCsrfCoordinate } from '../identity/types'

export type OnboardingState = 'TEAM_REQUIRED' | 'COMPLETE'

export interface OnboardingStatus {
  state: OnboardingState
  onboardingRequired: boolean
  activeTeamCount: number
}

export interface OnboardingReceipt {
  commandId: string
  domainEventId: string
  committedVersion: number
  correlationId: string
  replayed: boolean
}

export interface CreateFirstTeamInput {
  name: string
  csrf: AuthCsrfCoordinate
  idempotencyKey: string
}
