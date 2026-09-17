export interface TeamSummary {
  id: string
  organizationId: string
  name: string
  status: string
  initializationStatus: string
  ownerMemberId: string | null
  defaultWorkspaceId: string | null
  version: number
}

export interface WorkProjectSummary {
  id: string
  organizationId: string
  teamId: string
  workspaceId: string
  key: string
  name: string
  status: string
  version: number
  createdAt: string
  createdByPrincipalId: string | null
  updatedAt: string
  updatedByPrincipalId: string | null
}

export interface WorkProjectPage {
  items: WorkProjectSummary[]
  nextCursor: string | null
}

export interface CreateWorkProjectInput {
  key: string
  name: string
}

export interface WorkProjectKeyAvailability {
  key: string
  available: boolean
}

/**
 * Membership enums as the server spells them (`TeamMemberStatus` / `TeamJoinMethod`).
 *
 * `TeamMemberSummary` still types these fields as `string` because the Team gateway is a
 * pass-through adapter, but the label maps are typed against these unions so an incomplete or
 * invented mapping fails typechecking instead of printing the raw constant on screen.
 */
export const teamMemberStatuses = ['INVITED', 'ACTIVE', 'SUSPENDED', 'LEFT', 'REMOVED'] as const
export const teamJoinMethods = ['BOOTSTRAP', 'INVITATION', 'OIDC', 'SCIM', 'IMPORT'] as const

export type TeamMemberStatus = typeof teamMemberStatuses[number]
export type TeamJoinMethod = typeof teamJoinMethods[number]

export interface TeamMemberSummary {
  id: string
  userPrincipalId: string
  displayName: string
  status: string
  joinMethod: string
  joinedAt: string | null
  roles?: string[]
  version: number
}

export interface CommandReceipt {
  commandId: string
  domainEventId: string
  committedVersion: number
  correlationId: string
}
