import { afterEach, describe, expect, it } from 'vitest'
import { ref } from 'vue'
import { activateF05Identity, clearF05UserData, F05_USER_PREFIX } from '../app/f05Storage'
import { useScopedUserState } from './useScopedUserState'

const identity = () => ({ accountId: 'account-1', principalId: 'principal-1', organizationId: 'org-1', teamId: ref<string | null>('team-1'), projectId: null, objectId: ref<string | null>('execution-1') })

describe('useScopedUserState', () => {
  afterEach(() => { clearF05UserData(); localStorage.clear() })

  it('round-trips reading state under the scoped namespace', () => {
    activateF05Identity('account-1')
    const dimensions = identity()
    const scope = () => dimensions.teamId.value && dimensions.objectId.value
      ? { ...dimensions, teamId: dimensions.teamId.value, objectId: dimensions.objectId.value }
      : null
    const state = useScopedUserState<string[]>({ scope, name: 'viewed-files', fallback: [], validate: (value): value is string[] => Array.isArray(value) })
    state.value.value = ['src/app.ts']
    expect(state.value.value).toEqual(['src/app.ts'])
    expect(Object.keys(localStorage).every(key => key.startsWith(F05_USER_PREFIX) || key === 'cs.user.epoch.v1')).toBe(true)

    const reRead = useScopedUserState<string[]>({ scope, name: 'viewed-files', fallback: [], validate: (value): value is string[] => Array.isArray(value) })
    expect(reRead.value.value).toEqual(['src/app.ts'])
  })

  it('reloads on scope switches without cross-writing and resets when the epoch seals', () => {
    activateF05Identity('account-1')
    const teamId = ref<string | null>('team-a')
    const scope = () => (teamId.value ? { accountId: 'account-1', principalId: 'principal-1', organizationId: 'org-1', teamId: teamId.value, projectId: null, objectId: 'execution-1' } : null)
    const state = useScopedUserState<Record<string, number>>({ scope, name: 'conversation-scroll', fallback: {}, validate: (value): value is Record<string, number> => Boolean(value && typeof value === 'object') })
    state.value.value = { 'conversation-1': 120 }

    // Switching the Team dimension reads the other Team's marks and never copies values across.
    teamId.value = 'team-b'
    expect(state.value.value).toEqual({})
    // Reading the same state back under the first Team still finds its marks.
    teamId.value = 'team-a'
    expect(state.value.value).toEqual({ 'conversation-1': 120 })

    clearF05UserData()
    expect(state.value.value).toEqual({})
  })

  it('falls back while signed out and rejects values that fail validation', () => {
    clearF05UserData()
    const scope = () => ({ accountId: 'account-1', principalId: 'principal-1', organizationId: 'org-1', teamId: 'team-1', projectId: null, objectId: 'execution-1' })
    const state = useScopedUserState<string[]>({ scope, name: 'viewed-files', fallback: [], validate: (value): value is string[] => Array.isArray(value) && value.every(item => typeof item === 'string') })
    expect(state.value.value).toEqual([])
    // Writes while sealed drop silently instead of throwing or persisting.
    state.value.value = ['src/app.ts']
    expect(Object.keys(localStorage).filter(key => key.startsWith(F05_USER_PREFIX))).toHaveLength(0)
  })
})
