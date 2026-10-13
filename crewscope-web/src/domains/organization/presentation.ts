import { CrewScopeApiError } from '../../api/client'

/**
 * Structured Chinese problem copy for the organization surfaces (TeamMembersPage
 * lifecycleProblemText precedent). The 422 body only carries a server message, so
 * callers pass the surface they invoked; returns null for unknown shapes and the
 * caller falls back to the envelope message.
 */
export interface OrganizationProblem {
  title: string
  message: string
}

/** The raising surface of a 422 — decides the actionable copy since the code alone cannot. */
export type OrganizationProblemSurface =
  | 'retention'
  | 'grant'
  | 'deletion-precondition'
  | 'deletion-confirm'
  | 'deletion-process'

const unprocessableProblems: Record<OrganizationProblemSurface, OrganizationProblem> = {
  retention: {
    title: '审计保留期过短',
    message: '审计事件至少保留 30 天。请输入不小于 30 的天数，或选择永久保留。',
  },
  grant: {
    title: '目标不在本组织',
    message: '该成员不属于本组织，无法授予组织角色。请核对 Principal ID。',
  },
  'deletion-precondition': {
    title: '尚不满足删除条件',
    message: '目标账号仍有在途任务执行或未转移的责任。请先完成执行收尾与责任移交（团队成员页），再发起删除。',
  },
  'deletion-confirm': {
    title: '确认令牌不匹配',
    message: '请输入目标账号的用户名作为确认令牌，必须与目标账号用户名完全一致。',
  },
  'deletion-process': {
    title: '请先确认',
    message: '删除作业需先经目标账号用户名确认，才能开始执行。',
  },
}

export function organizationProblemText(
  error: unknown,
  surface?: OrganizationProblemSurface,
): OrganizationProblem | null {
  if (!(error instanceof CrewScopeApiError)) return null
  return organizationProblem(error.status, error.envelope.code, surface)
}

/** Status/code core so Store command state (no exception object) maps to the same copy. */
export function organizationProblem(
  status: number | null,
  code: string | null,
  surface?: OrganizationProblemSurface,
): OrganizationProblem | null {
  if (code === 'last_owner_protection') {
    return {
      title: '最后一个组织 Owner 保护',
      message: '组织至少要保留一名有效 Owner。请先把 Owner 角色授予其他成员，再执行撤销。',
    }
  }
  if (code === 'token_mismatch') return unprocessableProblems['deletion-confirm']
  if (status === 409 && code === 'optimistic_lock_conflict') {
    return {
      title: '内容已变化',
      message: '其他操作先一步更新了这份事实。页面会加载最新版本，请基于最新状态重试。',
    }
  }
  if (status === 409) {
    return {
      title: '作业状态不允许',
      message: '该操作与作业当前状态冲突：已完成或已取消的作业不能再次执行或取消，未完成的导出不能下载。',
    }
  }
  if (status === 422 && surface) return unprocessableProblems[surface]
  if (status === 403) {
    return {
      title: '组织角色不足',
      message: '当前组织角色无权执行此操作。如果你最近被授予或撤销了角色，刷新页面更新会话后重试。',
    }
  }
  return null
}
