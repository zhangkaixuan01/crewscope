import { afterEach, describe, expect, it } from 'vitest'
import { activateF05Identity, clearF05UserData, F05_USER_PREFIX } from '../../app/f05Storage'
import { clearCodingTargetDraft, readCodingTargetDraft, writeCodingTargetDraft, type CodingTargetDraft } from './draft'
import type { CodingScope } from './types'

const scope: CodingScope = { organizationId: 'org-1', teamId: 'team-1', projectId: 'project-1' }
const principal = { id: 'principal-1', accountId: 'account-1' }
const draft: CodingTargetDraft = { enabled: true, repositoryBindingId: 'binding-1', baselineRef: 'main', allowedPaths: 'src/**', buildProfileCoordinate: 'gradle:check' }

describe('coding target draft', () => {
  afterEach(() => { clearF05UserData(); localStorage.clear(); sessionStorage.clear() })

  it('round-trips a draft under the scoped namespace', () => {
    activateF05Identity('account-1')
    writeCodingTargetDraft(scope, 'work-item-1', draft, principal)
    expect(readCodingTargetDraft(scope, 'work-item-1', principal)).toEqual(draft)
    expect(Object.keys(localStorage).every(key => key.startsWith(F05_USER_PREFIX) || key === 'cs.user.epoch.v1')).toBe(true)
    clearCodingTargetDraft(scope, 'work-item-1', principal)
    expect(readCodingTargetDraft(scope, 'work-item-1', principal)).toBeNull()
  })

  it('keeps drafts apart per work item and per principal', () => {
    activateF05Identity('account-1')
    writeCodingTargetDraft(scope, 'work-item-1', draft, principal)
    writeCodingTargetDraft(scope, 'work-item-2', { ...draft, baselineRef: 'release' }, principal)
    expect(readCodingTargetDraft(scope, 'work-item-1', principal)?.baselineRef).toBe('main')
    expect(readCodingTargetDraft(scope, 'work-item-2', principal)?.baselineRef).toBe('release')
    expect(readCodingTargetDraft(scope, 'work-item-1', { id: 'principal-2', accountId: 'account-1' })).toBeNull()
  })

  it('rejects drafts whose shape no longer matches and stays silent while signed out', () => {
    activateF05Identity('account-1')
    writeCodingTargetDraft(scope, 'work-item-1', { ...draft, enabled: 'yes' } as unknown as CodingTargetDraft, principal)
    expect(readCodingTargetDraft(scope, 'work-item-1', principal)).toBeNull()
    writeCodingTargetDraft(scope, 'work-item-1', draft, principal)
    clearF05UserData()
    expect(readCodingTargetDraft(scope, 'work-item-1', principal)).toBeNull()
    expect(() => writeCodingTargetDraft(scope, 'work-item-1', draft, principal)).not.toThrow()
  })

  it('ignores calls without a principal', () => {
    activateF05Identity('account-1')
    writeCodingTargetDraft(scope, 'work-item-1', draft, null)
    expect(readCodingTargetDraft(scope, 'work-item-1', null)).toBeNull()
    expect(Object.keys(localStorage).filter(key => key.startsWith(F05_USER_PREFIX))).toHaveLength(0)
  })
})
