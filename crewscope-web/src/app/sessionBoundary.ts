import type { AuthStore, AuthTransitionReason } from '../domains/identity/store'

/** Domain caches and pending intents belong to one account/principal/security epoch. */
export function subscribeSessionBoundary(authStore: AuthStore, reset: (reason: AuthTransitionReason) => void): () => void {
  let previous: string | null = null
  return authStore.subscribe((phase, reason) => {
    if (phase !== 'anonymous' && phase !== 'authenticated') return
    const session = authStore.state.session
    const identity = phase === 'authenticated'
      ? JSON.stringify([session?.account?.accountId, session?.principal?.principalId,
        session?.principal?.organizationId, session?.account?.securityVersion])
      : null
    const changed = identity !== previous
    previous = identity
    if (changed || (phase === 'anonymous' && reason !== 'restored')) reset(reason)
  })
}
