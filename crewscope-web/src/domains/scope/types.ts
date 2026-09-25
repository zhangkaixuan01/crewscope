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
  key?: string
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
  /** 撤销角色需要 grantId；由 M9b-A07 成员列表响应携带，旧响应无此字段时退回 roles。 */
  grants?: MemberRoleGrant[]
  authorizationVersion?: number
  version: number
}

export interface MemberRoleGrant {
  id: string
  roleKey: string
  status?: string
}

export interface MemberResponsibility {
  assignmentId: string
  workItemId: string
  role: string
  version: number
}

export const memberLifecycleActions = ['suspend', 'activate', 'remove'] as const
export type MemberLifecycleAction = typeof memberLifecycleActions[number]

export const handoverJobStatuses = ['PENDING', 'RUNNING', 'COMPLETED', 'CANCELLED'] as const
export type HandoverJobStatus = typeof handoverJobStatuses[number]

export const handoverItemStates = ['PENDING', 'DONE', 'CONFLICT', 'DENIED'] as const
export type HandoverItemState = typeof handoverItemStates[number]

export interface HandoverItemView {
  id: string
  workItemId: string
  assignmentId: string
  state: HandoverItemState | string
  resultAssignmentId?: string | null
  errorCode?: string | null
}

export interface HandoverJobView {
  id: string
  role: string
  status: HandoverJobStatus | string
  sourceMemberId: string
  targetPrincipalId: string
  sourceAuthorizationVersion: number
  version: number
  items: HandoverItemView[]
}

/** Counts are derived on the client; the wire shape carries only the item list. */
export function handoverCounts(job: HandoverJobView): { done: number, conflict: number, denied: number, pending: number } {
  let done = 0, conflict = 0, denied = 0, pending = 0
  for (const item of job.items) {
    if (item.state === 'DONE') done += 1
    else if (item.state === 'CONFLICT') conflict += 1
    else if (item.state === 'DENIED') denied += 1
    else pending += 1
  }
  return { done, conflict, denied, pending }
}

export interface CommandReceipt {
  /** Client-only, resolved through the authorized result endpoint; not legacy POST fields. */
  creation?: import('../../api/creationRecovery').CreationResult
  createdResource?: unknown
  recoveryKey?: string
  commandId: string
  domainEventId: string
  committedVersion: number
  correlationId: string
}
