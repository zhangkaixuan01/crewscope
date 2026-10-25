// @vitest-environment node
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

  it('maps invalid_value by field: input fields keep remediation copy, platform registry fields explain the deployment conflict', () => {
    const teamName = invalidValueError('team.name')
    expectPublic(presentOnboardingProblem(teamName), 'invalid_value')
    expect(presentOnboardingProblem(teamName).message).toContain('1 至 200 个字符')

    for (const field of ['modelProvider.contentHash', 'modelCatalog.contentHash', 'modelPrice.contentHash']) {
      const problem = presentOnboardingProblem(invalidValueError(field))
      expectPublic(problem, 'platform_catalog_conflict')
      expect(problem.message).toContain('管理员')
    }

    expectPublic(presentOnboardingProblem(apiError('invalid_value')), 'invalid_value')
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

function invalidValueError(field: string): CrewScopeApiError {
  return new CrewScopeApiError(422, {
    code: 'invalid_value', message: 'private onboarding detail', correlationId: 'private-correlation',
    retryable: false, currentVersion: null, details: { field, reason: 'private reason' },
  })
}