import { BookOpen, CalendarDays, ChartColumn, Gauge, Inbox, Layers, LayoutDashboard, ListTree, MessageSquare, Network, Search, Settings2, UsersRound, Bot, KeyRound, GitFork, ScanSearch, ShieldCheck } from '@lucide/vue'
import type { Router } from 'vue-router'
import type { AuthenticatedPrincipal } from './auth'
import { permissions } from './auth'
import type { ActionDefinition, ActionRegistry } from './actionRegistry'

/** Navigation and safe entry actions available in every authenticated workspace. */
export function registerDefaultActions(registry: ActionRegistry, router: Router, principal: AuthenticatedPrincipal): () => void {
  const definitions: ActionDefinition[] = [
    action('nav.today', '打开今日', '查看需要你行动的工作摘要', '导航', 'g t', CalendarDays, 'today', permissions.scopeRead),
    action('nav.conversation', '打开对话', '', '导航', 'g c', MessageSquare, 'conversation', permissions.conversationUse),
    action('nav.work', '打开工作项', '', '导航', 'g w', LayoutDashboard, 'work', permissions.workRead),
    action('nav.inbox', '打开 Inbox', '查看待处理的通知与决策', '导航', 'g i', Inbox, 'inbox', permissions.scopeRead),
    action('nav.team-observer', '打开团队观测', '查看团队执行摘要', '导航', 'g o', ScanSearch, 'team-observer', permissions.scopeRead),
    // M13b-F08（R50）：命令面板补齐知识闭环四页——与侧栏同词表同图标，快捷键 g k/n/l/q 未占用。
    action('nav.knowledge-base', '打开知识库', '浏览注入的知识条目与版本', '导航', 'g k', BookOpen, 'knowledge-base', permissions.scopeRead),
    action('nav.knowledge-index', '打开索引作业', '', '导航', 'g n', Layers, 'knowledge-index', permissions.scopeRead),
    action('nav.skill-catalog', '打开 Skill 目录', '', '导航', 'g l', ListTree, 'skill-catalog', permissions.scopeRead),
    action('nav.observability', '打开成本质量', '查看用量、预算与质量观测', '导航', 'g q', ChartColumn, 'observability', permissions.scopeRead),
    action('nav.members', '打开团队成员', '', '导航', 'g m', UsersRound, 'team-members', permissions.teamMembersRead),
    action('nav.audit', '打开审计中心', '', '导航', 'g a', ShieldCheck, 'audit', permissions.auditRead),
    action('nav.setup', '打开配置中心', '查看 Team 就绪状态', '导航', 'g s', Settings2, 'setup', permissions.scopeRead),
    action('nav.agents', '打开 Agent 中心', '管理 Personal Agent 与 Team Agent', '导航', 'g e', Bot, 'agent-settings', permissions.scopeRead),
    action('nav.models', '打开模型与凭证', '', '导航', 'g d', KeyRound, 'model-settings', permissions.scopeRead),
    action('nav.repositories', '打开仓库设置', '管理受管仓库与绑定', '导航', 'g r', GitFork, 'repository-settings', permissions.repositoriesManage),
    action('nav.operations', '打开运行与发布', '查看执行、交付与恢复状态', '导航', 'g x', Gauge, 'operations', permissions.scopeRead),
    action('nav.search', '打开统一搜索', '搜索工作、成员或 Agent', '导航', 'g f', Search, 'search', permissions.scopeRead),
    action('create.conversation', '发起对话', '', '创建', 'c c', MessageSquare, 'conversation', permissions.conversationUse),
    action('create.work', '创建工作项', '', '创建', 'c w', LayoutDashboard, 'work', permissions.workCreate),
    // M13c-C01（S2）：view.today/conversation/work 与 nav.* 同路由同语义，删除（v t/v c/v w 序列
    // 随之退役，g 系快捷键在 nav.* 不变）；work-graph 是唯一带 view=graph 直达的入口（M11-F02），
    // 保留条目、去复述 description。
    action('view.work-graph', '切换到依赖图视图', '', '视图', 'v g', Network, 'work', permissions.workRead, { view: 'graph' }),
    action('execution.operations', '打开执行控制', '', '执行控制', 'x o', Gauge, 'operations', permissions.scopeRead),
  ]
  const cleanups = definitions.map(definition => registry.register(definition))
  return () => cleanups.forEach(cleanup => cleanup())

  function action(id: string, label: string, description: string, group: ActionDefinition['group'], shortcut: string, icon: typeof CalendarDays, routeName: string, requiredPermission: string, extraQuery: Record<string, string> = {}): ActionDefinition {
    return {
      id, label, description, group, shortcut, icon, requiredPermission,
      keywords: [label, ...(description ? [description] : [])],
      execute: async ({ router: currentRouter, route }) => {
        await currentRouter.push({ name: routeName, query: { team: route.query.team,
          project: routeName === 'work' ? route.query.project : undefined,
          create: id.startsWith('create.') ? '1' : undefined,
          ...extraQuery } })
      },
      visible: context => context.principal?.id === principal.id,
    }
  }
}
