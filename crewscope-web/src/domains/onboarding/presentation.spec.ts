import { CrewScopeApiError } from '../../api/client'
import {
  OnboardingRequestTimeoutError,
  onboardingNotConvergedProblem,
  onboardingProjectionProblem,
  offlineOnboardingProblem,
  presentOnboardingProblem,
} from './presentation'

describe('onboarding presentation', () => {
  it.each([
    ['network_unavailable', 'network_unavailable'],
    ['onboarding_unavailable', 'onboarding_unavailable'],
    ['onboarding_already_complete', 'onboarding_already_complete'],
    ['idempotency_conflict', 'idempotency_conflict'],
    ['csrf_rejected', 'csrf_rejected'],
    ['invalid_request', 'invalid_request'],
    ['request_too_large', 'invalid_request'],
    ['unknown', 'onboarding_unavailable'],
  ])('maps %s to stable public state %s', (serverCode, publicCode) => {
    expectPublic(presentOnboardingProblem(apiError(serverCode)), publicCode)
  })

  it('covers timeout, convergence, projection and offline states', () => {
    expectPublic(presentOnboardingProblem(new OnboardingRequestTimeoutError()), 'request_timeout')
    expectPublic(presentOnboardingProblem(new Error('private onboarding detail')), 'onboarding_unavailable')
    expectPublic(onboardingNotConvergedProblem(), 'onboarding_not_converged')
    expectPublic(onboardingProjectionProblem(), 'onboarding_projection_unavailable')
    expectPublic(offlineOnboardingProblem(), 'network_unavailable')
  })
})

function expectPublic(problem: { code: string, title: string, message: string }, code: string): void {
  expect(problem.code).toBe(code)
  expect(`${problem.title} ${problem.message}`).not.toContain('private')
}

function apiError(code: string): CrewScopeApiError {
  return new CrewScopeApiError(400, {
    code, message: 'private onboarding detail', correlationId: 'private-correlation',
    retryable: false, currentVersion: null, details: {},
  })
}
