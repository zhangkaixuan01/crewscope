import { apiClient, type CrewScopeApiClient } from '../../api/client'
import type { CreateFirstTeamInput, OnboardingReceipt, OnboardingStatus } from './types'

export interface OnboardingGateway {
  status(signal?: AbortSignal): Promise<OnboardingStatus>
  createFirstTeam(input: CreateFirstTeamInput, signal?: AbortSignal): Promise<OnboardingReceipt>
}

/** Closed HTTP adapter for current-account first-Team onboarding. */
export class HttpOnboardingGateway implements OnboardingGateway {
  constructor(private readonly client: CrewScopeApiClient = apiClient) {}

  async status(signal?: AbortSignal): Promise<OnboardingStatus> {
    return mapStatus(await this.client.get('/onboarding', { signal }))
  }

  async createFirstTeam(input: CreateFirstTeamInput, signal?: AbortSignal): Promise<OnboardingReceipt> {
    const response = await this.client.open('/onboarding/team', {
      method: 'POST',
      signal,
      idempotencyKey: input.idempotencyKey,
      headers: { [input.csrf.headerName]: input.csrf.token },
      body: { name: input.name },
    })
    const replayed = response.headers.get('Idempotency-Replayed')
    if (replayed !== null && replayed !== 'true') {
      throw new TypeError('Onboarding replay header is invalid')
    }
    return { ...mapReceipt(await response.json()), replayed: replayed === 'true' }
  }
}

function mapStatus(input: unknown): OnboardingStatus {
  const value = record(input)
  const state = oneOf(value.state, ['TEAM_REQUIRED', 'COMPLETE'] as const)
  const onboardingRequired = boolean(value.onboardingRequired)
  const activeTeamCount = nonNegativeInteger(value.activeTeamCount)
  if ((state === 'TEAM_REQUIRED') !== onboardingRequired) {
    throw new TypeError('Onboarding status requirement is inconsistent')
  }
  if ((state === 'TEAM_REQUIRED') !== (activeTeamCount === 0)) {
    throw new TypeError('Onboarding Team count is inconsistent')
  }
  return { state, onboardingRequired, activeTeamCount }
}

function mapReceipt(input: unknown): Omit<OnboardingReceipt, 'replayed'> {
  const value = record(input)
  return {
    commandId: nonEmptyString(value.commandId),
    domainEventId: nonEmptyString(value.domainEventId),
    committedVersion: nonNegativeInteger(value.committedVersion),
    correlationId: nonEmptyString(value.correlationId),
  }
}

function record(input: unknown): Record<string, unknown> {
  if (!input || typeof input !== 'object' || Array.isArray(input)) {
    throw new TypeError('Onboarding response object is invalid')
  }
  return input as Record<string, unknown>
}

function boolean(input: unknown): boolean {
  if (typeof input !== 'boolean') throw new TypeError('Onboarding response boolean is invalid')
  return input
}

function nonEmptyString(input: unknown): string {
  if (typeof input !== 'string' || !input.trim()) throw new TypeError('Onboarding response string is invalid')
  return input
}

function nonNegativeInteger(input: unknown): number {
  if (typeof input !== 'number' || !Number.isSafeInteger(input) || input < 0) {
    throw new TypeError('Onboarding response number is invalid')
  }
  return input
}

function oneOf<const T extends readonly string[]>(input: unknown, values: T): T[number] {
  if (typeof input !== 'string' || !values.includes(input)) {
    throw new TypeError('Onboarding response state is invalid')
  }
  return input as T[number]
}
