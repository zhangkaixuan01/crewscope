import { CrewScopeApiError } from '../../api/client'
import type { AuthCsrfCoordinate } from '../identity/types'
import type { OnboardingGateway } from './gateway'
import { createOnboardingStore } from './store'
import type { CreateFirstTeamInput, OnboardingReceipt, OnboardingStatus } from './types'

const csrf: AuthCsrfCoordinate = { headerName: 'X-XSRF-TOKEN', parameterName: '_csrf', token: 'csrf-memory' }

describe('OnboardingStore', () => {
  it('derives required and complete phases from server status', async () => {
    const required = createOnboardingStore(gateway([status(false)]))
    expect(await required.load()).toBe(false)
    expect(required.state.phase).toBe('required')

    const complete = createOnboardingStore(gateway([status(true)]))
    expect(await complete.load()).toBe(true)
    expect(complete.state.phase).toBe('complete')
  })

  it('creates once and verifies the complete server projection', async () => {
    const onboarding = gateway([status(false), status(true)])
    const store = createOnboardingStore(onboarding)
    await store.load()

    expect(await store.createFirstTeam(' Platform Engineering ', csrf)).toBe(true)
    expect(store.state.phase).toBe('complete')
    expect(store.state.receipt?.commandId).toBe('command-1')
    expect(onboarding.createFirstTeam).toHaveBeenCalledWith(
      expect.objectContaining({ name: 'Platform Engineering', idempotencyKey: expect.any(String) }),
      expect.any(AbortSignal),
    )
  })

  it('reuses one private idempotency key after an uncertain transport failure', async () => {
    const keys: string[] = []
    let attempts = 0
    const onboarding = gateway([status(false), status(true)], async input => {
      keys.push(input.idempotencyKey)
      attempts += 1
      if (attempts === 1) throw apiError(0, 'network_unavailable')
      return receipt()
    })
    const store = createOnboardingStore(onboarding)
    await store.load()

    expect(await store.createFirstTeam('Platform', csrf)).toBe(false)
    expect(await store.retry(csrf)).toBe(true)
    expect(keys).toHaveLength(2)
    expect(keys[0]).toBe(keys[1])
  })

  it('starts a new idempotency intent after a conflict', async () => {
    const keys: string[] = []
    let attempts = 0
    const onboarding = gateway([status(false), status(true)], async input => {
      keys.push(input.idempotencyKey)
      attempts += 1
      if (attempts === 1) throw apiError(409, 'idempotency_conflict')
      return receipt()
    })
    const store = createOnboardingStore(onboarding)
    await store.load()

    expect(await store.createFirstTeam('Platform', csrf)).toBe(false)
    expect(store.state.problem?.code).toBe('idempotency_conflict')
    expect(await store.createFirstTeam('Platform', csrf)).toBe(true)
    expect(keys[0]).not.toBe(keys[1])
  })

  it('recovers an accepted command when status verification was interrupted', async () => {
    let statusCalls = 0
    const onboarding: OnboardingGateway = {
      status: vi.fn(async () => {
        statusCalls += 1
        if (statusCalls === 1) return status(false)
        if (statusCalls === 2) throw apiError(503, 'onboarding_unavailable')
        return status(true)
      }),
      createFirstTeam: vi.fn(async () => receipt()),
    }
    const store = createOnboardingStore(onboarding)
    await store.load()

    expect(await store.createFirstTeam('Platform', csrf)).toBe(false)
    expect(store.state.receipt?.commandId).toBe('command-1')
    expect(await store.retry(csrf)).toBe(true)
    expect(onboarding.createFirstTeam).toHaveBeenCalledOnce()
  })

  it('invalidates a late status response after identity reset', async () => {
    const pending = deferred<OnboardingStatus>()
    const onboarding = gateway([])
    onboarding.status = vi.fn(async () => pending.promise)
    const store = createOnboardingStore(onboarding)

    const request = store.load()
    store.reset()
    pending.resolve(status(true))
    await request

    expect(store.state.phase).toBe('idle')
    expect(store.state.status).toBeNull()
  })
})

function gateway(
  statuses: OnboardingStatus[],
  create: (input: CreateFirstTeamInput) => Promise<OnboardingReceipt> = async () => receipt(),
): OnboardingGateway {
  let index = 0
  return {
    status: vi.fn(async () => statuses[Math.min(index++, statuses.length - 1)]!),
    createFirstTeam: vi.fn(async input => create(input)),
  }
}

function status(complete: boolean): OnboardingStatus {
  return complete
    ? { state: 'COMPLETE', onboardingRequired: false, activeTeamCount: 1 }
    : { state: 'TEAM_REQUIRED', onboardingRequired: true, activeTeamCount: 0 }
}

function receipt(): OnboardingReceipt {
  return {
    commandId: 'command-1', domainEventId: 'event-1', committedVersion: 0,
    correlationId: 'correlation-1', replayed: false,
  }
}

function apiError(statusCode: number, code: string): CrewScopeApiError {
  return new CrewScopeApiError(statusCode, {
    code, message: 'private onboarding error', correlationId: 'safe-correlation',
    retryable: statusCode === 0 || statusCode >= 429, currentVersion: null, details: {},
  })
}

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>(accept => { resolve = accept })
  return { promise, resolve }
}
