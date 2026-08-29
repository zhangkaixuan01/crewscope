import type { LocationQuery, Router } from 'vue-router'

export const DEFAULT_LOGIN_DESTINATION = '/conversation'

const restorableRouteNames = new Set([
  'conversation',
  'today',
  'work',
  'activity',
  'inbox',
  'team-observer',
  'operations',
  'audit',
  'team-members',
  'repository-settings',
  'agent-settings',
  'model-settings',
  'lark-settings',
  'onboarding',
  'account',
  'invite',
])

/** Restores registered protected routes plus the proof-free invitation handoff route. */
export function safeLoginDestination(query: LocationQuery, router: Router): string {
  const candidate = query.returnTo
  if (typeof candidate !== 'string' || !isSafeCandidate(candidate)) return DEFAULT_LOGIN_DESTINATION
  const resolved = router.resolve(candidate)
  return typeof resolved.name === 'string' && restorableRouteNames.has(resolved.name)
    ? resolved.fullPath
    : DEFAULT_LOGIN_DESTINATION
}

function isSafeCandidate(candidate: string): boolean {
  return candidate.length > 0
    && candidate.length <= 2_048
    && candidate.startsWith('/')
    && !candidate.startsWith('//')
    && !candidate.includes('\\')
    && !candidate.includes('#')
    && !/[\u0000-\u001f\u007f]/.test(candidate)
}
