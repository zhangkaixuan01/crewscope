import { CrewScopeApiError } from '../../api/client'

export interface OnboardingProblem {
  code: string
  title: string
  message: string
  tone: 'error' | 'warning'
}

export class OnboardingRequestTimeoutError extends Error {
  constructor() {
    super('Onboarding request timed out')
    this.name = 'OnboardingRequestTimeoutError'
  }
}

/** Keeps server implementation details out of the first-Team experience. */
export function presentOnboardingProblem(error: unknown): OnboardingProblem {
  if (error instanceof OnboardingRequestTimeoutError) {
    return problem('request_timeout', '创建结果尚未确认', '请安全重试，我们会继续检查同一次创建请求。', 'warning')
  }
  if (!(error instanceof CrewScopeApiError)) {
    return problem('onboarding_unavailable', '暂时无法建立团队', '请稍后重新检查或安全重试。', 'error')
  }
  switch (error.envelope.code) {
    case 'network_unavailable':
      return offlineOnboardingProblem()
    case 'onboarding_unavailable':
      return problem('onboarding_unavailable', '团队初始化暂时不可用', '请保留当前团队名称并安全重试。', 'warning')
    case 'onboarding_already_complete':
      return problem('onboarding_already_complete', '团队已经完成初始化', '正在重新读取当前团队入口。', 'warning')
    case 'idempotency_conflict':
      return problem('idempotency_conflict', '创建请求需要重新确认', '请检查团队名称后重新提交。', 'warning')
    case 'csrf_rejected':
      return problem('csrf_rejected', '安全校验已失效', '请重新检查当前会话后再创建团队。', 'warning')
    case 'invalid_request':
    case 'request_too_large':
      return problem('invalid_request', '团队名称无法提交', '请使用 1 至 200 个字符的团队名称。', 'error')
    default:
      return problem('onboarding_unavailable', '暂时无法建立团队', '请稍后重新检查或安全重试。', 'error')
  }
}

export function onboardingNotConvergedProblem(): OnboardingProblem {
  return problem('onboarding_not_converged', '团队入口仍在准备', '创建请求已被接受，请重新检查初始化结果。', 'warning')
}

export function onboardingProjectionProblem(): OnboardingProblem {
  return problem('onboarding_projection_unavailable', '工作入口仍在同步', '团队已创建，请重新检查工作空间和 Personal Agent。', 'warning')
}

export function offlineOnboardingProblem(): OnboardingProblem {
  return problem('network_unavailable', '当前处于离线状态', '恢复网络后即可继续建立团队。', 'warning')
}

function problem(
  code: string,
  title: string,
  message: string,
  tone: OnboardingProblem['tone'],
): OnboardingProblem {
  return { code, title, message, tone }
}
