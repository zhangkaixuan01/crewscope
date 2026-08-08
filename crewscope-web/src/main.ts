import { createApp } from 'vue'
import App from './App.vue'
import { bootstrapPrincipal, installAuthPlaceholder } from './app/auth'
import { installGlobalErrorHandling } from './app/errors'
import { router } from './app/router'
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
installWorkItemStore(app, new HttpWorkItemGateway())
installGlobalErrorHandling(app)
app.use(router)
app.mount('#app')
