import { CrewScopeApiError } from '../../api/client'
import {
  AccountRequestTimeoutError,
  offlineAccountProblem,
  presentAccountProblem,
} from './presentation'

describe('account presentation', () => {
  it.each([
    ['network_unavailable', 'network_unavailable', false],
    ['invalid_credentials', 'invalid_credentials', false],
    ['account_identifier_conflict', 'account_identifier_conflict', false],
    ['optimistic_lock_conflict', 'optimistic_lock_conflict', true],
    ['security_version_conflict', 'security_version_conflict', true],
    ['account_credential_conflict', 'account_credential_conflict', true],
    ['csrf_rejected', 'csrf_rejected', false],
    ['invalid_request', 'invalid_request', false],
    ['request_too_large', 'invalid_request', false],
    ['account_service_unavailable', 'account_service_unavailable', false],
    ['unknown', 'account_service_unavailable', false],
  ])('maps %s to %s without exposing account facts', (serverCode, publicCode, conflict) => {
    const problem = presentAccountProblem(apiError(serverCode))
    expect(problem).toMatchObject({ code: publicCode, conflict })
    expect(`${problem.title} ${problem.message}`).not.toContain('private')
  })

  it('covers timeout, non-API and offline fallbacks', () => {
    expect(presentAccountProblem(new AccountRequestTimeoutError()).code).toBe('request_timeout')
    expect(presentAccountProblem(new Error('private account detail')).code).toBe('account_service_unavailable')
    expect(offlineAccountProblem().code).toBe('network_unavailable')
  })
})

function apiError(code: string): CrewScopeApiError {
  return new CrewScopeApiError(400, {
    code, message: 'private account detail', correlationId: 'private-correlation',
    retryable: false, currentVersion: null, details: {},
  })
}
