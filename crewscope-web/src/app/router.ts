import {
  createRouter,
  createWebHistory,
  type Router,
  type RouterHistory,
} from 'vue-router'
import { bootstrapPrincipal, can, permissions, type AuthenticatedPrincipal } from './auth'

export function createCrewScopeRouter(
  history: RouterHistory,
  principal: AuthenticatedPrincipal = bootstrapPrincipal,
): Router {
  const router = createRouter({
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
        meta: { mode: 'conversation', section: 'conversation', requiredPermission: permissions.conversationUse },
      },
      {
        path: '/today',
        name: 'today',
        component: () => import('../pages/TodayPage.vue'),
        meta: { mode: 'control', section: 'today', requiredPermission: permissions.scopeRead },
      },
      {
        path: '/work',
        name: 'work',
        component: () => import('../pages/WorkPage.vue'),
        meta: { mode: 'control', section: 'work', requiredPermission: permissions.workRead },
      },
      {
        path: '/team/members',
        name: 'team-members',
        component: () => import('../pages/TeamMembersPage.vue'),
        meta: { mode: 'control', section: 'members', requiredPermission: permissions.teamMembersRead },
      },
      {
        path: '/control',
        redirect: to => ({ name: 'today', query: to.query }),
      },
      {
        path: '/access-denied',
        name: 'access-denied',
        component: () => import('../pages/AccessDeniedPage.vue'),
        meta: { mode: 'control', section: 'access-denied' },
      },
      {
        path: '/:pathMatch(.*)*',
        name: 'not-found',
        component: () => import('../pages/NotFoundPage.vue'),
      },
    ],
    scrollBehavior: () => ({ top: 0 }),
  })

  router.beforeEach(to => {
    const requiredPermission = to.meta.requiredPermission
    if (typeof requiredPermission === 'string' && !can(principal, requiredPermission)) {
      return { name: 'access-denied', query: { from: to.fullPath } }
    }
    return true
  })

  return router
}

export const router = createCrewScopeRouter(createWebHistory(), bootstrapPrincipal)
