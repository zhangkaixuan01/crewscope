import {
  createRouter,
  createWebHistory,
  type Router,
  type RouterHistory,
} from 'vue-router'

export function createCrewScopeRouter(history: RouterHistory): Router {
  return createRouter({
    history,
    routes: [
      {
        path: '/',
        redirect: { name: 'conversation' },
      },
      {
        path: '/conversation',
        name: 'conversation',
        component: () => import('../pages/ConversationPage.vue'),
        meta: { mode: 'conversation' },
      },
      {
        path: '/control',
        name: 'control',
        component: () => import('../pages/ControlPage.vue'),
        meta: { mode: 'control' },
      },
      {
        path: '/:pathMatch(.*)*',
        name: 'not-found',
        component: () => import('../pages/NotFoundPage.vue'),
      },
    ],
    scrollBehavior: () => ({ top: 0 }),
  })
}

export const router = createCrewScopeRouter(createWebHistory())
