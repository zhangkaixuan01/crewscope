import { createApp, h } from 'vue'
import { createRouter, createWebHistory, RouterView } from 'vue-router'
import Harness from './Harness.vue'
import { setCreationIdentity, stopCreationQueries, observeCreationStorage } from '../../src/api/creationRecovery'

const scope = (window as unknown as { a01Scope: { actor: string } }).a01Scope
setCreationIdentity(scope.actor)
window.addEventListener('storage', observeCreationStorage)
const router = createRouter({ history: createWebHistory(), routes: [
  { name: 'work', path: '/', component: Harness },
  { name: 'conversation', path: '/conversation', component: Harness },
] })
router.beforeEach(stopCreationQueries)
createApp({ render: () => h(RouterView) }).use(router).mount('#app')
