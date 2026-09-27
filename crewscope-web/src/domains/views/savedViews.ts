import {
  F05_VIEW_TTL_MS,
  readF05,
  readF05Views,
  removeF05View,
  upsertF05View,
  writeF05,
  writeF05Views,
  type F05SavedView,
  type F05Scope,
  type F05StorageResult,
} from '../../app/f05Storage'

/**
 * R41 个人保存视图与固定项目：本机存储的筛选定义，永不是服务端状态。
 *
 * 一条视图只存「筛选定义」——页面自己声明的那组 URL 筛选键（work：view/status/type/
 * priority/sort/direction；today：deskProject/deskRole/deskAction/deskGroup），存取都经
 * F05 冻结合同（f05Storage 的 view/pin kind），按 账号+principal+组织+Team 隔离。应用
 * 优先级固定为：显式 URL > 已保存默认视图 > 页面默认——URL 里带着筛选键进入时，旧偏好
 * 永远不覆盖分享链接（契约 §4.6）。
 */

/**
 * The filter coordinates each page owns; a view can never carry anything else. Work carries no
 * direction: the server sort contract fixes every ordering's direction, and the sanitized read
 * below drops the key from views stored by older builds.
 */
export const workFilterKeys = ['view', 'status', 'type', 'priority', 'sort'] as const
export const todayFilterKeys = ['deskProject', 'deskRole', 'deskAction', 'deskGroup'] as const
export type SavedViewRouteName = 'work' | 'today'

export function filterKeysFor(routeName: SavedViewRouteName): readonly string[] {
  return routeName === 'work' ? workFilterKeys : todayFilterKeys
}

export interface SavedViewOwner {
  accountId: string
  principalId: string
  organizationId: string
  teamId: string
}

/** The F05 segment views live in: one Team, no project or object coordinate. */
export function savedViewsScope(owner: SavedViewOwner): F05Scope {
  return { ...owner, projectId: null, objectId: null, objectVersion: null }
}

// --- serialization -----------------------------------------------------------

export type SavedViewFilters = Record<string, string>

/**
 * Captures the committed filter keys from a route query. Only plain string values ride along;
 * anything absent, defaulted-away or malformed drops out, so the stored definition stays exactly
 * what the member saw.
 */
export function captureFilters(query: Record<string, unknown>, keys: readonly string[]): SavedViewFilters {
  const filters: SavedViewFilters = {}
  for (const key of keys) {
    const value = query[key]
    if (typeof value === 'string' && value !== '') filters[key] = value
  }
  return filters
}

/** Coerces a stored record back to plain string filters, dropping anything a view must not carry. */
function sanitizeFilters(view: F05SavedView, keys: readonly string[]): SavedViewFilters {
  const filters: SavedViewFilters = {}
  for (const key of keys) {
    const value = view.filters?.[key]
    if (typeof value === 'string' && value !== '') filters[key] = value
    else if (typeof value === 'number' && Number.isFinite(value)) filters[key] = String(value)
    else if (typeof value === 'boolean') filters[key] = value ? 'true' : 'false'
  }
  return filters
}

export function viewFilters(view: F05SavedView, keys: readonly string[] = filterKeysFor(view.routeName as SavedViewRouteName)): SavedViewFilters {
  return sanitizeFilters(view, keys)
}

/** Builds the frozen record from the committed query; the sort field mirrors the sort keys. */
export function buildSavedView(input: { id: string, name: string, routeName: SavedViewRouteName, filters: SavedViewFilters }): F05SavedView {
  const sort = input.filters.sort
    ? { field: input.filters.sort, direction: (input.filters.direction === 'asc' ? 'asc' : 'desc') as 'asc' | 'desc' }
    : null
  return {
    id: input.id,
    name: input.name,
    routeName: input.routeName,
    filters: { ...input.filters },
    sort,
    pinned: false,
    updatedAt: Date.now(),
  }
}

// --- entry priority ----------------------------------------------------------

/**
 * The view to apply on page entry: none while the URL carries any filter key of its own — an
 * explicit link always beats a stored preference — and otherwise the first pinned view (the
 * switcher offers 「默认」 for going back to the page defaults).
 *
 * `defaults` serves the pages that canonicalize their whole filter set into the URL (work): a
 * key sitting at its page default says the same as no key at all, so a reload must not strand
 * the stored default view behind freshly written default values. Pages that drop defaulted
 * keys instead of writing them (today) pass nothing — a hand-minted default-valued link stays
 * an explicit link there.
 */
export function resolveEntryView(
  query: Record<string, unknown>,
  keys: readonly string[],
  views: F05SavedView[],
  defaults?: Record<string, string>,
): F05SavedView | null {
  for (const key of keys) {
    const value = query[key]
    if (typeof value !== 'string' || value === '') continue
    if (defaults?.[key] === value) continue
    return null
  }
  return views.find(view => view.pinned) ?? null
}

/**
 * Merges a view (or the 「默认」 reset) into the current query. Filter keys are replaced
 * wholesale; every other coordinate (team, project) rides along untouched.
 */
export function viewQuery(query: Record<string, unknown>, view: F05SavedView | null, keys: readonly string[]): Record<string, string> {
  const next: Record<string, string> = {}
  for (const [key, value] of Object.entries(query)) {
    if (keys.includes(key)) continue
    if (typeof value === 'string' && value !== '') next[key] = value
  }
  if (view) Object.assign(next, viewFilters(view, keys))
  return next
}

// --- view CRUD (thin, typed wrappers over the frozen contract) ----------------

/** Views of one page, pinned first; storage already keeps the newest write on top. */
export function listViews(owner: SavedViewOwner, routeName: SavedViewRouteName): F05SavedView[] {
  const keys = filterKeysFor(routeName)
  return readF05Views(savedViewsScope(owner))
    .filter(view => view.routeName === routeName && typeof view.name === 'string' && view.name.trim() !== '')
    .map(view => ({ ...view, filters: sanitizeFilters(view, keys) }))
    .sort((a, b) => Number(b.pinned) - Number(a.pinned) || b.updatedAt - a.updatedAt)
}

export function saveView(owner: SavedViewOwner, view: F05SavedView): F05StorageResult {
  return upsertF05View(savedViewsScope(owner), view)
}

export function deleteView(owner: SavedViewOwner, viewId: string): F05StorageResult {
  return removeF05View(savedViewsScope(owner), viewId)
}

/**
 * Re-orders by moving one view to the front as the new default. Exactly one stored default per
 * page: pinning demotes every other pinned view of the same page, so 「默认视图」 never drifts
 * to whichever view happened to be updated most recently.
 */
export function pinView(owner: SavedViewOwner, viewId: string, pinned: boolean): F05StorageResult {
  const scope = savedViewsScope(owner)
  const stored = readF05Views(scope)
  const target = stored.find(view => view.id === viewId)
  const views = stored.map(view => {
    if (view.id === viewId) return { ...view, pinned, updatedAt: Date.now() }
    return pinned && target && view.pinned && view.routeName === target.routeName
      ? { ...view, pinned: false }
      : view
  })
  return writeF05Views(scope, views)
}

// --- pinned projects ----------------------------------------------------------

export interface PinnedProject { projectId: string, pinnedAt: number }

const PIN_MAX = 20

/**
 * Fixed projects are a quick-entry list, not a copy of the project: names are always resolved
 * again through the currently authorized project list at render time, so a revoked project shows
 * as 「项目已不可见」 instead of a stale title (R41 打开时重新授权).
 */
export function readPinnedProjects(owner: SavedViewOwner): PinnedProject[] {
  const value = readF05<PinnedProject[]>('pin', savedViewsScope(owner))?.value
  if (!Array.isArray(value)) return []
  const seen = new Set<string>()
  const pins: PinnedProject[] = []
  for (const item of value) {
    if (!item || typeof item !== 'object' || typeof (item as PinnedProject).projectId !== 'string' || (item as PinnedProject).projectId === '') continue
    if (seen.has((item as PinnedProject).projectId)) continue
    seen.add((item as PinnedProject).projectId)
    pins.push({ projectId: (item as PinnedProject).projectId, pinnedAt: Number.isFinite((item as PinnedProject).pinnedAt) ? (item as PinnedProject).pinnedAt : Date.now() })
  }
  return pins.slice(0, PIN_MAX)
}

export function writePinnedProjects(owner: SavedViewOwner, pins: PinnedProject[]): F05StorageResult {
  return writeF05('pin', savedViewsScope(owner), pins.slice(0, PIN_MAX), { ttlMs: F05_VIEW_TTL_MS })
}

export function togglePinnedProject(owner: SavedViewOwner, projectId: string, pinned: boolean): { result: F05StorageResult, pins: PinnedProject[] } {
  const pins = readPinnedProjects(owner)
  const next = pinned
    ? [{ projectId, pinnedAt: Date.now() }, ...pins.filter(pin => pin.projectId !== projectId)]
    : pins.filter(pin => pin.projectId !== projectId)
  return { result: writePinnedProjects(owner, next), pins: next }
}
