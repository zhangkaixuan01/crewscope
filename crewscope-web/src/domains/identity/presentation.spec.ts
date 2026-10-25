// @vitest-environment node
import { CrewScopeApiError } from '../../api/client'
import {
  IdentityRequestTimeoutError,
  offlineLoginProblem,
  offlineRegistrationProblem,
  presentLoginProblem,
  presentRegistrationProblem,
  presentSessionProblem,
  registrationInvalidValueField,
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
    ['invalid_value', 'invalid_value'],
    ['unknown', 'registration_unavailable'],
  ])('maps registration error %s to stable public code %s', (serverCode, publicCode) => {
    expectPublic(presentRegistrationProblem(apiError(serverCode)), publicCode)
  })

  it('maps invalid_value field coordinates to inline copy without rendering server text', () => {
    expect(registrationInvalidValueField(invalidValueError('userAccount.username', 'may contain letters private')))
      .toEqual({ field: 'username', message: '用户名只能包含字母、数字和单个 . - _ 分隔符，并以字母或数字开头结尾' })
    expect(registrationInvalidValueField(invalidValueError('userAccount.email', 'private')))
      .toEqual({ field: 'email', message: '邮箱需要包含一个可用的本地部分和域名（如 name@example.com）' })
    expect(registrationInvalidValueField(invalidValueError('userAccount.displayName', 'private')))
      .toEqual({ field: 'displayName', message: '展示名包含不支持的字符，请调整后重试' })
    expect(registrationInvalidValueField(apiError('invalid_value'))).toBeNull()
    expect(registrationInvalidValueField(apiError('registration_conflict'))).toBeNull()
  })

  it('distinguishes password invalid_value reason classes', () => {
    expect(registrationInvalidValueField(invalidValueError('password', 'must not be a blocked common password')))
      .toEqual({ field: 'password', message: '密码过于常见，请更换一段不易猜到的长密码' })
    expect(registrationInvalidValueField(invalidValueError('password', 'must contain at least 12 Unicode code points')))
      .toEqual({ field: 'password', message: '密码需要包含 12 至 128 个字符，且不超过 512 字节' })
  })

  it('covers registration, Session and offline fallback states', () => {
    expectPublic(presentRegistrationProblem(new IdentityRequestTimeoutError()), 'request_timeout')
    expectPublic(presentRegistrationProblem(new Error('private registration detail')), 'registration_unknown')
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

function invalidValueError(field: string, reason: string): CrewScopeApiError {
  return new CrewScopeApiError(422, {
    code: 'invalid_value', message: 'private identity detail', correlationId: 'private-correlation',
    retryable: false, currentVersion: null, details: { field, reason },
  })
}