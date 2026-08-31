import type { TeamMemberSummary } from './types'

export type PrincipalNameDirectory = Readonly<Record<string, string>>

/** Builds the current Team's trusted human-name directory from the member API projection. */
export function principalNameDirectory(members: readonly TeamMemberSummary[]): PrincipalNameDirectory {
  return Object.fromEntries(members.map(member => [member.userPrincipalId, member.displayName]))
}

/** Uses a real display name when known and a clearly technical identifier for historical facts. */
export function principalDisplayName(
  directory: PrincipalNameDirectory,
  principalId: string,
  fallbackLabel = '成员',
): string {
  const displayName = directory[principalId]?.trim()
  return displayName || `${fallbackLabel} · ${shortPrincipalId(principalId)}`
}

export function shortPrincipalId(principalId: string): string {
  return principalId.length > 12
    ? `${principalId.slice(0, 8)}…${principalId.slice(-4)}`
    : principalId
}
