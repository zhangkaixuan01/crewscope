import { inject, type App, type InjectionKey } from 'vue'
import { apiClient, type CrewScopeApiClient } from '../../api/client'
import { organizationRoles, roleGrantStatuses } from './types'
import type {
  OrganizationCommandReceipt,
  OrganizationProfile,
  OrganizationRole,
  OrganizationRoleGrant,
  OrganizationSettings,
} from './types'

/**
 * M12-F01 organization HTTP adapter. Every method maps the response through an explicit
 * whitelist (model gateway precedent): unknown enum values and missing fields fail loudly,
 * and fields the browser must not disclose (confirmationTokenHash) never enter the DTOs.
 */
export interface OrganizationGateway {
  profile(organizationId: string, signal?: AbortSignal): Promise<OrganizationProfile>
  settings(organizationId: string, signal?: AbortSignal): Promise<OrganizationSettings>
  updateSettings(
    organizationId: string,
    input: { auditEventRetentionDays: number | null },
    expectedVersion: number,
    idempotencyKey: string,
  ): Promise<OrganizationCommandReceipt>
  /** The role-management audit face: a bare array, active grants first, revoked history behind. */
  roleGrants(organizationId: string, signal?: AbortSignal): Promise<OrganizationRoleGrant[]>
  grantRole(
    organizationId: string,
    input: { principalId: string, role: OrganizationRole, reason?: string },
    idempotencyKey: string,
  ): Promise<OrganizationCommandReceipt>
  revokeRoleGrant(
    organizationId: string,
    grantId: string,
    input: { reason?: string },
    expectedVersion: number,
    idempotencyKey: string,
  ): Promise<OrganizationCommandReceipt>
}

export const ORGANIZATION_GATEWAY: InjectionKey<OrganizationGateway> = Symbol('crewscope-organization-gateway')

export class HttpOrganizationGateway implements OrganizationGateway {
  constructor(private readonly client: CrewScopeApiClient = apiClient) {}

  async profile(organizationId: string, signal?: AbortSignal): Promise<OrganizationProfile> {
    return mapProfile(await this.client.get(`${organizationRoot(organizationId)}`, { signal }))
  }

  async settings(organizationId: string, signal?: AbortSignal): Promise<OrganizationSettings> {
    return mapSettings(await this.client.get(`${organizationRoot(organizationId)}/settings`, { signal }))
  }

  async updateSettings(
    organizationId: string,
    input: { auditEventRetentionDays: number | null },
    expectedVersion: number,
    idempotencyKey: string,
  ): Promise<OrganizationCommandReceipt> {
    // The first write materializes the row at version 0; null clears the dimension back to forever.
    const response = await this.client.open(`${organizationRoot(organizationId)}/settings`, {
      method: 'PUT',
      idempotencyKey,
      expectedVersion,
      body: { auditEventRetentionDays: input.auditEventRetentionDays },
    })
    return mapReceipt(await response.json(), replayed(response))
  }

  async roleGrants(organizationId: string, signal?: AbortSignal): Promise<OrganizationRoleGrant[]> {
    const value = await this.client.get(`${organizationRoot(organizationId)}/role-grants`, { signal })
    if (!Array.isArray(value)) throw new TypeError('Organization role grants response is invalid')
    return value.map(mapRoleGrant)
  }

  async grantRole(
    organizationId: string,
    input: { principalId: string, role: OrganizationRole, reason?: string },
    idempotencyKey: string,
  ): Promise<OrganizationCommandReceipt> {
    const response = await this.client.open(`${organizationRoot(organizationId)}/role-grants`, {
      method: 'POST',
      idempotencyKey,
      body: { principalId: input.principalId, role: input.role, reason: input.reason ?? null },
    })
    return mapReceipt(await response.json(), replayed(response))
  }

  async revokeRoleGrant(
    organizationId: string,
    grantId: string,
    input: { reason?: string },
    expectedVersion: number,
    idempotencyKey: string,
  ): Promise<OrganizationCommandReceipt> {
    const response = await this.client.open(
      `${organizationRoot(organizationId)}/role-grants/${encodeURIComponent(grantId)}/revoke`,
      {
        method: 'POST',
        idempotencyKey,
        expectedVersion,
        body: { reason: input.reason ?? null },
      },
    )
    return mapReceipt(await response.json(), replayed(response))
  }
}

export function installOrganizationGateway(app: App, gateway: OrganizationGateway): void {
  app.provide(ORGANIZATION_GATEWAY, gateway)
}

export function useOrganizationGateway(): OrganizationGateway {
  const gateway = inject(ORGANIZATION_GATEWAY)
  if (!gateway) throw new Error('CrewScope Organization Gateway is not installed')
  return gateway
}

function organizationRoot(organizationId: string): string {
  return `/organizations/${encodeURIComponent(organizationId)}`
}

function replayed(response: Response): boolean {
  return response.headers.get('Idempotency-Replayed') === 'true'
}

function mapProfile(input: unknown): OrganizationProfile {
  const value = record(input)
  return {
    organizationId: nonEmptyString(value.organizationId),
    name: nonEmptyString(value.name),
    status: oneOf(value.status, ['ACTIVE'] as const),
    version: nonNegativeInteger(value.version),
  }
}

function mapSettings(input: unknown): OrganizationSettings {
  const value = record(input)
  return {
    auditEventRetentionDays: nullableInteger(value.auditEventRetentionDays),
    version: nonNegativeInteger(value.version),
  }
}

function mapRoleGrant(input: unknown): OrganizationRoleGrant {
  const value = record(input)
  return {
    id: nonEmptyString(value.id),
    organizationId: nonEmptyString(value.organizationId),
    principalId: nonEmptyString(value.principalId),
    role: oneOf(value.role, organizationRoles),
    status: oneOf(value.status, roleGrantStatuses),
    grantedByPrincipalId: nonEmptyString(value.grantedByPrincipalId),
    grantedAt: nonEmptyString(value.grantedAt),
    revokedAt: nullableString(value.revokedAt),
    revokedByPrincipalId: nullableString(value.revokedByPrincipalId),
    reason: nullableString(value.reason),
    version: nonNegativeInteger(value.version),
  }
}

function nullableString(input: unknown): string | null {
  return input === null ? null : nonEmptyString(input)
}

function mapReceipt(input: unknown, replayed: boolean): OrganizationCommandReceipt {
  const value = record(input)
  return {
    commandId: nonEmptyString(value.commandId),
    domainEventId: nonEmptyString(value.domainEventId),
    committedVersion: nonNegativeInteger(value.committedVersion),
    correlationId: nonEmptyString(value.correlationId),
    replayed,
  }
}

function nullableInteger(input: unknown): number | null {
  return input === null ? null : nonNegativeInteger(input)
}

function record(input: unknown): Record<string, unknown> {
  if (!input || typeof input !== 'object' || Array.isArray(input)) {
    throw new TypeError('Organization response object is invalid')
  }
  return input as Record<string, unknown>
}

function nonEmptyString(input: unknown): string {
  if (typeof input !== 'string' || !input.trim()) throw new TypeError('Organization response string is invalid')
  return input
}

function nonNegativeInteger(input: unknown): number {
  if (typeof input !== 'number' || !Number.isSafeInteger(input) || input < 0) {
    throw new TypeError('Organization response integer is invalid')
  }
  return input
}

function oneOf<const T extends readonly string[]>(input: unknown, values: T): T[number] {
  if (typeof input !== 'string' || !values.includes(input)) throw new TypeError('Organization response enum is invalid')
  return input as T[number]
}
