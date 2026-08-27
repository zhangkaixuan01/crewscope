import { createApp } from 'vue'
import App from './App.vue'
import { bootstrapPrincipal, installAuthPlaceholder } from './app/auth'
import { installGlobalErrorHandling } from './app/errors'
import { router } from './app/router'
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
import './design/tokens.css'
import './design/base.css'
import './design/layout.css'

const app = createApp(App)

installAuthPlaceholder(app, bootstrapPrincipal)
installScopeStore(app, new HttpScopeGateway(), bootstrapPrincipal)
installConversationStore(app, new HttpConversationGateway())
installConversationMessageStore(app, new HttpConversationMessageGateway())
installConversationRealtimeStore(app, new HttpConversationRealtimeGateway())
installTaskIntentStore(app, new HttpTaskIntentGateway())
installConversationWorkItemLinkStore(app, new HttpConversationWorkItemLinkGateway())
installWorkItemStore(app, new HttpWorkItemGateway())
installTaskStore(app, new HttpTaskGateway())
installCodingStore(app, new HttpCodingGateway())
installModelStore(app, new HttpModelGateway())
installAgentStore(app, new HttpAgentGateway())
installReviewStore(app, new HttpReviewGateway())
installDeliveryStore(app, new HttpDeliveryGateway())
const teamOpsGateway = new HttpTeamOpsGateway()
const teamOpsStore = installTeamOpsStore(app, teamOpsGateway)
installActivityRealtimeStore(app, teamOpsGateway, teamOpsStore)
installTeamObserverStore(app, new HttpTeamObserverGateway())
installGlobalErrorHandling(app)
app.use(router)
app.mount('#app')
