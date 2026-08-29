import { CrewScopeApiError } from '../../api/client'

export interface AccountProblem {
  code: string
  title: string
  message: string
  tone: 'error' | 'warning'
  conflict: boolean
}

export class AccountRequestTimeoutError extends Error {
  constructor() {
    super('Account request timed out')
    this.name = 'AccountRequestTimeoutError'
  }
}

/** Maps account failures to stable, non-enumerating and credential-free UI text. */
export function presentAccountProblem(error: unknown): AccountProblem {
  if (error instanceof AccountRequestTimeoutError) {
    return problem('request_timeout', '账号服务响应超时', '本次操作结果尚未确认，请重新读取账号状态。', 'warning')
  }
  if (!(error instanceof CrewScopeApiError)) return unavailable()
  switch (error.envelope.code) {
    case 'network_unavailable':
      return offlineAccountProblem()
    case 'invalid_credentials':
      return problem('invalid_credentials', '当前密码不正确', '请重新输入当前密码后再提交。', 'error')
    case 'account_identifier_conflict':
      return problem('account_identifier_conflict', '账号标识无法使用', '用户名或邮箱与现有账号冲突，请更换后重试。', 'error')
    case 'optimistic_lock_conflict':
      return problem('optimistic_lock_conflict', '账号资料已在其他位置更新', '已重新读取最新资料，请确认后再次提交。', 'warning', true)
    case 'security_version_conflict':
    case 'account_credential_conflict':
      return problem(error.envelope.code, '安全状态已经变化', '当前操作未提交，请重新登录后再试。', 'warning', true)
    case 'csrf_rejected':
      return problem('csrf_rejected', '安全校验已失效', '请重新检查当前会话后再提交。', 'warning')
    case 'invalid_request':
    case 'request_too_large':
      return problem('invalid_request', '账号信息无法提交', '请检查字段长度和密码要求。', 'error')
    case 'account_service_unavailable':
      return unavailable()
    default:
      return unavailable()
  }
}

export function offlineAccountProblem(): AccountProblem {
  return problem('network_unavailable', '当前处于离线状态', '账号安全操作需要联网，请恢复网络后重试。', 'warning')
}

function unavailable(): AccountProblem {
  return problem('account_service_unavailable', '账号服务暂时不可用', '请稍后重新读取账号状态。', 'error')
}

function problem(
  code: string,
  title: string,
  message: string,
  tone: AccountProblem['tone'],
  conflict = false,
): AccountProblem {
  return { code, title, message, tone, conflict }
}
