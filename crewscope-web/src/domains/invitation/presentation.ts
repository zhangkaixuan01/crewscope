import { CrewScopeApiError } from '../../api/client'

export interface InvitationProblem {
  code: string
  title: string
  message: string
  tone: 'error' | 'warning'
}

export class InvitationRequestTimeoutError extends Error {
  constructor() {
    super('Invitation request timed out')
    this.name = 'InvitationRequestTimeoutError'
  }
}

/** Keeps token validity, account matching and internal invitation facts non-enumerating. */
export function presentInvitationProblem(error: unknown): InvitationProblem {
  if (error instanceof InvitationRequestTimeoutError) {
    return problem('request_timeout', '邀请服务响应超时', '本次操作结果尚未确认，请使用相同操作重试。', 'warning')
  }
  if (!(error instanceof CrewScopeApiError)) return unavailable()
  switch (error.envelope.code) {
    case 'network_unavailable':
      return offlineInvitationProblem()
    case 'invitation_invalid':
      return problem('invitation_invalid', '无法使用这个邀请', '邀请可能已失效、已经使用，或与当前账号不匹配。', 'error')
    case 'invitation_not_pending':
      return problem('invitation_not_pending', '邀请状态已经变化', '已重新读取团队邀请，请确认最新状态。', 'warning')
    case 'idempotency_conflict':
      return problem('idempotency_conflict', '邀请操作已经变化', '请重新发起这次邀请操作。', 'warning')
    case 'access_denied':
      return problem('access_denied', '没有邀请管理权限', '当前账号不能管理这个 Team 的邀请。', 'error')
    case 'csrf_rejected':
      return problem('csrf_rejected', '安全校验已失效', '请重新检查当前会话后再提交。', 'warning')
    case 'invalid_request':
    case 'request_too_large':
      return problem('invalid_request', '邀请信息无法提交', '请检查邮箱、角色和有效期。', 'error')
    case 'invitation_unavailable':
      return unavailable()
    default:
      return unavailable()
  }
}

export function offlineInvitationProblem(): InvitationProblem {
  return problem('network_unavailable', '当前处于离线状态', '邀请操作需要联网，请恢复网络后重试。', 'warning')
}

function unavailable(): InvitationProblem {
  return problem('invitation_unavailable', '邀请服务暂时不可用', '请稍后重新读取邀请状态。', 'error')
}

function problem(
  code: string,
  title: string,
  message: string,
  tone: InvitationProblem['tone'],
): InvitationProblem {
  return { code, title, message, tone }
}
