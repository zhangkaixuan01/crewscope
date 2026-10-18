import { secureId } from '../api/secureId'
import { ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { normalizeQuery } from '../utils/queryParams'
import type { usePageRequestScope } from './usePageRequestScope'

/** Command envelope shape both library stores expose (phase/retryable are all the page needs). */
export interface LibraryCommandPhase {
  phase: 'idle' | 'pending' | 'success' | 'error' | 'conflict'
  retryable: boolean
}

/** Pre-selection placeholder for the version-history slot (both stores share this idle shape). */
export interface IdleVersionPage {
  phase: 'idle'
  value: null
  errorMessage: null
  errorStatus: null
  nextAfter: null
  loadingMore: false
}

export function idleVersionPage(): IdleVersionPage {
  return { phase: 'idle', value: null, errorMessage: null, errorStatus: null, nextAfter: null, loadingMore: false }
}

/**
 * Shared page lifecycle for the skill/knowledge library twins (M12b-F04): the idempotent
 * command runner (attempt-key reuse, conflict recovery, fact refresh), the selection watch
 * (detail/effective/versions loads per tab), the query-normalization watch and the
 * query-coordinated navigation helpers.
 *
 * Domain divergence enters through options: which query key carries the selection, which
 * filters normalize into the URL, whether the effective version loads on the draft tab
 * (skills feed the editor diff baseline; knowledge only reads it on the versions tab) and
 * the store load functions. The skillKey deep-link resolver stays in the page — it carries
 * domain-specific paging state.
 *
 * Watch registration order matters and is preserved from the original pages: the catalog
 * watch registers first (activateScope + catalog load run before any selection load), then
 * the selection watch, then query normalization.
 */
export function useEntryLibraryPage<Id extends string>(options: {
  /** Page-level request ownership; pass the page's usePageRequestScope() instance. */
  pageRequests: ReturnType<typeof usePageRequestScope>
  /** Catalog watch sources: [scope phase, organization id, team id, ...filter values]. */
  catalogSources: () => readonly unknown[]
  /** True while the team scope object exists (the catalog watch's activation gate). */
  scopePresent: () => boolean
  /** Activates the store against the resolved scope before each catalog load. */
  activateScope: () => void
  /** Domain reset side effects when the catalog reloads (skills clear deep-link paging). */
  onCatalogReset?: () => void
  /** Query key that carries the selected id ('skill' / 'entry'). */
  queryKeys: { selected: string }
  /** Filters normalized into the URL (status, category); tab is always normalized. */
  filters: Array<{ key: string, value: () => string, fallback: string }>
  /** Extra query keys that retrigger normalization without being normalized themselves. */
  extraQueryKeys?: string[]
  /** True once the team scope is resolved; loads stay gated until then. */
  scopeReady: () => boolean
  /** Team coordinate re-triggers the selection loads even when readiness itself does not flip. */
  teamId: () => string | null | undefined
  /** Network hint; commands and refreshes stay local-only while offline. */
  online: () => boolean
  selectedId: () => Id | null
  selectedRevision: () => number | null
  tab: () => 'draft' | 'versions'
  clearCommand: () => void
  commandPhase: () => LibraryCommandPhase
  loadCatalog: (force: boolean) => Promise<unknown> | unknown
  loadDetail: (id: Id, force?: boolean) => Promise<unknown> | unknown
  loadVersions: (id: Id, more?: boolean, force?: boolean) => Promise<unknown> | unknown
  loadVersion?: (id: Id, revision: number) => Promise<unknown> | unknown
  /** Skills load the effective version on both tabs (editor baseline); knowledge on versions only. */
  loadEffective?: (id: Id, force?: boolean) => Promise<unknown> | unknown
  effectiveOnDraftTab?: boolean
  /** Tab-change side loads (optimistic pre-loads before the selection watch reruns). */
  onEnterVersionsTab?: (id: Id) => void
}) {
  const route = useRoute()
  const router = useRouter()

  /** Entry-scoped commands mint their key here; create/distill dialogs own their own keys. */
  const commandAttempt = ref<{ op: string, resourceId: Id, key: string } | null>(null)

  function attemptKeyFor(op: string, resourceId: Id): string {
    const current = commandAttempt.value
    if (current && current.op === op && current.resourceId === resourceId) return current.key
    const next = { op, resourceId, key: secureId() }
    commandAttempt.value = next
    return next.key
  }

  /**
   * Runs a selection-scoped command: reuses the attempt key for an unchanged retry, refreshes
   * the fact set on success, drops the key and reloads the catalog+detail on a conflict, and
   * drops it again when the failure is not retryable.
   */
  async function runCommand(op: string, resourceId: Id, run: (key: string) => Promise<boolean>): Promise<boolean> {
    const pageOwner = options.pageRequests.captureSelection()
    if (!options.online()) return false
    const success = await run(attemptKeyFor(op, resourceId))
    if (!pageOwner.isCurrent()) return success
    if (success) {
      commandAttempt.value = null
      await refreshFacts(resourceId)
      return true
    }
    if (options.commandPhase().phase === 'conflict') {
      // Drop the attempt key (the next submit is a new logical request) and reload facts
      // without touching the editor.
      commandAttempt.value = null
      await Promise.all([
        options.loadCatalog(true),
        options.loadDetail(resourceId, true),
      ])
    } else if (!options.commandPhase().retryable) {
      commandAttempt.value = null
    }
    return false
  }

  /** Reloads every fact surface a confirmed command can move. */
  async function refreshFacts(resourceId: Id): Promise<void> {
    await Promise.all([
      options.loadCatalog(true),
      options.loadDetail(resourceId, true),
      options.loadVersions(resourceId, false, true),
      options.loadEffective?.(resourceId, true),
    ])
  }

  // Catalog lifecycle — registers first so activation + catalog load always run before any
  // selection load (the original page watch order). A scope or filter change abandons any
  // kept retry key and the pending command state, then reloads the catalog.
  watch(
    options.catalogSources,
    async ([phase]) => {
      const pageOwner = options.pageRequests.capture()
      if (phase !== 'ready' || !options.scopePresent()) return
      options.activateScope()
      commandAttempt.value = null
      options.onCatalogReset?.()
      options.clearCommand()
      await options.loadCatalog(true)
      if (!pageOwner.isCurrent()) return
    },
    { immediate: true },
  )

  // Selection lifecycle: any coordinate change drops the pending command state first, then
  // loads what the current tab needs. The effective version feeds the editor diff baseline
  // (skills) or the versions panel (knowledge), per effectiveOnDraftTab.
  watch(
    () => [
      options.scopeReady(),
      options.teamId(),
      options.selectedId(),
      options.tab(),
      options.selectedRevision(),
    ] as const,
    ([ready, , id, currentTab, revision]) => {
      commandAttempt.value = null
      options.clearCommand()
      if (!ready || !id) return
      void options.loadDetail(id)
      if (options.effectiveOnDraftTab) void options.loadEffective?.(id)
      if (currentTab === 'versions') {
        void options.loadVersions(id)
        if (!options.effectiveOnDraftTab) void options.loadEffective?.(id)
        if (revision) void options.loadVersion?.(id, revision)
      }
    },
    { immediate: true },
  )

  // Query normalization: filters and tab collapse to their fallbacks (URL stays shareable),
  // and a selected/revision coordinate that no longer resolves is dropped instead of kept.
  watch(
    () => [
      options.scopeReady(),
      ...options.filters.map(filter => route.query[filter.key]),
      route.query[options.queryKeys.selected],
      route.query.revision,
      route.query.tab,
      ...(options.extraQueryKeys ?? []).map(key => route.query[key]),
    ],
    ([ready]) => {
      if (!ready) return
      const query = { ...route.query } as Record<string, string>
      let changed = false
      for (const filter of options.filters) {
        changed = normalizeQuery(query, filter.key, filter.value(), filter.fallback) || changed
      }
      changed = normalizeQuery(query, 'tab', options.tab(), 'draft') || changed
      if (route.query[options.queryKeys.selected] != null && !options.selectedId()) {
        delete query[options.queryKeys.selected]
        changed = true
      }
      if (route.query.revision != null && !options.selectedRevision()) {
        delete query.revision
        changed = true
      }
      if (changed) void router.replace({ query })
    },
    { immediate: true },
  )

  function select(id: Id): void {
    const query = { ...route.query } as Record<string, string>
    delete query.revision
    query[options.queryKeys.selected] = id
    void router.replace({ query })
  }

  function closeDetail(): void {
    const query = { ...route.query } as Record<string, string>
    delete query[options.queryKeys.selected]
    delete query.revision
    void router.replace({ query })
  }

  function replaceFilter(key: string, value: string, fallback: string): void {
    const query = { ...route.query } as Record<string, string>
    if (value === fallback) delete query[key]
    else query[key] = value
    delete query[options.queryKeys.selected]
    delete query.revision
    void router.replace({ query })
  }

  function changeTab(next: 'draft' | 'versions'): void {
    const query = { ...route.query } as Record<string, string>
    if (next === 'draft') delete query.tab
    else query.tab = next
    const id = options.selectedId()
    if (next === 'versions' && id) options.onEnterVersionsTab?.(id)
    void router.replace({ query })
  }

  function selectRevision(revision: number): void {
    void router.replace({ query: { ...route.query, revision: String(revision) } })
  }

  async function reload(): Promise<boolean> {
    const pageOwner = options.pageRequests.capture()
    // Refresh is a manual action on a rendered page, so readiness is implied; the offline
    // hint and a resolved scope are the real gates (both pages bail identically).
    if (!options.scopeReady() || !options.online()) return false
    await options.loadCatalog(true)
    const id = options.selectedId()
    if (id) await options.loadDetail(id, true)
    return pageOwner.isCurrent()
  }

  function retryDetail(): void {
    const id = options.selectedId()
    if (id) void options.loadDetail(id, true)
  }

  function retryVersions(): void {
    const id = options.selectedId()
    if (id) void options.loadVersions(id, false, true)
  }

  function loadMoreVersions(): void {
    const id = options.selectedId()
    if (id) void options.loadVersions(id, true)
  }

  return {
    runCommand,
    refreshFacts,
    idleVersionPage,
    select,
    closeDetail,
    replaceFilter,
    changeTab,
    selectRevision,
    reload,
    retryDetail,
    retryVersions,
    loadMoreVersions,
  }
}
