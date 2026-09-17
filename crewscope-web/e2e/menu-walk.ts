import type { Page, Route } from '@playwright/test'
import { authenticatedSession } from './auth-session'

/**
 * 「逐个菜单走一遍」的共用夹具。
 *
 * 抽出来是因为有两条契约要走同一组菜单：窄屏命中区（m9-mobile-reach）与暗色主题
 * （m9-dark-theme）。两者都必须**逐个**而不是抽样——M9 的出口结果是「每个菜单都达到好的
 * 产品体验」，抽样验不出「第 11 个菜单的工具条在 390px 下溢出」或者「第 7 个菜单有一块
 * 白底面板」这种缺陷，而那恰好是最容易漏的一类。复制两份的话，下一个新菜单会被加进一份、
 * 漏掉另一份。
 */

export const ids = {
  organization: '00000000-0000-0000-0000-000000000001',
  team: '00000000-0000-0000-0000-000000000201',
  project: '00000000-0000-0000-0000-000000000401',
  workspace: '00000000-0000-0000-0000-000000000501',
  principal: '00000000-0000-0000-0000-000000000101',
}

export const MENUS = [
  { path: '/today', name: '我的工作台' },
  { path: '/work', name: '团队工作项' },
  { path: '/conversation', name: '对话' },
  { path: '/search', name: '搜索' },
  { path: '/setup', name: '接入' },
  { path: '/activity', name: '动态' },
  { path: '/inbox', name: 'Inbox' },
  { path: '/team/observer', name: 'Team Observer' },
  { path: '/operations', name: '运维' },
  { path: '/audit', name: '审计' },
  { path: '/team/members', name: '成员' },
  { path: '/settings/repositories', name: '仓库设置' },
  { path: '/settings/agents', name: 'Agent 设置' },
  { path: '/settings/models', name: '模型设置' },
]

/**
 * 逐个菜单需要逐个菜单的数据，但这两条契约验的是呈现而不是数据，所以未命中的 GET 统一返回
 * 一个**形状合法的空结果**：分页列表给 `{ items: [], nextCursor: null }`，其余给 `{}`。
 * 页面因此渲染各自的空态——空态一样带工具条、筛选器与主操作按钮，正是要量的那些东西。
 * 写操作仍然 404：这两条不应该因为某个页面在加载时误发了写请求而变绿。
 */
export async function mockApi(page: Page): Promise<void> {
  await page.route(/\/api\/v1\//, async route => {
    const request = route.request()
    const path = new URL(request.url()).pathname
    if (request.method() !== 'GET') return notFound(route)
    if (path === '/api/v1/auth/session') return json(route, authenticatedSession(ids.organization, ids.principal, ids.team))
    if (path.endsWith('/teams')) return json(route, [team()])
    if (path.endsWith(`/${ids.team}/work-projects`)) return json(route, { items: [project()], nextCursor: null })
    if (path.endsWith('/members')) return json(route, [])
    if (path.endsWith('/work-desk')) return json(route, workDesk())
    return json(route, /\/(items|events|list|search|history|revisions)$|s$/.test(path) ? { items: [], nextCursor: null } : {})
  })
}

function team() {
  return {
    id: ids.team, organizationId: ids.organization, name: 'Platform Engineering', status: 'ACTIVE',
    initializationStatus: 'READY', ownerMemberId: 'member-1', defaultWorkspaceId: ids.workspace, version: 1,
  }
}

function project() {
  return {
    id: ids.project, organizationId: ids.organization, teamId: ids.team, workspaceId: ids.workspace,
    key: 'CRW', name: 'CrewScope', status: 'ACTIVE', version: 1,
    createdAt: '2026-08-27T07:00:00Z', createdByPrincipalId: ids.principal,
    updatedAt: '2026-08-27T07:00:00Z', updatedByPrincipalId: ids.principal,
  }
}

function workDesk() {
  return {
    organizationId: ids.organization, teamId: ids.team, projectId: null, generatedAt: '2026-09-13T10:00:00Z',
    sections: [
      { key: 'HUMAN_GATE', title: '等我决策', priority: 1, total: 0, truncated: false, items: [] },
      { key: 'REVIEW', title: '待我 Review', priority: 2, total: 0, truncated: false, items: [] },
      { key: 'BLOCKED', title: '被我阻塞', priority: 3, total: 0, truncated: false, items: [] },
      { key: 'WORK_ITEM', title: '我的工作项', priority: 4, total: 0, truncated: false, items: [] },
      { key: 'TASK_EXECUTION', title: '进行中的执行', priority: 5, total: 0, truncated: false, items: [] },
      { key: 'INBOX', title: '未读 Inbox', priority: 6, total: 0, truncated: false, items: [] },
    ],
  }
}

function json(route: Route, value: unknown) {
  return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(value) })
}

function notFound(route: Route) {
  return route.fulfill({
    status: 404, contentType: 'application/json',
    body: JSON.stringify({ code: 'not_found', message: 'Not found', correlationId: 'corr-404', retryable: false, currentVersion: null, details: {} }),
  })
}
