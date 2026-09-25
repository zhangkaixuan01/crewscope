import { createApp } from 'vue'
import { createWebHistory } from 'vue-router'
import App from './App.vue'
import { installGlobalErrorHandling } from './app/errors'
import { clearF05TeamScope } from './app/f05Storage'
import { subscribeSessionBoundary } from './app/sessionBoundary'
import { createCrewScopeRouter } from './app/router'
import { apiClient } from './api/client'
import { observeCreationStorage, setCreationIdentity, stopCreationQueries } from './api/creationRecovery'
import { HttpConversationGateway } from './domains/conversation/gateway'
import { HttpConversationMessageGateway } from './domains/conversation/messageGateway'
import { installConversationMessageStore } from './domains/conversation/messageStore'
import { HttpConversationRealtimeGateway } from './domains/conversation/realtimeGateway'
import { installConversationRealtimeStore } from './domains/conversation/realtimeStore'
import { installConversationStore } from './domains/conversation/store'
import { HttpTaskIntentGateway } from './domains/conversation/taskIntentGateway'
import { installTaskIntentStore } from './domains/conversation/taskIntentStore'
import { HttpConversationWorkItemLinkGateway } from './domains/conversation/workItemLinkGateway'
import { installConversationWorkItemLinkStore } from './domains/conversation/workItemLinkStore'
import { HttpScopeGateway } from './domains/scope/gateway'
import { installScopeStore } from './domains/scope/store'
import { HttpWorkItemGateway } from './domains/workitem/gateway'
import { installWorkItemStore } from './domains/workitem/store'
import { HttpTaskGateway } from './domains/task/gateway'
import { installTaskStore } from './domains/task/store'
import { HttpCodingGateway } from './domains/coding/gateway'
import { installCodingStore } from './domains/coding/store'
import { HttpModelGateway } from './domains/model/gateway'
import { installModelStore } from './domains/model/store'
import { HttpAgentGateway } from './domains/agent/gateway'
import { installAgentStore } from './domains/agent/store'
import { HttpReviewGateway } from './domains/review/gateway'
import { installReviewStore } from './domains/review/store'
import { HttpDeliveryGateway } from './domains/delivery/gateway'
import { installDeliveryStore } from './domains/delivery/store'
import { HttpTeamOpsGateway } from './domains/teamops/gateway'
import { installTeamOpsStore } from './domains/teamops/store'
import { installActivityRealtimeStore } from './domains/teamops/activityRealtimeStore'
import { HttpTeamObserverGateway } from './domains/teamobserver/gateway'
import { installTeamObserverStore } from './domains/teamobserver/store'
import { HttpSetupGateway } from './domains/setup/gateway'
import { installSetupStore } from './domains/setup/store'
import { HttpIdentityGateway, installIdentityGateway } from './domains/identity/gateway'
import { createAuthStore, installAuthStore } from './domains/identity/store'
import { HttpOnboardingGateway } from './domains/onboarding/gateway'
import { createOnboardingStore, installOnboardingStore } from './domains/onboarding/store'
import { HttpAccountGateway } from './domains/account/gateway'
import { createAccountStore, installAccountStore } from './domains/account/store'
import { HttpInvitationGateway } from './domains/invitation/gateway'
import { createInvitationStore, installInvitationStore } from './domains/invitation/store'
import { HttpWorkDeskGateway } from './domains/workdesk/gateway'
import { installWorkDeskStore } from './domains/workdesk/store'
import { HttpSearchGateway } from './domains/search/gateway'
import { installSearchStore } from './domains/search/store'
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
const onboardingStore = createOnboardingStore(new HttpOnboardingGateway())
installOnboardingStore(app, onboardingStore)
const accountStore = installAccountStore(app, createAccountStore(new HttpAccountGateway()))
const invitationStore = installInvitationStore(app, createInvitationStore(new HttpInvitationGateway()))
const workDeskStore = installWorkDeskStore(app, new HttpWorkDeskGateway())
const searchStore = installSearchStore(app, new HttpSearchGateway())
const scopeStore = installScopeStore(app, new HttpScopeGateway(), authStore.principal)
const conversationStore = installConversationStore(app, new HttpConversationGateway())
const conversationMessageStore = installConversationMessageStore(app, new HttpConversationMessageGateway())
const conversationRealtimeStore = installConversationRealtimeStore(app, new HttpConversationRealtimeGateway())
const taskIntentStore = installTaskIntentStore(app, new HttpTaskIntentGateway())
const conversationWorkItemLinkStore = installConversationWorkItemLinkStore(app, new HttpConversationWorkItemLinkGateway())
const workItemStore = installWorkItemStore(app, new HttpWorkItemGateway())
const taskStore = installTaskStore(app, new HttpTaskGateway())
const codingStore = installCodingStore(app, new HttpCodingGateway())
const modelStore = installModelStore(app, new HttpModelGateway())
const agentStore = installAgentStore(app, new HttpAgentGateway())
const reviewStore = installReviewStore(app, new HttpReviewGateway())
const deliveryStore = installDeliveryStore(app, new HttpDeliveryGateway())
const teamOpsGateway = new HttpTeamOpsGateway()
const teamOpsStore = installTeamOpsStore(app, teamOpsGateway)
const activityRealtimeStore = installActivityRealtimeStore(app, teamOpsGateway, teamOpsStore)
const teamObserverStore = installTeamObserverStore(app, new HttpTeamObserverGateway())
const setupStore = installSetupStore(app, new HttpSetupGateway())
installGlobalErrorHandling(app)
subscribeSessionBoundary(authStore, reason => {
  activityRealtimeStore.stop()
  onboardingStore.reset()
  accountStore.reset()
  invitationStore.resetManagement()
  if (reason === 'explicit-sign-out') invitationStore.clearProof()
  scopeStore.reset()
  conversationStore.reset()
  conversationMessageStore.reset()
  conversationRealtimeStore.reset()
  taskIntentStore.reset()
  conversationWorkItemLinkStore.reset()
  workItemStore.reset()
  taskStore.reset()
  codingStore.reset()
  modelStore.reset()
  agentStore.reset()
  reviewStore.reset()
  deliveryStore.reset()
  teamOpsStore.reset()
  teamObserverStore.reset()
  setupStore.reset()
  workDeskStore.reset()
  searchStore.reset()
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
