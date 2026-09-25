import { onScopeDispose, ref, watch, type Ref } from 'vue'
import { readF05Reading, subscribeF05Epoch, writeF05Reading, type F05Scope } from '../app/f05Storage'

export interface UseScopedUserStateOptions<T> {
  /**
   * Full F05 scope provider (identity + Team/Project + object). While it resolves to null —
   * signed out, account switched, scope not ready — reads yield the fallback and writes drop,
   * so reading marks never leak across accounts or Teams (M9b-F05).
   */
  scope: () => F05Scope | null
  /** Segment under the object dimension, such as `scroll` or `viewed-files`. */
  name: string
  fallback: T
  /** Optional runtime guard for values restored from untrusted storage. */
  validate?: (value: unknown) => value is T
}

export interface UseScopedUserStateResult<T> {
  value: Ref<T>
  reset: () => void
}

/**
 * Reading-state counterpart of {@link usePreference}: scroll marks, read sequences and viewed
 * files move from device-level keys into the scoped `reading` kind, keyed by the object they
 * belong to. Scope switches reload lazily and never cross-write, and a sealed epoch resets to
 * the fallback immediately.
 */
export function useScopedUserState<T>(options: UseScopedUserStateOptions<T>): UseScopedUserStateResult<T> {
  const validate = options.validate ?? (() => true)
  const cloneFallback = (): T => structuredCloneSafe(options.fallback)
  const value = ref(cloneFallback()) as Ref<T>
  let initialized = false

  function read(): void {
    const scope = options.scope()
    const stored = scope ? readF05Reading<unknown>(scope, options.name) : null
    value.value = stored !== null && validate(stored) ? stored as T : cloneFallback()
  }

  function write(): void {
    const scope = options.scope()
    // Reading marks are convenience state; a failed or sealed write degrades silently.
    if (scope) writeF05Reading(scope, options.name, value.value)
  }

  function reset(): void {
    value.value = cloneFallback()
    write()
  }

  read()
  initialized = true
  watch(options.scope, () => {
    // Reload without persisting: the values just read belong where they were read from.
    initialized = false
    read()
    initialized = true
  }, { flush: 'sync' })
  // Reading marks persist the moment they change, so even an abrupt unload cannot eat them.
  watch(value, () => { if (initialized) write() }, { deep: true, flush: 'sync' })
  // The epoch listener holds this instance's refs; dropping the unsubscribe would leak one
  // listener per mount for the lifetime of the tab.
  onScopeDispose(subscribeF05Epoch(epoch => { if (epoch === null) value.value = cloneFallback() }))

  return { value, reset }
}

function structuredCloneSafe<T>(value: T): T {
  if (typeof structuredClone === 'function') return structuredClone(value)
  if (value === null || typeof value !== 'object') return value
  return JSON.parse(JSON.stringify(value)) as T
}
