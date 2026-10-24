import { expect, test, type Page, type Route } from '@playwright/test'
import { authenticatedSession } from './auth-session'

/**
 * M13f 布局几何审查（可复跑）：对全部 authenticated 路由做 DOM 盒探针计算，逐页回答
 * 「位置是否合理、是否展示完整、是否变形」。探针只做几何判定，不做像素 diff——
 * 视觉基线只能发现「相对基线的变化」，基线自身固化的变形（横向溢出、无意截断）只有
 * 运行时盒计算能抓（判例：e2e-stale-dist-and-baseline-locks-defects）。
 *
 * 探针清单（误报控制见 probeLayout 注释）：
 * - page-overflow-x      页面级横向溢出（变形铁证）
 * - element-out-of-view  可见元素越出视口左右边界（最外层归并，跳过横滚容器内部）
 * - text-clipped         overflow hidden 容器裁掉了真实文本宿主（无意截断）
 * - fixed-out-of-view    fixed/sticky 定位元素越出视口
 *
 * 判例驱动的排除规则：
 * - `.touch-target::after` 的 44px 命中区（absolute 伪元素）与 aria-hidden 装饰 bleed
 *   都会计入宿主 scrollWidth，但无文本损害——text-clipped 只看「直接持有文本节点的
 *   元素」是否真的越出裁剪盒（participant-panel 折叠条、auth-brand-panel 判例）。
 * - ellipsis 是设计截断手段（scope-switcher 团队名、列表标题），不属于本探针的
 *   「无意截断」——除非宿主已声明 clip/hidden 却没有 ellipsis 语义。
 *
 * 数据策略：inbox/work/activity/conversation 带最小数据集（结构抄 m13b-f09），其余端点
 * 404 兜底——页面渲染空态。空态也是交付面；内容态暴露的拥挤问题由第二层补数据复核。
 * identity 三页（login/register/invite）由 m7-identity-experience-spike 的视觉基线背书，
 * 不在本 spec 范围。
 */
const ids = {
  organization: '00000000-4000-4000-8000-000000000001',
  team: '00000000-4000-4000-8000-000000000201',
  project: '00000000-4000-4000-8000-000000000401',
  workspace: '00000000-4000-4000-8000-000000000501',
  principal: '00000000-4000-4000-8000-000000000101',
  member: '00000000-4000-4000-8000-000000000301',
  personalAgent: '00000000-4000-4000-8000-000000000105',
  workItem: '00000000-4000-4000-8000-000000000601',
  conversation: '00000000-0000-0000-0000-000000001101',
  ownership: '00000000-4000-4000-8000-000000000901',
  source: '00000000-4000-4000-8000-000000000951',
}

function conversation(id: string, title: string) {
  return { id, organizationId: ids.organization, teamId: ids.team, workspaceId: ids.workspace, ownerMemberId: ids.member, ownerPrincipalId: ids.principal, personalAgentPrincipalId: ids.personalAgent, title, visibility: 'PRIVATE', status: 'ACTIVE', lastMessageSequence: 4, unreadCount: 0, version: 0, createdAt: '2026-08-08T01:00:00Z', updatedAt: '2026-08-08T03:00:00Z' }
}

function participant(principalId: string, role: string) {
  const personalAgent = role === 'AGENT'
  return {
    id: crypto.randomUUID(), conversationId: ids.conversation, principalId, teamMemberId: personalAgent ? null : ids.member,
    displayName: personalAgent ? '张凯旋 · Personal Agent' : '张凯旋',
    principalType: personalAgent ? 'PERSONAL_AGENT' : 'USER',
    ownerPrincipalId: personalAgent ? ids.principal : null, ownerDisplayName: personalAgent ? '张凯旋' : null,
    role, status: 'ACTIVE', joinedByPrincipalId: ids.principal,
    joinedAt: '2026-08-08T01:00:00Z', leftAt: null, version: 0,
  }
}

function conversationMessage(id: string, sequence: number, type: string, authorPrincipalId: string | null, content: string) {
  return { id, conversationId: ids.conversation, sequence, participantId: type === 'SYSTEM_NOTICE' ? null : crypto.randomUUID(), authorPrincipalId, content, createdAt: `2026-08-08T01:1${sequence}:00Z` }
}

function inbox(inboxItemId: string, itemType: string, priority: string, sourceType: string, sourceRevision: number, deadline: string | null) {
  return {
    inboxItemId, itemType, priority, deadline, openedAt: `2026-08-27T0${sourceRevision}:00:00Z`,
    sourceStatus: 'OPEN', closeReason: null, closedAt: null,
    dispositionStatus: 'UNREAD', dispositionVersion: 0, etag: '"0"',
    source: { type: sourceType, id: ids.source, revision: sourceRevision },
  }
}

function fulfillJson(route: Route, value: unknown) {
  return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(value) })
}

function fulfillSse(route: Route, events: unknown[]) {
  const body = events.map((event, index) => {
    const envelope = event as { eventId?: string, eventType?: string }
    return `id:${envelope.eventId ?? `event-${index}`}\nevent:${envelope.eventType ?? 'message'}\ndata:${JSON.stringify(event)}\n\n`
  }).join('')
  return route.fulfill({ status: 200, contentType: 'text/event-stream', headers: { 'Cache-Control': 'no-store' }, body })
}

const conversations = [conversation(ids.conversation, '规划 GitHub Provider 接入')]
const messages = [
  conversationMessage('00000000-0000-0000-0000-000000001301', 1, 'USER_MESSAGE', ids.principal, '**目标**：规划 GitHub Provider 接入。'),
  conversationMessage('00000000-0000-0000-0000-000000001302', 2, 'AGENT_MESSAGE', ids.personalAgent, '已收到。我会先梳理 `Connection`、权限和审计边界。'),
  conversationMessage('00000000-0000-0000-0000-000000001303', 3, 'SYSTEM_NOTICE', null, 'Conversation 已切换为真实消息模式。'),
  conversationMessage('00000000-0000-0000-0000-000000001304', 4, 'USER_MESSAGE', ids.principal, '请保留团队协作与最小权限原则。'),
]
const inboxItems = [
  inbox(ids.ownership, 'OWNERSHIP', 'URGENT', 'RESPONSIBILITY_ASSIGNMENT', 3, '2026-08-27T09:00:00Z'),
  inbox('00000000-4000-4000-8000-000000000902', 'OWNERSHIP', 'NORMAL', 'RESPONSIBILITY_ASSIGNMENT', 2, null),
]
const workItems = [
  { id: ids.workItem, organizationId: ids.organization, teamId: ids.team, workspaceId: ids.workspace, projectId: ids.project, key: 'CRW-01', type: 'TASK', title: '结算看板主链路', description: '结算看板主链路的协作说明', status: 'IN_PROGRESS', priority: 'HIGH', labels: ['team-work'], dueAt: null, source: 'CREWSCOPE', sourceReference: null, version: 0, createdAt: '2026-09-01T01:00:00Z', createdByPrincipalId: ids.principal, updatedAt: '2026-09-02T01:00:00Z', updatedByPrincipalId: ids.principal, availableActions: [] },
  { id: '00000000-4000-4000-8000-000000000602', organizationId: ids.organization, teamId: ids.team, workspaceId: ids.workspace, projectId: ids.project, key: 'CRW-02', type: 'FEATURE', title: '执行看板迁移', description: '执行看板迁移的协作说明', status: 'OPEN', priority: 'MEDIUM', labels: [], dueAt: null, source: 'CREWSCOPE', sourceReference: null, version: 0, createdAt: '2026-09-01T01:00:00Z', createdByPrincipalId: ids.principal, updatedAt: '2026-09-02T01:00:00Z', updatedByPrincipalId: ids.principal, availableActions: [] },
]
const activityEvents = [
  {
    eventId: 'event-review', domainEventId: 'event-review-domain', teamSequence: 4, eventType: 'REVIEW_APPROVED',
    category: 'REVIEW', visibility: 'TEAM', subject: { type: 'REVIEW_REQUEST', id: 'review-1' },
    actor: { type: 'MEMBER', principalId: ids.principal }, references: [{ type: 'WORK_ITEM', id: ids.workItem }],
    occurredAt: '2026-08-27T08:04:00Z', payload: { schemaName: 'activity-summary', schemaVersion: 1, values: { outcome: 'APPROVED' } },
  },
]

async function mockAuditApi(page: Page): Promise<void> {
  // SSE 保活：挂起 handler 保持连接（有限空流会退避重连，重连波及布局稳定性）。
  await page.route(/\/events(?:\?.*)?$/, (route: Route) => {
    if (route.request().headers().accept?.includes('text/event-stream')) {
      return new Promise<void>(() => {})
    }
    return route.fallback()
  })
  await page.route(/\/api\/v1\//, async route => {
    const request = route.request()
    const path = new URL(request.url()).pathname
    if (request.method() === 'GET' && path === '/api/v1/auth/session') return fulfillJson(route, authenticatedSession(ids.organization, ids.principal, ids.team))
    if (request.method() === 'GET' && path.endsWith('/teams')) {
      return fulfillJson(route, [{ id: ids.team, organizationId: ids.organization, name: 'Platform Engineering', status: 'ACTIVE', initializationStatus: 'READY', ownerMemberId: ids.member, defaultWorkspaceId: ids.workspace, version: 1 }])
    }
    if (request.method() === 'GET' && path.endsWith(`/${ids.team}/work-projects`)) {
      return fulfillJson(route, { items: [{ id: ids.project, organizationId: ids.organization, teamId: ids.team, workspaceId: ids.workspace, key: 'CRW', name: 'CrewScope', status: 'ACTIVE', version: 1, createdAt: '2026-08-27T07:00:00Z', createdByPrincipalId: ids.principal, updatedAt: '2026-08-27T07:00:00Z', updatedByPrincipalId: ids.principal }], nextCursor: null })
    }
    if (request.method() === 'GET' && path.endsWith('/members')) {
      return fulfillJson(route, [{ id: ids.member, userPrincipalId: ids.principal, displayName: 'Zhang Kaixuan', status: 'ACTIVE', joinMethod: 'CREATED_WITH_TEAM', joinedAt: '2026-08-08T01:00:00Z', version: 0 }])
    }
    if (request.method() === 'GET' && path.endsWith('/inbox/counts')) {
      return fulfillJson(route, { total: 2, unread: 2, byType: {} })
    }
    if (request.method() === 'GET' && path.endsWith('/inbox')) {
      return fulfillJson(route, { items: inboxItems, nextCursor: null })
    }
    if (request.method() === 'GET' && path.endsWith('/work-items')) {
      return fulfillJson(route, { items: workItems, nextCursor: null })
    }
    if (request.method() === 'GET' && path.endsWith('/activity/snapshot')) {
      return fulfillJson(route, { items: activityEvents, hasMore: false, nextCursor: null, snapshotCursor: 'snapshot-cursor' })
    }
    if (request.method() === 'GET' && path.endsWith('/activity/events')) {
      return fulfillSse(route, activityEvents)
    }
    const inboxDetail = path.match(/\/inbox\/([^/]+)$/)
    if (request.method() === 'GET' && inboxDetail) return fulfillJson(route, inboxItems.find(item => item.inboxItemId === inboxDetail[1]))
    if (request.method() === 'GET' && path.endsWith('/conversations')) {
      return fulfillJson(route, { items: conversations, nextCursor: null })
    }
    const detail = path.match(/\/conversations\/([^/]+)$/)
    if (request.method() === 'GET' && detail) {
      return fulfillJson(route, { conversation: conversations[0], participants: [participant(ids.principal, 'OWNER'), participant(ids.personalAgent, 'AGENT')] })
    }
    const messageList = path.match(/\/conversations\/([^/]+)\/messages$/)
    if (request.method() === 'GET' && messageList) {
      return fulfillJson(route, { items: [...messages].sort((left, right) => right.sequence - left.sequence), nextCursor: null })
    }
    const conversationTasks = path.match(/\/conversations\/([^/]+)\/tasks$/)
    if (request.method() === 'GET' && conversationTasks) return fulfillJson(route, { items: [], nextCursor: null })
    const conversationWorkItems = path.match(/\/conversations\/([^/]+)\/work-items$/)
    if (request.method() === 'GET' && conversationWorkItems) return fulfillJson(route, [])
    return route.fulfill({ status: 404, contentType: 'application/json', body: JSON.stringify({ code: 'not_found', message: 'not_found', correlationId: crypto.randomUUID(), retryable: false, details: {} }) })
  })
}

/** 审查对象路由：带 team/project 坐标的 authenticated 页面全集。 */
const routes: Array<{ name: string, path: string }> = [
  { name: 'today', path: `/today?team=${ids.team}&project=${ids.project}` },
  { name: 'conversation', path: `/conversation?team=${ids.team}&project=${ids.project}&conversation=${ids.conversation}` },
  { name: 'search', path: `/search?team=${ids.team}&project=${ids.project}&q=Provider` },
  { name: 'setup', path: `/setup?from=work` },
  { name: 'work', path: `/work?team=${ids.team}&project=${ids.project}` },
  { name: 'activity', path: `/activity?team=${ids.team}&project=${ids.project}` },
  { name: 'inbox', path: `/inbox?team=${ids.team}&project=${ids.project}` },
  { name: 'team-observer', path: `/team/observer?team=${ids.team}&project=${ids.project}` },
  { name: 'knowledge', path: `/knowledge?team=${ids.team}&project=${ids.project}` },
  { name: 'knowledge-index', path: `/knowledge/index?team=${ids.team}&project=${ids.project}` },
  { name: 'skills', path: `/skills?team=${ids.team}&project=${ids.project}` },
  { name: 'observability', path: `/observability?team=${ids.team}&project=${ids.project}` },
  { name: 'operations', path: `/operations?team=${ids.team}&project=${ids.project}` },
  { name: 'audit', path: `/audit?team=${ids.team}&project=${ids.project}` },
  { name: 'team-members', path: `/team/members?team=${ids.team}&project=${ids.project}` },
  { name: 'settings-organization', path: `/settings/organization?team=${ids.team}&project=${ids.project}` },
  { name: 'settings-quota', path: `/settings/quota?team=${ids.team}&project=${ids.project}` },
  { name: 'settings-repositories', path: `/settings/repositories?team=${ids.team}&project=${ids.project}` },
  { name: 'settings-agents', path: `/settings/agents?team=${ids.team}&project=${ids.project}` },
  { name: 'settings-models', path: `/settings/models?team=${ids.team}&project=${ids.project}` },
  { name: 'settings-lark', path: `/settings/integrations/lark?team=${ids.team}&project=${ids.project}` },
  { name: 'settings-github', path: `/settings/integrations/github?team=${ids.team}&project=${ids.project}` },
  { name: 'control', path: `/control?team=${ids.team}&project=${ids.project}` },
  { name: 'account', path: `/account` },
  { name: 'onboarding', path: `/onboarding` },
  { name: 'access-denied', path: `/access-denied` },
  { name: 'not-found', path: `/no-such-route` },
]

type LayoutIssue = { route: string, kind: string, detail: string }

/**
 * 盒探针。误报控制：
 * - element-out-of-view 只报最外层越界元素（祖先已报则子孙跳过）；跳过横向滚动容器
 *   内部（有意横滚）与视觉隐藏（sr-only / aria-hidden / display:none）；
 * - text-clipped 只在「直接持有非空文本节点的后代元素」真的越出 overflow hidden 容器
 *   的裁剪盒时报——伪元素命中区（touch-target::after）、aria-hidden 装饰 bleed 会让
 *   scrollWidth 虚高，但文本宿主仍在盒内，不构成展示损害；
 * - fixed-out-of-view 报 fixed/sticky 越界（贴边吸附由 right<=vw 保证）。
 */
async function probeLayout(page: Page, routeName: string): Promise<LayoutIssue[]> {
  return page.evaluate((routeNameArg: string) => {
    const issues: Array<{ route: string, kind: string, detail: string }> = []
    const vw = document.documentElement.clientWidth
    const describe = (el: HTMLElement) => {
      const cls = (el.getAttribute('class') ?? '').trim().split(/\s+/)[0] ?? ''
      return `${el.tagName.toLowerCase()}${cls ? `.${cls}` : ''}`
    }
    const overflowX = document.documentElement.scrollWidth - document.documentElement.clientWidth
    if (overflowX > 1) issues.push({ route: routeNameArg, kind: 'page-overflow-x', detail: `document ${overflowX}px wider than viewport` })

    const all = Array.from(document.querySelectorAll<HTMLElement>('body *'))
    const hidden = (el: HTMLElement) => {
      const st = getComputedStyle(el)
      if (st.display === 'none' || st.visibility === 'hidden' || Number(st.opacity) === 0) return true
      if (el.getAttribute('aria-hidden') === 'true') return true
      const clip = el.getBoundingClientRect()
      return clip.width < 4 || clip.height < 4
    }
    const withinHorizontalScroller = (el: HTMLElement) => {
      for (let cur = el.parentElement; cur && cur !== document.body; cur = cur.parentElement) {
        const st = getComputedStyle(cur)
        if (st.overflowX === 'auto' || st.overflowX === 'scroll') return true
      }
      return false
    }
    const reported = new Set<HTMLElement>()
    const hasReportedAncestor = (el: HTMLElement) => {
      for (let cur = el.parentElement; cur; cur = cur.parentElement) if (reported.has(cur)) return true
      return false
    }
    for (const el of all) {
      if (hidden(el) || hasReportedAncestor(el)) continue
      const st = getComputedStyle(el)
      const rect = el.getBoundingClientRect()
      if (st.position === 'fixed' || st.position === 'sticky') {
        if (rect.right > vw + 1.5 || rect.left < -1.5) {
          issues.push({ route: routeNameArg, kind: 'fixed-out-of-view', detail: `${describe(el)} ${st.position} left=${rect.left.toFixed(0)} right=${rect.right.toFixed(0)} vw=${vw}` })
          reported.add(el)
        }
        continue
      }
      if (withinHorizontalScroller(el)) continue
      if (rect.right > vw + 1.5 || rect.left < -1.5) {
        issues.push({ route: routeNameArg, kind: 'element-out-of-view', detail: `${describe(el)} left=${rect.left.toFixed(0)} right=${rect.right.toFixed(0)} vw=${vw}` })
        reported.add(el)
        continue
      }
    }
    for (const el of all) {
      if (hidden(el)) continue
      const st = getComputedStyle(el)
      if (st.overflowX !== 'hidden') continue
      if (el.clientWidth <= 8 || el.scrollWidth <= el.clientWidth + 2) continue
      const clipRight = el.getBoundingClientRect().right
      // 文本宿主 = 直接持有非空文本节点的元素；它们全在裁剪盒内时，溢出源只能是
      // 伪元素命中区或装饰（aria-hidden / 绝对定位 bleed），不构成展示损害。
      const textHosts = Array.from(el.querySelectorAll<HTMLElement>('*')).filter(child => {
        if (hidden(child)) return false
        return Array.from(child.childNodes).some(node => node.nodeType === Node.TEXT_NODE && (node.textContent ?? '').trim().length > 0)
      })
      const worst = textHosts.reduce((maxRight, host) => Math.max(maxRight, host.getBoundingClientRect().right), 0)
      if (worst > clipRight + 1.5) {
        const sample = textHosts.find(host => host.getBoundingClientRect().right > clipRight + 1.5)
        issues.push({ route: routeNameArg, kind: 'text-clipped', detail: `${describe(el)} clips text of ${sample ? describe(sample) : '?'} by ${(worst - clipRight).toFixed(0)}px` })
      }
    }
    return issues
  }, routeName)
}

test.beforeEach(async ({ page }) => {
  await page.clock.setFixedTime(new Date('2026-08-27T08:30:00Z'))
  await mockAuditApi(page)
})

for (const route of routes) {
  test(`layout audit: ${route.name}`, async ({ page }, testInfo) => {
    test.skip(testInfo.project.name !== 'desktop-chromium' && testInfo.project.name !== 'narrow-chromium', 'audit targets the two reference viewports')
    await page.goto(route.path)
    // 空态/内容态的骨架与 store 同步稳定：与 f09 一致用固定等待，审查不引入 race。
    await page.waitForTimeout(900)
    const issues = await probeLayout(page, `${route.name}@${testInfo.project.name}`)
    if (issues.length > 0) {
      console.log(`[m13f-audit] ${route.name}@${testInfo.project.name} -> ${issues.length} issue(s)`)
      for (const issue of issues) console.log(`[m13f-audit]   ${issue.kind}: ${issue.detail}`)
    }
    // 审查报告期先收口为零违规基准（发现的问题修复后此断言即长期门禁）。
    expect(issues, issues.map(issue => `${issue.kind}: ${issue.detail}`).join('\n')).toEqual([])
  })
}
