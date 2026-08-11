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
installGlobalErrorHandling(app)
app.use(router)
app.mount('#app')
