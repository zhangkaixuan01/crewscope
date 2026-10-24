import { getCurrentInstance, type App } from 'vue'

// M13d-P01: domain stores install lazily via ensureXxxStore(app). Components that consume a
// store through a raw inject (instead of a use* helper) call this in setup to hand the helper
// the owning app; ensure* keeps app-level fixture provides winning over lazy installation.
export function currentApp(): App {
  const instance = getCurrentInstance()
  if (!instance) throw new Error('currentApp() must be called from a component setup context')
  return instance.appContext.app
}
