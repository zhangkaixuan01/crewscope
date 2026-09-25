import type { AuthCsrfCoordinate } from '../identity/types'

export const invitationRoles = ['TEAM_ADMIN', 'TEAM_LEAD', 'MEMBER', 'AUDITOR'] as const
export type InvitationRole = typeof invitationRoles[number]

export const invitationStatuses = ['PENDING', 'ACCEPTED', 'REVOKED', 'EXPIRED'] as const
export type InvitationStatus = typeof invitationStatuses[number]

export const invitationPreviewStates = ['AVAILABLE', 'EXPIRED', 'UNAVAILABLE'] as const
export type InvitationPreviewState = typeof invitationPreviewStates[number]

export interface TeamInvitationSummary {
  id: string
  organizationId: string
  teamId: string
  invitedByPrincipalId: string
  targetEmail: string | null
  targetRole: InvitationRole
  status: InvitationStatus
  expiresAt: string
  acceptedMemberId: string | null
  resolvedAt: string | null
  version: number
  createdAt: string
  updatedAt: string
}

export interface TeamInvitationPage {
  items: TeamInvitationSummary[]
  nextCursor: string | null
}

export interface InvitationCommandReceipt {
  commandId: string
  domainEventId: string
  committedVersion: number
  correlationId: string
}

export interface InvitationCreationInput {
  targetEmail?: string
  targetRole: InvitationRole
  expiresInMinutes: number
}

export interface InvitationCreationResult {
  command: InvitationCommandReceipt
  invitation: TeamInvitationSummary | null
  /** One-way proof returned only by the first successful issue response. */
  token: string | null
  replayed: boolean
}

export interface InvitationPreview {
  state: InvitationPreviewState
  invitationId: string | null
  teamName: string | null
  targetRole: InvitationRole | null
  expiresAt: string | null
  targetRestricted: boolean
}

export const invitationMembershipDispositions = ['CREATED', 'ACTIVATED', 'REUSED'] as const
export type InvitationMembershipDisposition = typeof invitationMembershipDispositions[number]

/**
 * Committed acceptance coordinates (A07): the exact team/member the accept created.
 * A replay backfills only the durable `teamId`/`memberId`; the disposition facts and a
 * replay without a stored result leave them (or the whole block) null.
 */
export interface InvitationAcceptance {
  teamId: string | null
  memberId: string | null
  invitationId: string | null
  membershipDisposition: InvitationMembershipDisposition | null
  roleGrantCreated: boolean | null
}

export interface InvitationAcceptanceResult {
  command: InvitationCommandReceipt
  acceptance: InvitationAcceptance | null
  replayed: boolean
}

export interface InvitationCommandContext {
  csrf: AuthCsrfCoordinate
  idempotencyKey: string
}
