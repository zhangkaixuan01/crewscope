import { CalendarDays, Gauge, Inbox, LayoutDashboard, MessageSquare, Search, Settings2, UsersRound, Bot, KeyRound, GitFork, ScanSearch, ShieldCheck } from '@lucide/vue'
import type { Router } from 'vue-router'
import type { AuthenticatedPrincipal } from './auth'
import { permissions } from './auth'
import type { ActionDefinition, ActionRegistry } from './actionRegistry'

/** Navigation and safe entry actions available in every authenticated workspace. */
export function registerDefaultActions(registry: ActionRegistry, router: Router, principal: AuthenticatedPrincipal): () => void {
  const definitions: ActionDefinition[] = [
    action('nav.today', '打开今日', '查看需要你行动的工作摘要', '导航', 'g t', CalendarDays, 'today', permissions.scopeRead),
    action('nav.conversation', '打开对话', '进入个人与团队对话工作区', '导航', 'g c', MessageSquare, 'conversation', permissions.conversationUse),
    action('nav.work', '打开工作项', '管理当前 WorkProject 的工作项', '导航', 'g w', LayoutDashboard, 'work', permissions.workRead),
    action('nav.inbox', '打开 Inbox', '查看待处理的通知与决策', '导航', 'g i', Inbox, 'inbox', permissions.scopeRead),
    action('nav.team-observer', '打开团队观测', '查看团队执行摘要', '导航', 'g o', ScanSearch, 'team-observer', permissions.scopeRead),
    action('nav.members', '打开团队成员', '查看 Team Membership', '导航', 'g m', UsersRound, 'team-members', permissions.teamMembersRead),
    action('nav.audit', '打开审计中心', '查看可追溯审计事实', '导航', 'g a', ShieldCheck, 'audit', permissions.auditRead),
    action('nav.setup', '打开配置中心', '查看 Team 就绪状态', '导航', 'g s', Settings2, 'setup', permissions.scopeRead),
    action('nav.agents', '打开 Agent 中心', '管理 Personal Agent 与 Team Agent', '导航', 'g e', Bot, 'agent-settings', permissions.scopeRead),
    action('nav.models', '打开模型与凭证', '管理模型连接与凭证', '导航', 'g d', KeyRound, 'model-settings', permissions.scopeRead),
    action('nav.repositories', '打开仓库设置', '管理受管仓库与绑定', '导航', 'g r', GitFork, 'repository-settings', permissions.repositoriesManage),
    action('nav.operations', '打开运行与发布', '查看执行、交付与恢复状态', '导航', 'g x', Gauge, 'operations', permissions.scopeRead),
    action('nav.search', '打开统一搜索', '搜索工作、成员或 Agent', '导航', 'g f', Search, 'search', permissions.scopeRead),
    action('create.conversation', '发起对话', '从当前范围开始新的对话', '创建', 'c c', MessageSquare, 'conversation', permissions.conversationUse),
    action('create.work', '创建工作项', '打开 Work 中的创建入口', '创建', 'c w', LayoutDashboard, 'work', permissions.workCreate),
    action('view.today', '切换到今日视图', '查看个人工作摘要', '视图', 'v t', CalendarDays, 'today', permissions.scopeRead),
    action('view.conversation', '切换到对话视图', '查看对话与 Agent 参与者', '视图', 'v c', MessageSquare, 'conversation', permissions.conversationUse),
    action('view.work', '切换到工作视图', '查看 WorkProject 工作项', '视图', 'v w', LayoutDashboard, 'work', permissions.workRead),
    action('execution.operations', '打开执行控制', '查看运行与发布状态', '执行控制', 'x o', Gauge, 'operations', permissions.scopeRead),
  ]
  const cleanups = definitions.map(definition => registry.register(definition))
  return () => cleanups.forEach(cleanup => cleanup())

  function action(id: string, label: string, description: string, group: ActionDefinition['group'], shortcut: string, icon: typeof CalendarDays, routeName: string, requiredPermission: string): ActionDefinition {
    return {
      id, label, description, group, shortcut, icon, requiredPermission,
      keywords: [label, description],
      execute: async ({ router: currentRouter, route }) => { await currentRouter.push({ name: routeName, query: route.query }) },
      visible: context => context.principal?.id === principal.id,
    }
  }
}
