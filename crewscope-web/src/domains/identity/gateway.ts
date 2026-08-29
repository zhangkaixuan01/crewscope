import { inject, type App, type InjectionKey } from 'vue'
import { apiClient, type CrewScopeApiClient } from '../../api/client'
import { registrationModes } from './types'
import type {
  AuthCsrfCoordinate,
  AuthSession,
  AuthSessionAccount,
  AuthSessionPrincipal,
  AuthSessionTeam,
  LoginCredentials,
  LoginResult,
  RegistrationInput,
  RegistrationMode,
  RegistrationResult,
} from './types'

export interface IdentityGateway {
  session(signal?: AbortSignal): Promise<AuthSession>
  login(credentials: LoginCredentials, csrf: AuthCsrfCoordinate, signal?: AbortSignal): Promise<LoginResult>
  logout(csrf: AuthCsrfCoordinate, signal?: AbortSignal): Promise<void>
  register(
    input: RegistrationInput,
    csrf: AuthCsrfCoordinate,
    idempotencyKey: string,
    signal?: AbortSignal,
  ): Promise<RegistrationResult>
}

export const IDENTITY_GATEWAY: InjectionKey<IdentityGateway> = Symbol('crewscope-identity-gateway')

/** HTTP adapter that keeps password and CSRF values inside the one authentication request. */
export class HttpIdentityGateway implements IdentityGateway {
  constructor(private readonly client: CrewScopeApiClient = apiClient) {}

  async session(signal?: AbortSignal): Promise<AuthSession> {
    return mapSession(await this.client.get('/auth/session', { signal }))
  }

  async login(
    credentials: LoginCredentials,
    csrf: AuthCsrfCoordinate,
    signal?: AbortSignal,
  ): Promise<LoginResult> {
    return mapLoginResult(await this.client.post('/auth/login', {
      identifier: credentials.identifier,
      password: credentials.password,
    }, {
      signal,
      headers: { [csrf.headerName]: csrf.token },
    }))
  }

  async logout(csrf: AuthCsrfCoordinate, signal?: AbortSignal): Promise<void> {
    const response = await this.client.open('/auth/logout', {
      method: 'POST',
      signal,
      headers: { [csrf.headerName]: csrf.token },
    })
    if (response.status !== 204) throw new TypeError('Logout response is invalid')
  }

  async register(
    input: RegistrationInput,
    csrf: AuthCsrfCoordinate,
    idempotencyKey: string,
    signal?: AbortSignal,
  ): Promise<RegistrationResult> {
    return mapRegistrationResult(await this.client.post('/auth/register', {
      username: input.username,
      email: input.email,
      displayName: input.displayName,
      password: input.password,
      ...(input.invitationToken ? { invitationToken: input.invitationToken } : {}),
    }, {
      signal,
      idempotencyKey,
      headers: { [csrf.headerName]: csrf.token },
    }))
  }
}

export function installIdentityGateway(app: App, gateway: IdentityGateway): void {
  app.provide(IDENTITY_GATEWAY, gateway)
}

export function useIdentityGateway(): IdentityGateway {
  const gateway = inject(IDENTITY_GATEWAY)
  if (!gateway) throw new Error('CrewScope Identity Gateway is not installed')
  return gateway
}

function mapSession(input: unknown): AuthSession {
  const value = record(input)
  const authenticated = boolean(value.authenticated)
  const account = nullable(value.account, mapAccount)
  const principal = nullable(value.principal, mapPrincipal)
  const teams = array(value.teams).map(mapTeam)
  const permissions = array(value.permissions).map(nonEmptyString)
  if ((authenticated && (!account || !principal)) || (!authenticated && (account !== null || principal !== null))) {
    throw new TypeError('Authentication session identity is inconsistent')
  }
  if (!authenticated && (teams.length > 0 || permissions.length > 0)) {
    throw new TypeError('Anonymous session must not contain authorization facts')
  }
  return {
    authenticated,
    registrationMode: oneOf(value.registrationMode, registrationModes),
    csrf: mapCsrf(value.csrf),
    account,
    principal,
    teams,
    permissions,
  }
}

function mapCsrf(input: unknown): AuthCsrfCoordinate {
  const value = record(input)
  const headerName = nonEmptyString(value.headerName)
  const parameterName = nonEmptyString(value.parameterName)
  if (headerName !== 'X-XSRF-TOKEN' || parameterName !== '_csrf') {
    throw new TypeError('Authentication CSRF coordinate is not supported')
  }
  return { headerName, parameterName, token: nonEmptyString(value.token) }
}

function mapAccount(input: unknown): AuthSessionAccount {
  const value = record(input)
  return {
    accountId: nonEmptyString(value.accountId),
    username: nonEmptyString(value.username),
    displayName: nonEmptyString(value.displayName),
    platformRole: oneOf(value.platformRole, ['USER', 'OPERATOR'] as const),
    securityVersion: nonNegativeInteger(value.securityVersion),
    version: nonNegativeInteger(value.version),
  }
}

function mapPrincipal(input: unknown): AuthSessionPrincipal {
  const value = record(input)
  return {
    principalId: nonEmptyString(value.principalId),
    organizationId: nonEmptyString(value.organizationId),
  }
}

function mapTeam(input: unknown): AuthSessionTeam {
  const value = record(input)
  return {
    teamId: nonEmptyString(value.teamId),
    name: nonEmptyString(value.name),
    memberId: nonEmptyString(value.memberId),
    permissions: array(value.permissions).map(nonEmptyString),
  }
}

function mapLoginResult(input: unknown): LoginResult {
  const value = record(input)
  if (value.authenticated !== true) throw new TypeError('Login response is not authenticated')
  return {
    authenticated: true,
    accountId: nonEmptyString(value.accountId),
    displayName: nonEmptyString(value.displayName),
  }
}

function mapRegistrationResult(input: unknown): RegistrationResult {
  const value = record(input)
  const onboardingRequired = boolean(value.onboardingRequired)
  const teamId = optionalString(value.teamId)
  const memberId = optionalString(value.memberId)
  if ((teamId === null) !== (memberId === null)) {
    throw new TypeError('Registration Team coordinates are inconsistent')
  }
  if (onboardingRequired !== (teamId === null)) {
    throw new TypeError('Registration onboarding state is inconsistent')
  }
  return {
    accountId: nonEmptyString(value.accountId),
    principalId: nonEmptyString(value.principalId),
    organizationId: nonEmptyString(value.organizationId),
    teamId,
    memberId,
    onboardingRequired,
    commandId: nonEmptyString(value.commandId),
    domainEventId: nonEmptyString(value.domainEventId),
    committedVersion: nonNegativeInteger(value.committedVersion),
    correlationId: nonEmptyString(value.correlationId),
    replayed: boolean(value.replayed),
  }
}

function nullable<T>(input: unknown, mapper: (value: unknown) => T): T | null {
  return input === null ? null : mapper(input)
}

function optionalString(input: unknown): string | null {
  return input === null || input === undefined ? null : nonEmptyString(input)
}

function record(input: unknown): Record<string, unknown> {
  if (!input || typeof input !== 'object' || Array.isArray(input)) throw new TypeError('Authentication response object is invalid')
  return input as Record<string, unknown>
}

function array(input: unknown): unknown[] {
  if (!Array.isArray(input)) throw new TypeError('Authentication response array is invalid')
  return input
}

function boolean(input: unknown): boolean {
  if (typeof input !== 'boolean') throw new TypeError('Authentication response boolean is invalid')
  return input
}

function nonEmptyString(input: unknown): string {
  if (typeof input !== 'string' || !input.trim()) throw new TypeError('Authentication response string is invalid')
  return input
}

function nonNegativeInteger(input: unknown): number {
  if (typeof input !== 'number' || !Number.isSafeInteger(input) || input < 0) {
    throw new TypeError('Authentication response version is invalid')
  }
  return input
}

function oneOf<const T extends readonly string[]>(input: unknown, values: T): T[number] {
  if (typeof input !== 'string' || !values.includes(input)) throw new TypeError('Authentication response enum is invalid')
  return input as T[number]
}

export function registrationModeLabel(mode: RegistrationMode): string {
  if (mode === 'OPEN') return '当前部署支持自行创建账号'
  if (mode === 'INVITE_ONLY') return '新成员通过团队邀请加入'
  return '当前部署未开放新账号注册'
}
