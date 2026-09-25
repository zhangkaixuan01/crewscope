import { afterEach, describe, expect, it } from 'vitest'
import { activateF05Identity, clearF05UserData, F05_USER_PREFIX } from '../../app/f05Storage'
import { readTaskDelegationDraft, writeTaskDelegationDraft, type TaskDelegationDraft } from './delegationDraft'
import type { TaskScope } from './types'

const scope: TaskScope = { organizationId: 'org-1', teamId: 'team-1' }
const principal = { id: 'principal-1', accountId: 'account-1' }
const draft: TaskDelegationDraft = { objective: '梳理注册流程', acceptanceCriteria: '覆盖邮箱验证', executorAgentProfileId: 'agent-1', agentConfigurationRevision: 3 }

describe('task delegation draft', () => {
  afterEach(() => { clearF05UserData(); localStorage.clear(); sessionStorage.clear() })

  it('round-trips a draft under the scoped namespace', () => {
    activateF05Identity('account-1')
    writeTaskDelegationDraft(scope, 'project-1', 'work-item-1', draft, principal)
    expect(readTaskDelegationDraft(scope, 'project-1', 'work-item-1', principal)).toEqual(draft)
    expect(Object.keys(localStorage).every(key => key.startsWith(F05_USER_PREFIX) || key === 'cs.user.epoch.v1')).toBe(true)
  })

  it('enforces field length limits and revision constraints', () => {
    activateF05Identity('account-1')
    writeTaskDelegationDraft(scope, 'project-1', 'work-item-1', { ...draft, objective: '长'.repeat(2_001) }, principal)
    expect(readTaskDelegationDraft(scope, 'project-1', 'work-item-1', principal)).toBeNull()
    writeTaskDelegationDraft(scope, 'project-1', 'work-item-1', { ...draft, acceptanceCriteria: '长'.repeat(8_001) }, principal)
    expect(readTaskDelegationDraft(scope, 'project-1', 'work-item-1', principal)).toBeNull()
    writeTaskDelegationDraft(scope, 'project-1', 'work-item-1', { ...draft, agentConfigurationRevision: 0 }, principal)
    expect(readTaskDelegationDraft(scope, 'project-1', 'work-item-1', principal)).toBeNull()
    writeTaskDelegationDraft(scope, 'project-1', 'work-item-1', { ...draft, agentConfigurationRevision: null }, principal)
    expect(readTaskDelegationDraft(scope, 'project-1', 'work-item-1', principal)).toEqual({ ...draft, agentConfigurationRevision: null })
  })

  it('keeps drafts apart per work item and disappears after a sign-out', () => {
    activateF05Identity('account-1')
    writeTaskDelegationDraft(scope, 'project-1', 'work-item-1', draft, principal)
    writeTaskDelegationDraft(scope, 'project-1', 'work-item-2', { ...draft, objective: '另一件事' }, principal)
    expect(readTaskDelegationDraft(scope, 'project-1', 'work-item-1', principal)?.objective).toBe('梳理注册流程')
    expect(readTaskDelegationDraft(scope, 'project-1', 'work-item-2', principal)?.objective).toBe('另一件事')
    clearF05UserData()
    expect(readTaskDelegationDraft(scope, 'project-1', 'work-item-1', principal)).toBeNull()
  })

  it('ignores calls without a principal', () => {
    activateF05Identity('account-1')
    writeTaskDelegationDraft(scope, 'project-1', 'work-item-1', draft, null)
    expect(readTaskDelegationDraft(scope, 'project-1', 'work-item-1', null)).toBeNull()
    expect(Object.keys(localStorage).filter(key => key.startsWith(F05_USER_PREFIX))).toHaveLength(0)
  })
})
