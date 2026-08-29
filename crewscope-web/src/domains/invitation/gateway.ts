import { apiClient, type CrewScopeApiClient } from '../../api/client'
import type {
  InvitationCommandContext,
  InvitationCommandReceipt,
  InvitationCreationInput,
  InvitationCreationResult,
  InvitationPreview,
  InvitationRole,
  InvitationStatus,
  TeamInvitationPage,
  TeamInvitationSummary,
} from './types'

export interface InvitationGateway {
  list(organizationId: string, teamId: string, after?: string | null, signal?: AbortSignal): Promise<TeamInvitationPage>
  create(
    organizationId: string,
    teamId: string,
    input: InvitationCreationInput,
    context: InvitationCommandContext,
    signal?: AbortSignal,
  ): Promise<InvitationCreationResult>
  revoke(
    organizationId: string,
    teamId: string,
    invitationId: string,
    context: InvitationCommandContext,
    signal?: AbortSignal,
  ): Promise<InvitationCommandReceipt>
  preview(token: string, signal?: AbortSignal): Promise<InvitationPreview>
  accept(token: string, context: InvitationCommandContext, signal?: AbortSignal): Promise<InvitationCommandReceipt>
}

/** Closed adapter for Team invitation management and one-way invitation proofs. */
export class HttpInvitationGateway implements InvitationGateway {
  constructor(private readonly client: CrewScopeApiClient = apiClient) {}

  async list(
    organizationId: string,
    teamId: string,
    after?: string | null,
    signal?: AbortSignal,
  ): Promise<TeamInvitationPage> {
    const query = new URLSearchParams({ limit: '50' })
    if (after) query.set('after', after)
    const input = await this.client.get<unknown>(`${managementPath(organizationId, teamId)}?${query}`, { signal })
    const value = record(input)
    return {
      items: array(value.items).map(mapInvitation),
      nextCursor: nullableString(value.nextCursor),
    }
  }

  async create(
    organizationId: string,
    teamId: string,
    input: InvitationCreationInput,
    context: InvitationCommandContext,
    signal?: AbortSignal,
  ): Promise<InvitationCreationResult> {
    const response = await this.client.open(managementPath(organizationId, teamId), {
      method: 'POST',
      signal,
      idempotencyKey: context.idempotencyKey,
      headers: { [context.csrf.headerName]: context.csrf.token },
      body: {
        ...(input.targetEmail ? { targetEmail: input.targetEmail } : {}),
        targetRole: input.targetRole,
        expiresInMinutes: input.expiresInMinutes,
      },
    })
    if (response.status !== 202) throw new TypeError('Invitation creation response is invalid')
    const value = record(await response.json())
    const replayed = response.headers.get('Idempotency-Replayed') === 'true'
    const invitation = value.invitation === null ? null : mapInvitation(value.invitation)
    const token = nullableProof(value.token)
    if (!replayed && (!invitation || !token)) throw new TypeError('Invitation creation proof is missing')
    if (replayed && (invitation || token)) throw new TypeError('Invitation replay exposes issue material')
    return { command: mapReceipt(value.command), invitation, token, replayed }
  }

  async revoke(
    organizationId: string,
    teamId: string,
    invitationId: string,
    context: InvitationCommandContext,
    signal?: AbortSignal,
  ): Promise<InvitationCommandReceipt> {
    return command(await this.client.open(
      `${managementPath(organizationId, teamId)}/${segment(invitationId)}/revoke`,
      {
        method: 'POST', signal, idempotencyKey: context.idempotencyKey,
        headers: { [context.csrf.headerName]: context.csrf.token },
      },
    ))
  }

  async preview(token: string, signal?: AbortSignal): Promise<InvitationPreview> {
    const value = record(await this.client.post<unknown>('/invitations/preview', { token }, { signal }))
    const state = oneOf(value.state, ['AVAILABLE', 'EXPIRED', 'UNAVAILABLE'] as const)
    const targetRole = value.targetRole === null
      ? null
      : oneOf(value.targetRole, ['TEAM_ADMIN', 'TEAM_LEAD', 'MEMBER', 'AUDITOR'] as const)
    const preview = {
      state,
      invitationId: nullableString(value.invitationId),
      teamName: nullableString(value.teamName),
      targetRole,
      expiresAt: nullableInstant(value.expiresAt),
      targetRestricted: boolean(value.targetRestricted),
    }
    if (state === 'AVAILABLE' && (!preview.invitationId || !preview.teamName || !preview.targetRole || !preview.expiresAt)) {
      throw new TypeError('Available invitation preview is incomplete')
    }
    return preview
  }

  async accept(
    token: string,
    context: InvitationCommandContext,
    signal?: AbortSignal,
  ): Promise<InvitationCommandReceipt> {
    return command(await this.client.open('/invitations/accept', {
      method: 'POST', signal, idempotencyKey: context.idempotencyKey,
      headers: { [context.csrf.headerName]: context.csrf.token },
      body: { token },
    }))
  }
}

async function command(response: Response): Promise<InvitationCommandReceipt> {
  if (response.status !== 202) throw new TypeError('Invitation command response is invalid')
  return mapReceipt(await response.json())
}

function mapInvitation(input: unknown): TeamInvitationSummary {
  const value = record(input)
  return {
    id: nonEmptyString(value.id),
    organizationId: nonEmptyString(value.organizationId),
    teamId: nonEmptyString(value.teamId),
    invitedByPrincipalId: nonEmptyString(value.invitedByPrincipalId),
    targetEmail: nullableString(value.targetEmail),
    targetRole: oneOf(value.targetRole, ['TEAM_ADMIN', 'TEAM_LEAD', 'MEMBER', 'AUDITOR'] as const) as InvitationRole,
    status: oneOf(value.status, ['PENDING', 'ACCEPTED', 'REVOKED', 'EXPIRED'] as const) as InvitationStatus,
    expiresAt: instant(value.expiresAt),
    acceptedMemberId: nullableString(value.acceptedMemberId),
    resolvedAt: nullableInstant(value.resolvedAt),
    version: nonNegativeInteger(value.version),
    createdAt: instant(value.createdAt),
    updatedAt: instant(value.updatedAt),
  }
}

function mapReceipt(input: unknown): InvitationCommandReceipt {
  const value = record(input)
  return {
    commandId: nonEmptyString(value.commandId),
    domainEventId: nonEmptyString(value.domainEventId),
    committedVersion: nonNegativeInteger(value.committedVersion),
    correlationId: nonEmptyString(value.correlationId),
  }
}

function managementPath(organizationId: string, teamId: string): string {
  return `/organizations/${segment(organizationId)}/teams/${segment(teamId)}/invitations`
}

function segment(value: string): string {
  return encodeURIComponent(value)
}

function record(input: unknown): Record<string, unknown> {
  if (!input || typeof input !== 'object' || Array.isArray(input)) throw new TypeError('Invitation object is invalid')
  return input as Record<string, unknown>
}

function array(input: unknown): unknown[] {
  if (!Array.isArray(input)) throw new TypeError('Invitation array is invalid')
  return input
}

function nonEmptyString(input: unknown): string {
  if (typeof input !== 'string' || !input.trim()) throw new TypeError('Invitation string is invalid')
  return input
}

function nullableString(input: unknown): string | null {
  return input === null ? null : nonEmptyString(input)
}

function nullableProof(input: unknown): string | null {
  if (input === null) return null
  const value = nonEmptyString(input)
  if (!/^[A-Za-z0-9_-]{43}$/.test(value)) throw new TypeError('Invitation proof is invalid')
  return value
}

function nonNegativeInteger(input: unknown): number {
  if (typeof input !== 'number' || !Number.isSafeInteger(input) || input < 0) throw new TypeError('Invitation version is invalid')
  return input
}

function instant(input: unknown): string {
  const value = nonEmptyString(input)
  if (Number.isNaN(Date.parse(value))) throw new TypeError('Invitation timestamp is invalid')
  return value
}

function nullableInstant(input: unknown): string | null {
  return input === null ? null : instant(input)
}

function boolean(input: unknown): boolean {
  if (typeof input !== 'boolean') throw new TypeError('Invitation boolean is invalid')
  return input
}

function oneOf<const T extends readonly string[]>(input: unknown, values: T): T[number] {
  if (typeof input !== 'string' || !values.includes(input)) throw new TypeError('Invitation enum is invalid')
  return input as T[number]
}
