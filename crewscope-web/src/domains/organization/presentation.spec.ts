import { CrewScopeApiError } from '../../api/client'
import { organizationProblem, organizationProblemText } from './presentation'

function apiError(status: number, code: string): CrewScopeApiError {
  return new CrewScopeApiError(status, {
    code, message: 'server message', correlationId: 'c-1', retryable: false, currentVersion: null, details: {},
  })
}

describe('organizationProblem', () => {
  it('explains the last-owner protection regardless of status', () => {
    const problem = organizationProblem(409, 'last_owner_protection', 'grant')

    expect(problem?.title).toBe('最后一个组织 Owner 保护')
    expect(problem?.message).toContain('至少要保留一名有效 Owner')
  })

  it('explains a confirmation-token mismatch by code', () => {
    expect(organizationProblem(422, 'token_mismatch', 'deletion-confirm')?.title).toBe('确认令牌不匹配')
  })

  it('distinguishes the optimistic-lock conflict from other job-state conflicts', () => {
    expect(organizationProblem(409, 'optimistic_lock_conflict', 'retention')?.title).toBe('内容已变化')
    expect(organizationProblem(409, 'invalid_job_state', 'deletion-process')?.title).toBe('作业状态不允许')
  })

  it('maps a 422 by the surface the caller invoked', () => {
    expect(organizationProblem(422, null, 'retention')?.title).toBe('审计保留期过短')
    expect(organizationProblem(422, null, 'grant')?.title).toBe('目标不在本组织')
    expect(organizationProblem(422, null, 'deletion-precondition')?.title).toBe('尚不满足删除条件')
    expect(organizationProblem(422, null, 'deletion-confirm')?.title).toBe('确认令牌不匹配')
    expect(organizationProblem(422, null, 'deletion-process')?.title).toBe('请先确认')
  })

  it('points a 403 at the stale-session explanation', () => {
    expect(organizationProblem(403, 'capability_missing', 'retention')?.title).toBe('组织角色不足')
  })

  it('returns null for unknown shapes so callers fall back to the envelope message', () => {
    expect(organizationProblem(500, 'internal_error', 'retention')).toBeNull()
    expect(organizationProblem(422, null)).toBeNull()
    expect(organizationProblem(null, null, 'retention')).toBeNull()
  })
})

describe('organizationProblemText', () => {
  it('maps a CrewScopeApiError through the same core', () => {
    expect(organizationProblemText(apiError(409, 'optimistic_lock_conflict'), 'retention')?.title).toBe('内容已变化')
  })

  it('ignores non-API errors', () => {
    expect(organizationProblemText(new Error('offline'))).toBeNull()
    expect(organizationProblemText(null)).toBeNull()
  })
})
