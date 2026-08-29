import { permissions, type AuthenticatedPrincipal } from '../app/auth'
import type { AuthPhase, AuthStore, AuthTransitionReason } from '../domains/identity/store'

/** Explicit authenticated identity for component and Store tests; never imported by production entrypoints. */
export const bootstrapPrincipal: AuthenticatedPrincipal = {
  id: '00000000-0000-0000-0000-000000000101',
  displayName: '测试成员',
  role: 'Team Owner',
  organizationId: '00000000-0000-0000-0000-000000000001',
  organization: 'Test Organization',
  permissions: new Set(Object.values(permissions)),
}

export function fixtureAuthStore(
  principal: AuthenticatedPrincipal = bootstrapPrincipal,
  phase: AuthPhase = 'authenticated',
): AuthStore {
  const listeners = new Set<(phase: AuthPhase, reason: AuthTransitionReason) => void>()
  return {
    state: { phase, session: null, activeTeamId: null, errorCode: null, errorMessage: null },
    principal,
    start() {},
    stop() {},
    async ensureRestored() {},
    async refresh() { return phase === 'authenticated' },
    async retry() {},
    selectTeam() {},
    authenticationRequired() {
      for (const listener of listeners) listener('anonymous', 'authentication-required')
    },
    signOutLocally() {
      for (const listener of listeners) listener('anonymous', 'explicit-sign-out')
    },
    subscribe(listener) {
      listeners.add(listener)
      return () => listeners.delete(listener)
    },
  }
}
