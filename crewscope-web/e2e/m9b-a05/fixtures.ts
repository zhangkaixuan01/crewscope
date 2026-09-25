import { readFile } from 'node:fs/promises'
import { extname, resolve, sep } from 'node:path'
import type { Page, Route } from '@playwright/test'
import { permissions } from '../../src/app/auth'

/**
 * M9b-A05 browser fixtures：与 m9b-f05 相同的「保留域源 + 全拦截 API」模式，世界聚焦在
 * 一个 WorkItem 的委托面：责任链、Agent 目录、A05 的 delegation-context 只读端点、
 * 携带 executorAssignment 的创建命令，以及它落下的 TASK command-result 恢复坐标。
 *
 * 夹具语义逐条对照真实服务端（A07 教训）：候选五态与在途执行事实从责任链 / Agent 目录 /
 * Task 投影推导，命令分支镜像 AgentTaskCreationService 的沿用 / 冲突 / 新分配规则。
 */
export const origin = 'http://crewscope-a05.test'
const dist = resolve('dist')

export const ids = {
  organization: '00000000-0000-0000-0000-000000000001',
  principal: '00000000-0000-0000-0000-000000000101',
  secondPrincipal: '00000000-0000-0000-0000-000000000102',
  member: '00000000-0000-0000-0000-000000000301',
  secondMember: '00000000-0000-0000-0000-000000000302',
  team: '00000000-0000-0000-0000-000000000201',
  project: '00000000-0000-0000-0000-000000000401',
  workspace: '00000000-0000-0000-0000-000000000501',
  workItem: '00000000-0000-0000-0000-000000000601',
  seedTask: '00000000-0000-0000-0000-000000000651',
  agentPersonalProfile: '00000000-0000-0000-0000-000000001701',
  agentPersonalPrincipal: '00000000-0000-0000-0000-000000001711',
  agentCodingProfile: '00000000-0000-0000-0000-000000001702',
  agentCodingPrincipal: '00000000-0000-0000-0000-000000001712',
  agentReviewerProfile: '00000000-0000-0000-0000-000000001703',
  agentReviewerPrincipal: '00000000-0000-0000-0000-000000001713',
  repositoryBinding: '00000000-0000-0000-0000-000000001801',
}

/** 基础世界只有一个 OWNER（张凯旋本人）——「分配并启动」与「仅分配」都要从这里起步。 */
export const ownerLineId = '00000000-0000-0000-0000-000000000901'

export function workUrl(extra: Record<string, string> = {}): string {
  const search = new URLSearchParams({ team: ids.team, project: ids.project, workItem: ids.workItem, ...extra })
  return `${origin}/work?${search.toString()}`
}

/** 权限可裁剪的会话：scenario「无 responsibility:manage」在 goto 前换掉 world.session。 */
export function sessionFor(without: string[] = []) {
  const granted = Object.values(permissions).filter(value => !without.includes(value))
  return {
    authenticated: true,
    registrationMode: 'OPEN',
    csrf: { headerName: 'X-XSRF-TOKEN', parameterName: '_csrf', token: 'csrf-a05' },
    account: {
      accountId: '00000000-0000-0000-0000-0000000000a1', username: 'a05-owner',
      displayName: '张凯旋', platformRole: 'USER', securityVersion: 1, version: 1,
    },
    principal: { principalId: ids.principal, organizationId: ids.organization },
    teams: [{
      teamId: ids.team, name: 'Platform Engineering', memberId: ids.member, permissions: granted,
    }],
    permissions: granted,
  }
}

export function agentDirectory() {
  return [
    agentRow(ids.agentPersonalProfile, ids.agentPersonalPrincipal, '张凯旋的 Personal Agent', 'USER', 'PERSONAL', 'personal-assistant', 'ACTIVE'),
    agentRow(ids.agentCodingProfile, ids.agentCodingPrincipal, 'CrewScope Coding Agent', 'USER', 'CODING', 'coding-specialist', 'ACTIVE'),
    agentRow(ids.agentReviewerProfile, ids.agentReviewerPrincipal, 'Architecture Reviewer', 'USER', 'REVIEWER', 'reviewer-specialist', 'DISABLED'),
  ]
}

function agentRow(id: string, principalId: string, displayName: string, ownershipType: string, runtimeRole: string, templateKey: string, status: string) {
  return {
    id, principalId, displayName, principalStatus: status,
    organizationId: ids.organization, teamId: ids.team, workspaceId: ids.workspace,
    ownershipType, ownerMemberId: ownershipType === 'USER' ? ids.member : null,
    runtimeRole, templateKey, templateVersion: 1, defaultProfile: false, status,
    currentConfigurationRevision: 2, currentConfigurationHash: 'a'.repeat(64),
    createdAt: '2026-08-08T01:00:00Z', updatedAt: '2026-08-08T04:00:00Z', version: 2,
  }
}

export function responsibilityLine(id: string, role: string, actorPrincipalId: string, actorType: string, actorDisplayName: string, actorAgentProfileId: string | null = null) {
  return {
    id, workItemId: ids.workItem, role, actorPrincipalId, actorType,
    actorMemberId: actorType === 'USER' ? crypto.randomUUID() : null,
    actorDisplayName, actorAgentProfileId, status: 'ACTIVE',
    assignedByPrincipalId: ids.principal, assignedAt: '2026-08-08T03:20:00Z', acceptedAt: '2026-08-08T03:20:00Z', version: 0,
  }
}

export function taskRow(id: string, objective: string, status = 'CREATED', executionStatus = 'READY') {
  return {
    id, workspaceId: ids.workspace, projectId: ids.project, workItemId: ids.workItem, objective,
    acceptanceCriteria: ['通过自动化测试'], status, currentExecutionId: crypto.randomUUID(),
    currentAttempt: 1, currentExecutionStatus: executionStatus, currentWaitingReason: null,
    previousExecutionId: null, previousAttempt: 1, executionVersion: 0,
    ownerPrincipalId: ids.principal, version: 0, createdAt: '2026-08-08T03:30:00Z', updatedAt: '2026-08-08T03:40:00Z',
  }
}

export interface A05World {
  session: ReturnType<typeof sessionFor>
  /** WorkItem 版本既是 detail ETag 也是创建命令的 If-Match。 */
  workItemVersion: number
  responsibilities: Array<ReturnType<typeof responsibilityLine>>
  tasks: Array<ReturnType<typeof taskRow>>
  comments: Array<{ id: string, workItemId: string, authorPrincipalId: string, content: string, source: string, externalId: string | null, createdAt: string }>
  /** 每一次被接受的 POST .../tasks：幂等键、If-Match 与完整命令体。 */
  taskCreates: Array<{ key: string, ifMatch: string | null, body: Record<string, unknown> }>
  /** 每一次被接受的 POST .../responsibilities/executors（「仅分配」意图）。 */
  executorAssignments: Array<{ key: string, ifMatch: string | null, body: { actorPrincipalId: string } }>
  commentPosts: Array<{ content: string }>
  /** 幂等键 → 已提交的 TASK 恢复坐标（V47 后 CommandResult 的真实投影）。 */
  commandResults: Map<string, { receipt: Record<string, unknown>, result: Record<string, unknown> }>
  /** 创建命令的人为延迟，让「双击提交」有一个真实的在途窗口。 */
  createLatencyMs: number
  unexpected: string[]
}

export async function mockA05App(page: Page): Promise<A05World> {
  const world: A05World = {
    session: sessionFor(),
    workItemVersion: 3,
    responsibilities: [responsibilityLine(ownerLineId, 'OWNER', ids.principal, 'USER', '张凯旋')],
    tasks: [],
    comments: [],
    taskCreates: [],
    executorAssignments: [],
    commentPosts: [],
    commandResults: new Map(),
    createLatencyMs: 0,
    unexpected: [],
  }
  await page.route('**/*', async route => {
    const request = route.request()
    const url = new URL(request.url())
    if (url.origin !== origin) { world.unexpected.push(url.origin); return route.abort() }
    if (url.pathname.startsWith('/api/')) return handleApi(route, world, url)
    return serveAsset(route, url.pathname)
  })
  return world
}

async function handleApi(route: Route, world: A05World, url: URL): Promise<void> {
  const request = route.request()
  const path = url.pathname
  const method = request.method()

  if (method === 'GET' && path === '/api/v1/auth/session') return json(route, world.session)
  if (method === 'GET' && path.endsWith('/command-results')) {
    const key = request.headers()['idempotency-key'] ?? ''
    const found = world.commandResults.get(key)
    if (!found) return json(route, envelope404(), 404)
    return json(route, found)
  }
  if (method === 'GET' && path.endsWith('/teams')) {
    return json(route, [{
      id: ids.team, organizationId: ids.organization, name: 'Platform Engineering',
      status: 'ACTIVE', initializationStatus: 'READY', ownerMemberId: ids.member,
      defaultWorkspaceId: ids.workspace, version: 1,
    }])
  }
  if (method === 'GET' && path.endsWith(`/${ids.team}/work-projects`)) {
    return json(route, { items: [{
      id: ids.project, organizationId: ids.organization, teamId: ids.team, workspaceId: ids.workspace,
      key: 'CRW', name: 'CrewScope', status: 'ACTIVE', version: 1,
      createdAt: '2026-08-08T01:00:00Z', createdByPrincipalId: ids.principal,
      updatedAt: '2026-08-08T01:00:00Z', updatedByPrincipalId: ids.principal,
    }], nextCursor: null })
  }
  if (method === 'GET' && path.endsWith(`/${ids.team}/setup-readiness`)) {
    return json(route, {
      organizationId: ids.organization, teamId: ids.team, snapshotVersion: 'a05-1',
      observedAt: '2026-08-08T04:00:00Z', requiredReady: true,
      capabilities: [
        { capability: 'PERSONAL_CONVERSATION', required: true, status: 'READY', reasonCode: 'READY', canConfigure: false, responsibleParty: 'Current member', actionKey: null },
        { capability: 'TEAM_TASK', required: true, status: 'READY', reasonCode: 'READY', canConfigure: false, responsibleParty: 'Current member', actionKey: null },
      ],
    })
  }
  if (method === 'GET' && path.endsWith(`/${ids.team}/configuration-health`)) {
    return json(route, {
      organizationId: ids.organization, teamId: ids.team, observedAt: '2026-08-08T04:00:00Z',
      overallStatus: 'READY', items: [
        { component: 'AGENT_CONFIGURATION', status: 'READY', reasonCode: 'READY', responsibleParty: 'Current member', actionKey: null },
        { component: 'MODEL_CONNECTION', status: 'READY', reasonCode: 'READY', responsibleParty: 'Current member', actionKey: null },
      ],
    })
  }
  // /work 的骨架先读 WorkDesk 投影；缺了它整个页面会渲染成失败态而不是工作台。
  if (method === 'GET' && path.endsWith('/work-desk')) {
    return json(route, {
      organizationId: ids.organization, teamId: ids.team, projectId: null, generatedAt: '2026-08-08T04:00:00Z',
      sections: [{
        key: 'WORK_ITEM', title: '我的工作项', priority: 1, total: 1, truncated: false,
        items: [{
          objectType: 'WORK_ITEM', objectId: ids.workItem, projectId: ids.project,
          title: '委托编排验收工作项', status: 'IN_PROGRESS', updatedAt: '2026-08-08T03:00:00Z',
          responsibilityRole: 'OWNER', needsAction: false, urgency: 'NORMAL', progress: null,
          availableActions: [], route: `/work?team=${ids.team}&project=${ids.project}&workItem=${ids.workItem}`,
        }],
      }],
    })
  }
  if (method === 'GET' && path.endsWith('/members')) {
    return json(route, [
      { id: ids.member, userPrincipalId: ids.principal, displayName: '张凯旋', status: 'ACTIVE', joinMethod: 'CREATED_WITH_TEAM', joinedAt: '2026-08-08T01:00:00Z', version: 0 },
      { id: ids.secondMember, userPrincipalId: ids.secondPrincipal, displayName: '林晨', status: 'ACTIVE', joinMethod: 'INVITED', joinedAt: '2026-08-08T01:10:00Z', version: 0 },
    ])
  }
  if (method === 'GET' && path.endsWith('/agent-profiles')) {
    return json(route, { items: agentDirectory() })
  }
  const agentHistoryMatch = path.match(/\/agent-profiles\/([^/]+)\/configurations$/)
  if (agentHistoryMatch && method === 'GET') {
    const agent = agentDirectory().find(row => row.id === agentHistoryMatch[1])
    if (!agent) return json(route, envelope404(), 404)
    const item = (revision: number) => ({
      revision, previousRevision: revision > 1 ? revision - 1 : null,
      templateKey: agent.templateKey, templateVersion: agent.templateVersion,
      templateContentHash: 'b'.repeat(64),
      personalBinding: agent.ownershipType === 'USER'
        ? { executionScope: 'PERSONAL', kind: 'EXPLICIT', primary: { connectionId: crypto.randomUUID(), providerKey: 'deepseek', catalogEntryId: crypto.randomUUID(), modelId: 'deepseek-v4-flash', catalogRevision: 4 }, fallback: null }
        : null,
      teamBinding: null, configurationHash: 'c'.repeat(64),
      createdAt: '2026-08-08T04:00:00Z', createdBy: ids.principal,
    })
    return json(route, { items: [item(2), item(1)] })
  }
  const agentDetailMatch = path.match(/\/agent-profiles\/([^/]+)$/)
  if (agentDetailMatch && method === 'GET') {
    const agent = agentDirectory().find(row => row.id === agentDetailMatch[1])
    if (!agent) return json(route, envelope404(), 404)
    return json(route, agent)
  }
  if (method === 'GET' && path.endsWith('/repository-catalog')) {
    return json(route, { items: [{ repositoryKey: 'crewscope-java', availability: 'AVAILABLE', suggestedDefaultBranch: 'main' }] })
  }
  if (method === 'GET' && path.endsWith('/repository-bindings')) {
    return json(route, { items: [repositoryBinding()] })
  }
  const repositoryPreflightMatch = path.match(/\/repository-bindings\/([^/]+)\/preflight$/)
  if (repositoryPreflightMatch && method === 'POST') {
    return json(route, { ready: true, repositoryKey: 'crewscope-java', baselineRef: 'main', baselineCommit: 'a'.repeat(40) })
  }
  if (method === 'GET' && path.endsWith('/coding-target/build-profiles')) {
    return json(route, { items: [{
      key: 'maven-java-17', version: 1, profileHash: 'b'.repeat(64),
      buildTool: 'MAVEN', javaRelease: 17, commandKinds: ['COMPILE', 'TEST', 'VERIFY'],
    }] })
  }
  if (method === 'POST' && path.endsWith('/coding-target/preflight')) {
    const input = request.postDataJSON() as { repositoryBindingId: string, baselineRef: string }
    if (input.repositoryBindingId !== ids.repositoryBinding) return json(route, envelope404(), 404)
    return json(route, { ready: true, repositoryKey: 'crewscope-java', baselineRef: input.baselineRef, baselineCommit: 'c'.repeat(40) })
  }

  // ── WorkItem 表面 ────────────────────────────────────────────────────────────
  if (method === 'GET' && path.endsWith('/work-items')) {
    return json(route, { items: [withAvailability(workItemRow(world))], nextCursor: null })
  }
  const workItemTaskPreflightMatch = path.match(/\/work-projects\/([^/]+)\/work-items\/([^/]+)\/tasks\/preflight$/)
  if (workItemTaskPreflightMatch && method === 'POST') {
    const input = request.postDataJSON() as { executorAgentProfileId: string, agentConfigurationRevision: number | null }
    const agent = agentDirectory().find(row => row.id === input.executorAgentProfileId)
    if (!agent || agent.status !== 'ACTIVE') {
      return json(route, {
        code: 'agent_not_assignable', message: 'Agent 不存在或已停用', correlationId: 'a05-fixture',
        retryable: false, currentVersion: null, details: {},
      }, 422)
    }
    return json(route, {
      agentProfileId: agent.id, agentProfileVersion: agent.version, executionScope: 'PERSONAL',
      configurationRevision: input.agentConfigurationRevision ?? agent.currentConfigurationRevision,
      configurationHash: 'c'.repeat(64), bindingSource: 'DIRECT',
      templateVersion: `${agent.templateKey}@${agent.templateVersion}`,
      primary: {
        role: 'PRIMARY', providerKey: 'deepseek', connectionId: '00000000-0000-0000-0000-000000002001',
        connectionOwnerType: 'USER', modelId: 'deepseek-v4-flash', catalogRevision: 7,
        modelRevision: '2026-08', priceRevision: 3,
      },
      fallback: null, policyPackId: '00000000-0000-0000-0000-000000002101', policyPackVersion: 4,
      resolutionHash: 'd'.repeat(64),
    })
  }
  const workItemTaskMatch = path.match(/\/work-projects\/([^/]+)\/work-items\/([^/]+)\/tasks$/)
  if (workItemTaskMatch && method === 'GET') {
    return json(route, {
      items: world.tasks.map(row => ({
        origin: 'WORK_ITEM_ROOT', associatedAt: row.createdAt,
        task: { ...row, href: `/work?team=${ids.team}&project=${row.projectId}&workItem=${row.workItemId}&task=${row.id}` },
      })),
      nextCursor: null,
    })
  }
  if (workItemTaskMatch && method === 'POST') {
    // M9b-A05：同一条创建命令可以携带 executorAssignment（决策①）。服务端规则在这里是
    // 真实语义的镜像——同 actor 沿用、不同 ACTIVE EXECUTOR 冲突拒绝、否则同事务新分配；
    // 幂等键重放返回原回执，不产生第二条副作用。
    const key = request.headers()['idempotency-key'] ?? ''
    if (!key) return json(route, envelope404(), 404)
    if (world.createLatencyMs) await new Promise(resolve => setTimeout(resolve, world.createLatencyMs))
    if (world.commandResults.has(key)) return receipt(route, 0)

    const input = request.postDataJSON() as {
      objective: string,
      executorAgentProfileId: string,
      executorAssignment: { agentProfileId: string } | null,
    }
    const agent = agentDirectory().find(row => row.id === input.executorAgentProfileId)
    if (!agent || agent.status !== 'ACTIVE') {
      return json(route, {
        code: 'agent_not_assignable', message: 'Agent 不存在或已停用', correlationId: 'a05-fixture',
        retryable: false, currentVersion: null, details: {},
      }, 422)
    }
    const existing = world.responsibilities.find(line => line.role === 'EXECUTOR' && line.status === 'ACTIVE')
    if (existing && existing.actorAgentProfileId && existing.actorAgentProfileId !== agent.id) {
      return json(route, {
        code: 'executor_conflict', message: '已有其他 ACTIVE EXECUTOR，需先显式释放再分配', correlationId: 'a05-fixture',
        retryable: false, currentVersion: null, details: {},
      }, 409)
    }
    const assignment = input.executorAssignment?.agentProfileId ?? null
    if (assignment && assignment !== agent.id) {
      return json(route, {
        code: 'validation_failed', message: 'executorAssignment 与所选执行 Agent 不一致', correlationId: 'a05-fixture',
        retryable: false, currentVersion: null, details: {},
      }, 422)
    }
    if (!existing && assignment) {
      const actorType = agent.ownershipType === 'USER' ? 'PERSONAL_AGENT' : 'TEAM_AGENT'
      world.responsibilities.push(responsibilityLine(crypto.randomUUID(), 'EXECUTOR', agent.principalId, actorType, agent.displayName, agent.id))
    }
    const created = taskRow(crypto.randomUUID(), input.objective)
    world.tasks.unshift(created)
    world.taskCreates.push({ key, ifMatch: request.headers()['if-match'] ?? null, body: input })
    world.commandResults.set(key, {
      receipt: commandReceipt(0),
      result: {
        organizationId: ids.organization, teamId: ids.team, projectId: ids.project,
        type: 'TASK', resourceId: created.id, committedVersion: 0, stage: 'COMMITTED',
      },
    })
    return receipt(route, 0)
  }
  const delegationContextMatch = path.match(/\/work-projects\/([^/]+)\/work-items\/([^/]+)\/delegation-context$/)
  if (delegationContextMatch && method === 'GET') {
    // 镜像 DelegationContextService：候选五态从责任链与 Agent 目录推导，activeExecution
    // 来自当前 Task 投影，权限布尔来自会话责任管理权与委托权威。
    const lines = world.responsibilities.filter(entry => entry.workItemId === delegationContextMatch[2])
    const agentExecutor = lines.find(entry => entry.role === 'EXECUTOR' && entry.status === 'ACTIVE' && entry.actorAgentProfileId)
    const inFlight = world.tasks.find(existing => existing.workItemId === delegationContextMatch[2]
      && ['READY', 'RUNNING', 'WAITING', 'PAUSE_REQUESTED'].includes(existing.currentExecutionStatus))
    const item = workItemRow(world)
    const canAssign = (world.session.teams[0]!.permissions as string[]).includes(permissions.responsibilityManage)
    return json(route, {
      workItem: { id: delegationContextMatch[2]!, projectId: delegationContextMatch[1]!, version: world.workItemVersion, title: item.title, status: item.status },
      responsibilities: lines.map(entry => ({
        assignmentId: entry.id, role: entry.role, actorPrincipalId: entry.actorPrincipalId,
        actorType: entry.actorType, actorDisplayName: entry.actorDisplayName,
        actorAgentProfileId: entry.actorAgentProfileId, version: entry.version,
      })),
      candidates: agentDirectory().map(agent => {
        const state = agent.status !== 'ACTIVE'
          ? 'AGENT_DISABLED'
          : agentExecutor && agent.id !== agentExecutor.actorAgentProfileId
            ? 'EXECUTOR_CONFLICT'
            : agentExecutor
              ? 'ASSIGNED'
              : 'AVAILABLE'
        return {
          agentProfileId: agent.id, agentProfileVersion: agent.version, agentPrincipalId: agent.principalId,
          displayName: agent.displayName, ownershipType: agent.ownershipType, runtimeRole: agent.runtimeRole,
          state,
          reason: state === 'AGENT_DISABLED' ? 'Agent 已停用'
            : state === 'EXECUTOR_CONFLICT' ? '已有其他执行者责任——需先显式释放再分配'
              : null,
        }
      }),
      defaults: {
        version: 1,
        repositoryBindingId: { value: ids.repositoryBinding, source: 'PROJECT_DEFAULT', availability: 'AVAILABLE', reason: '项目已选择仓库绑定' },
        repositoryBindingVersion: { value: 1, source: 'PROJECT_DEFAULT', availability: 'AVAILABLE', reason: '项目已选择仓库绑定' },
        branch: { value: 'main', source: 'PROJECT_DEFAULT', availability: 'AVAILABLE', reason: '项目默认分支' },
        buildProfile: { value: { key: 'maven-java-17', version: 1, profileHash: 'b'.repeat(64) }, source: 'PROJECT_DEFAULT', availability: 'AVAILABLE', reason: '项目已选择受控构建方案' },
        agentProfileId: { value: null, source: 'PROJECT_DEFAULT', availability: 'INHERITED', reason: '使用任务/团队解析结果' },
        agentProfileRevision: { value: null, source: 'PROJECT_DEFAULT', availability: 'INHERITED', reason: '使用任务/团队解析结果' },
      },
      activeExecution: Boolean(inFlight),
      permissions: { canAssignResponsibility: canAssign, canDelegate: true },
    })
  }
  const responsibilityMatch = path.match(/\/work-items\/([^/]+)\/responsibilities$/)
  if (responsibilityMatch && method === 'GET') {
    return json(route, world.responsibilities.filter(entry => entry.workItemId === responsibilityMatch[1]))
  }
  const executorAssignmentMatch = path.match(/\/work-items\/([^/]+)\/responsibilities\/executors$/)
  if (executorAssignmentMatch && method === 'POST') {
    // 「仅分配」复用的既有责任命令端点：幂等键重放不重复落责任。
    const key = request.headers()['idempotency-key'] ?? ''
    const input = request.postDataJSON() as { actorPrincipalId: string }
    if (!world.executorAssignments.some(entry => entry.key === key)) {
      const agent = agentDirectory().find(row => row.principalId === input.actorPrincipalId)
      const actorType = agent ? (agent.ownershipType === 'USER' ? 'PERSONAL_AGENT' : 'TEAM_AGENT') : 'USER'
      world.responsibilities.push(responsibilityLine(
        crypto.randomUUID(), 'EXECUTOR', input.actorPrincipalId, actorType,
        agent?.displayName ?? '成员', agent?.id ?? null,
      ))
      world.executorAssignments.push({ key, ifMatch: request.headers()['if-match'] ?? null, body: input })
    }
    return receipt(route, 0)
  }
  const commentMatch = path.match(/\/work-items\/([^/]+)\/comments$/)
  if (commentMatch && method === 'POST') {
    // R42 的服务端事实：评论只是讨论记录，永远不产生 Task 或执行。
    const input = request.postDataJSON() as { content: string }
    world.comments.push({
      id: crypto.randomUUID(), workItemId: commentMatch[1]!, authorPrincipalId: ids.principal,
      content: input.content, source: 'CREWSCOPE', externalId: null, createdAt: '2026-08-08T04:00:00Z',
    })
    world.commentPosts.push({ content: input.content })
    return receipt(route, 0)
  }
  const availabilityMatch = path.match(/\/work-items\/([^/]+)\/transitions\/availability$/)
  if (availabilityMatch && method === 'GET') return json(route, { transitions: [] })
  const timelineMatch = path.match(/\/work-items\/([^/]+)\/timeline$/)
  if (timelineMatch && method === 'GET') {
    return json(route, {
      items: [{
        eventId: '00000000-0000-0000-0000-000000001001', domainEventId: '00000000-0000-0000-0000-000000001001',
        source: 'DOMAIN_EVENT', eventType: 'WORK_ITEM_CREATED', schemaVersion: '1',
        aggregateType: 'WorkItem', aggregateId: ids.workItem, aggregateVersion: 0,
        actorType: 'USER', actorPrincipalId: ids.principal, actorDisplayName: '张凯旋',
        correlationId: 'a05-correlation', causationId: null, occurredAt: '2026-08-08T01:00:00Z',
        outcome: 'SUCCEEDED', payload: { workItemId: ids.workItem },
      }],
      nextCursor: null,
    })
  }
  const workItemActivityMatch = path.match(/\/work-projects\/([^/]+)\/work-items\/([^/]+)\/activity(?:\/snapshot)?$/)
  if (workItemActivityMatch && method === 'GET') {
    return json(route, {
      items: [],
      hasMore: false,
      nextCursor: null,
      snapshotCursor: path.endsWith('/snapshot') ? 'a05-activity-cursor' : undefined,
    })
  }
  const workItemConversationMatch = path.match(/\/work-projects\/([^/]+)\/work-items\/([^/]+)\/conversations$/)
  if (workItemConversationMatch && method === 'GET') return json(route, [])
  const detailMatch = path.match(/\/work-items\/([^/]+)$/)
  if (detailMatch && method === 'GET') {
    if (detailMatch[1] !== ids.workItem) return json(route, envelope404(), 404)
    const body = JSON.stringify({
      workItem: withAvailability(workItemRow(world)),
      comments: world.comments,
      resourceLinks: [],
    })
    return route.fulfill({
      status: 200, contentType: 'application/json',
      headers: { ETag: `"${world.workItemVersion}"`, 'Cache-Control': 'no-store' }, body,
    })
  }

  // ── Task 表面（创建后的深链刷新会全部走到） ──────────────────────────────────
  if (method === 'GET' && path.endsWith('/runtime-health')) {
    return json(route, {
      environment: 'production', observedAt: '2026-08-08T03:41:00Z', health: 'HEALTHY',
      runtimeCount: 1, workerCount: 2, activeWorkerCount: 2, staleWorkerCount: 0, drainingWorkerCount: 0,
      capacity: { maximum: 6, active: 2, available: 4 }, waitingRuntimeExecutions: 0,
      waitingCauses: [], workers: [],
    })
  }
  const taskRuntimeFactsMatch = path.match(/\/tasks\/([^/]+)\/attempts\/([^/]+)\/runtime-facts$/)
  if (taskRuntimeFactsMatch && method === 'GET') {
    const selected = world.tasks.find(row => row.id === taskRuntimeFactsMatch[1])
    if (!selected) return json(route, envelope404(), 404)
    return json(route, {
      execution: taskExecutionRow(selected),
      planVersions: [], steps: [], sessions: [], agentRuns: [], interrupts: [], snapshots: [], leases: [],
    })
  }
  const taskCommandEvidenceMatch = path.match(/\/tasks\/([^/]+)\/attempts\/([^/]+)\/coding\/(commands|test-evidence)$/)
  if (taskCommandEvidenceMatch && method === 'GET') {
    return json(route, { items: [], nextCursor: null })
  }
  const taskAttemptMatch = path.match(/\/tasks\/([^/]+)\/attempts$/)
  if (taskAttemptMatch && method === 'GET') {
    const selected = world.tasks.find(row => row.id === taskAttemptMatch[1])
    if (!selected) return json(route, envelope404(), 404)
    return json(route, [taskExecutionRow(selected)])
  }
  const taskEventMatch = path.match(/\/tasks\/([^/]+)\/events$/)
  if (taskEventMatch && method === 'GET') {
    if (request.headers().accept?.includes('text/event-stream')) {
      return route.fulfill({ status: 200, contentType: 'text/event-stream', headers: { 'Cache-Control': 'no-store' }, body: '' })
    }
    return json(route, { items: [], hasMore: false, taskTerminal: false, nextCursor: null })
  }
  const taskAssociationsMatch = path.match(/\/tasks\/([^/]+)\/associations$/)
  if (taskAssociationsMatch && method === 'GET') {
    const selected = world.tasks.find(row => row.id === taskAssociationsMatch[1])
    if (!selected) return json(route, envelope404(), 404)
    return json(route, {
      task: { id: selected.id, projectId: selected.projectId, workItemId: selected.workItemId, status: selected.status, objective: selected.objective, href: `/work?task=${selected.id}` },
      workItem: { id: selected.workItemId, projectId: selected.projectId, key: 'A05-1', title: '委托编排验收工作项', status: 'IN_PROGRESS', href: `/work?workItem=${selected.workItemId}` },
      conversations: { items: [], nextCursor: null },
    })
  }
  const taskDetailMatch = path.match(/\/tasks\/([^/]+)$/)
  if (taskDetailMatch && method === 'GET') {
    const selected = world.tasks.find(row => row.id === taskDetailMatch[1])
    if (!selected) return json(route, envelope404(), 404)
    const executor = world.responsibilities.find(line => line.role === 'EXECUTOR' && line.status === 'ACTIVE')
    return json(route, {
      id: selected.id, teamId: ids.team, workspaceId: selected.workspaceId, projectId: selected.projectId,
      workItemId: selected.workItemId, objective: selected.objective, acceptanceCriteria: selected.acceptanceCriteria,
      source: { type: 'WORK_ITEM', workItemVersion: world.workItemVersion, conversationId: null, inputType: null, inputId: null, inputVersion: null },
      responsibilitySnapshot: [
        { assignmentId: ownerLineId, assignmentVersion: 0, role: 'OWNER', principalId: ids.principal, principalType: 'USER', memberId: ids.member, assignedAt: selected.createdAt, acceptedAt: selected.createdAt },
        ...(executor ? [{ assignmentId: executor.id, assignmentVersion: 0, role: 'EXECUTOR', principalId: executor.actorPrincipalId, principalType: executor.actorType, memberId: null, assignedAt: selected.createdAt, acceptedAt: selected.createdAt }] : []),
      ],
      responsibilityCapturedAt: selected.createdAt, status: selected.status,
      currentExecutionId: selected.currentExecutionId, cancellation: null, version: selected.version,
      audit: { createdByPrincipalId: ids.principal, createdAt: selected.createdAt, updatedByPrincipalId: ids.principal, updatedAt: selected.updatedAt },
      attempts: [taskExecutionRow(selected)],
    })
  }
  if (method === 'GET' && path.endsWith('/tasks')) {
    return json(route, { items: world.tasks, nextCursor: null })
  }

  // 未知端点像真实服务端一样 fail closed；本套件不应依赖它们。
  return json(route, envelope404(), 404)
}

function workItemRow(world: A05World) {
  return {
    id: ids.workItem, organizationId: ids.organization, teamId: ids.team, workspaceId: ids.workspace,
    projectId: ids.project, key: 'A05-1', type: 'FEATURE', title: '委托编排验收工作项',
    description: '验证 M9b-A05 的责任、候选与默认值一处置备。', status: 'IN_PROGRESS', priority: 'HIGH',
    labels: [], dueAt: null, source: 'CREWSCOPE', sourceReference: null, version: world.workItemVersion,
    createdAt: '2026-08-08T01:00:00Z', createdByPrincipalId: ids.principal,
    updatedAt: '2026-08-08T02:00:00Z', updatedByPrincipalId: ids.principal,
  }
}

function withAvailability<T extends { status: string }>(item: T) {
  return { ...item, availableActions: [] }
}

function taskExecutionRow(value: ReturnType<typeof taskRow>) {
  return {
    id: value.currentExecutionId, attempt: value.currentAttempt, maxAttempts: 3, parentExecutionId: null,
    priority: 50, notBefore: value.createdAt, status: value.currentExecutionStatus,
    waiting: value.currentWaitingReason ? { reason: value.currentWaitingReason, waitingSince: value.updatedAt } : null,
    controlRequest: null,
    terminal: null,
    executorPrincipalId: ids.agentPersonalPrincipal,
    currentPlanVersionId: null, version: value.executionVersion,
    audit: { createdByPrincipalId: ids.principal, createdAt: value.createdAt, updatedByPrincipalId: ids.principal, updatedAt: value.updatedAt },
  }
}

function repositoryBinding() {
  return {
    id: ids.repositoryBinding, organizationId: ids.organization, teamId: ids.team, workspaceId: ids.workspace,
    projectId: ids.project, kind: 'LOCAL_MANAGED', repositoryKey: 'crewscope-java', defaultBranch: 'main',
    status: 'ACTIVE', version: 1, createdAt: '2026-08-08T01:00:00Z', createdByPrincipalId: ids.principal,
    updatedAt: '2026-08-08T04:00:00Z', updatedByPrincipalId: ids.principal,
  }
}

function commandReceipt(committedVersion: number) {
  return { commandId: crypto.randomUUID(), domainEventId: crypto.randomUUID(), committedVersion, correlationId: crypto.randomUUID() }
}

function receipt(route: Route, committedVersion: number) {
  return route.fulfill({ status: 202, contentType: 'application/json', body: JSON.stringify(commandReceipt(committedVersion)) })
}

function envelope404() {
  return {
    code: 'not_found', message: 'fixture has no such resource', correlationId: 'a05-fixture',
    retryable: false, currentVersion: null, details: {},
  }
}

function json(route: Route, body: unknown, status = 200) {
  return route.fulfill({ status, json: body })
}

async function serveAsset(route: Route, pathname: string): Promise<void> {
  const mime: Record<string, string> = {
    '.html': 'text/html', '.js': 'application/javascript', '.css': 'text/css',
    '.svg': 'image/svg+xml', '.woff2': 'font/woff2', '.png': 'image/png', '.ico': 'image/x-icon',
  }
  // SPA 路由（/work、/login）回落到唯一的构建产物文档。
  for (const file of [resolve(dist, `.${pathname}`), resolve(dist, 'index.html')]) {
    if (!file.startsWith(`${dist}${sep}`)) continue
    try {
      return route.fulfill({ body: await readFile(file), contentType: mime[extname(file)] ?? 'application/octet-stream' })
    } catch { /* 尝试下一个候选 */ }
  }
  return route.abort()
}
