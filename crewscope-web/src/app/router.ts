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
        meta: { section: 'identity', publicIdentity: true, title: '登录' },
      },
      {
        path: '/register',
        name: 'register',
        component: () => import('../pages/RegisterPage.vue'),
        meta: { section: 'identity', publicIdentity: true, title: '注册' },
      },
      {
        path: '/invite',
        name: 'invite',
        component: () => import('../pages/InvitePage.vue'),
        meta: { section: 'identity', publicIdentity: true, title: '接受邀请' },
      },
      {
        path: '/onboarding',
        name: 'onboarding',
        component: () => import('../pages/OnboardingPage.vue'),
        meta: { section: 'identity', title: '初始化团队' },
      },
      {
        path: '/account',
        name: 'account',
        component: () => import('../pages/AccountPage.vue'),
        meta: { mode: 'control', section: 'account', title: '账号设置' },
      },
      {
        path: '/',
        redirect: { name: 'today' },
        meta: { title: '个人工作台' },
      },
      {
        path: '/conversation',
        name: 'conversation',
        component: () => import('../pages/ConversationPage.vue'),
        meta: { mode: 'conversation', section: 'conversation', title: '对话', requiredPermission: permissions.conversationUse },
      },
      {
        path: '/search',
        name: 'search',
        component: () => import('../pages/SearchPage.vue'),
        meta: { mode: 'control', section: 'search', title: '统一搜索', requiredPermission: permissions.scopeRead },
      },
      {
        path: '/today',
        name: 'today',
        component: () => import('../pages/TodayPage.vue'),
        meta: { mode: 'control', section: 'today', title: '今日工作', requiredPermission: permissions.scopeRead },
      },
      {
        path: '/setup',
        name: 'setup',
        component: () => import('../pages/SetupPage.vue'),
        meta: { mode: 'control', section: 'setup', title: '配置中心', requiredPermission: permissions.scopeRead },
      },
      {
        path: '/work',
        name: 'work',
        component: () => import('../pages/WorkPage.vue'),
        meta: { mode: 'control', section: 'work', title: '工作项', requiredPermission: permissions.workRead },
      },
      {
        path: '/activity',
        name: 'activity',
        component: () => import('../pages/ActivityPage.vue'),
        meta: { mode: 'control', section: 'activity', title: '团队动态', requiredPermission: permissions.scopeRead },
      },
      {
        path: '/inbox',
        name: 'inbox',
        component: () => import('../pages/InboxPage.vue'),
        meta: { mode: 'control', section: 'inbox', title: '我的 Inbox', requiredPermission: permissions.scopeRead },
      },
      {
        path: '/team/observer',
        name: 'team-observer',
        component: () => import('../pages/TeamObserverPage.vue'),
        meta: { mode: 'control', section: 'team-observer', title: '团队观测', requiredPermission: permissions.scopeRead },
      },
      {
        path: '/operations',
        name: 'operations',
        component: () => import('../pages/OperationsPage.vue'),
        meta: { mode: 'control', section: 'operations', title: '运行与发布', requiredPermission: permissions.scopeRead },
      },
      {
        path: '/audit',
        name: 'audit',
        component: () => import('../pages/AuditPage.vue'),
        meta: { mode: 'control', section: 'audit', title: '审计中心', requiredPermission: permissions.auditRead },
      },
      {
        path: '/team/members',
        name: 'team-members',
        component: () => import('../pages/TeamMembersPage.vue'),
        meta: { mode: 'control', section: 'members', title: '团队成员', requiredPermission: permissions.teamMembersRead },
      },
      {
        path: '/settings/repositories',
        name: 'repository-settings',
        component: () => import('../pages/RepositorySettingsPage.vue'),
        meta: { mode: 'control', section: 'repositories', title: '受管仓库', requiredPermission: permissions.repositoriesManage },
      },
      {
        path: '/settings/agents',
        name: 'agent-settings',
        component: () => import('../pages/AgentSettingsPage.vue'),
        meta: { mode: 'control', section: 'agents', title: 'Agent 中心', requiredPermission: permissions.scopeRead },
      },
      {
        path: '/settings/models',
        name: 'model-settings',
        component: () => import('../pages/ModelSettingsPage.vue'),
        meta: { mode: 'control', section: 'models', title: '模型与凭证', requiredPermission: permissions.scopeRead },
      },
      {
        path: '/settings/integrations/lark',
        name: 'lark-settings',
        component: () => import('../pages/LarkSettingsPage.vue'),
        meta: { mode: 'control', section: 'lark', title: '飞书与通知', requiredPermission: permissions.providerManage },
      },
      {
        path: '/settings/integrations/github',
        name: 'github-settings',
        component: () => import('../pages/GitHubSettingsPage.vue'),
        meta: { mode: 'control', section: 'github', title: 'GitHub 集成', requiredPermission: permissions.providerManage },
      },
      {
        path: '/control',
        redirect: to => ({ name: 'today', query: to.query }),
        meta: { title: '控制台兼容入口' },
      },
      {
        path: '/access-denied',
        name: 'access-denied',
        component: () => import('../pages/AccessDeniedPage.vue'),
        meta: { mode: 'control', section: 'access-denied', title: '无权访问' },
      },
      {
        path: '/:pathMatch(.*)*',
        name: 'not-found',
        component: () => import('../pages/NotFoundPage.vue'),
        meta: { title: '页面不存在' },
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
      return { name: 'access-denied', query: { requiredPermission, from: to.name ? String(to.name) : 'requested-route' } }
    }
    return true
  })

  router.afterEach(to => {
    if (typeof document !== 'undefined' && typeof to.meta.title === 'string') document.title = `${to.meta.title} | CrewScope`
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
