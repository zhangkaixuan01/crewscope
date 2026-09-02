import { createApp } from 'vue'
import { createWebHistory } from 'vue-router'
import App from './App.vue'
import { installGlobalErrorHandling } from './app/errors'
import { createCrewScopeRouter } from './app/router'
import { apiClient } from './api/client'
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
authStore.subscribe((phase, reason) => {
  if (phase !== 'anonymous' || reason === 'restored') return
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
})
const router = createCrewScopeRouter(createWebHistory(), authStore)
apiClient.onAuthenticationRequired(() => authStore.authenticationRequired())
authStore.start()
app.use(router)
app.mount('#app')
