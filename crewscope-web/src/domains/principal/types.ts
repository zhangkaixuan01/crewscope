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
  /** Legacy offset continuation; empty on a cursor walk or a by-id lookup. */
  nextOffset: number | null
  /** Signed keyset continuation; absent once the visible set is exhausted. */
  nextCursor: string | null
}

export interface PrincipalDirectoryQuery extends PrincipalScope {
  /** Prefix filter; `namePrefix` is its long-form alias — send one, never both. */
  q?: string
  namePrefix?: string
  types?: PrincipalKind[]
  /** Fixed-point lookup of at most 50 identities; excludes every filter parameter. */
  ids?: string[]
  /** Signed continuation from a previous page's `nextCursor`. */
  after?: string
  offset?: number
  limit?: number
}
