import { createApp } from 'vue'
import { createWebHistory } from 'vue-router'
import App from './App.vue'
import { installGlobalErrorHandling } from './app/errors'
import { clearF05TeamScope } from './app/f05Storage'
import { subscribeSessionBoundary } from './app/sessionBoundary'
import { createCrewScopeRouter } from './app/router'
import { resetActiveStores } from './app/storeLifecycle'
import { apiClient } from './api/client'
import { observeCreationStorage, setCreationIdentity, stopCreationQueries } from './api/creationRecovery'
// M13d-P01: every domain-scoped store installs lazily on the pages that consume it
// (ensureXxxStore in each domain), which keeps those domains out of the eager main chunk.
// Cross-cutting domains stay eager here on purpose: identity bootstraps the session and owns
// the API auth callbacks, scope drives every route's shell, and the palette search session is
// mounted by App.vue's always-present CommandPalette. At the session boundary the lazily
// installed stores are reset through the store lifecycle registry (app/storeLifecycle) —
// importing any domain's reset helper here would drag the domain back into this chunk.
import { HttpIdentityGateway, installIdentityGateway } from './domains/identity/gateway'
import { createAuthStore, installAuthStore } from './domains/identity/store'
import { HttpScopeGateway } from './domains/scope/gateway'
import { installScopeStore } from './domains/scope/store'
import { HttpSearchGateway } from './domains/search/gateway'
import { installPaletteSearchStore } from './domains/search/store'
import { createActionRegistry, installActionRegistry } from './app/actionRegistry'
import { createShortcutManager, installShortcutManager } from './app/shortcuts'
import { registerDefaultActions } from './app/defaultActions'
import './design/tokens.css'
import './design/base.css'
import './design/layout.css'

const app = createApp(App)

const identityGateway = new HttpIdentityGateway()
const authStore = createAuthStore(identityGateway)
installIdentityGateway(app, identityGateway)
installAuthStore(app, authStore)
const scopeStore = installScopeStore(app, new HttpScopeGateway(), authStore.principal)
// R20: the command palette keeps a separate session so its debounced preview queries
// can never clear or replace what the full search page is showing.
const paletteSearchStore = installPaletteSearchStore(app, new HttpSearchGateway())
installGlobalErrorHandling(app)
subscribeSessionBoundary(authStore, reason => {
  resetActiveStores(app, reason)
  scopeStore.reset()
  paletteSearchStore.reset()
})
const router = createCrewScopeRouter(createWebHistory(), authStore)
router.beforeEach(() => { stopCreationQueries() })
window.addEventListener('storage', observeCreationStorage)
authStore.subscribe(phase => {
  if (phase === 'authenticated') {
    const session = authStore.state.session
    setCreationIdentity(JSON.stringify([session?.account?.accountId, session?.principal?.principalId,
      session?.principal?.organizationId, session?.account?.securityVersion]))
  } else if (phase === 'anonymous') setCreationIdentity(null)
  else stopCreationQueries()
})
const actionRegistry = createActionRegistry()
installActionRegistry(app, actionRegistry)
registerDefaultActions(actionRegistry, router, authStore.principal)
const shortcutManager = createShortcutManager({
  registry: actionRegistry,
  getContext: () => ({ router, route: router.currentRoute.value, principal: authStore.principal }),
})
installShortcutManager(app, shortcutManager)
apiClient.onAuthenticationRequired(() => authStore.authenticationRequired())
// A 403 means the identity lost a permission: scoped local content of that team
// disappears immediately without touching another Team's data (M9b-F05).
apiClient.onForbidden(() => {
  const session = authStore.state.session
  const teamId = authStore.state.activeTeamId
  if (!session?.account || !session.principal || !teamId) return
  clearF05TeamScope({ accountId: session.account.accountId, organizationId: session.principal.organizationId, teamId })
})
authStore.start()
app.use(router)
shortcutManager.start()
app.mount('#app')
