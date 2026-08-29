import { apiClient, type CrewScopeApiClient } from '../../api/client'
import type {
  AccountCommandContext,
  AccountPasswordChangeInput,
  AccountProfile,
  AccountProfileUpdateInput,
  AccountSessionRevocationInput,
  VersionedAccountProfile,
} from './types'

export interface AccountGateway {
  current(signal?: AbortSignal): Promise<VersionedAccountProfile>
  updateProfile(
    input: AccountProfileUpdateInput,
    context: AccountCommandContext,
    signal?: AbortSignal,
  ): Promise<VersionedAccountProfile>
  changePassword(
    input: AccountPasswordChangeInput,
    context: AccountCommandContext,
    signal?: AbortSignal,
  ): Promise<number>
  revokeAllSessions(
    input: AccountSessionRevocationInput,
    context: AccountCommandContext,
    signal?: AbortSignal,
  ): Promise<number>
}

/** Closed HTTP adapter for the current authenticated Account only. */
export class HttpAccountGateway implements AccountGateway {
  constructor(private readonly client: CrewScopeApiClient = apiClient) {}

  async current(signal?: AbortSignal): Promise<VersionedAccountProfile> {
    return accountResponse(await this.client.open('/account', { method: 'GET', signal }))
  }

  async updateProfile(
    input: AccountProfileUpdateInput,
    context: AccountCommandContext,
    signal?: AbortSignal,
  ): Promise<VersionedAccountProfile> {
    return accountResponse(await this.client.open('/account', {
      method: 'PATCH',
      signal,
      expectedVersion: context.expectedVersion,
      headers: { [context.csrf.headerName]: context.csrf.token },
      body: {
        ...(input.username !== undefined ? { username: input.username } : {}),
        ...(input.email !== undefined ? { email: input.email } : {}),
        ...(input.displayName !== undefined ? { displayName: input.displayName } : {}),
        ...(input.currentPassword !== undefined ? { currentPassword: input.currentPassword } : {}),
        ...(input.securityVersion !== undefined ? { securityVersion: input.securityVersion } : {}),
      },
    }))
  }

  async changePassword(
    input: AccountPasswordChangeInput,
    context: AccountCommandContext,
    signal?: AbortSignal,
  ): Promise<number> {
    return emptyCommand(await this.client.open('/account/password', {
      method: 'POST',
      signal,
      expectedVersion: context.expectedVersion,
      headers: { [context.csrf.headerName]: context.csrf.token },
      body: {
        currentPassword: input.currentPassword,
        newPassword: input.newPassword,
        securityVersion: input.securityVersion,
      },
    }))
  }

  async revokeAllSessions(
    input: AccountSessionRevocationInput,
    context: AccountCommandContext,
    signal?: AbortSignal,
  ): Promise<number> {
    return emptyCommand(await this.client.open('/account/sessions/revoke', {
      method: 'POST',
      signal,
      expectedVersion: context.expectedVersion,
      headers: { [context.csrf.headerName]: context.csrf.token },
      body: { currentPassword: input.currentPassword, securityVersion: input.securityVersion },
    }))
  }
}

async function accountResponse(response: Response): Promise<VersionedAccountProfile> {
  const value = mapAccount(await response.json())
  const etag = strongVersion(response.headers.get('ETag'))
  if (etag !== value.version) throw new TypeError('Account ETag does not match aggregate version')
  return { value, etag }
}

function emptyCommand(response: Response): number {
  if (response.status !== 204) throw new TypeError('Account security command response is invalid')
  return strongVersion(response.headers.get('ETag'))
}

function mapAccount(input: unknown): AccountProfile {
  const value = record(input)
  return {
    accountId: nonEmptyString(value.accountId),
    username: nonEmptyString(value.username),
    email: nonEmptyString(value.email),
    displayName: nonEmptyString(value.displayName),
    status: oneOf(value.status, ['ACTIVE', 'DISABLED', 'LOCKED'] as const),
    platformRole: oneOf(value.platformRole, ['USER', 'OPERATOR'] as const),
    securityVersion: nonNegativeInteger(value.securityVersion),
    version: nonNegativeInteger(value.version),
    createdAt: instant(value.createdAt),
    updatedAt: instant(value.updatedAt),
  }
}

function strongVersion(input: string | null): number {
  const match = input?.match(/^"(0|[1-9]\d*)"$/)
  if (!match) throw new TypeError('Account ETag is invalid')
  const value = Number(match[1])
  if (!Number.isSafeInteger(value)) throw new TypeError('Account ETag version is invalid')
  return value
}

function record(input: unknown): Record<string, unknown> {
  if (!input || typeof input !== 'object' || Array.isArray(input)) {
    throw new TypeError('Account response object is invalid')
  }
  return input as Record<string, unknown>
}

function nonEmptyString(input: unknown): string {
  if (typeof input !== 'string' || !input.trim()) throw new TypeError('Account response string is invalid')
  return input
}

function nonNegativeInteger(input: unknown): number {
  if (typeof input !== 'number' || !Number.isSafeInteger(input) || input < 0) {
    throw new TypeError('Account response version is invalid')
  }
  return input
}

function instant(input: unknown): string {
  const value = nonEmptyString(input)
  if (Number.isNaN(Date.parse(value))) throw new TypeError('Account response timestamp is invalid')
  return value
}

function oneOf<const T extends readonly string[]>(input: unknown, values: T): T[number] {
  if (typeof input !== 'string' || !values.includes(input)) throw new TypeError('Account response enum is invalid')
  return input as T[number]
}
