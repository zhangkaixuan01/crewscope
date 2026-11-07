import type { Page, Route } from '@playwright/test'
import { authenticatedSession } from './auth-session'
import { workItemStateMachine } from '../src/api/generated/state-machines'

/**
 * 布局几何审查共享层（M13f 建立、M13g 扩展）：mock 数据面 + 盒探针。探针只做几何判定，
 * 不做像素 diff——视觉基线只能发现「相对基线的变化」，基线自身固化的变形（横向溢出、
 * 无意截断）只有运行时盒计算能抓（判例：e2e-stale-dist-and-baseline-locks-defects）。
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
 * 数据策略：inbox/work/activity/conversation 带最小数据集（结构抄 m13b-f09）。M14 逐页评审
 * 扩到内容态：work-desk / settings 三页 / observability / account 等 13 端点补齐（fixture 结构
 * 抄 app-shell.spec.ts 同名替身），让 9 核心页以真实数据渲染；未覆盖端点仍 404 兜底，
 * AUDIT_TRACE=1 时在控制台打印漏网路径。identity 三页（login/register/invite）由
 * m7-identity-experience-spike 的视觉基线背书，不在审计范围。
 */

export const ids = {
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
  task: '00000000-4000-4000-8000-000000000701',
  taskExecution: '00000000-4000-4000-8000-000000000801',
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

function conversationMessage(id: string, sequence: number, type: string, authorPrincipalId: string | null, content: string, createdAt: string) {
  return { id, conversationId: ids.conversation, sequence, participantId: type === 'SYSTEM_NOTICE' ? null : crypto.randomUUID(), authorPrincipalId, content, createdAt }
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
  conversationMessage('00000000-0000-0000-0000-000000001301', 1, 'USER_MESSAGE', ids.principal, '**目标**：规划 GitHub Provider 接入。', '2026-08-26T01:10:00Z'),
  conversationMessage('00000000-0000-0000-0000-000000001302', 2, 'AGENT_MESSAGE', ids.personalAgent, '已收到。我会先梳理 `Connection`、权限和审计边界。', '2026-08-26T23:05:00Z'),
  conversationMessage('00000000-0000-0000-0000-000000001303', 3, 'SYSTEM_NOTICE', null, 'Conversation 已切换为真实消息模式。', '2026-08-27T07:40:00Z'),
  conversationMessage('00000000-0000-0000-0000-000000001304', 4, 'USER_MESSAGE', ids.principal, '请保留团队协作与最小权限原则。', '2026-08-27T08:10:00Z'),
]
const inboxItems = [
  inbox(ids.ownership, 'OWNERSHIP', 'URGENT', 'RESPONSIBILITY_ASSIGNMENT', 3, '2026-08-27T09:00:00Z'),
  inbox('00000000-4000-4000-8000-000000000902', 'OWNERSHIP', 'NORMAL', 'RESPONSIBILITY_ASSIGNMENT', 2, null),
]
const workItems = [
  { id: ids.workItem, organizationId: ids.organization, teamId: ids.team, workspaceId: ids.workspace, projectId: ids.project, key: 'CRW-01', type: 'TASK', title: '结算看板主链路', description: '结算看板主链路的协作说明', status: 'IN_PROGRESS', priority: 'HIGH', labels: ['team-work'], dueAt: null, source: 'CREWSCOPE', sourceReference: null, version: 0, createdAt: '2026-09-01T01:00:00Z', createdByPrincipalId: ids.principal, updatedAt: '2026-09-02T01:00:00Z', updatedByPrincipalId: ids.principal, availableActions: [] },
  { id: '00000000-4000-4000-8000-000000000602', organizationId: ids.organization, teamId: ids.team, workspaceId: ids.workspace, projectId: ids.project, key: 'CRW-02', type: 'FEATURE', title: '执行看板迁移', description: '执行看板迁移的协作说明', status: 'READY', priority: 'MEDIUM', labels: [], dueAt: null, source: 'CREWSCOPE', sourceReference: null, version: 0, createdAt: '2026-09-01T01:00:00Z', updatedAt: '2026-09-02T01:00:00Z', updatedByPrincipalId: ids.principal, availableActions: [] },
]
const activityEvents = [
  {
    eventId: 'event-review', domainEventId: 'event-review-domain', teamSequence: 4, eventType: 'REVIEW_APPROVED',
    category: 'REVIEW', visibility: 'TEAM', subject: { type: 'REVIEW_REQUEST', id: 'review-1' },
    actor: { type: 'MEMBER', principalId: ids.principal }, references: [{ type: 'WORK_ITEM', id: ids.workItem }],
    occurredAt: '2026-08-27T08:04:00Z', payload: { schemaName: 'activity-summary', schemaVersion: 1, values: { outcome: 'APPROVED' } },
  },
]

// M14 逐页评审：内容态数据面。形状逐条对齐网关严格校验（work-desk 六段顺序/orgId 回显/
// ETag=version），fixture 结构抄 app-shell.spec.ts 同名替身——真值只有一份，替身不发明形状。

const WORK_DESK_CATALOG: Record<string, Record<string, { actionId: string, label: string, strength: string, reversible: boolean }>> = {
  READY: {
    IN_PROGRESS: { actionId: 'start', label: '开始执行', strength: 'PRIMARY', reversible: true },
    CANCELLED: { actionId: 'cancel', label: '取消工作项', strength: 'DANGER', reversible: false },
  },
  IN_PROGRESS: {
    IN_REVIEW: { actionId: 'submit-review', label: '提交评审', strength: 'PRIMARY', reversible: true },
    BLOCKED: { actionId: 'block', label: '标记阻塞', strength: 'SECONDARY', reversible: true },
    CANCELLED: { actionId: 'cancel', label: '取消工作项', strength: 'DANGER', reversible: false },
  },
  BLOCKED: {
    READY: { actionId: 'return-ready', label: '退回待开始', strength: 'SECONDARY', reversible: true },
    IN_PROGRESS: { actionId: 'resume-work', label: '继续执行', strength: 'PRIMARY', reversible: true },
    IN_REVIEW: { actionId: 'submit-review', label: '提交评审', strength: 'PRIMARY', reversible: true },
    CANCELLED: { actionId: 'cancel', label: '取消工作项', strength: 'DANGER', reversible: false },
  },
}

function workDeskTransitions(status: string) {
  const edges = workItemStateMachine.transitions as Record<string, readonly string[]>
  return [...(edges[status] ?? [])].sort().flatMap(target => {
    const metadata = WORK_DESK_CATALOG[status]?.[target]
    if (!metadata) return []
    return [{
      ...metadata, targetStatus: target,
      reversible: metadata.reversible && (edges[target] ?? []).includes(status),
      enabled: true, reason: null, reasonMessage: null, remedyLabel: null, remedyRoute: null,
    }]
  })
}

function workDesk() {
  const workItemRow = (objectId: string, title: string, status: string, role: string | null, needsAction: boolean, progress: number | null) => ({
    objectType: 'WORK_ITEM', objectId, projectId: ids.project, title, status, updatedAt: '2026-08-27T07:30:00Z',
    responsibilityRole: role, needsAction, urgency: needsAction ? 'HIGH' : 'NORMAL', progress,
    availableActions: workDeskTransitions(status),
    route: `/work?team=${ids.team}&project=${ids.project}&workItem=${objectId}`,
  })
  return {
    organizationId: ids.organization, teamId: ids.team, projectId: null, generatedAt: '2026-08-27T08:20:00Z',
    sections: [
      {
        key: 'HUMAN_GATE', title: '等我决策', priority: 1, total: 1, truncated: false,
        items: [{
          objectType: 'HUMAN_GATE', objectId: ids.taskExecution, projectId: ids.project, title: '确认分支推送范围',
          status: 'OPEN', updatedAt: '2026-08-27T07:40:00Z', responsibilityRole: 'REVIEWER', needsAction: true,
          urgency: 'URGENT', progress: null, availableActions: [],
          route: `/work?team=${ids.team}&project=${ids.project}&task=${ids.task}`,
        }],
      },
      {
        key: 'REVIEW', title: '待我 Review', priority: 2, total: 1, truncated: false,
        items: [{
          objectType: 'REVIEW_REQUEST', objectId: ids.workItem, projectId: ids.project, title: 'Provider 回调实现评审',
          status: 'IN_PROGRESS', updatedAt: '2026-08-27T07:20:00Z', responsibilityRole: 'REVIEWER', needsAction: true,
          urgency: 'NORMAL', progress: null, availableActions: [],
          route: `/work?team=${ids.team}&project=${ids.project}&workItem=${ids.workItem}`,
        }],
      },
      {
        key: 'BLOCKED', title: '被我阻塞', priority: 3, total: 1, truncated: false,
        items: [workItemRow('00000000-4000-4000-8000-000000000602', '执行看板迁移', 'BLOCKED', 'OWNER', true, 40)],
      },
      {
        key: 'WORK_ITEM', title: '我的工作项', priority: 4, total: 2, truncated: false,
        items: [
          workItemRow(ids.workItem, '结算看板主链路', 'IN_PROGRESS', 'EXECUTOR', false, 65),
          workItemRow('00000000-4000-4000-8000-000000000602', '执行看板迁移', 'BLOCKED', 'OWNER', true, 40),
        ],
      },
      {
        key: 'TASK_EXECUTION', title: '进行中的执行', priority: 5, total: 1, truncated: false,
        items: [{
          objectType: 'TASK_EXECUTION', objectId: ids.taskExecution, projectId: ids.project, title: '接入 Provider 回调',
          status: 'RUNNING', updatedAt: '2026-08-27T07:50:00Z', responsibilityRole: null, needsAction: false,
          urgency: 'NORMAL', progress: null, availableActions: [],
          route: `/work?team=${ids.team}&project=${ids.project}&task=${ids.task}&execution=${ids.taskExecution}`,
        }],
      },
      {
        key: 'INBOX', title: '未读 Inbox', priority: 6, total: 2, truncated: false,
        items: [{
          objectType: 'INBOX', objectId: ids.task, projectId: ids.project, title: '2 条未读 Inbox 消息',
          status: 'OPEN', updatedAt: '2026-08-27T07:55:00Z', responsibilityRole: null, needsAction: false,
          urgency: 'LOW', progress: null, availableActions: [],
          route: `/inbox?team=${ids.team}&project=${ids.project}`,
        }],
      },
    ],
  }
}

function agentTemplateRows(ownershipType: 'USER' | 'TEAM') {
  const definition = (key: string, version: number, runtimeRole: string, scopes: string[], approvedSkillKeys: string[] = [], platformManaged = false) => ({
    publisherType: 'ORGANIZATION', publisherId: ids.organization, key, version, runtimeRole,
    allowedOwnershipTypes: [ownershipType], allowedExecutionScopes: scopes,
    declaredCapabilities: runtimeRole === 'CODING' ? ['coding', 'repository'] : ['collaboration'],
    requiredModelCapabilities: ['TOOLS'], approvedSkillKeys,
    memberConfigurableSlots: ['MODEL_BINDING', 'SUPPLEMENTAL_INSTRUCTIONS', 'APPROVED_SKILLS', 'OUTPUT_PREFERENCE'],
    administratorConfigurableSlots: ['BUDGET'], creatable: !platformManaged, platformManaged,
    contentHash: 'd'.repeat(64), status: 'ACTIVE', lifecycleVersion: 1,
  })
  if (ownershipType === 'TEAM') return [
    definition('team-orchestrator', 1, 'ORCHESTRATOR', ['TEAM']),
    definition('team-observer', 1, 'TEAM_COORDINATOR', ['TEAM'], [], true),
  ]
  return [
    definition('coding', 3, 'CODING', ['PERSONAL', 'TEAM'], ['coding-baseline']),
    definition('reviewer-specialist', 2, 'REVIEWER', ['PERSONAL'], ['review-baseline']),
    definition('personal-assistant', 1, 'PERSONAL_ASSISTANT', ['PERSONAL', 'TEAM'], [], true),
  ]
}

function agentProfileRow(id: string, displayName: string, ownershipType: string, runtimeRole: string, templateKey: string, defaultProfile: boolean, status: string, templateVersion = 1, currentRevision: number | null = 2) {
  return {
    id, principalId: crypto.randomUUID(), displayName, principalStatus: status,
    organizationId: ids.organization, teamId: ids.team, workspaceId: ids.workspace,
    ownershipType, ownerMemberId: ownershipType === 'USER' ? ids.member : null,
    runtimeRole, templateKey, templateVersion, defaultProfile, status,
    currentConfigurationRevision: currentRevision, currentConfigurationHash: currentRevision ? 'a'.repeat(64) : null,
    createdAt: '2026-08-08T01:00:00Z', updatedAt: '2026-08-26T04:00:00Z', version: 2,
  }
}

const agentProfiles = [
  agentProfileRow('00000000-4000-4000-8000-000000000a01', '张凯旋的 Personal Agent', 'USER', 'PERSONAL_ASSISTANT', 'personal-assistant', true, 'ACTIVE'),
  agentProfileRow('00000000-4000-4000-8000-000000000a02', 'CrewScope Coding Agent', 'USER', 'CODING', 'coding', false, 'ACTIVE', 3),
  agentProfileRow('00000000-4000-4000-8000-000000000a03', 'Architecture Reviewer', 'USER', 'REVIEWER', 'reviewer-specialist', false, 'DISABLED', 2),
  agentProfileRow('00000000-4000-4000-8000-000000000a04', 'Team Delivery Agent', 'TEAM', 'ORCHESTRATOR', 'team-orchestrator', false, 'ARCHIVED'),
]

function modelConnectionRow(id: string, ownerType: string, ownerId: string, healthStatus: string) {
  return {
    id, organizationId: ids.organization, providerKey: 'deepseek', ownerType, ownerId, region: 'cn',
    billingSubjectType: ownerType === 'USER' ? 'PRINCIPAL' : ownerType, billingSubjectId: ownerId, credentialVersion: 2, status: 'ACTIVE',
    healthStatus, healthFailureCode: healthStatus === 'UNHEALTHY' ? 'AUTHENTICATION_FAILED' : null,
    checkedAt: '2026-08-27T07:30:00Z', lastHealthyAt: healthStatus === 'HEALTHY' ? '2026-08-27T07:30:00Z' : null,
    consecutiveFailures: healthStatus === 'UNHEALTHY' ? 2 : 0, revocationReason: null,
    createdAt: '2026-08-07T01:00:00Z', updatedAt: '2026-08-27T07:30:00Z', version: 4,
  }
}

const modelConnections = [
  modelConnectionRow('00000000-4000-4000-8000-000000000b01', 'USER', ids.principal, 'HEALTHY'),
  modelConnectionRow('00000000-4000-4000-8000-000000000b02', 'TEAM', ids.team, 'UNHEALTHY'),
]

const modelProviderRow = {
  key: 'deepseek', displayName: 'DeepSeek', availableRegions: ['cn', 'sg'], retentionMode: 'NONE',
  maximumRetentionSeconds: null, trainingUsagePolicy: 'PROHIBITED', status: 'ACTIVE', version: 2,
}

const modelCatalogRows = [{
  id: '00000000-4000-4000-8000-000000000c01', providerKey: 'deepseek', modelId: 'deepseek-v4-flash',
  catalogRevision: 4, modelRevision: 'DeepSeek-V4-Flash-0801', displayName: 'DeepSeek V4 Flash',
  contextWindowTokens: 128000, maximumOutputTokens: 120000, capabilities: ['TOOLS', 'STRUCTURED_OUTPUT'],
  availableRegions: ['cn', 'sg'], status: 'ACTIVE', version: 2,
  effectivePrice: {
    revision: 2, effectiveFrom: '2026-08-01T00:00:00Z', inputPerMillionTokens: '0.1',
    outputPerMillionTokens: '0.2', cachedInputPerMillionTokens: '0.02', currencyCode: 'CNY',
  },
}]

const setupReadiness = {
  organizationId: ids.organization, teamId: ids.team, snapshotVersion: 'setup-12', observedAt: '2026-08-27T08:20:00Z', requiredReady: false,
  capabilities: [
    { capability: 'PERSONAL_CONVERSATION', required: true, status: 'READY', reasonCode: 'READY', canConfigure: false, responsibleParty: 'Current member', actionKey: null },
    { capability: 'TEAM_TASK', required: true, status: 'ACTION_REQUIRED', reasonCode: 'TEAM_AGENT_CONFIGURATION_REQUIRED', canConfigure: true, responsibleParty: 'Team Owner', actionKey: 'OPEN_AGENT_SETTINGS' },
    { capability: 'CODING_REVIEW', required: true, status: 'ACTION_REQUIRED', reasonCode: 'WORKPROJECT_REQUIRED', canConfigure: true, responsibleParty: 'Team Owner', actionKey: 'OPEN_WORKPROJECT_SETTINGS' },
    { capability: 'GITHUB_DRAFT_PR', required: false, status: 'ACTION_REQUIRED', reasonCode: 'GITHUB_REPOSITORY_IMPORT_REQUIRED', canConfigure: true, responsibleParty: 'Team Owner', actionKey: 'START_GITHUB_IMPORT' },
    { capability: 'LARK_NOTIFICATIONS', required: false, status: 'BLOCKED', reasonCode: 'LARK_CONNECTION_REQUIRED', canConfigure: false, responsibleParty: 'Team Owner', actionKey: null },
    { capability: 'TEAM_OBSERVER', required: false, status: 'UNAVAILABLE', reasonCode: 'RUNTIME_UNAVAILABLE', canConfigure: false, responsibleParty: 'Platform Operator', actionKey: null },
  ],
}

const blockedWorkItems = [{
  workItemId: '00000000-4000-4000-8000-000000000602', itemKey: 'CRW-02', title: '执行看板迁移',
  status: 'BLOCKED', projectId: ids.project, blockedSince: '2026-08-26T03:00:00Z', blockerCount: 1, crossesProject: false,
  blockers: [{ workItemId: ids.workItem, title: '结算看板主链路', status: 'IN_PROGRESS', projectId: ids.project }],
}]

const taskRows = [{
  id: ids.task, workspaceId: ids.workspace, projectId: ids.project, workItemId: ids.workItem,
  objective: '完成结算看板主链路的端到端验证', acceptanceCriteria: ['主链路可走通', '关键操作进入审计'],
  status: 'WAITING', currentExecutionId: ids.taskExecution, currentAttempt: 2, currentExecutionStatus: 'RUNNING',
  currentWaitingReason: 'REVIEW', ownerPrincipalId: ids.principal, version: 2,
  createdAt: '2026-08-25T02:00:00Z', updatedAt: '2026-08-27T07:50:00Z',
}]

function costMonthRow(month: string, unpricedTokens: number) {
  return {
    month,
    roles: {
      EXECUTION: { inputTokens: 1_200_000, outputTokens: 60_000, cachedTokens: 40_000, factCount: 40 },
      EMBEDDING: { inputTokens: 100_000, outputTokens: 0, cachedTokens: 0, factCount: 2 },
    },
    currencies: [
      { currency: 'CNY', inputCost: '0.050000000000', outputCost: '0', cachedInputCost: '0' },
      { currency: 'USD', inputCost: '0.528000000000', outputCost: '0.105600000000', cachedInputCost: '0' },
    ],
    unpricedTokens, totalFactCount: 42,
  }
}

const observabilityMonths = { months: [costMonthRow('2026-08', 300_000), costMonthRow('2026-07', 0)], nextAfter: null }

const observabilityBudget = {
  usageMonth: '2026-08',
  config: { alertsEnabled: true, monthlyTokenBudget: 2_000_000, tokenWarningThreshold: 1_700_000, warningRatio: 0.85, monthlyAmountBudgets: { CNY: '1000.00' } },
  usage: { totalTokens: 1_800_000, costsByCurrency: { CNY: '0.050000000000', USD: '0.528000000000' } },
}

const auditSessions = { sessions: [
  { sessionId: 'session-current', createdAt: '2026-08-25T08:00:00Z', lastAccessedAt: '2026-08-27T08:00:00Z', device: 'Chrome · macOS', current: true },
  { sessionId: 'session-other', createdAt: '2026-08-24T08:00:00Z', lastAccessedAt: '2026-08-26T20:00:00Z', current: false },
] }

const costMonthDetail = (month: string) => ({
  month,
  rows: [
    {
      role: 'CHAT_PRIMARY', providerKey: 'deepseek', modelId: 'deepseek-v4-flash', currencyCode: 'CNY',
      catalogRevision: 4, priceRevision: 2, attempt: 1,
      inputTokens: 1_000_000, outputTokens: 50_000, cachedTokens: 10_000,
      inputCost: '0.050000000000', outputCost: '0.010000000000', cachedInputCost: '0',
      factCount: 30, unreportedFactCount: 2, costStatus: 'PRICED',
    },
    {
      role: 'EMBEDDING', providerKey: 'deepseek', modelId: 'text-embedding-v4', currencyCode: 'XXX',
      catalogRevision: null, priceRevision: null, attempt: 2,
      inputTokens: 300_000, outputTokens: 0, cachedTokens: 0,
      inputCost: null, outputCost: null, cachedInputCost: null,
      factCount: 4, unreportedFactCount: 0, costStatus: 'UNPRICED',
    },
  ],
})

const qualityMonth = (month: string) => ({
  month,
  executionAttempts: { total: 20, completed: 15, failed: 4, cancelled: 1, successRate: 0.75 },
  reviewFirstPass: { enteredReview: 8, firstPassApproved: 6, firstPassRate: 0.75 },
})

const selfNotificationPreference = { memberId: ids.member, enabled: true, enabledItemTypes: ['OWNERSHIP', 'EXECUTION', 'REVIEW', 'CONFIRMATION', 'EXCEPTION'], mutedUntil: null, version: 3 }

function agentConfigurationBody(profileId: string, revision: number) {
  const profile = agentProfiles.find(agent => agent.id === profileId)
  const binding = (executionScope: string) => ({
    executionScope, kind: 'EXPLICIT',
    primary: { connectionId: crypto.randomUUID(), providerKey: 'deepseek', catalogEntryId: crypto.randomUUID(), modelId: 'deepseek-v4-flash', catalogRevision: 4 },
    fallback: { connectionId: crypto.randomUUID(), providerKey: 'deepseek', catalogEntryId: crypto.randomUUID(), modelId: 'deepseek-chat', catalogRevision: 3 },
  })
  const teamOwned = profile?.ownershipType === 'TEAM'
  return {
    revision, previousRevision: revision > 1 ? revision - 1 : null,
    templateKey: profile?.templateKey ?? 'coding', templateVersion: profile?.templateVersion ?? 1,
    templateContentHash: 'b'.repeat(64),
    personalBinding: teamOwned ? null : binding('PERSONAL'),
    teamBinding: binding('TEAM'),
    supplementalInstructions: null, approvedSkillKeys: [], memoryPolicy: null, budgetPolicy: null,
    generateOptions: { temperature: null, topP: null, maximumOutputTokens: 120000, reasoningMode: 'DEFAULT', cacheEnabled: true, parallelToolCalls: true, seed: null, maximumAttempts: 2 },
    policyPackId: 'default', policyPackVersion: 1, configurationHash: 'c'.repeat(64), createdAt: '2026-08-26T04:00:00Z',
  }
}

const accountProfile = {
  accountId: 'account-1', username: 'zhangkaixuan', email: 'zhangkaixuan@example.com', displayName: 'Zhang Kaixuan',
  status: 'ACTIVE', platformRole: 'USER', securityVersion: 3, version: 4,
  createdAt: '2026-08-01T00:00:00Z', updatedAt: '2026-08-26T00:00:00Z',
}

export async function mockAuditApi(page: Page): Promise<void> {
  // 单一 /api/v1/ 路由承担全部请求分发：显式 SSE 分支（activity/conversation）回放有限
  // 事件流，其余 event-stream 请求在同一 handler 内挂起保活。绝不拆成两条路由——
  // Playwright 后注册者先匹配，拆开会让兜底分支先吃掉事件流并 404，页面级错误横幅
  // 会盖住交互面（判例：m13g 移动导航点击被拦截）。
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
    // 分支顺序：对话关联请求（…/conversations/{id}/work-items）必须先于工作项列表
    // （endsWith '/work-items'）匹配，否则列表会吞掉关联请求并返回 WorkItem 形状的数据，
    // 组件按 association.workItem 解析即抛错并弹出全局错误横幅（判例：m13g 移动导航被拦）。
    // work-graph/blocked-work-items 同样以 'work-items' 结尾，也必须在这里之前。
    if (request.method() === 'GET' && path.endsWith('/work-graph/blocked-work-items')) {
      return fulfillJson(route, { items: blockedWorkItems, nextCursor: null })
    }
    const conversationWorkItems = path.match(/\/conversations\/([^/]+)\/work-items$/)
    if (request.method() === 'GET' && conversationWorkItems) return fulfillJson(route, [])
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
    const conversationEvents = path.match(/\/conversations\/([^/]+)\/events$/)
    if (request.method() === 'GET' && conversationEvents) return fulfillSse(route, [])
    // M14 内容态分支（account 的 ETag=version 是网关强契约，走独立 fulfill）。
    if (request.method() === 'GET' && path === '/api/v1/auth/sessions') return fulfillJson(route, auditSessions)
    if (request.method() === 'GET' && path === '/api/v1/account') {
      return route.fulfill({ status: 200, contentType: 'application/json', headers: { ETag: `"${accountProfile.version}"` }, body: JSON.stringify(accountProfile) })
    }
    const organizationProfileMatch = path.match(/\/organizations\/([^/]+)$/)
    if (request.method() === 'GET' && organizationProfileMatch) return fulfillJson(route, { organizationId: organizationProfileMatch[1], name: 'CrewScope Organization', status: 'ACTIVE', version: 1 })
    const organizationSettingsMatch = path.match(/\/organizations\/([^/]+)\/settings$/)
    if (request.method() === 'GET' && organizationSettingsMatch) return fulfillJson(route, { auditEventRetentionDays: 365, version: 0 })
    if (request.method() === 'GET' && path.endsWith('/work-desk')) return fulfillJson(route, workDesk())
    if (request.method() === 'GET' && path.endsWith('/agent-templates')) {
      const ownershipType = new URL(request.url()).searchParams.get('ownershipType')
      return fulfillJson(route, { items: agentTemplateRows(ownershipType === 'TEAM' ? 'TEAM' : 'USER') })
    }
    if (request.method() === 'GET' && path.endsWith('/model-providers')) return fulfillJson(route, { items: [modelProviderRow] })
    if (request.method() === 'GET' && path.endsWith('/model-providers/deepseek/catalog')) return fulfillJson(route, { items: modelCatalogRows })
    if (request.method() === 'GET' && path.endsWith('/model-connections')) {
      const ownerType = new URL(request.url()).searchParams.get('ownerType')
      return fulfillJson(route, { items: modelConnections.filter(connection => connection.ownerType === ownerType) })
    }
    if (request.method() === 'GET' && path.endsWith('/agent-profiles')) return fulfillJson(route, { items: agentProfiles })
    if (request.method() === 'GET' && path.endsWith('/setup-readiness')) return fulfillJson(route, setupReadiness)
    if (request.method() === 'GET' && path.endsWith('/observability/cost/months')) return fulfillJson(route, observabilityMonths)
    const costDetailMatch = path.match(/\/observability\/cost\/months\/(\d{4}-\d{2})$/)
    if (request.method() === 'GET' && costDetailMatch) return fulfillJson(route, costMonthDetail(costDetailMatch[1]!))
    const qualityDetailMatch = path.match(/\/observability\/quality\/months\/(\d{4}-\d{2})$/)
    if (request.method() === 'GET' && qualityDetailMatch) return fulfillJson(route, qualityMonth(qualityDetailMatch[1]!))
    if (request.method() === 'GET' && path.endsWith('/observability/budget')) return fulfillJson(route, observabilityBudget)
    if (request.method() === 'GET' && path.endsWith('/tasks')) return fulfillJson(route, { items: taskRows, nextCursor: null })
    if (request.method() === 'GET' && path.endsWith('/members/me/notification-preference')) {
      return route.fulfill({ status: 200, contentType: 'application/json', headers: { ETag: `"${selfNotificationPreference.version}"` }, body: JSON.stringify(selfNotificationPreference) })
    }
    const agentConfigurationMatch = path.match(/\/agent-profiles\/([^/]+)\/configurations\/current$/)
    if (request.method() === 'GET' && agentConfigurationMatch && agentProfiles.some(agent => agent.id === agentConfigurationMatch[1])) {
      return route.fulfill({ status: 200, contentType: 'application/json', headers: { ETag: '"2"' }, body: JSON.stringify(agentConfigurationBody(agentConfigurationMatch[1]!, 2)) })
    }
    // 其余 event-stream 请求（未覆盖的实时通道）挂起保活：立即 404/关闭会触发页面级
    // 错误横幅盖住交互面，有限空流则会退避重连波及布局稳定性。
    if (request.headers().accept?.includes('text/event-stream')) {
      return new Promise<void>(() => {})
    }
    if (process.env.AUDIT_TRACE) console.log(`[audit-404] ${request.method()} ${path}`)
    return route.fulfill({ status: 404, contentType: 'application/json', body: JSON.stringify({ code: 'not_found', message: 'not_found', correlationId: crypto.randomUUID(), retryable: false, details: {} }) })
  })
}

export type LayoutIssue = { route: string, kind: string, detail: string }

/**
 * 盒探针。误报控制：
 * - element-out-of-view 只报最外层越界元素（祖先已报则子孙跳过）；跳过横向滚动容器
 *   内部（有意横滚）与视觉隐藏（sr-only / aria-hidden / display:none）；
 * - text-clipped 只在「直接持有非空文本节点的后代元素」真的越出 overflow hidden 容器
 *   的裁剪盒时报——伪元素命中区（touch-target::after）、aria-hidden 装饰 bleed 会让
 *   scrollWidth 虚高，但文本宿主仍在盒内，不构成展示损害；
 * - fixed-out-of-view 报 fixed/sticky 越界（贴边吸附由 right<=vw 保证）。
 */
export async function probeLayout(page: Page, routeName: string): Promise<LayoutIssue[]> {
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
