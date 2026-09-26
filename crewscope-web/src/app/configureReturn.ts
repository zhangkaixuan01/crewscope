import type { LocationQuery } from 'vue-router'

/**
 * F02 configure-return contract (§4.2): a configuration page may only receive a return
 * coordinate that names a route registered here — never an arbitrary returnTo URL. The
 * coordinate carries object ids alone; credentials, invitation tokens and draft text never
 * enter the query string, and every value must pass the UUID shape check below.
 */
export interface ConfigureReturnRoute {
  /** Button label shown on configuration pages. */
  label: string
  /** Coordinate keys this origin may carry; anything else in the URL is dropped. */
  coordinateKeys: readonly string[]
  /** Extra one-shot keys the origin is allowed to restore on return (value whitelist). */
  allowReturnQuery?: Readonly<Record<string, readonly string[]>>
}

export const CONFIGURE_RETURN_ROUTES: Readonly<Record<string, ConfigureReturnRoute>> = {
  work: {
    label: '返回工作项',
    coordinateKeys: ['team', 'project', 'workItem'],
    allowReturnQuery: { delegate: ['coding'] },
  },
  today: { label: '返回今日工作', coordinateKeys: ['team', 'project'] },
  conversation: { label: '返回对话', coordinateKeys: ['team', 'conversation'] },
  // `project` lets OPEN_EXECUTION_DEFAULTS land the repository settings on the right project.
  setup: { label: '返回配置中心', coordinateKeys: ['team', 'project'] },
  onboarding: { label: '返回初始化', coordinateKeys: [] },
}

const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i
const ROUTE_NAME_PATTERN = /^[a-z][a-z0-9-]{0,31}$/

/** The validated return target a configuration page may offer back to the member. */
export interface ConfigureReturn {
  /** A registered in-app route name — not an arbitrary path or URL. */
  routeName: string
  label: string
  /** Coordinates restored when returning; every value passed the UUID check. */
  query: Record<string, string>
}

/**
 * Builds the query for navigating *into* a configuration page: `from` plus the coordinates
 * the registered origin is allowed to carry. Unknown origins and non-UUID values are dropped
 * silently, so a malformed caller degrades to a plain navigation without a return affordance.
 * Callers may append one-shot keys (e.g. `delegate=coding`) that the origin whitelisted in
 * `allowReturnQuery`.
 */
export function buildConfigureReturn(
  from: string,
  coordinates: Record<string, string | null | undefined>,
): Record<string, string> {
  const query: Record<string, string> = {}
  if (!ROUTE_NAME_PATTERN.test(from)) return query
  const route = CONFIGURE_RETURN_ROUTES[from]
  if (!route) return query
  query.from = from
  for (const key of route.coordinateKeys) {
    const value = coordinates[key]
    if (typeof value === 'string' && UUID_PATTERN.test(value)) query[key] = value
  }
  return query
}

/**
 * Reads a return coordinate off a configuration page's query. Returns null when `from` is
 * absent, unregistered, malformed or overlong — callers then simply omit the return button.
 */
export function applyConfigureReturn(query: LocationQuery): ConfigureReturn | null {
  const from = query.from
  if (typeof from !== 'string' || !ROUTE_NAME_PATTERN.test(from)) return null
  const route = CONFIGURE_RETURN_ROUTES[from]
  if (!route) return null
  const restored: Record<string, string> = {}
  for (const key of route.coordinateKeys) {
    const value = query[key]
    if (typeof value === 'string' && UUID_PATTERN.test(value)) restored[key] = value
  }
  for (const [key, values] of Object.entries(route.allowReturnQuery ?? {})) {
    const value = query[key]
    if (typeof value === 'string' && values.includes(value)) restored[key] = value
  }
  return { routeName: from, label: route.label, query: restored }
}
