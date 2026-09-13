import { ref, watch, type Ref } from 'vue'

interface StoredPreference<T> {
  version: number
  value: T
}

export interface UsePreferenceOptions {
  version?: number
  /** Set to false for ephemeral preferences that should not be persisted. */
  persist?: boolean
}

export interface UsePreferenceResult<T> {
  value: Ref<T>
  reset: () => void
  remove: () => void
}

/**
 * The only localStorage entry point for device preferences. Values carry a
 * schema version, invalid data falls back safely, and changes in another tab
 * are applied through the browser storage event.
 */
export function usePreference<T>(key: string, fallback: T, options: UsePreferenceOptions = {}): UsePreferenceResult<T> {
  const version = options.version ?? 1
  const persist = options.persist ?? true
  const cloneFallback = (): T => structuredCloneSafe(fallback)
  const value = ref(cloneFallback()) as Ref<T>
  let initialized = false

  function read(): void {
    if (!persist || typeof window === 'undefined') return
    try {
      const raw = window.localStorage.getItem(key)
      if (!raw) return
      const parsed = JSON.parse(raw) as Partial<StoredPreference<T>>
      if (parsed.version === version && 'value' in parsed) value.value = parsed.value as T
    } catch {
      value.value = cloneFallback()
    }
  }
  function write(): void {
    if (!persist || typeof window === 'undefined') return
    try { window.localStorage.setItem(key, JSON.stringify({ version, value: value.value })) } catch { /* storage may be disabled */ }
  }
  function reset(): void { value.value = cloneFallback(); write() }
  function remove(): void {
    if (typeof window !== 'undefined') {
      try { window.localStorage.removeItem(key) } catch { /* storage may be disabled */ }
    }
    value.value = cloneFallback()
  }

  read()
  initialized = true
  watch(value, () => { if (initialized) write() }, { deep: true })
  if (persist && typeof window !== 'undefined') {
    window.addEventListener('storage', event => {
      if (event.key !== key || !event.newValue) return
      try {
        const parsed = JSON.parse(event.newValue) as Partial<StoredPreference<T>>
        if (parsed.version === version && 'value' in parsed) value.value = parsed.value as T
      } catch { /* ignore malformed values from another tab */ }
    })
  }

  return { value, reset, remove }
}

function structuredCloneSafe<T>(value: T): T {
  if (typeof structuredClone === 'function') return structuredClone(value)
  if (value === null || typeof value !== 'object') return value
  return JSON.parse(JSON.stringify(value)) as T
}

