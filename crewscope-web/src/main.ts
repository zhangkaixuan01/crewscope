import { createApp } from 'vue'
import App from './App.vue'
import { installAuthPlaceholder } from './app/auth'
import { installGlobalErrorHandling } from './app/errors'
import { router } from './app/router'
import './design/tokens.css'
import './design/base.css'
import './design/layout.css'

const app = createApp(App)

installAuthPlaceholder(app)
installGlobalErrorHandling(app)
app.use(router)
app.mount('#app')
