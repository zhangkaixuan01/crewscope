import type { LocationQueryRaw } from 'vue-router'

export interface ConfigurationSearchTarget {
  path: string
  query: LocationQueryRaw
}

/**
 * Turns a server-authored configuration search route into a Settings link.
 *
 * The search projection returns a path plus the field's own coordinates — never a Team, because the
 * projection is already Team-scoped — while every Settings page restores its scope from `team=` in
 * the URL. Without merging the active scope the link lands on a page that cannot resolve which Agent
 * or repository it is being asked to open.
 */
export function configurationSearchTarget(route: string, teamId: string): ConfigurationSearchTarget | null {
  if (!route.startsWith('/')) return null
  const separator = route.indexOf('?')
  const path = separator === -1 ? route : route.slice(0, separator)
  if (!path || path === '/') return null
  const query: LocationQueryRaw = { team: teamId }
  if (separator === -1) return { path, query }
  for (const [key, value] of new URLSearchParams(route.slice(separator + 1))) {
    // The active scope wins: a stale or relative server route must not re-point the navigation.
    if (key === 'team' || !value) continue
    query[key] = value
  }
  return { path, query }
}
