import { CrewScopeApiError } from '../../api/client'
import {
  IdentityRequestTimeoutError,
  offlineLoginProblem,
  offlineRegistrationProblem,
  presentLoginProblem,
  presentRegistrationProblem,
  presentSessionProblem,
} from './presentation'

describe('identity presentation', () => {
  it.each([
    ['invalid_credentials', 'invalid_credentials'],
    ['too_many_requests', 'too_many_requests'],
    ['network_unavailable', 'network_unavailable'],
    ['csrf_rejected', 'csrf_rejected'],
    ['invalid_request', 'invalid_request'],
    ['request_too_large', 'invalid_request'],
    ['unknown', 'authentication_unavailable'],
  ])('maps login error %s to stable public code %s', (serverCode, publicCode) => {
    expectPublic(presentLoginProblem(apiError(serverCode)), publicCode)
  })

  it('maps login timeout and non-API failures without implementation details', () => {
    expectPublic(presentLoginProblem(new IdentityRequestTimeoutError()), 'request_timeout')
    expectPublic(presentLoginProblem(new Error('private identity detail')), 'authentication_unavailable')
  })

  it.each([
    ['registration_conflict', 'registration_conflict'],
    ['registration_session_unavailable', 'registration_session_unavailable'],
    ['registration_recovery_failed', 'registration_recovery_failed'],
    ['too_many_requests', 'too_many_requests'],
    ['registration_unavailable', 'registration_unavailable'],
    ['network_unavailable', 'network_unavailable'],
    ['csrf_rejected', 'csrf_rejected'],
    ['invalid_request', 'invalid_request'],
    ['request_too_large', 'invalid_request'],
    ['unknown', 'registration_unavailable'],
  ])('maps registration error %s to stable public code %s', (serverCode, publicCode) => {
    expectPublic(presentRegistrationProblem(apiError(serverCode)), publicCode)
  })

  it('covers registration, Session and offline fallback states', () => {
    expectPublic(presentRegistrationProblem(new IdentityRequestTimeoutError()), 'request_timeout')
    expectPublic(presentRegistrationProblem(new Error('private registration detail')), 'registration_unavailable')
    expectPublic(presentSessionProblem(new IdentityRequestTimeoutError()), 'session_timeout')
    expectPublic(presentSessionProblem(apiError('network_unavailable')), 'network_unavailable')
    expectPublic(presentSessionProblem(new Error('private Session detail')), 'session_unavailable')
    expectPublic(offlineLoginProblem(), 'network_unavailable')
    expectPublic(offlineRegistrationProblem(), 'network_unavailable')
  })
})

function expectPublic(problem: { code: string, title: string, message: string }, code: string): void {
  expect(problem.code).toBe(code)
  expect(`${problem.title} ${problem.message}`).not.toContain('private')
}

function apiError(code: string): CrewScopeApiError {
  return new CrewScopeApiError(400, {
    code, message: 'private identity detail', correlationId: 'private-correlation',
    retryable: false, currentVersion: null, details: {},
  })
}
