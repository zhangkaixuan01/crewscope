export const principalKinds = ['USER', 'AGENT'] as const
export const principalStatuses = ['ACTIVE', 'SUSPENDED', 'DISABLED', 'ARCHIVED'] as const

export type PrincipalKind = typeof principalKinds[number]

/**
 * `PrincipalType` as the server serialises it. Distinct from {@link PrincipalKind}, which is the
 * coarse directory filter: a directory entry is USER or AGENT, while a responsibility assignment,
 * a Conversation participant and an audit actor all carry the full five-value type.
 */
export const principalTypes = ['USER', 'PERSONAL_AGENT', 'TEAM_AGENT', 'SPECIALIST_AGENT', 'SERVICE'] as const
export type PrincipalType = typeof principalTypes[number]
export type PrincipalStatus = typeof principalStatuses[number]

/**
 * `PlatformRole`, the account-level role the session carries. It is not a team role: a member with
 * `OPERATOR` can reach the operations surfaces regardless of which team they are currently in.
 */
export const platformRoles = ['USER', 'OPERATOR'] as const
export type PlatformRole = typeof platformRoles[number]

export interface PrincipalScope {
  organizationId: string
  teamId: string
}

/**
 * Member-safe subject projection from the A07 directory contract.
 *
 * This is deliberately the whole of what the UI knows about a subject: enough to recognise a
 * teammate or an Agent, and nothing about contact details or lifecycle internals.
 */
export interface PrincipalEntry {
  principalId: string
  kind: PrincipalKind
  displayName: string
  status: PrincipalStatus
  roles: string[]
}

export interface PrincipalPage {
  items: PrincipalEntry[]
  nextOffset: number | null
}

export interface PrincipalDirectoryQuery extends PrincipalScope {
  q?: string
  offset?: number
  limit?: number
}
