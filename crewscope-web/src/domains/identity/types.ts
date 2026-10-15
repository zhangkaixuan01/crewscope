export const registrationModes = ['OPEN', 'INVITE_ONLY', 'DISABLED'] as const
export type RegistrationMode = typeof registrationModes[number]

/** Deployment-level identity policy (ADR-036): a dimension stacked on the security mode, not a fourth mode. */
export const identityPolicies = ['local-only', 'sso-only', 'coexist'] as const
export type IdentityPolicy = typeof identityPolicies[number]

export interface AuthCsrfCoordinate {
  headerName: 'X-XSRF-TOKEN'
  parameterName: '_csrf'
  token: string
}

/** Owner-facing login identity of the account; the subject value never leaves the server (M12-I01 §1). */
export const loginIdentityStatuses = ['ACTIVE', 'DISABLED', 'REVOKED'] as const
export type LoginIdentityStatus = typeof loginIdentityStatuses[number]

export interface AuthLoginIdentity {
  provider: string
  status: LoginIdentityStatus
  lastAuthenticatedAt: string | null
}

export interface AuthSessionAccount {
  accountId: string
  username: string
  displayName: string
  platformRole: 'USER' | 'OPERATOR'
  securityVersion: number
  version: number
  loginIdentities: AuthLoginIdentity[]
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

/** Highest active ORG_* role of the signed-in account; projected only when one exists (M12-A01 §3.5). */
export const organizationRoleNames = ['ORG_OWNER', 'ORG_ADMIN', 'ORG_AUDITOR'] as const
export type OrganizationRoleName = typeof organizationRoleNames[number]

export interface AuthSessionOrganization {
  organizationId: string
  name: string
  role: OrganizationRoleName
}

export interface AuthSession {
  authenticated: boolean
  registrationMode: RegistrationMode
  identityPolicy: IdentityPolicy
  /** Framework SSO entry (`/oauth2/authorization/{registrationId}`) or null when no OIDC registration exists. */
  ssoAuthorizationUrl: string | null
  csrf: AuthCsrfCoordinate
  account: AuthSessionAccount | null
  principal: AuthSessionPrincipal | null
  organization: AuthSessionOrganization | null
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

/**
 * One browser session of the signed-in account (GET /auth/sessions), newest activity first.
 * This is the owner-facing session-management projection (M12-I01 §2) — the id is the handle
 * for per-device revocation, not part of the anonymous-safe AuthSession projection above.
 */
export interface BrowserSessionSummary {
  sessionId: string
  createdAt: string
  lastAccessedAt: string
  current: boolean
}
