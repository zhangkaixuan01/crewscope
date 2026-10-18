/**
 * Structural resource shapes shared by the twin-dissolution shells (M12b-F04).
 *
 * The skill and knowledge stores each declare a same-shaped `XxxResource<T>` under its own
 * name; these interfaces are the structural superset the shells consume, so either domain's
 * store resources satisfy them without adapter code. Keep them field-for-field compatible
 * with `domains/skill/store.ts` and `domains/knowledge/store.ts`.
 */

export type ResourcePhase = 'idle' | 'loading' | 'ready' | 'empty' | 'error'

export interface Resource<T> {
  phase: ResourcePhase
  value: T | null
  errorMessage: string | null
  errorStatus: number | null
}

/** An entry-listing resource with a key-string cursor and an in-flight continuation flag. */
export interface ListPageResource<T> extends Resource<T[]> {
  /** Key keyset cursor (entryKey / skillKey); null means the listing reached its end. */
  nextAfter: string | null
  loadingMore: boolean
}

/** A version-history resource with a revision cursor (keyset on the revision number). */
export interface VersionPageResource<T> extends Resource<T[]> {
  /** Revision keyset cursor; null means the history reached its end. */
  nextAfter: number | null
  loadingMore: boolean
}

/** Strong-ETag wrapper the gateways return for single-resource reads. */
export interface Etagged<T> {
  value: T
  etag: string
}

/** Head fields the detail shell's facts/audit sections render; both twins carry them. */
export interface EntryLike {
  id: string
  latestRevision: number
  effectiveRevision: number | null
  updatedAt: string
  createdBy: string
  updatedBy: string
  createdAt: string
  origin?: { taskExecutionId: string; attempt: number } | null
}

/** Common fields every published version shape carries (skill and knowledge alike). */
export interface VersionLike {
  revision: number
  content: string
  contentHash: string
  createdAt: string
  /** Published titles exist on knowledge versions; skill revisions render "修订 rN" instead. */
  title?: string
}
