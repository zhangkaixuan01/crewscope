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

export interface TeamMemberSummary {
  id: string
  userPrincipalId: string
  status: string
  joinMethod: string
  joinedAt: string | null
  version: number
}

export interface CommandReceipt {
  commandId: string
  domainEventId: string
  committedVersion: number
  correlationId: string
}
