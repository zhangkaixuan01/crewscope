import {
  createRouter,
  type Router,
  type RouterHistory,
} from 'vue-router'
import { can, permissions } from './auth'
import type { AuthStore } from '../domains/identity/store'

export function createCrewScopeRouter(
  history: RouterHistory,
  authStore: AuthStore,
): Router {
  const router = createRouter({
    history,
    routes: [
      {
        path: '/login',
        name: 'login',
        component: () => import('../pages/LoginPage.vue'),
        meta: { section: 'identity', publicIdentity: true },
      },
      {
        path: '/register',
        name: 'register',
        component: () => import('../pages/RegisterPage.vue'),
        meta: { section: 'identity', publicIdentity: true },
      },
      {
        path: '/invite',
        name: 'invite',
        component: () => import('../pages/InvitePage.vue'),
        meta: { section: 'identity', publicIdentity: true },
      },
      {
        path: '/onboarding',
        name: 'onboarding',
        component: () => import('../pages/OnboardingPage.vue'),
        meta: { section: 'identity' },
      },
      {
        path: '/account',
        name: 'account',
        component: () => import('../pages/AccountPage.vue'),
        meta: { mode: 'control', section: 'account' },
      },
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
        path: '/activity',
        name: 'activity',
        component: () => import('../pages/ActivityPage.vue'),
        meta: { mode: 'control', section: 'activity', requiredPermission: permissions.scopeRead },
      },
      {
        path: '/inbox',
        name: 'inbox',
        component: () => import('../pages/InboxPage.vue'),
        meta: { mode: 'control', section: 'inbox', requiredPermission: permissions.scopeRead },
      },
      {
        path: '/team/observer',
        name: 'team-observer',
        component: () => import('../pages/TeamObserverPage.vue'),
        meta: { mode: 'control', section: 'team-observer', requiredPermission: permissions.scopeRead },
      },
      {
        path: '/operations',
        name: 'operations',
        component: () => import('../pages/OperationsPage.vue'),
        meta: { mode: 'control', section: 'operations', requiredPermission: permissions.scopeRead },
      },
      {
        path: '/audit',
        name: 'audit',
        component: () => import('../pages/AuditPage.vue'),
        meta: { mode: 'control', section: 'audit', requiredPermission: permissions.auditRead },
      },
      {
        path: '/team/members',
        name: 'team-members',
        component: () => import('../pages/TeamMembersPage.vue'),
        meta: { mode: 'control', section: 'members', requiredPermission: permissions.teamMembersRead },
      },
      {
        path: '/settings/repositories',
        name: 'repository-settings',
        component: () => import('../pages/RepositorySettingsPage.vue'),
        meta: { mode: 'control', section: 'repositories', requiredPermission: permissions.repositoriesManage },
      },
      {
        path: '/settings/agents',
        name: 'agent-settings',
        component: () => import('../pages/AgentSettingsPage.vue'),
        meta: { mode: 'control', section: 'agents', requiredPermission: permissions.scopeRead },
      },
      {
        path: '/settings/models',
        name: 'model-settings',
        component: () => import('../pages/ModelSettingsPage.vue'),
        meta: { mode: 'control', section: 'models', requiredPermission: permissions.scopeRead },
      },
      {
        path: '/settings/integrations/lark',
        name: 'lark-settings',
        component: () => import('../pages/LarkSettingsPage.vue'),
        meta: { mode: 'control', section: 'lark', requiredPermission: permissions.providerManage },
      },
      {
        path: '/settings/integrations/github',
        name: 'github-settings',
        component: () => import('../pages/GitHubSettingsPage.vue'),
        meta: { mode: 'control', section: 'github', requiredPermission: permissions.providerManage },
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

  router.beforeEach(async to => {
    if (authStore.state.phase === 'idle' || authStore.state.phase === 'restoring') {
      await authStore.ensureRestored()
    }
    if (to.meta.publicIdentity === true) return true
    if (authStore.state.phase !== 'authenticated') {
      return { name: 'login', query: { returnTo: to.fullPath } }
    }
    const targetTeamId = queryValue(to.query.team)
    if (targetTeamId !== null || authStore.state.activeTeamId === null) {
      authStore.selectTeam(targetTeamId)
    }
    const requiredPermission = to.meta.requiredPermission
    if (typeof requiredPermission === 'string' && !can(authStore.principal, requiredPermission)) {
      return { name: 'access-denied', query: { from: to.fullPath } }
    }
    return true
  })

  authStore.subscribe((phase, reason) => {
    if (phase !== 'anonymous' || reason === 'restored') return
    const current = router.currentRoute.value
    if (current.meta.publicIdentity === true || typeof current.name !== 'string') return
    void router.replace({ name: 'login', query: { returnTo: current.fullPath } })
  })

  return router
}

function queryValue(value: unknown): string | null {
  return typeof value === 'string' && value.length > 0 ? value : null
}
