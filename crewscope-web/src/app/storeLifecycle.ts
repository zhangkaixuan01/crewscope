import type { App } from 'vue'

// M13d-P01: domain stores install lazily on the pages that consume them, so main cannot import
// their modules to reset them at the session boundary — importing a reset helper would drag the
// whole domain back into the eager chunk. Instead each store registers its own reset callback
// here when it is lazily installed; the boundary then resets exactly the stores that were ever
// active on this app. The registry is keyed by app (WeakMap), so spec apps never see each
// other's callbacks and a discarded app is collected with its entries.
const resets = new WeakMap<App, Array<(reason: string) => void>>()

export function registerStoreReset(app: App, reset: (reason: string) => void): void {
  const list = resets.get(app)
  if (list) list.push(reset)
  else resets.set(app, [reset])
}

export function resetActiveStores(app: App, reason: string): void {
  for (const reset of resets.get(app) ?? []) reset(reason)
}
