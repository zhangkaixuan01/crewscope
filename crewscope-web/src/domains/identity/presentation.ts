import { CrewScopeApiError } from '../../api/client'

export interface LoginProblem {
  code: string
  title: string
  message: string
  tone: 'error' | 'warning'
}

export type RegistrationProblem = LoginProblem

export class IdentityRequestTimeoutError extends Error {
  constructor() {
    super('Identity request timed out')
    this.name = 'IdentityRequestTimeoutError'
  }
}

/** Maps stable server codes to non-enumerating copy without rendering server exception text. */
export function presentLoginProblem(error: unknown): LoginProblem {
  if (error instanceof IdentityRequestTimeoutError) {
    return problem('request_timeout', '登录请求超时', '服务器没有及时响应，请确认网络后重试。', 'warning')
  }
  if (!(error instanceof CrewScopeApiError)) {
    return problem('authentication_unavailable', '登录服务暂时不可用', '请稍后重新尝试。', 'error')
  }
  switch (error.envelope.code) {
    case 'invalid_credentials':
      return problem('invalid_credentials', '无法登录', '登录信息无效，请检查后重试。', 'error')
    case 'too_many_requests':
      return problem('too_many_requests', '暂时无法继续尝试', '请求过于频繁，请稍后再试。', 'warning')
    case 'network_unavailable':
      return offlineLoginProblem()
    case 'csrf_rejected':
      return problem('csrf_rejected', '安全校验已失效', '请重新载入安全会话后再提交。', 'warning')
    case 'invalid_request':
    case 'request_too_large':
      return problem('invalid_request', '无法提交登录信息', '请检查输入长度后重试。', 'error')
    default:
      return problem('authentication_unavailable', '登录服务暂时不可用', '请稍后重新尝试。', 'error')
  }
}

export function presentSessionProblem(error: unknown): LoginProblem {
  if (error instanceof IdentityRequestTimeoutError) {
    return problem('session_timeout', '会话检查超时', '服务器没有及时响应，请确认网络后重新检查。', 'warning')
  }
  if (error instanceof CrewScopeApiError && error.envelope.code === 'network_unavailable') {
    return offlineLoginProblem()
  }
  return problem('session_unavailable', '无法准备安全登录', '会话服务暂时不可用，请稍后重新检查。', 'error')
}

/** Registration copy deliberately folds username, email and invitation failures into stable states. */
export function presentRegistrationProblem(error: unknown): RegistrationProblem {
  if (error instanceof IdentityRequestTimeoutError) {
    return problem('request_timeout', '注册请求超时', '提交结果尚未确认，请保留当前信息并重试。', 'warning')
  }
  if (!(error instanceof CrewScopeApiError)) {
    return problem('registration_unavailable', '暂时无法创建账号', '请稍后使用当前信息重新尝试。', 'error')
  }
  switch (error.envelope.code) {
    case 'registration_conflict':
      return problem('registration_conflict', '无法使用这些注册信息', '用户名或邮箱暂不可用，请修改后重试。', 'error')
    case 'registration_session_unavailable':
      return problem(
        'registration_session_unavailable',
        '账号已提交，登录会话尚未恢复',
        '请保留当前注册信息并再次提交，我们会安全恢复这次注册。',
        'warning',
      )
    case 'registration_recovery_failed':
      return problem('registration_recovery_failed', '无法恢复这次注册', '请确认注册信息未改变，或稍后重新尝试。', 'error')
    case 'too_many_requests':
      return problem('too_many_requests', '暂时无法继续注册', '请求过于频繁，请稍后再试。', 'warning')
    case 'registration_unavailable':
      return problem('registration_unavailable', '当前无法创建账号', '注册服务暂时不可用，请稍后重试。', 'error')
    case 'network_unavailable':
      return offlineRegistrationProblem()
    case 'csrf_rejected':
      return problem('csrf_rejected', '安全校验已失效', '请重新载入安全会话后再提交。', 'warning')
    case 'invalid_request':
    case 'request_too_large':
      return problem('invalid_request', '无法提交注册信息', '请检查输入是否符合要求后重试。', 'error')
    default:
      return problem('registration_unavailable', '暂时无法创建账号', '请稍后使用当前信息重新尝试。', 'error')
  }
}

export function offlineRegistrationProblem(): RegistrationProblem {
  return problem('network_unavailable', '当前处于离线状态', '恢复网络连接后即可继续创建账号。', 'warning')
}

export function offlineLoginProblem(): LoginProblem {
  return problem('network_unavailable', '当前处于离线状态', '恢复网络连接后即可继续登录。', 'warning')
}

function problem(code: string, title: string, message: string, tone: LoginProblem['tone']): LoginProblem {
  return { code, title, message, tone }
}
