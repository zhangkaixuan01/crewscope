export const registrationModes = ['OPEN', 'INVITE_ONLY', 'DISABLED'] as const
export type RegistrationMode = typeof registrationModes[number]

export interface AuthCsrfCoordinate {
  headerName: 'X-XSRF-TOKEN'
  parameterName: '_csrf'
  token: string
}

export interface AuthSessionAccount {
  accountId: string
  username: string
  displayName: string
  platformRole: 'USER' | 'OPERATOR'
  securityVersion: number
  version: number
}

export interface AuthSessionPrincipal {
  principalId: string
  organizationId: string
}

export interface AuthSessionTeam {
  teamId: string
  name: string
  memberId: string
  permissions: string[]
}

export interface AuthSession {
  authenticated: boolean
  registrationMode: RegistrationMode
  csrf: AuthCsrfCoordinate
  account: AuthSessionAccount | null
  principal: AuthSessionPrincipal | null
  teams: AuthSessionTeam[]
  permissions: string[]
}

export interface LoginCredentials {
  identifier: string
  password: string
}

export interface LoginResult {
  authenticated: true
  accountId: string
  displayName: string
}

/** One-way registration input. Password and invitation proof must never enter a Store or persistence. */
export interface RegistrationInput {
  username: string
  email: string
  displayName: string
  password: string
  invitationToken?: string
}

export interface RegistrationResult {
  accountId: string
  principalId: string
  organizationId: string
  teamId: string | null
  memberId: string | null
  onboardingRequired: boolean
  commandId: string
  domainEventId: string
  committedVersion: number
  correlationId: string
  replayed: boolean
}
