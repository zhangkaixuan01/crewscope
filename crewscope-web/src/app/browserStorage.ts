/** Shared best-effort browser storage accessors; server idempotency stays authoritative. */
export function browserStorage(): Storage | null {
  try { return typeof sessionStorage === 'undefined' ? null : sessionStorage } catch { return null }
}
export function safeGet(storage: Storage | null, key: string): string | null { try { return storage?.getItem(key) ?? null } catch { return null } }
export function safeSet(storage: Storage | null, key: string, value: string): void { try { storage?.setItem(key, value) } catch { /* Storage is an optimization. */ } }
export function safeRemove(storage: Storage | null, key: string): void { try { storage?.removeItem(key) } catch { /* Unavailable storage never weakens server-side recovery. */ } }
