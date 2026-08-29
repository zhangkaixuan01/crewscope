import { CrewScopeApiError } from '../../api/client'
import {
  InvitationRequestTimeoutError,
  offlineInvitationProblem,
  presentInvitationProblem,
} from './presentation'

describe('invitation presentation', () => {
  it.each([
    ['network_unavailable', 'network_unavailable'],
    ['invitation_invalid', 'invitation_invalid'],
    ['invitation_not_pending', 'invitation_not_pending'],
    ['idempotency_conflict', 'idempotency_conflict'],
    ['access_denied', 'access_denied'],
    ['csrf_rejected', 'csrf_rejected'],
    ['invalid_request', 'invalid_request'],
    ['request_too_large', 'invalid_request'],
    ['invitation_unavailable', 'invitation_unavailable'],
    ['unknown', 'invitation_unavailable'],
  ])('maps %s to privacy-bounded state %s', (serverCode, publicCode) => {
    expectPublic(presentInvitationProblem(apiError(serverCode)), publicCode)
  })

  it('covers timeout, non-API and offline fallbacks', () => {
    expectPublic(presentInvitationProblem(new InvitationRequestTimeoutError()), 'request_timeout')
    expectPublic(presentInvitationProblem(new Error('private invitation detail')), 'invitation_unavailable')
    expectPublic(offlineInvitationProblem(), 'network_unavailable')
  })
})

function expectPublic(problem: { code: string, title: string, message: string }, code: string): void {
  expect(problem.code).toBe(code)
  expect(`${problem.title} ${problem.message}`).not.toContain('private')
}

function apiError(code: string): CrewScopeApiError {
  return new CrewScopeApiError(400, {
    code, message: 'private invitation detail', correlationId: 'private-correlation',
    retryable: false, currentVersion: null, details: {},
  })
}
