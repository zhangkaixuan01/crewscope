import { computed, watch, type ComputedRef } from 'vue'
import type { LocationQuery, RouteLocationNormalizedLoaded, Router } from 'vue-router'
import type { useScopeStore } from '../domains/scope/store'
import {
  captureFilters,
  listViews,
  resolveEntryView,
  viewQuery,
  workFilterKeys,
  type SavedViewFilters,
  type SavedViewOwner,
} from '../domains/views/savedViews'
import type { F05SavedView } from '../app/f05Storage'
import { readWorkItemSortKey, type WorkItemSortKey } from '../domains/workitem/list'

/** R41 saved views: the member's own filter definitions for this list, on this machine. */
export function useWorkSavedViews(options: {
  route: RouteLocationNormalizedLoaded
  router: Router
  principal: { accountId: string, id: string, organizationId: string } | null | undefined
  scopeStore: ReturnType<typeof useScopeStore>
  view: ComputedRef<'list' | 'board' | 'graph'>
  statusFilter: ComputedRef<string>
  typeFilter: ComputedRef<string>
  priorityFilter: ComputedRef<string>
  sortKey: ComputedRef<WorkItemSortKey>
}) {
  const { route, router, principal, scopeStore, view, statusFilter, typeFilter, priorityFilter, sortKey } = options
  const savedViewsOwner = computed<SavedViewOwner | null>(() => {
    const teamId = scopeStore.state.selectedTeamId
    return principal && principal.accountId && teamId
      ? { accountId: principal.accountId, principalId: principal.id, organizationId: principal.organizationId, teamId }
      : null
  })
  const committedWorkFilters = computed<SavedViewFilters>(() => captureFilters(route.query, workFilterKeys))
  /** Applies a saved definition (or the 「默认」 reset) by rewriting the list's filter keys. */
  function applySavedView(target: F05SavedView | null): void {
    void router.replace({ query: viewQuery(route.query, target, workFilterKeys) })
  }
  let entryViewHandled = false
  let entryViewTeam: string | null = null
  /** The canonical values the watch below writes; at these a query key says the same as no key. */
  const workEntryDefaults: Record<string, string> = { view: 'list', status: 'all', type: 'all', priority: 'all', sort: readWorkItemSortKey(undefined) }
  /**
   * The stored default applies once on entry, only while the URL carries no filter key of its own
   * (契约 §4.6). It runs before the canonicalization below so both rewrites compose into one query.
   * Canonical default values in the URL do not count as explicit — otherwise a reload would strand
   * the stored default behind the defaults the watch just wrote.
   */
  function applyEntryViewOnce(): boolean {
    const ownerTeam = savedViewsOwner.value?.teamId ?? null
    if (entryViewTeam !== ownerTeam) {
      // Switching Team reuses this component instance; each Team's namespace deserves its own
      // entry pass, so the once-guard resets with the owner's Team instead of shielding it.
      entryViewTeam = ownerTeam
      entryViewHandled = false
    }
    if (entryViewHandled) return false
    entryViewHandled = true
    const owner = savedViewsOwner.value
    const target = owner ? resolveEntryView(route.query, workFilterKeys, listViews(owner, 'work'), workEntryDefaults) : null
    if (!target) return false
    void router.replace({ query: viewQuery(route.query, target, workFilterKeys) })
    return true
  }
  // The URL owns the filters: every read key is written back canonically (or dropped when it
  // equals the default spelling), so a pasted link reopens the same list and the stored entry
  // view composes with whatever the link carried.
  watch(
    () => [scopeStore.state.phase, route.query.view, route.query.status, route.query.type, route.query.priority, route.query.sort, route.query.direction],
    () => {
      // Avoid changing the route while AppShell is still restoring a Team/Project deep link.
      if (scopeStore.state.phase !== 'ready') return
      // A stored default view rewrote the query this tick; the watch replays on the applied keys.
      if (applyEntryViewOnce()) return
      const canonical: LocationQuery = {
        ...route.query,
        view: view.value,
        status: statusFilter.value,
        type: typeFilter.value,
        priority: priorityFilter.value,
        sort: sortKey.value,
      }
      // Links minted before the server sorts carried a direction the server contract now owns; the
      // canonical URL drops it so the query says only what the list actually reads.
      delete canonical.direction
      if (
        route.query.view !== canonical.view
        || route.query.status !== canonical.status
        || route.query.type !== canonical.type
        || route.query.priority !== canonical.priority
        || route.query.sort !== canonical.sort
        // A link carrying only the retired direction still needs the cleanup rewrite above.
        || route.query.direction !== undefined
      ) void router.replace({ query: canonical })
    },
    { immediate: true },
  )
  return { savedViewsOwner, committedWorkFilters, applySavedView }
}
