import { readFile } from 'node:fs/promises'
import { extname, resolve, sep } from 'node:path'
import type { Page, Route } from '@playwright/test'
import { permissions } from '../../src/app/auth'

/**
 * M9b-F02 browser fixtures：与 m9b-a05 相同的「保留域源 + 全拦截 API」模式，世界聚焦在
 * 配置往返（configure-return）：委托表单的 preflight 模型缺口与项目默认值缺口，携
 * from/delegate 坐标离开 work，settings 页「返回 <来源>」，回来后 delegate 深链重开
 * 表单并恢复草稿。URL 只携带对象 id——草稿文本与凭证绝不进入查询串。
 */
export const origin = 'http://crewscope-f02.test'
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
  agentPersonalProfile: '00000000-0000-0000-0000-000000001701',
  agentPersonalPrincipal: '00000000-0000-0000-0000-000000001711',
  agentReviewerProfile: '00000000-0000-0000-0000-000000001703',
  agentReviewerPrincipal: '00000000-0000-0000-0000-000000001713',
  repositoryBinding: '00000000-0000-0000-0000-000000001801',
}

export const ownerLineId = '00000000-0000-0000-0000-000000000901'

export function workUrl(extra: Record<string, string> = {}): string {
  const search = new URLSearchParams({ team: ids.team, project: ids.project, workItem: ids.workItem, ...extra })
  return `${origin}/work?${search.toString()}`
}

export function sessionFor() {
  const granted = Object.values(permissions)
  return {
    authenticated: true,
    registrationMode: 'OPEN',
    csrf: { headerName: 'X-XSRF-TOKEN', parameterName: '_csrf', token: 'csrf-f02' },
    account: {
      accountId: '00000000-0000-0000-0000-0000000000a1', username: 'f02-owner',
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
    agentRow(ids.agentReviewerProfile, ids.agentReviewerPrincipal, 'Architecture Reviewer', 'USER', 'REVIEWER', 'reviewer-specialist', 'ACTIVE'),
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

export interface F02World {
  session: ReturnType<typeof sessionFor>
  workItemVersion: number
  responsibilities: Array<ReturnType<typeof responsibilityLine>>
  /** tasks/preflight 是否以模型缺口失败（MODEL_BINDING_MISSING）——「去配置并返回」的场景开关。 */
  preflightModelGap: boolean
  /** delegation-context 的项目默认值是否缺仓库与构建方案。 */
  defaultsGap: boolean
  unexpected: string[]
}

export async function mockF02App(page: Page): Promise<F02World> {
  const world: F02World = {
    session: sessionFor(),
    workItemVersion: 3,
    responsibilities: [responsibilityLine(ownerLineId, 'OWNER', ids.principal, 'USER', '张凯旋')],
    preflightModelGap: false,
    defaultsGap: false,
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

async function handleApi(route: Route, world: F02World, url: URL): Promise<void> {
  const request = route.request()
  const path = url.pathname
  const method = request.method()

  if (method === 'GET' && path === '/api/v1/auth/session') return json(route, world.session)
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
    // F02 的新词表：CODING_REVIEW 的执行默认值缺口有自己的 reasonCode 与 actionKey。
    // 网关在浏览器边界硬校验六项能力齐全，夹具必须提供完整集合。
    return json(route, {
      organizationId: ids.organization, teamId: ids.team, snapshotVersion: 'f02-1',
      observedAt: '2026-08-08T04:00:00Z', requiredReady: false,
      capabilities: [
        { capability: 'PERSONAL_CONVERSATION', required: true, status: 'ACTION_REQUIRED', reasonCode: 'PERSONAL_AGENT_CONFIGURATION_REQUIRED', canConfigure: true, responsibleParty: '当前成员', actionKey: 'OPEN_AGENT_SETTINGS' },
        { capability: 'TEAM_TASK', required: true, status: 'READY', reasonCode: 'READY', canConfigure: false, responsibleParty: 'Team 管理员', actionKey: null },
        { capability: 'CODING_REVIEW', required: true, status: 'ACTION_REQUIRED', reasonCode: 'EXECUTION_DEFAULTS_REQUIRED', canConfigure: true, responsibleParty: 'Team 管理员', actionKey: 'OPEN_EXECUTION_DEFAULTS' },
        { capability: 'GITHUB_DRAFT_PR', required: false, status: 'READY', reasonCode: 'READY', canConfigure: false, responsibleParty: 'Team 管理员', actionKey: null },
        { capability: 'LARK_NOTIFICATIONS', required: false, status: 'READY', reasonCode: 'READY', canConfigure: false, responsibleParty: 'Team 管理员', actionKey: null },
        { capability: 'TEAM_OBSERVER', required: false, status: 'READY', reasonCode: 'READY', canConfigure: false, responsibleParty: 'Team 成员', actionKey: null },
      ],
    })
  }
  if (method === 'GET' && path.endsWith(`/${ids.team}/configuration-health`)) {
    return json(route, {
      organizationId: ids.organization, teamId: ids.team, observedAt: '2026-08-08T04:00:00Z',
      overallStatus: 'ACTION_REQUIRED', items: [
        { component: 'AGENT_CONFIGURATION', status: 'ACTION_REQUIRED', reasonCode: 'AGENT_CONFIGURATION_REQUIRED', responsibleParty: '当前成员', actionKey: 'OPEN_AGENT_SETTINGS' },
        { component: 'MODEL_CONNECTION', status: 'READY', reasonCode: 'READY', responsibleParty: '当前成员', actionKey: null },
        { component: 'CREDENTIAL', status: 'READY', reasonCode: 'READY', responsibleParty: '当前成员', actionKey: null },
        { component: 'INTEGRATION', status: 'READY', reasonCode: 'READY', responsibleParty: 'Team 管理员', actionKey: null },
      ],
    })
  }
  if (method === 'GET' && path.endsWith('/work-desk')) {
    return json(route, {
      organizationId: ids.organization, teamId: ids.team, projectId: null, generatedAt: '2026-08-08T04:00:00Z',
      sections: [{
        key: 'WORK_ITEM', title: '我的工作项', priority: 1, total: 1, truncated: false,
        items: [{
          objectType: 'WORK_ITEM', objectId: ids.workItem, projectId: ids.project,
          title: '配置往返验收工作项', status: 'IN_PROGRESS', updatedAt: '2026-08-08T03:00:00Z',
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

  // ── settings 页面（configure-return 的目的地只要能挂载并展示返回按钮） ─────────
  if (method === 'GET' && path.endsWith('/model-providers')) return json(route, { items: [], nextOffset: null })
  if (method === 'GET' && path.endsWith('/model-connections')) return json(route, { items: [], nextOffset: null })
  if (method === 'GET' && path.endsWith('/execution-defaults/options')) {
    return json(route, { items: [{ key: 'maven-java-17', version: 1, profileHash: 'b'.repeat(64) }] })
  }
  if (method === 'GET' && path.endsWith('/execution-defaults')) {
    return json(route, {
      version: 0,
      repositoryBindingId: { value: null, source: 'PROJECT_DEFAULT', availability: 'MISSING', reason: '尚未设置项目仓库' },
      repositoryBindingVersion: { value: null, source: 'PROJECT_DEFAULT', availability: 'MISSING', reason: '尚未设置项目仓库' },
      branch: { value: null, source: 'PROJECT_DEFAULT', availability: 'MISSING', reason: '仓库绑定默认分支将被使用' },
      buildProfile: { value: null, source: 'PROJECT_DEFAULT', availability: 'MISSING', reason: '尚未设置构建方案' },
      agentProfileId: { value: null, source: 'PROJECT_DEFAULT', availability: 'INHERITED', reason: '使用任务/团队解析结果' },
      agentProfileRevision: { value: null, source: 'PROJECT_DEFAULT', availability: 'INHERITED', reason: '使用任务/团队解析结果' },
    })
  }
  if (method === 'GET' && path.endsWith('/repository-catalog')) {
    return json(route, { items: [{ repositoryKey: 'crewscope-java', availability: 'AVAILABLE', suggestedDefaultBranch: 'main' }] })
  }
  if (method === 'GET' && path.endsWith('/repository-bindings')) {
    return json(route, { items: [repositoryBinding()] })
  }
  if (method === 'GET' && path.endsWith('/coding-target/build-profiles')) {
    return json(route, { items: [{
      key: 'maven-java-17', version: 1, profileHash: 'b'.repeat(64),
      buildTool: 'MAVEN', javaRelease: 17, commandKinds: ['COMPILE', 'TEST', 'VERIFY'],
    }] })
  }

  // ── WorkItem 表面 ────────────────────────────────────────────────────────────
  if (method === 'GET' && path.endsWith('/work-items')) {
    return json(route, { items: [withAvailability(workItemRow(world))], nextCursor: null })
  }
  const workItemTaskPreflightMatch = path.match(/\/work-projects\/([^/]+)\/work-items\/([^/]+)\/tasks\/preflight$/)
  if (workItemTaskPreflightMatch && method === 'POST') {
    if (world.preflightModelGap) {
      return json(route, {
        code: 'model_binding_missing', message: '执行范围没有可用模型 Binding', correlationId: 'f02-fixture',
        retryable: false, currentVersion: null, details: { reason: 'MODEL_BINDING_MISSING' },
      }, 422)
    }
    return json(route, {
      agentProfileId: ids.agentPersonalProfile, agentProfileVersion: 2, executionScope: 'PERSONAL',
      configurationRevision: 2, configurationHash: 'c'.repeat(64), bindingSource: 'DIRECT',
      templateVersion: 'personal-assistant@1',
      primary: {
        role: 'PRIMARY', providerKey: 'deepseek', connectionId: '00000000-0000-0000-0000-000000002001',
        connectionOwnerType: 'USER', modelId: 'deepseek-v4-flash', catalogRevision: 7,
        modelRevision: '2026-08', priceRevision: 3,
      },
      fallback: null, policyPackId: '00000000-0000-0000-0000-000000002101', policyPackVersion: 4,
      resolutionHash: 'd'.repeat(64),
    })
  }
  const delegationContextMatch = path.match(/\/work-projects\/([^/]+)\/work-items\/([^/]+)\/delegation-context$/)
  if (delegationContextMatch && method === 'GET') {
    const lines = world.responsibilities.filter(entry => entry.workItemId === delegationContextMatch[2])
    const unavailable = (reason: string) => ({ value: null, source: 'PROJECT_DEFAULT', availability: 'MISSING', reason })
    const available = <T,>(value: T, reason: string) => ({ value, source: 'PROJECT_DEFAULT', availability: 'AVAILABLE', reason })
    return json(route, {
      workItem: { id: delegationContextMatch[2]!, projectId: delegationContextMatch[1]!, version: world.workItemVersion, title: workItemRow(world).title, status: 'IN_PROGRESS' },
      responsibilities: lines.map(entry => ({
        assignmentId: entry.id, role: entry.role, actorPrincipalId: entry.actorPrincipalId,
        actorType: entry.actorType, actorDisplayName: entry.actorDisplayName,
        actorAgentProfileId: entry.actorAgentProfileId, version: entry.version,
      })),
      candidates: agentDirectory().map(agent => ({
        agentProfileId: agent.id, agentProfileVersion: agent.version, agentPrincipalId: agent.principalId,
        displayName: agent.displayName, ownershipType: agent.ownershipType, runtimeRole: agent.runtimeRole,
        state: 'AVAILABLE', reason: null,
      })),
      defaults: {
        version: 1,
        repositoryBindingId: world.defaultsGap ? unavailable('尚未设置项目仓库') : available(ids.repositoryBinding, '项目已选择仓库绑定'),
        repositoryBindingVersion: world.defaultsGap ? unavailable('尚未设置项目仓库') : available(1, '项目已选择仓库绑定'),
        branch: world.defaultsGap ? unavailable('仓库绑定默认分支将被使用') : available('main', '项目默认分支'),
        buildProfile: world.defaultsGap ? unavailable('尚未设置构建方案') : available({ key: 'maven-java-17', version: 1, profileHash: 'b'.repeat(64) }, '项目已选择受控构建方案'),
        agentProfileId: { value: null, source: 'PROJECT_DEFAULT', availability: 'INHERITED', reason: '使用任务/团队解析结果' },
        agentProfileRevision: { value: null, source: 'PROJECT_DEFAULT', availability: 'INHERITED', reason: '使用任务/团队解析结果' },
      },
      activeExecution: false,
      permissions: { canAssignResponsibility: true, canDelegate: true },
    })
  }
  const responsibilityMatch = path.match(/\/work-items\/([^/]+)\/responsibilities$/)
  if (responsibilityMatch && method === 'GET') {
    return json(route, world.responsibilities.filter(entry => entry.workItemId === responsibilityMatch[1]))
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
        correlationId: 'f02-correlation', causationId: null, occurredAt: '2026-08-08T01:00:00Z',
        outcome: 'SUCCEEDED', payload: { workItemId: ids.workItem },
      }],
      nextCursor: null,
    })
  }
  const workItemActivityMatch = path.match(/\/work-projects\/([^/]+)\/work-items\/([^/]+)\/activity(?:\/snapshot)?$/)
  if (workItemActivityMatch && method === 'GET') {
    return json(route, {
      items: [], hasMore: false, nextCursor: null,
      snapshotCursor: path.endsWith('/snapshot') ? 'f02-activity-cursor' : undefined,
    })
  }
  const workItemConversationMatch = path.match(/\/work-projects\/([^/]+)\/work-items\/([^/]+)\/conversations$/)
  if (workItemConversationMatch && method === 'GET') return json(route, [])
  if (method === 'GET' && path.endsWith('/tasks')) return json(route, { items: [], nextCursor: null })
  if (method === 'GET' && path.endsWith('/runtime-health')) {
    return json(route, {
      environment: 'production', observedAt: '2026-08-08T03:41:00Z', health: 'HEALTHY',
      runtimeCount: 1, workerCount: 2, activeWorkerCount: 2, staleWorkerCount: 0, drainingWorkerCount: 0,
      capacity: { maximum: 6, active: 2, available: 4 }, waitingRuntimeExecutions: 0,
      waitingCauses: [], workers: [],
    })
  }
  const detailMatch = path.match(/\/work-items\/([^/]+)$/)
  if (detailMatch && method === 'GET') {
    if (detailMatch[1] !== ids.workItem) return json(route, envelope404(), 404)
    const body = JSON.stringify({
      workItem: withAvailability(workItemRow(world)),
      comments: [],
      resourceLinks: [],
    })
    return route.fulfill({
      status: 200, contentType: 'application/json',
      headers: { ETag: `"${world.workItemVersion}"`, 'Cache-Control': 'no-store' }, body,
    })
  }

  // 未知端点像真实服务端一样 fail closed；本套件不应依赖它们。
  return json(route, envelope404(), 404)
}

function workItemRow(world: F02World) {
  return {
    id: ids.workItem, organizationId: ids.organization, teamId: ids.team, workspaceId: ids.workspace,
    projectId: ids.project, key: 'F02-1', type: 'FEATURE', title: '配置往返验收工作项',
    description: '验证 M9b-F02 的配置往返与 delegate 重开。', status: 'IN_PROGRESS', priority: 'HIGH',
    labels: [], dueAt: null, source: 'CREWSCOPE', sourceReference: null, version: world.workItemVersion,
    createdAt: '2026-08-08T01:00:00Z', createdByPrincipalId: ids.principal,
    updatedAt: '2026-08-08T02:00:00Z', updatedByPrincipalId: ids.principal,
  }
}

function withAvailability<T extends { status: string }>(item: T) {
  return { ...item, availableActions: [] }
}

function repositoryBinding() {
  return {
    id: ids.repositoryBinding, organizationId: ids.organization, teamId: ids.team, workspaceId: ids.workspace,
    projectId: ids.project, kind: 'LOCAL_MANAGED', repositoryKey: 'crewscope-java', defaultBranch: 'main',
    status: 'ACTIVE', version: 1, createdAt: '2026-08-08T01:00:00Z', createdByPrincipalId: ids.principal,
    updatedAt: '2026-08-08T04:00:00Z', updatedByPrincipalId: ids.principal,
  }
}

function envelope404() {
  return {
    code: 'not_found', message: 'fixture has no such resource', correlationId: 'f02-fixture',
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
  // SPA 路由（/work、/settings/*）回落到唯一的构建产物文档。
  for (const file of [resolve(dist, `.${pathname}`), resolve(dist, 'index.html')]) {
    if (!file.startsWith(`${dist}${sep}`)) continue
    try {
      return route.fulfill({ body: await readFile(file), contentType: mime[extname(file)] ?? 'application/octet-stream' })
    } catch { /* 尝试下一个候选 */ }
  }
  return route.abort()
}
