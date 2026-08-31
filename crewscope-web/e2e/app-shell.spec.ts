import { expect, test, type Route } from '@playwright/test'
import AxeBuilder from '@axe-core/playwright'
import { createHash } from 'node:crypto'
import { authenticatedSession } from './auth-session'

const ids = {
  organization: '00000000-0000-0000-0000-000000000001',
  principal: '00000000-0000-0000-0000-000000000101',
  team: '00000000-0000-0000-0000-000000000201',
  secondTeam: '00000000-0000-0000-0000-000000000202',
  member: '00000000-0000-0000-0000-000000000301',
  project: '00000000-0000-0000-0000-000000000401',
  secondProject: '00000000-0000-0000-0000-000000000402',
  workspace: '00000000-0000-0000-0000-000000000501',
  secondWorkspace: '00000000-0000-0000-0000-000000000502',
  workItem: '00000000-0000-0000-0000-000000000601',
  secondPrincipal: '00000000-0000-0000-0000-000000000102',
  thirdPrincipal: '00000000-0000-0000-0000-000000000103',
  specialistAgent: '00000000-0000-0000-0000-000000000104',
  personalAgent: '00000000-0000-0000-0000-000000000105',
  conversation: '00000000-0000-0000-0000-000000001101',
  secondConversation: '00000000-0000-0000-0000-000000001102',
  taskIntent: '00000000-0000-0000-0000-000000001201',
  task: '00000000-0000-0000-0000-000000001501',
  taskExecution: '00000000-0000-0000-0000-000000001601',
  previousTaskExecution: '00000000-0000-0000-0000-000000001602',
  taskPlan: '00000000-0000-0000-0000-000000001611',
  previousTaskPlan: '00000000-0000-0000-0000-000000001612',
  taskStep: '00000000-0000-0000-0000-000000001621',
  taskRun: '00000000-0000-0000-0000-000000001631',
  taskLease: '00000000-0000-0000-0000-000000001641',
  agentProfile: '00000000-0000-0000-0000-000000001701',
  agentCoding: '00000000-0000-0000-0000-000000001702',
  agentReviewer: '00000000-0000-0000-0000-000000001703',
  agentTeam: '00000000-0000-0000-0000-000000001704',
  agentCreated: '00000000-0000-0000-0000-000000001705',
  repositoryBinding: '00000000-0000-0000-0000-000000001801',
  codingWorkspace: '00000000-0000-0000-0000-000000001901',
  previousCodingWorkspace: '00000000-0000-0000-0000-000000001902',
  userModelConnection: '00000000-0000-0000-0000-000000002001',
  teamModelConnection: '00000000-0000-0000-0000-000000002002',
  reviewRequest: '00000000-0000-0000-0000-000000002101',
  previousReviewRequest: '00000000-0000-0000-0000-000000002102',
  reviewFinding: '00000000-0000-0000-0000-000000002103',
  reviewContext: '00000000-0000-0000-0000-000000002104',
  reviewDiffArtifact: '00000000-0000-0000-0000-000000002105',
  reviewDecision: '00000000-0000-0000-0000-000000002106',
  reviewModificationRound: '00000000-0000-0000-0000-000000002107',
  githubConnection: '00000000-0000-0000-0000-000000002201',
  githubBinding: '00000000-0000-0000-0000-000000002202',
  actionBundle: '00000000-0000-0000-0000-000000002203',
  actionConfirmation: '00000000-0000-0000-0000-000000002204',
  pushAction: '00000000-0000-0000-0000-000000002205',
  pullRequestAction: '00000000-0000-0000-0000-000000002206',
  pushDispatch: '00000000-0000-0000-0000-000000002207',
  pullRequestDispatch: '00000000-0000-0000-0000-000000002208',
}

test.beforeEach(async ({ page }) => {
  // Keep Today and date rendering deterministic so visual diffs represent UI changes instead of wall-clock drift.
  await page.clock.setFixedTime(new Date('2026-08-08T04:00:00Z'))
  const workItems = [
    workItem(ids.workItem, 'CRW-18', '共享范围与筛选状态', 'FEATURE', 'IN_PROGRESS', 'HIGH'),
    workItem('00000000-0000-0000-0000-000000000602', 'CRW-19', '修复工作项游标', 'BUG', 'READY', 'URGENT'),
    workItem('00000000-0000-0000-0000-000000000603', 'CRW-20', '准备阶段发布', 'TASK', 'BACKLOG', 'MEDIUM'),
    workItem('00000000-0000-0000-0000-000000000604', 'CRW-21', '审核协作入口', 'FEATURE', 'IN_REVIEW', 'HIGH'),
    workItem('00000000-0000-0000-0000-000000000605', 'CRW-22', '归档测试证据', 'TASK', 'DONE', 'LOW'),
  ]
  const comments = [{ id: '00000000-0000-0000-0000-000000000701', workItemId: ids.workItem, authorPrincipalId: ids.principal, content: '已确认交付范围。', source: 'CREWSCOPE', externalId: null, createdAt: '2026-08-08T03:00:00Z' }]
  const resources: Array<{ id: string; workItemId: string; resourceType: string; resourceReference: string; label: string | null; createdAt: string; createdByPrincipalId: string }> = [{ id: '00000000-0000-0000-0000-000000000801', workItemId: ids.workItem, resourceType: 'REPOSITORY', resourceReference: 'crewscope-java', label: '主仓库', createdAt: '2026-08-08T03:10:00Z', createdByPrincipalId: ids.principal }]
  let responsibilities = [
    responsibility('00000000-0000-0000-0000-000000000901', 'OWNER', ids.principal, 'USER', '张凯旋'),
    responsibility('00000000-0000-0000-0000-000000000902', 'EXECUTOR', ids.secondPrincipal, 'USER', '林晨'),
    responsibility('00000000-0000-0000-0000-000000000904', 'EXECUTOR', ids.personalAgent, 'PERSONAL_AGENT', '张凯旋的 Personal Agent', ids.agentProfile),
    responsibility('00000000-0000-0000-0000-000000000903', 'REVIEWER', ids.specialistAgent, 'SPECIALIST_AGENT', 'Architecture Reviewer'),
  ]
  const tasks = [task(ids.task, ids.workItem, '完成 Agent Task 列表与委托入口', 'WAITING', 'WAITING', 'CAPACITY', 2)]
  const conversationTaskIds = new Set([ids.task])
  const acceptedTaskKeys = new Map<string, string>()
  const acceptedTaskCommandKeys = new Set<string>()
  const timeline = [
    timelineEvent('00000000-0000-0000-0000-000000001001', 'RESPONSIBILITY_ASSIGNED', '2026-08-08T03:20:00Z', '林晨'),
    timelineEvent('00000000-0000-0000-0000-000000001002', 'WORK_ITEM_CREATED', '2026-08-08T01:00:00Z', '张凯旋'),
  ]
  const conversations = [
    conversation(ids.conversation, ids.team, ids.workspace, '规划 GitHub Provider 接入', 'PRIVATE', 4),
    conversation(ids.secondConversation, ids.team, ids.workspace, '协作准备 M2 发布', 'TEAM', null),
  ]
  const associations = [conversationWorkItemAssociation()]
  const messagesByConversation: Record<string, Array<ReturnType<typeof conversationMessage>>> = {
    [ids.conversation]: [
      conversationMessage('00000000-0000-0000-0000-000000001301', ids.conversation, 1, 'USER_MESSAGE', ids.principal, '**目标**：规划 GitHub Provider 接入。', '2026-08-08T01:10:00Z'),
      conversationMessage('00000000-0000-0000-0000-000000001302', ids.conversation, 2, 'AGENT_MESSAGE', ids.personalAgent, '已收到。我会先梳理 `Connection`、权限和审计边界。', '2026-08-08T01:11:00Z'),
      conversationMessage('00000000-0000-0000-0000-000000001303', ids.conversation, 3, 'SYSTEM_NOTICE', null, 'Conversation 已切换为真实消息模式。', '2026-08-08T01:12:00Z'),
      conversationMessage('00000000-0000-0000-0000-000000001304', ids.conversation, 4, 'USER_MESSAGE', ids.principal, '请保留团队协作与最小权限原则。', '2026-08-08T01:13:00Z'),
    ],
    [ids.secondConversation]: [],
  }
  const acceptedMessageKeys = new Set<string>()
  const acceptedInvocations = new Map<string, { invocationId: string; userMessageId: string; agentMessageId: string }>()
  let repositoryBindingVersion = 1
  let repositoryBindingStatus = 'ACTIVE'
  let managedAgents = agentDirectory()
  const configurationRevisions = new Map(managedAgents.map(agent => [agent.id, 2]))
  let modelConnections = [
    modelConnection(ids.userModelConnection, 'USER', ids.principal, 'HEALTHY'),
    modelConnection(ids.teamModelConnection, 'TEAM', ids.team, 'UNHEALTHY'),
  ]
  let reviewVersion = 1
  let reviewStatus = 'OPEN'
  let reviewLatestDecision: string | null = null
  let reviewDecisions: ReturnType<typeof reviewDecision>[] = []
  let reviewModificationRounds: ReturnType<typeof reviewModificationRound>[] = []
  const acceptedReviewCommandKeys = new Set<string>()
  const acceptedDeliveryCommandKeys = new Set<string>()
  let plannedDelivery: ReturnType<typeof githubActionBundle> | null = null
  let deliveryConfirmed = false
  let deliveryDetailReadsAfterConfirmation = 0
  const deliveryProjection = () => {
    const value = structuredClone(plannedDelivery!)
    if (!deliveryConfirmed) return value
    value.version = 1
    value.confirmation = {
      id: ids.actionConfirmation, version: 0, status: 'ACTIVE', confirmedByPrincipalId: ids.principal,
      confirmedAt: '2026-08-08T04:00:00Z', validUntil: '2026-08-08T05:00:00Z', cancellationReason: null,
    }
    value.actions[0]!.dispatch = actionDispatch(ids.pushDispatch, 'SUCCEEDED', 0)
    value.actions[0]!.receipt = actionReceipt('SUCCEEDED', 'BRANCH', 'REMOTE_HEAD_MATCHED')
    const webhookObserved = deliveryDetailReadsAfterConfirmation >= 2
    value.actions[1]!.dispatch = actionDispatch(
      ids.pullRequestDispatch, webhookObserved ? 'SUCCEEDED' : 'FAILED', webhookObserved ? 1 : 0,
    )
    value.actions[1]!.receipt = actionReceipt(
      webhookObserved ? 'SUCCEEDED' : 'FAILED', webhookObserved ? 'PULL_REQUEST' : null,
      webhookObserved ? 'DRAFT_PR_VERIFIED' : 'PROVIDER_UNAVAILABLE',
    )
    value.actions[1]!.externalResult = webhookObserved ? {
      status: 'OPEN', externalObjectType: 'PULL_REQUEST', externalIdentityHash: '3'.repeat(64),
      providerVersion: 42, providerUpdatedAt: '2026-08-08T04:02:00Z', source: 'WEBHOOK',
      observedAt: '2026-08-08T04:02:01Z', version: 2,
    } : null
    return value
  }
  await page.route(/\/api\/v1\//, async route => {
    const request = route.request()
    const url = new URL(request.url())
    const path = url.pathname
    if (request.method() === 'GET' && path === '/api/v1/auth/session') {
      await fulfillJson(route, authenticatedSession(ids.organization, ids.principal, ids.team))
      return
    }
    if (request.method() === 'GET' && path.endsWith('/teams')) {
      await fulfillJson(route, [team(ids.team, 'Platform Engineering', ids.workspace), team(ids.secondTeam, 'Security Engineering', ids.secondWorkspace)])
      return
    }
    if (request.method() === 'GET' && path.endsWith(`/${ids.team}/work-projects`)) {
      await fulfillJson(route, { items: [project(ids.project, ids.team, ids.workspace, 'CRW', 'CrewScope')], nextCursor: null })
      return
    }
    if (request.method() === 'GET' && path.endsWith(`/${ids.secondTeam}/work-projects`)) {
      await fulfillJson(route, { items: [project(ids.secondProject, ids.secondTeam, ids.secondWorkspace, 'SEC', 'Runtime Security')], nextCursor: null })
      return
    }
    if (request.method() === 'GET' && path.endsWith('/members')) {
      await fulfillJson(route, [
        { id: ids.member, userPrincipalId: ids.principal, status: 'ACTIVE', joinMethod: 'CREATED_WITH_TEAM', joinedAt: '2026-08-08T01:00:00Z', version: 0 },
        { id: '00000000-0000-0000-0000-000000000302', userPrincipalId: ids.secondPrincipal, status: 'ACTIVE', joinMethod: 'INVITED', joinedAt: '2026-08-08T01:10:00Z', version: 0 },
        { id: '00000000-0000-0000-0000-000000000303', userPrincipalId: ids.thirdPrincipal, status: 'ACTIVE', joinMethod: 'INVITED', joinedAt: '2026-08-08T01:20:00Z', version: 0 },
      ])
      return
    }
    if (request.method() === 'GET' && path.endsWith('/model-providers')) {
      await fulfillJson(route, { items: [modelProvider()] })
      return
    }
    if (request.method() === 'GET' && path.endsWith('/model-providers/deepseek/catalog')) {
      await fulfillJson(route, { items: modelCatalog() })
      return
    }
    if (request.method() === 'GET' && path.endsWith('/model-connections')) {
      const ownerType = url.searchParams.get('ownerType')
      await fulfillJson(route, { items: modelConnections.filter(connection => connection.ownerType === ownerType) })
      return
    }
    if (request.method() === 'POST' && path.endsWith('/model-connections')) {
      expect(request.headers()['idempotency-key']).toBeTruthy()
      const input = request.postDataJSON() as { providerKey: string, ownerType: string, teamId: string | null, region: string, apiKey: string, credentialExpiresAt: string | null }
      expect(input.apiKey).toBeTruthy()
      expect(Object.keys(input).sort()).toEqual(['apiKey', 'credentialExpiresAt', 'ownerType', 'providerKey', 'region', 'teamId'])
      const created = modelConnection(crypto.randomUUID(), input.ownerType, input.ownerType === 'TEAM' ? ids.team : ids.principal, 'UNKNOWN')
      modelConnections = [...modelConnections, created]
      await fulfillReceipt(route, 0)
      return
    }
    const modelConnectionCommand = path.match(/\/model-connections\/([^/]+)\/(verify|rotate|suspend|revoke)$/)
    if (request.method() === 'POST' && modelConnectionCommand) {
      const current = modelConnections.find(connection => connection.id === modelConnectionCommand[1])!
      expect(request.headers()['idempotency-key']).toBeTruthy()
      expect(request.headers()['if-match']).toBe(`"${current.version}"`)
      const input = request.postDataJSON() as { credentialVersion: number, apiKey?: string, reason?: string }
      expect(input.credentialVersion).toBe(current.credentialVersion)
      const operation = modelConnectionCommand[2]
      if (operation === 'rotate') expect(input.apiKey).toBeTruthy()
      if (operation === 'revoke') expect(['OWNER_REQUESTED', 'CREDENTIAL_REVOKED', 'PROVIDER_DISABLED', 'POLICY_REVOKED', 'SECURITY_INCIDENT']).toContain(input.reason)
      const updated = {
        ...current,
        credentialVersion: operation === 'rotate' ? current.credentialVersion + 1 : current.credentialVersion,
        status: operation === 'suspend' ? 'SUSPENDED' : operation === 'revoke' ? 'REVOKED' : current.status,
        healthStatus: operation === 'verify' ? 'HEALTHY' : operation === 'rotate' ? 'UNKNOWN' : current.healthStatus,
        healthFailureCode: operation === 'verify' || operation === 'rotate' ? null : current.healthFailureCode,
        checkedAt: operation === 'verify' ? '2026-08-08T04:00:00Z' : current.checkedAt,
        lastHealthyAt: operation === 'verify' ? '2026-08-08T04:00:00Z' : current.lastHealthyAt,
        consecutiveFailures: operation === 'verify' || operation === 'rotate' ? 0 : current.consecutiveFailures,
        revocationReason: operation === 'revoke' ? input.reason ?? 'OWNER_REQUESTED' : current.revocationReason,
        version: current.version + 1,
      }
      modelConnections = modelConnections.map(connection => connection.id === current.id ? updated : connection)
      await fulfillReceipt(route, updated.version)
      return
    }
    const modelConnectionDetail = path.match(/\/model-connections\/([^/]+)$/)
    if (request.method() === 'GET' && modelConnectionDetail) {
      const current = modelConnections.find(connection => connection.id === modelConnectionDetail[1])
      if (!current) return fulfillError(route, 404, 'model_connection_not_found', 'Model Connection 不存在')
      await route.fulfill({ status: 200, contentType: 'application/json', headers: { ETag: `"${current.version}"`, 'Cache-Control': 'no-store' }, body: JSON.stringify(current) })
      return
    }
    if (request.method() === 'GET' && path.endsWith('/agent-templates')) {
      const ownershipType = url.searchParams.get('ownershipType')
      await fulfillJson(route, { items: agentTemplates(ownershipType === 'TEAM' ? 'TEAM' : 'USER') })
      return
    }
    if (request.method() === 'GET' && path.endsWith('/agent-profiles')) {
      const requestedTeam = path.match(/\/teams\/([^/]+)\/agent-profiles$/)?.[1]
      await fulfillJson(route, { items: requestedTeam === ids.team ? managedAgents : [] })
      return
    }
    if (request.method() === 'POST' && path.endsWith('/agent-profiles')) {
      expect(request.headers()['idempotency-key']).toBeTruthy()
      const input = request.postDataJSON() as {
        publisherType: string, templateKey: string, templateVersion: number,
        ownershipType: string, displayName: string,
      }
      expect(Object.keys(input).sort()).toEqual([
        'displayName', 'ownershipType', 'publisherType', 'templateKey', 'templateVersion',
      ])
      const runtimeRole = input.templateKey.includes('review') ? 'REVIEWER' : 'CODING'
      managedAgents = [...managedAgents, agentProfile(
        ids.agentCreated, input.displayName, input.ownershipType, runtimeRole,
        input.templateKey, false, 'ACTIVE', input.templateVersion, null,
      )]
      configurationRevisions.delete(ids.agentCreated)
      await fulfillReceipt(route, 0)
      return
    }
    const agentDetailMatch = path.match(/\/agent-profiles\/([^/]+)$/)
    if (request.method() === 'GET' && agentDetailMatch) {
      const profile = managedAgents.find(agent => agent.id === agentDetailMatch[1])
      if (!profile) {
        await fulfillError(route, 404, 'agent_not_found', 'Agent 不存在')
        return
      }
      await route.fulfill({
        status: 200, contentType: 'application/json', headers: { ETag: `"${profile.version}"` },
        body: JSON.stringify(profile),
      })
      return
    }
    const agentHistoryMatch = path.match(/\/agent-profiles\/([^/]+)\/configurations$/)
    if (request.method() === 'GET' && agentHistoryMatch) {
      const revision = configurationRevisions.get(agentHistoryMatch[1]!)
      await fulfillJson(route, { items: revision ? configurationHistory(agentHistoryMatch[1]!, revision) : [] })
      return
    }
    const agentConfigurationMatch = path.match(/\/agent-profiles\/([^/]+)\/configurations\/current$/)
    if (request.method() === 'GET' && agentConfigurationMatch) {
      const revision = configurationRevisions.get(agentConfigurationMatch[1]!)
      if (!revision) {
        await fulfillError(route, 404, 'configuration_not_found', 'Configuration 尚未创建')
        return
      }
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        headers: { ETag: `"${revision}"` },
        body: JSON.stringify(agentConfiguration(agentConfigurationMatch[1]!, revision)),
      })
      return
    }
    const agentModelCatalogMatch = path.match(/\/agent-profiles\/([^/]+)\/model-catalog$/)
    if (request.method() === 'GET' && agentModelCatalogMatch) {
      await fulfillJson(route, { items: selectableAgentModels(url.searchParams.get('executionScope') ?? 'PERSONAL') })
      return
    }
    const agentConfigurationAppendMatch = path.match(/\/agent-profiles\/([^/]+)\/configurations$/)
    if (request.method() === 'POST' && agentConfigurationAppendMatch) {
      const profileId = agentConfigurationAppendMatch[1]!
      const expected = configurationRevisions.get(profileId) ?? 0
      expect(request.headers()['if-match']).toBe(`"${expected}"`)
      expect(request.headers()['idempotency-key']).toBeTruthy()
      expect(JSON.stringify(request.postDataJSON())).not.toMatch(/apiKey|credential|systemPrompt|toolPayload/)
      const committed = expected + 1
      configurationRevisions.set(profileId, committed)
      managedAgents = managedAgents.map(agent => agent.id === profileId
        ? { ...agent, currentConfigurationRevision: committed, currentConfigurationHash: 'c'.repeat(64) }
        : agent)
      await fulfillReceipt(route, committed)
      return
    }
    const agentPreflightMatch = path.match(/\/agent-profiles\/([^/]+)\/model-preflight$/)
    if (request.method() === 'POST' && agentPreflightMatch) {
      const executionScope = (request.postDataJSON() as { executionScope: string }).executionScope
      const revision = configurationRevisions.get(agentPreflightMatch[1]!) ?? 1
      await fulfillJson(route, agentPreflight(agentPreflightMatch[1]!, executionScope, revision))
      return
    }
    const agentLifecycleMatch = path.match(/\/agent-profiles\/([^/]+)\/(activate|disable|archive)$/)
    if (request.method() === 'POST' && agentLifecycleMatch) {
      expect(request.headers()['idempotency-key']).toBeTruthy()
      const profile = managedAgents.find(agent => agent.id === agentLifecycleMatch[1])!
      expect(request.headers()['if-match']).toBe(`"${profile.version}"`)
      const status = agentLifecycleMatch[2] === 'activate' ? 'ACTIVE' : agentLifecycleMatch[2]!.toUpperCase() + 'D'
      managedAgents = managedAgents.map(agent => agent.id === profile.id
        ? { ...agent, status, principalStatus: status, version: agent.version + 1 }
        : agent)
      await fulfillReceipt(route, profile.version + 1)
      return
    }
    if (request.method() === 'GET' && path.endsWith('/repository-catalog')) {
      await fulfillJson(route, { items: [
        { repositoryKey: 'crewscope-java', availability: 'AVAILABLE', suggestedDefaultBranch: 'main' },
        { repositoryKey: 'agentscope-java', availability: 'AVAILABLE', suggestedDefaultBranch: 'main' },
        { repositoryKey: 'archived-service', availability: 'UNAVAILABLE', suggestedDefaultBranch: null },
      ] })
      return
    }
    if (request.method() === 'GET' && path.endsWith('/repository-bindings')) {
      await fulfillJson(route, { items: [repositoryBinding(repositoryBindingStatus, repositoryBindingVersion)] })
      return
    }
    if (request.method() === 'POST' && path.endsWith('/repository-bindings/preflight')) {
      const input = request.postDataJSON() as { repositoryKey: string, defaultBranch: string }
      await fulfillJson(route, {
        ready: true, repositoryKey: input.repositoryKey, baselineRef: input.defaultBranch,
        baselineCommit: 'a'.repeat(40),
      })
      return
    }
    const repositoryPreflightMatch = path.match(/\/repository-bindings\/([^/]+)\/preflight$/)
    if (request.method() === 'POST' && repositoryPreflightMatch) {
      await fulfillJson(route, {
        ready: true, repositoryKey: 'crewscope-java', baselineRef: 'main', baselineCommit: 'a'.repeat(40),
      })
      return
    }
    const repositoryTransitionMatch = path.match(/\/repository-bindings\/([^/]+)\/(activate|disable)$/)
    if (request.method() === 'POST' && repositoryTransitionMatch) {
      expect(request.headers()['idempotency-key']).toBeTruthy()
      expect(request.headers()['if-match']).toBe(`"${repositoryBindingVersion}"`)
      repositoryBindingStatus = repositoryTransitionMatch[2] === 'activate' ? 'ACTIVE' : 'DISABLED'
      repositoryBindingVersion += 1
      await fulfillReceipt(route, repositoryBindingVersion)
      return
    }
    if (request.method() === 'GET' && path.includes('/repository-bindings/')) {
      await fulfillJson(route, repositoryBinding(repositoryBindingStatus, repositoryBindingVersion))
      return
    }
    if (request.method() === 'GET' && path.endsWith('/coding-target/build-profiles')) {
      await fulfillJson(route, { items: [{
        key: 'maven-java-17', version: 1, profileHash: 'b'.repeat(64),
        buildTool: 'MAVEN', javaRelease: 17, commandKinds: ['COMPILE', 'TEST', 'VERIFY'],
      }] })
      return
    }
    if (request.method() === 'POST' && path.endsWith('/coding-target/preflight')) {
      const input = request.postDataJSON() as { repositoryBindingId: string, baselineRef: string }
      expect(input.repositoryBindingId).toBe(ids.repositoryBinding)
      if (input.baselineRef === 'missing') {
        await fulfillError(route, 422, 'repository_ref_invalid', 'Ref 不存在或不可解析')
        return
      }
      await fulfillJson(route, {
        ready: true, repositoryKey: 'crewscope-java', baselineRef: input.baselineRef,
        baselineCommit: 'c'.repeat(40),
      })
      return
    }
    const workItemTaskPreflightMatch = path.match(/\/work-projects\/([^/]+)\/work-items\/([^/]+)\/tasks\/preflight$/)
    if (workItemTaskPreflightMatch && request.method() === 'POST') {
      const input = request.postDataJSON() as { executorAgentProfileId: string; agentConfigurationRevision: number | null }
      expect(input.executorAgentProfileId).toBe(ids.agentProfile)
      const revision = input.agentConfigurationRevision ?? configurationRevisions.get(input.executorAgentProfileId) ?? 2
      await fulfillJson(route, {
        agentProfileId: input.executorAgentProfileId,
        agentProfileVersion: 2,
        executionScope: 'PERSONAL',
        configurationRevision: revision,
        configurationHash: 'c'.repeat(64),
        bindingSource: 'DIRECT',
        templateVersion: 'personal-assistant@1',
        primary: {
          role: 'PRIMARY', providerKey: 'deepseek', connectionId: ids.userModelConnection,
          connectionOwnerType: 'USER', modelId: 'deepseek-v4-flash', catalogRevision: 7,
          modelRevision: '2026-08', priceRevision: 3,
        },
        fallback: null,
        policyPackId: '00000000-0000-0000-0000-000000002101',
        policyPackVersion: 4,
        resolutionHash: 'd'.repeat(64),
      })
      return
    }
    const workItemTaskMatch = path.match(/\/work-projects\/([^/]+)\/work-items\/([^/]+)\/tasks$/)
    if (workItemTaskMatch && request.method() === 'GET') {
      await fulfillJson(route, {
        items: tasks.filter(item => item.projectId === workItemTaskMatch[1] && item.workItemId === workItemTaskMatch[2])
          .map(item => ({ origin: 'WORK_ITEM_ROOT', associatedAt: item.createdAt, task: { ...item, href: `/work?team=${ids.team}&project=${item.projectId}&workItem=${item.workItemId}&task=${item.id}` } })),
        nextCursor: null,
      })
      return
    }
    if (workItemTaskMatch && request.method() === 'POST') {
      const key = request.headers()['idempotency-key']!
      expect(key).toBeTruthy()
      expect(request.headers()['if-match']).toBe('"0"')
      const input = request.postDataJSON() as { objective: string; acceptanceCriteria: string[]; executorAgentProfileId: string; agentConfigurationRevision: number; conversationSource: { conversationId: string, messageId: string } | null; codingTarget: { repositoryBindingId: string, baselineRef: string, allowedPaths: string[], buildProfile: { key: string, version: number, profileHash: string } } | null }
      expect(input.executorAgentProfileId).toBe(ids.agentProfile)
      expect(input.agentConfigurationRevision).toBe(2)
      expect(input.codingTarget).toEqual({
        repositoryBindingId: ids.repositoryBinding,
        baselineRef: 'main',
        allowedPaths: ['.'],
        buildProfile: { key: 'maven-java-17', version: 1, profileHash: 'b'.repeat(64) },
      })
      if (!acceptedTaskKeys.has(key)) {
        const taskId = crypto.randomUUID()
        acceptedTaskKeys.set(key, taskId)
        tasks.unshift(task(taskId, workItemTaskMatch[2]!, input.objective, 'CREATED', 'READY', null))
        if (input.conversationSource?.conversationId === ids.conversation) conversationTaskIds.add(taskId)
      }
      await fulfillReceipt(route, 0)
      return
    }
    const taskCommandMatch = path.match(/\/tasks\/([^/]+)\/attempts\/([^/]+)\/(pause|resume|cancel|retry)$/)
    if (taskCommandMatch && request.method() === 'POST') {
      const selected = tasks.find(item => item.id === taskCommandMatch[1])
      if (!selected || selected.currentExecutionId !== taskCommandMatch[2]) {
        return fulfillError(route, 404, 'task_execution_not_found', 'Task execution not found')
      }
      const key = request.headers()['idempotency-key']!
      expect(key).toBeTruthy()
      if (request.headers()['if-match'] !== `"${selected.executionVersion}"`) {
        return fulfillError(route, 409, 'optimistic_lock_conflict', 'Task execution version changed', selected.executionVersion)
      }
      if (!acceptedTaskCommandKeys.has(key)) {
        acceptedTaskCommandKeys.add(key)
        const operation = taskCommandMatch[3]!
        if (operation === 'pause') {
          expect((request.postDataJSON() as { reason: string }).reason).toBeTruthy()
          selected.currentExecutionStatus = 'PAUSE_REQUESTED'
        } else if (operation === 'resume') {
          selected.currentExecutionStatus = 'READY'
        } else if (operation === 'cancel') {
          expect((request.postDataJSON() as { reason: string }).reason).toBeTruthy()
          selected.currentExecutionStatus = 'CANCELLED'
          selected.status = 'CANCELLED'
        } else {
          selected.previousExecutionId = selected.currentExecutionId
          selected.previousAttempt = selected.currentAttempt
          selected.currentExecutionId = crypto.randomUUID()
          selected.currentAttempt += 1
          selected.currentExecutionStatus = 'READY'
          selected.status = 'ACTIVE'
          selected.executionVersion = 2
        }
        selected.currentWaitingReason = null
        if (operation !== 'retry') selected.executionVersion += 1
        selected.version += 1
      }
      await fulfillReceipt(route, selected.executionVersion)
      return
    }
    if (request.method() === 'GET' && path.endsWith('/github-connections')) {
      const ownerType = url.searchParams.get('ownerType')
      await fulfillJson(route, { items: ownerType === 'TEAM' ? [githubConnection()] : [] })
      return
    }
    if (request.method() === 'GET' && path.endsWith(`/${ids.githubConnection}/bindings`)) {
      await fulfillJson(route, { items: [githubProviderBinding()] })
      return
    }
    if (request.method() === 'GET' && path.endsWith(`/${ids.githubConnection}/repositories`)) {
      await fulfillJson(route, { items: [githubRepository()] })
      return
    }
    if (request.method() === 'POST' && path.endsWith(`/${ids.githubConnection}/repositories/synchronize`)) {
      expect(request.headers()['if-match']).toBe('"3"')
      await fulfillJson(route, { items: [githubRepository()] })
      return
    }
    if (request.method() === 'GET' && path.endsWith(`/${ids.githubConnection}/health`)) {
      await fulfillJson(route, githubHealth())
      return
    }
    const githubPreflightMatch = path.match(/\/github-connections\/([^/]+)\/repositories\/([^/]+)\/preflight$/)
    if (githubPreflightMatch && request.method() === 'POST') {
      expect(githubPreflightMatch.slice(1)).toEqual([ids.githubConnection, '101'])
      expect(url.searchParams.get('bindingId')).toBe(ids.githubBinding)
      expect(request.headers()['if-match']).toBe('"3"')
      await fulfillJson(route, {
        connectionVersion: 3, externalRepositoryId: '101', fullName: 'crewscope/crewscope-java',
        defaultBranch: 'main', permissionsHash: '1'.repeat(64),
      })
      return
    }
    const actionCollectionMatch = path.match(/\/tasks\/([^/]+)\/attempts\/([^/]+)\/actions\/bundles$/)
    if (actionCollectionMatch && request.method() === 'GET') {
      await fulfillJson(route, { items: plannedDelivery ? [deliveryProjection()] : [] })
      return
    }
    if (actionCollectionMatch && request.method() === 'POST') {
      expect(actionCollectionMatch.slice(1)).toEqual([ids.task, ids.taskExecution])
      const key = request.headers()['idempotency-key']!
      expect(key).toBeTruthy()
      const input = request.postDataJSON() as {
        reviewDecisionId: string, providerBindingId: string, repositoryId: string,
        expectedRemoteHead?: string, title: string, body: string,
      }
      expect(input).toMatchObject({ reviewDecisionId: ids.reviewDecision, providerBindingId: ids.githubBinding, repositoryId: '101' })
      if (!acceptedDeliveryCommandKeys.has(key)) {
        acceptedDeliveryCommandKeys.add(key)
        plannedDelivery = githubActionBundle(input.title, input.body)
      }
      await fulfillCommandReceipt(route, 0)
      return
    }
    const actionDetailMatch = path.match(/\/tasks\/([^/]+)\/attempts\/([^/]+)\/actions\/bundles\/([^/]+)$/)
    if (actionDetailMatch && request.method() === 'GET') {
      if (!plannedDelivery || actionDetailMatch[3] !== ids.actionBundle) {
        return fulfillError(route, 404, 'action_bundle_not_found', 'ActionBundle not found')
      }
      if (deliveryConfirmed) deliveryDetailReadsAfterConfirmation += 1
      const detail = deliveryProjection()
      await route.fulfill({
        status: 200, contentType: 'application/json',
        headers: { ETag: `"${detail.version}"`, 'Cache-Control': 'no-store' },
        body: JSON.stringify(detail),
      })
      return
    }
    const actionConfirmMatch = path.match(/\/tasks\/([^/]+)\/attempts\/([^/]+)\/actions\/bundles\/([^/]+)\/confirmations$/)
    if (actionConfirmMatch && request.method() === 'POST') {
      expect(actionConfirmMatch.slice(1)).toEqual([ids.task, ids.taskExecution, ids.actionBundle])
      expect(request.headers()['if-match']).toBe('"0"')
      expect((request.postDataJSON() as { bundleDigest: string }).bundleDigest).toBe('a'.repeat(64))
      const key = request.headers()['idempotency-key']!
      expect(key).toBeTruthy()
      if (!acceptedDeliveryCommandKeys.has(key)) {
        acceptedDeliveryCommandKeys.add(key)
        deliveryConfirmed = true
        deliveryDetailReadsAfterConfirmation = 0
      }
      await fulfillCommandReceipt(route, 1)
      return
    }
    const reviewCollectionMatch = path.match(/\/tasks\/([^/]+)\/attempts\/([^/]+)\/reviews$/)
    if (reviewCollectionMatch && request.method() === 'GET') {
      const currentAttempt = reviewCollectionMatch[1] === ids.task && reviewCollectionMatch[2] === ids.taskExecution
      await fulfillJson(route, { items: currentAttempt ? [
        reviewSummary(ids.reviewRequest, 2, reviewVersion, reviewStatus, null, reviewLatestDecision, reviewModificationRounds.length),
        reviewSummary(ids.previousReviewRequest, 1, 3, 'INVALIDATED', 'DIFF_CHANGED', 'COMMENTED', 0),
      ] : [] })
      return
    }
    const reviewDetailMatch = path.match(/\/tasks\/([^/]+)\/attempts\/([^/]+)\/reviews\/([^/]+)$/)
    if (reviewDetailMatch && request.method() === 'GET') {
      const reviewRequestId = reviewDetailMatch[3]!
      if (reviewDetailMatch[1] !== ids.task || reviewDetailMatch[2] !== ids.taskExecution
        || ![ids.reviewRequest, ids.previousReviewRequest].includes(reviewRequestId)) {
        return fulfillError(route, 404, 'review_request_not_found', 'Review request not found')
      }
      const previous = reviewRequestId === ids.previousReviewRequest
      const detail = reviewDetails({
        id: reviewRequestId,
        revision: previous ? 1 : 2,
        version: previous ? 3 : reviewVersion,
        status: previous ? 'INVALIDATED' : reviewStatus,
        invalidationReason: previous ? 'DIFF_CHANGED' : null,
        decisions: previous
          ? [reviewDecision('COMMENTED', '历史 Review 已记录，等待新 Diff。', 1)]
          : reviewDecisions,
        modificationRounds: previous ? [] : reviewModificationRounds,
      })
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        headers: { ETag: `"${detail.version}"`, 'Cache-Control': 'no-store' },
        body: JSON.stringify(detail),
      })
      return
    }
    const reviewExecuteMatch = path.match(/\/tasks\/([^/]+)\/attempts\/([^/]+)\/reviews\/([^/]+)\/execute$/)
    if (reviewExecuteMatch && request.method() === 'POST') {
      expect(reviewExecuteMatch.slice(1)).toEqual([ids.task, ids.taskExecution, ids.reviewRequest])
      expect(request.headers()['if-match']).toBe(`"${reviewVersion}"`)
      const key = request.headers()['idempotency-key']!
      expect(key).toBeTruthy()
      if (!acceptedReviewCommandKeys.has(key)) {
        acceptedReviewCommandKeys.add(key)
        reviewStatus = 'COMPLETED'
        reviewVersion += 1
      }
      const receipt = commandReceipt(reviewVersion)
      await route.fulfill({ status: 200, contentType: 'application/json', headers: { ETag: `"${reviewVersion}"` }, body: JSON.stringify({
        receipt,
        reviewRequestId: ids.reviewRequest,
        reviewRequestVersion: reviewVersion,
        status: reviewStatus,
        effectiveFindingCount: 1,
        insertedFindingCount: 1,
        duplicateObservationCount: 0,
      }) })
      return
    }
    const reviewDecisionMatch = path.match(/\/tasks\/([^/]+)\/attempts\/([^/]+)\/reviews\/([^/]+)\/decisions$/)
    if (reviewDecisionMatch && request.method() === 'POST') {
      expect(reviewDecisionMatch.slice(1)).toEqual([ids.task, ids.taskExecution, ids.reviewRequest])
      expect(request.headers()['if-match']).toBe(`"${reviewVersion}"`)
      const key = request.headers()['idempotency-key']!
      expect(key).toBeTruthy()
      const input = request.postDataJSON() as { type: string, rationale: string }
      expect(input.rationale).toBeTruthy()
      if (!acceptedReviewCommandKeys.has(key)) {
        acceptedReviewCommandKeys.add(key)
        reviewVersion += 1
        reviewLatestDecision = input.type
        reviewDecisions.push(reviewDecision(input.type, input.rationale, reviewDecisions.length + 1))
      }
      await fulfillCommandReceipt(route, reviewVersion)
      return
    }
    const reviewModificationMatch = path.match(/\/tasks\/([^/]+)\/attempts\/([^/]+)\/reviews\/([^/]+)\/modifications$/)
    if (reviewModificationMatch && request.method() === 'POST') {
      expect(reviewModificationMatch.slice(1)).toEqual([ids.task, ids.taskExecution, ids.reviewRequest])
      expect(request.headers()['if-match']).toBe(`"${reviewVersion}"`)
      const key = request.headers()['idempotency-key']!
      expect(key).toBeTruthy()
      const input = request.postDataJSON() as { rationale: string }
      expect(input.rationale).toBeTruthy()
      if (!acceptedReviewCommandKeys.has(key)) {
        acceptedReviewCommandKeys.add(key)
        reviewVersion += 1
        reviewLatestDecision = 'CHANGES_REQUESTED'
        reviewDecisions.push(reviewDecision('CHANGES_REQUESTED', input.rationale, reviewDecisions.length + 1))
        reviewModificationRounds.push(reviewModificationRound(reviewDecisions.at(-1)!.id))
      }
      await fulfillCommandReceipt(route, reviewVersion)
      return
    }
    const codingCommandMatch = path.match(/\/tasks\/([^/]+)\/attempts\/([^/]+)\/coding\/commands$/)
    if (codingCommandMatch && request.method() === 'GET') {
      const selected = tasks.find(item => item.id === codingCommandMatch[1])
      if (!selected) return fulfillError(route, 404, 'task_not_found', 'Task not found')
      await fulfillJson(route, { items: [codingCommandEvidence(codingCommandMatch[2]!)], nextCursor: null })
      return
    }
    const codingTestMatch = path.match(/\/tasks\/([^/]+)\/attempts\/([^/]+)\/coding\/test-evidence$/)
    if (codingTestMatch && request.method() === 'GET') {
      const selected = tasks.find(item => item.id === codingTestMatch[1])
      if (!selected) return fulfillError(route, 404, 'task_not_found', 'Task not found')
      await fulfillJson(route, { items: [codingTestEvidence(codingTestMatch[2]!)], nextCursor: null })
      return
    }
    const commandLogMatch = path.match(/\/tasks\/([^/]+)\/attempts\/([^/]+)\/coding\/commands\/([^/]+)\/log$/)
    if (commandLogMatch && request.method() === 'GET') {
      const source = Buffer.from(codingCommandLog(), 'utf8')
      await fulfillArtifactPage(route, url, source, 'text/plain;charset=utf-8', 'crewscope-command.log')
      return
    }
    const testReportMatch = path.match(/\/tasks\/([^/]+)\/attempts\/([^/]+)\/coding\/test-evidence\/([^/]+)\/report$/)
    if (testReportMatch && request.method() === 'GET') {
      const source = Buffer.from(codingTestReport(), 'utf8')
      await fulfillArtifactPage(route, url, source, 'application/json;charset=utf-8', 'crewscope-test.json')
      return
    }
    const codingPatchMatch = path.match(/\/tasks\/([^/]+)\/attempts\/([^/]+)\/coding\/artifacts\/patch$/)
    if (codingPatchMatch && request.method() === 'GET') {
      const selected = tasks.find(item => item.id === codingPatchMatch[1])
      if (!selected) return fulfillError(route, 404, 'task_not_found', 'Task not found')
      const source = Buffer.from(codingPatch(), 'utf8')
      const offset = Number(url.searchParams.get('offset') ?? '0')
      const limit = Number(url.searchParams.get('limit') ?? String(source.byteLength))
      const end = Math.min(source.byteLength, offset + limit)
      await route.fulfill({
        status: 206,
        contentType: 'text/x-diff;charset=utf-8',
        headers: {
          'Content-Range': `bytes ${offset}-${end - 1}/${source.byteLength}`,
          ETag: '"coding-patch-v1"',
          'Cache-Control': 'no-store',
        },
        body: source.subarray(offset, end),
      })
      return
    }
    const codingAttemptMatch = path.match(/\/tasks\/([^/]+)\/attempts\/([^/]+)\/coding$/)
    if (codingAttemptMatch && request.method() === 'GET') {
      const selected = tasks.find(item => item.id === codingAttemptMatch[1])
      if (!selected) return fulfillError(route, 404, 'task_not_found', 'Task not found')
      await fulfillJson(route, codingAttempt(selected, codingAttemptMatch[2]!))
      return
    }
    const codingAttemptHistoryMatch = path.match(/\/tasks\/([^/]+)\/coding-attempts$/)
    if (codingAttemptHistoryMatch && request.method() === 'GET') {
      const selected = tasks.find(item => item.id === codingAttemptHistoryMatch[1])
      if (!selected) return fulfillError(route, 404, 'task_not_found', 'Task not found')
      await fulfillJson(route, selected.currentAttempt > 1
        ? [codingAttempt(selected, selected.currentExecutionId), codingAttempt(selected, selected.previousExecutionId)]
        : [codingAttempt(selected, selected.currentExecutionId)])
      return
    }
    const currentCodingAttemptMatch = path.match(/\/tasks\/([^/]+)\/coding$/)
    if (currentCodingAttemptMatch && request.method() === 'GET') {
      const selected = tasks.find(item => item.id === currentCodingAttemptMatch[1])
      if (!selected) return fulfillError(route, 404, 'task_not_found', 'Task not found')
      await fulfillJson(route, { taskId: selected.id, currentAttempt: codingAttempt(selected, selected.currentExecutionId) })
      return
    }
    const taskRuntimeFactsMatch = path.match(/\/tasks\/([^/]+)\/attempts\/([^/]+)\/runtime-facts$/)
    if (taskRuntimeFactsMatch && request.method() === 'GET') {
      const selected = tasks.find(item => item.id === taskRuntimeFactsMatch[1])
      if (!selected) return fulfillError(route, 404, 'task_not_found', 'Task not found')
      await fulfillJson(route, taskRuntimeFacts(selected, taskRuntimeFactsMatch[2]!))
      return
    }
    if (path.endsWith('/runtime-health') && request.method() === 'GET') {
      await fulfillJson(route, runtimeFleetSummary())
      return
    }
    const taskAttemptMatch = path.match(/\/tasks\/([^/]+)\/attempts$/)
    if (taskAttemptMatch && request.method() === 'GET') {
      const selected = tasks.find(item => item.id === taskAttemptMatch[1])
      if (!selected) return fulfillError(route, 404, 'task_not_found', 'Task not found')
      await fulfillJson(route, selected.currentAttempt > 1
        ? [taskExecution(selected), historicalTaskExecution(selected)]
        : [taskExecution(selected)])
      return
    }
    const taskEventMatch = path.match(/\/tasks\/([^/]+)\/events$/)
    if (taskEventMatch && request.method() === 'GET') {
      if (request.headers().accept?.includes('text/event-stream')) {
        await fulfillSse(route, [])
      } else {
        await fulfillJson(route, {
          items: taskEventMatch[1] === ids.task ? codingDiffTaskEvents() : [],
          hasMore: false, taskTerminal: false,
          nextCursor: taskEventMatch[1] === ids.task ? 'coding-diff-cursor-2' : null,
        })
      }
      return
    }
    const taskAssociationsMatch = path.match(/\/tasks\/([^/]+)\/associations$/)
    if (taskAssociationsMatch && request.method() === 'GET') {
      const selected = tasks.find(item => item.id === taskAssociationsMatch[1])
      if (!selected) return fulfillError(route, 404, 'task_not_found', 'Task not found')
      await fulfillJson(route, taskAssociations(selected, conversationTaskIds.has(selected.id)))
      return
    }
    const taskDetailMatch = path.match(/\/tasks\/([^/]+)$/)
    if (taskDetailMatch && request.method() === 'GET') {
      const selected = tasks.find(item => item.id === taskDetailMatch[1])
      if (!selected) return fulfillError(route, 404, 'task_not_found', 'Task not found')
      await fulfillJson(route, taskDetails(selected))
      return
    }
    const conversationTaskMatch = path.match(/\/conversations\/([^/]+)\/tasks$/)
    if (conversationTaskMatch && request.method() === 'GET') {
      const items = conversationTaskMatch[1] === ids.conversation
        ? tasks.filter(item => conversationTaskIds.has(item.id)).map(item => taskAssociation(item, 'CONVERSATION_SOURCE'))
        : []
      await fulfillJson(route, { items, nextCursor: null })
      return
    }
    if (path.endsWith('/tasks') && request.method() === 'GET') {
      const projectId = url.searchParams.get('projectId')
      const status = url.searchParams.get('status')
      const ownerPrincipalId = url.searchParams.get('ownerPrincipalId')
      const teamId = path.split('/teams/')[1]?.split('/')[0]
      const items = teamId === ids.team ? tasks.filter(item =>
        (!projectId || item.projectId === projectId)
        && (!status || item.status === status)
        && (!ownerPrincipalId || item.ownerPrincipalId === ownerPrincipalId),
      ) : []
      await fulfillJson(route, { items, nextCursor: null })
      return
    }
    const workItemConversationMatch = path.match(/\/work-projects\/([^/]+)\/work-items\/([^/]+)\/conversations$/)
    if (workItemConversationMatch && request.method() === 'GET') {
      await fulfillJson(route, associations.filter(item => (
        item.workItem.projectId === workItemConversationMatch[1]
        && item.workItem.id === workItemConversationMatch[2]
      )))
      return
    }
    const conversationWorkItemMatch = path.match(/\/conversations\/([^/]+)\/work-items$/)
    if (conversationWorkItemMatch && request.method() === 'GET') {
      await fulfillJson(route, associations.filter(item => item.conversation.id === conversationWorkItemMatch[1]))
      return
    }
    if (path.endsWith('/conversations') && request.method() === 'GET') {
      const teamId = path.split('/teams/')[1]?.split('/')[0]
      await fulfillJson(route, { items: teamId === ids.team ? conversations : [conversation(crypto.randomUUID(), ids.secondTeam, ids.secondWorkspace, '检查 Runtime 身份边界', 'TEAM', 2)], nextCursor: null })
      return
    }
    if (path.endsWith('/conversations') && request.method() === 'POST') {
      const input = request.postDataJSON() as { title: string; visibility: 'PRIVATE' | 'TEAM' }
      expect(request.headers()['idempotency-key']).toBeTruthy()
      const created = conversation(crypto.randomUUID(), ids.team, ids.workspace, input.title, input.visibility, null)
      conversations.unshift(created)
      messagesByConversation[created.id] = []
      await fulfillReceipt(route, 0)
      return
    }
    const conversationEventMatch = path.match(/\/conversations\/([^/]+)\/events$/)
    if (conversationEventMatch && request.method() === 'GET') {
      await fulfillSse(route, [])
      return
    }
    const cancelMatch = path.match(/\/conversations\/([^/]+)\/agent-invocations\/([^/]+)\/cancel$/)
    if (cancelMatch && request.method() === 'POST') {
      await route.fulfill({
        status: 202,
        contentType: 'application/json',
        body: JSON.stringify({ invocationId: cancelMatch[2], result: 'ACCEPTED', correlationId: crypto.randomUUID() }),
      })
      return
    }
    const invocationMatch = path.match(/\/conversations\/([^/]+)\/agent-invocations$/)
    if (invocationMatch && request.method() === 'POST') {
      const input = request.postDataJSON() as { message: string }
      const idempotencyKey = request.headers()['idempotency-key']!
      expect(idempotencyKey).toBeTruthy()
      let accepted = acceptedInvocations.get(idempotencyKey)
      const items = messagesByConversation[invocationMatch[1]!] ?? []
      if (!accepted) {
        accepted = { invocationId: crypto.randomUUID(), userMessageId: crypto.randomUUID(), agentMessageId: crypto.randomUUID() }
        acceptedInvocations.set(idempotencyKey, accepted)
        const userSequence = items.reduce((latest, item) => Math.max(latest, item.sequence), 0) + 1
        items.push(conversationMessage(accepted.userMessageId, invocationMatch[1]!, userSequence, 'USER_MESSAGE', ids.principal, input.message, '2026-08-08T04:00:00Z'))
        items.push(conversationMessage(accepted.agentMessageId, invocationMatch[1]!, userSequence + 1, 'AGENT_MESSAGE', ids.personalAgent, `已收到：${input.message}`, '2026-08-08T04:00:01Z'))
        messagesByConversation[invocationMatch[1]!] = items
      }
      const segmentId = crypto.randomUUID()
      await fulfillSse(route, [
        realtimeEvent('RUN_STARTED', { threadId: invocationMatch[1], runId: accepted.invocationId, segmentId, segmentKind: 'INVOKE' }),
        realtimeEvent('TEXT_MESSAGE_CONTENT', { threadId: invocationMatch[1], runId: accepted.invocationId, segmentId, messageId: accepted.agentMessageId, delta: `已收到：${input.message}` }),
        realtimeEvent('RUN_FINISHED', { threadId: invocationMatch[1], runId: accepted.invocationId, segmentId, status: 'COMPLETED' }),
      ], { 'X-CrewScope-Invocation-Id': accepted.invocationId })
      return
    }
    const messageMatch = path.match(/\/conversations\/([^/]+)\/messages$/)
    if (messageMatch && request.method() === 'GET') {
      const items = [...(messagesByConversation[messageMatch[1]!] ?? [])].sort((left, right) => right.sequence - left.sequence)
      await fulfillJson(route, { items, nextCursor: null })
      return
    }
    if (messageMatch && request.method() === 'POST') {
      const input = request.postDataJSON() as { content: string }
      const idempotencyKey = request.headers()['idempotency-key']
      expect(idempotencyKey).toBeTruthy()
      const items = messagesByConversation[messageMatch[1]!] ?? []
      if (!acceptedMessageKeys.has(idempotencyKey!)) {
        acceptedMessageKeys.add(idempotencyKey!)
        const sequence = items.reduce((latest, item) => Math.max(latest, item.sequence), 0) + 1
        items.push(conversationMessage(crypto.randomUUID(), messageMatch[1]!, sequence, 'USER_MESSAGE', ids.principal, input.content, '2026-08-08T04:00:00Z'))
        messagesByConversation[messageMatch[1]!] = items
        const conversation = conversations.find(item => item.id === messageMatch[1])
        if (conversation) conversation.lastMessageSequence = sequence
      }
      await fulfillReceipt(route, items.at(-1)?.sequence ?? 0)
      return
    }
    const conversationMatch = path.match(/\/conversations\/([^/]+)$/)
    if (conversationMatch && request.method() === 'GET') {
      const item = conversations.find(candidate => candidate.id === conversationMatch[1])
      if (!item) return fulfillError(route, 404, 'conversation_not_found', 'Conversation not found')
      await fulfillJson(route, {
        conversation: item,
        participants: [
          participant(crypto.randomUUID(), item.id, ids.principal, ids.member, 'OWNER'),
          participant(crypto.randomUUID(), item.id, ids.personalAgent, null, 'AGENT'),
        ],
      })
      return
    }
    if (path.endsWith('/work-items') && request.method() === 'GET') {
      const status = url.searchParams.get('status')
      const matching = status ? workItems.filter(item => item.status === status) : workItems
      const after = url.searchParams.get('after')
      await fulfillJson(route, after
        ? { items: matching.slice(4), nextCursor: null }
        : { items: status ? matching : matching.slice(0, 4), nextCursor: status ? null : 'work-page-2' })
      return
    }
    if (path.endsWith('/work-items') && request.method() === 'POST') {
      const input = request.postDataJSON() as ReturnType<typeof workItem>
      workItems.unshift({ ...workItem(crypto.randomUUID(), input.key, input.title, input.type, 'BACKLOG', input.priority), description: input.description, labels: input.labels, dueAt: input.dueAt })
      await route.fulfill({ status: 202, contentType: 'application/json', body: JSON.stringify({ commandId: crypto.randomUUID(), domainEventId: crypto.randomUUID(), committedVersion: 0, correlationId: crypto.randomUUID() }) })
      return
    }
    const transitionMatch = path.match(/\/work-items\/([^/]+)\/transitions$/)
    if (transitionMatch && request.method() === 'POST') {
      const item = workItems.find(candidate => candidate.id === transitionMatch[1])
      if (!item) return fulfillError(route, 404, 'work_item_not_found', 'WorkItem not found')
      const input = request.postDataJSON() as { targetStatus: string }
      if (input.targetStatus === 'BLOCKED') {
        item.version = 2
        return fulfillError(route, 409, 'optimistic_lock_conflict', 'WorkItem version changed', 2)
      }
      expect(request.headers()['if-match']).toBe(`"${item.version}"`)
      item.status = input.targetStatus
      item.version += 1
      await fulfillReceipt(route, item.version)
      return
    }
    const commentMatch = path.match(/\/work-items\/([^/]+)\/comments$/)
    if (commentMatch && request.method() === 'POST') {
      const input = request.postDataJSON() as { content: string }
      comments.push({ id: crypto.randomUUID(), workItemId: commentMatch[1]!, authorPrincipalId: ids.principal, content: input.content, source: 'CREWSCOPE', externalId: null, createdAt: '2026-08-08T04:00:00Z' })
      await fulfillReceipt(route, 0)
      return
    }
    const resourceMatch = path.match(/\/work-items\/([^/]+)\/resource-links$/)
    if (resourceMatch && request.method() === 'POST') {
      const input = request.postDataJSON() as { resourceType: string; resourceReference: string; label: string | null }
      resources.push({ id: crypto.randomUUID(), workItemId: resourceMatch[1]!, ...input, createdAt: '2026-08-08T04:10:00Z', createdByPrincipalId: ids.principal })
      await fulfillReceipt(route, 0)
      return
    }
    const responsibilityMatch = path.match(/\/work-items\/([^/]+)\/responsibilities$/)
    if (responsibilityMatch && request.method() === 'GET') {
      await fulfillJson(route, responsibilities.filter(entry => entry.workItemId === responsibilityMatch[1]))
      return
    }
    const ownerMatch = path.match(/\/work-items\/([^/]+)\/responsibilities\/owner$/)
    if (ownerMatch && request.method() === 'POST') {
      const input = request.postDataJSON() as { actorPrincipalId: string; expectedAssignmentId: string; expectedVersion: number }
      const owner = responsibilities.find(entry => entry.role === 'OWNER')!
      expect(input).toMatchObject({ expectedAssignmentId: owner.id, expectedVersion: owner.version })
      responsibilities = responsibilities.filter(entry => entry.role !== 'OWNER')
      responsibilities.unshift(responsibility(crypto.randomUUID(), 'OWNER', input.actorPrincipalId, 'USER', '周宁'))
      timeline.unshift(timelineEvent(crypto.randomUUID(), 'RESPONSIBILITY_ASSIGNED', '2026-08-08T04:20:00Z', '张凯旋'))
      await fulfillReceipt(route, 0)
      return
    }
    const assignmentMatch = path.match(/\/work-items\/([^/]+)\/responsibilities\/(executors|gate-reviewers|advisory-reviewers)$/)
    if (assignmentMatch && request.method() === 'POST') {
      const input = request.postDataJSON() as { actorPrincipalId: string }
      const role = assignmentMatch[2] === 'executors' ? 'EXECUTOR' : 'REVIEWER'
      const actorType = assignmentMatch[2] === 'gate-reviewers' ? 'USER' : assignmentMatch[2] === 'advisory-reviewers' ? 'SPECIALIST_AGENT' : input.actorPrincipalId === ids.specialistAgent ? 'TEAM_AGENT' : 'USER'
      responsibilities.push(responsibility(crypto.randomUUID(), role, input.actorPrincipalId, actorType, actorType === 'USER' ? '周宁' : 'Agent'))
      await fulfillReceipt(route, 0)
      return
    }
    const releaseMatch = path.match(/\/work-items\/([^/]+)\/responsibilities\/([^/]+)\/releases$/)
    if (releaseMatch && request.method() === 'POST') {
      const assignment = responsibilities.find(entry => entry.id === releaseMatch[2])!
      expect(request.headers()['if-match']).toBe(`"${assignment.version}"`)
      responsibilities = responsibilities.filter(entry => entry.id !== releaseMatch[2])
      await fulfillReceipt(route, assignment.version + 1)
      return
    }
    const timelineMatch = path.match(/\/work-items\/([^/]+)\/timeline$/)
    if (timelineMatch && request.method() === 'GET') {
      await fulfillJson(route, url.searchParams.get('after')
        ? { items: [timeline.at(-1), timelineEvent('00000000-0000-0000-0000-000000001003', 'COMMENT_ADDED', '2026-08-07T20:00:00Z', '张凯旋')], nextCursor: null }
        : { items: timeline, nextCursor: 'timeline-page-2' })
      return
    }
    const workItemActivityMatch = path.match(/\/work-projects\/([^/]+)\/work-items\/([^/]+)\/activity(?:\/snapshot)?$/)
    if (workItemActivityMatch && request.method() === 'GET') {
      const items = [workItemActivity('00000000-0000-0000-0000-000000002301', workItemActivityMatch[2]!)]
      await fulfillJson(route, {
        items,
        hasMore: false,
        nextCursor: null,
        snapshotCursor: path.endsWith('/snapshot') ? 'work-item-activity-cursor' : undefined,
      })
      return
    }
    const detailMatch = path.match(/\/work-items\/([^/]+)$/)
    if (detailMatch && request.method() === 'GET') {
      const item = workItems.find(candidate => candidate.id === detailMatch[1])
      if (!item) return fulfillError(route, 404, 'work_item_not_found', 'WorkItem not found')
      await route.fulfill({ status: 200, contentType: 'application/json', headers: { ETag: `"${item.version}"` }, body: JSON.stringify({ workItem: item, comments: comments.filter(entry => entry.workItemId === item.id), resourceLinks: resources.filter(entry => entry.workItemId === item.id) }) })
      return
    }
    await route.fulfill({ status: 404, contentType: 'application/json', body: '{"code":"not_found"}' })
  })
})

test('Conversation restores its Team deep link and shares the selected scope with Today', async ({ page }) => {
  await page.goto(`/conversation?focus=CRW-18&team=${ids.team}&project=${ids.project}&conversation=${ids.conversation}`)
  await expect(page.getByRole('heading', { name: '规划 GitHub Provider 接入', exact: true }).first()).toBeVisible()
  await expect(page.getByText('请保留团队协作与最小权限原则。', { exact: true })).toBeVisible()
  await expect(page.getByText('Connection', { exact: true })).toBeVisible()

  await page.reload()
  await expect(page.getByRole('heading', { name: '规划 GitHub Provider 接入', exact: true }).first()).toBeVisible()

  await page.getByRole('link', { name: '工作台', exact: true }).click()

  await expect(page).toHaveURL(/\/today\?/)
  const restoredQuery = new URL(page.url()).searchParams
  expect(restoredQuery.get('focus')).toBe('CRW-18')
  expect(restoredQuery.get('team')).toBe(ids.team)
  expect(restoredQuery.get('project')).toBe(ids.project)
  expect(restoredQuery.get('conversation')).toBe(ids.conversation)
  await expect(page.getByRole('heading', { name: 'Platform Engineering', exact: true })).toBeVisible()
})

test('Conversation restores multiple visible Tasks and preserves Conversation, WorkItem and Task navigation', async ({ page }, testInfo) => {
  const completed = task('00000000-0000-0000-0000-000000001502', ids.workItem, '验证 Conversation Task 恢复', 'COMPLETED', 'COMPLETED', null, 1)
  await page.route(new RegExp(`/conversations/${ids.conversation}/tasks(?:\\?.*)?$`), route => fulfillJson(route, {
    items: [
      taskAssociation(task(ids.task, ids.workItem, '完成 Agent Task 列表与委托入口', 'WAITING', 'WAITING', 'CAPACITY', 2), 'CONVERSATION_SOURCE'),
      taskAssociation(completed, 'WORK_ITEM_ROOT'),
    ],
    nextCursor: null,
  }))
  await page.route(new RegExp(`/tasks/${completed.id}/events$`), route => fulfillSse(route, []))

  await page.goto(`/conversation?team=${ids.team}&project=${ids.project}&conversation=${ids.conversation}`)
  const taskCards = page.getByTestId('conversation-task-cards')
  await expect(taskCards.getByText('完成 Agent Task 列表与委托入口', { exact: true })).toBeVisible()
  await expect(taskCards.getByText('验证 Conversation Task 恢复', { exact: true })).toBeVisible()
  await page.reload()
  await expect(taskCards.getByText('验证 Conversation Task 恢复', { exact: true })).toBeVisible()
  await expect(taskCards).not.toContainText('不可见的私有 Task')
  await expect(taskCards).toHaveScreenshot(`conversation-tasks-${testInfo.project.name}.png`)

  await taskCards.locator(`[data-task-id="${ids.task}"]`).getByRole('button', { name: /查看 Task/ }).click()
  await expect(page).toHaveURL(new RegExp(`task=${ids.task}`))
  const taskDialog = page.getByRole('dialog', { name: /完成 Agent Task 列表与委托入口 Task 详情/ })
  await expect(taskDialog).toBeVisible()
  await expect(taskDialog.getByTestId('execution-studio').getByRole('heading', { name: 'Execution Studio' })).toBeVisible()
  await expect.poll(() => new URL(page.url()).searchParams.get('workspace')).toBe(ids.codingWorkspace)
  await taskDialog.getByRole('button', { name: /规划 GitHub Provider 接入/ }).click()
  await expect(page).toHaveURL(/\/conversation\?/)
  expect(new URL(page.url()).searchParams.get('conversation')).toBe(ids.conversation)

  await taskCards.locator(`[data-task-id="${ids.task}"]`).getByRole('button', { name: '工作项' }).click()
  await expect(page).toHaveURL(/\/work\?/)
  const query = new URL(page.url()).searchParams
  expect(query.get('workItem')).toBe(ids.workItem)
  expect(query.get('conversation')).toBe(ids.conversation)
  expect(query.get('task')).toBeNull()
})

test('Conversation Task SSE invalidates durable facts and stops after the terminal projection', async ({ page }) => {
  let associationReads = 0
  await page.route(new RegExp(`/conversations/${ids.conversation}/tasks(?:\\?.*)?$`), async route => {
    associationReads += 1
    const terminal = associationReads > 1
    const value = task(
      ids.task,
      ids.workItem,
      '实时更新耐久 Task',
      terminal ? 'COMPLETED' : 'WAITING',
      terminal ? 'COMPLETED' : 'WAITING',
      terminal ? null : 'WAITING_APPROVAL',
      2,
    )
    await fulfillJson(route, { items: [taskAssociation(value, 'CONVERSATION_SOURCE')], nextCursor: null })
  })
  await page.route(new RegExp(`/tasks/${ids.task}/events$`), route => fulfillSse(route, [{
    cursor: 'task-live-cursor',
    context: { taskId: ids.task, taskExecutionId: ids.taskExecution, stepExecutionId: null, agentRunId: null, executionLeaseId: null },
    projectionGap: false,
    event: realtimeEvent('TASK_COMPLETED', { status: 'COMPLETED' }, { eventId: 'task-live-event', domainEventId: 'task-live-domain', streamType: 'TASK', aggregateVersion: 2 }),
  }]))

  await page.goto(`/conversation?team=${ids.team}&project=${ids.project}&conversation=${ids.conversation}`)
  const card = page.getByTestId('conversation-task-cards').locator(`[data-task-id="${ids.task}"]`)
  await expect(card.getByText('已完成', { exact: true })).toBeVisible()
  await expect(card.getByText('WAITING_APPROVAL')).toBeHidden()
  await expect.poll(() => associationReads).toBe(2)
})

test('Conversation reloads current server facts when returning from Control Mode', async ({ page }) => {
  let collectionReads = 0
  await page.route(/\/conversations(?:\?.*)?$/, async route => {
    collectionReads += 1
    const items = [
      conversation(ids.conversation, ids.team, ids.workspace, '规划 GitHub Provider 接入', 'PRIVATE', 4),
      conversation(ids.secondConversation, ids.team, ids.workspace, '协作准备 M2 发布', 'TEAM', null),
    ]
    if (collectionReads > 1) {
      items.unshift(conversation(
        '00000000-0000-0000-0000-000000001103',
        ids.team,
        ids.workspace,
        '返回页面后读取的新对话',
        'TEAM',
        null,
      ))
    }
    await fulfillJson(route, { items, nextCursor: null })
  })
  await page.goto(`/conversation?team=${ids.team}&project=${ids.project}`)
  await expect(page.getByRole('button', { name: '打开对话 规划 GitHub Provider 接入' })).toBeVisible()

  await page.getByRole('link', { name: '工作台', exact: true }).click()
  await page.getByRole('link', { name: '对话', exact: true }).click()

  await expect(page.getByRole('button', { name: '打开对话 返回页面后读取的新对话' })).toBeVisible()
  expect(collectionReads).toBe(2)
})

test('Conversation sends Markdown with Enter and restores the committed message after refresh', async ({ page }) => {
  await page.goto(`/conversation?team=${ids.team}&project=${ids.project}&conversation=${ids.conversation}`)
  const composer = page.getByLabel('消息内容')

  await composer.fill('**请补充** OAuth 边界。')
  await composer.press('Enter')

  await expect(page.getByText('请补充 OAuth 边界。', { exact: true })).toBeVisible()
  await expect(composer).toHaveValue('')
  await page.reload()
  await expect(page.getByText('请补充 OAuth 边界。', { exact: true })).toBeVisible()
})

test('Conversation sends the current draft when the Send button is clicked', async ({ page }) => {
  await page.goto(`/conversation?team=${ids.team}&project=${ids.project}&conversation=${ids.conversation}`)
  const composer = page.getByRole('form', { name: '发送消息' })
  const message = composer.getByLabel('消息内容')

  await message.fill('点击发送按钮提交的消息。')
  await composer.getByRole('button', { name: '发送' }).click()

  await expect(page.getByText('已收到：点击发送按钮提交的消息。', { exact: true })).toBeVisible()
  await expect(message).toHaveValue('')
})

test('Conversation shows the submitted owner message before the Agent stream is reconciled', async ({ page }) => {
  let releasePost!: () => void
  const postGate = new Promise<void>(resolve => { releasePost = resolve })
  await page.route(/\/agent-invocations$/, async route => {
    if (route.request().method() !== 'POST') return route.fallback()
    await postGate
    await route.fallback()
  })
  await page.goto(`/conversation?team=${ids.team}&project=${ids.project}&conversation=${ids.conversation}`)

  await page.getByLabel('消息内容').fill('验证 Pending 消息收口。')
  await page.getByLabel('消息内容').press('Enter')
  await expect(page.getByText('验证 Pending 消息收口。', { exact: true })).toBeVisible()
  await expect(page.getByText('正在连接 Personal Agent', { exact: true })).toBeVisible()
  await expect(page.getByTestId('conversation-task-cards').getByText('完成 Agent Task 列表与委托入口', { exact: true })).toBeVisible()

  releasePost()
  await expect(page.getByText('已收到：验证 Pending 消息收口。', { exact: true })).toBeVisible()
  await expect(page.getByText('正在连接 Personal Agent', { exact: true })).toBeHidden()
})

test('Conversation retains input after failure and retries the original message idempotently', async ({ page }) => {
  let attempts = 0
  await page.route(new RegExp(`/conversations/${ids.conversation}$`), route => fulfillJson(route, {
    conversation: { ...conversation(ids.conversation, ids.team, ids.workspace, '规划 GitHub Provider 接入', 'PRIVATE', 4), ownerPrincipalId: ids.secondPrincipal },
    participants: [
      participant(crypto.randomUUID(), ids.conversation, ids.principal, ids.member, 'MEMBER'),
      participant(crypto.randomUUID(), ids.conversation, ids.personalAgent, null, 'AGENT'),
    ],
  }))
  await page.route(/\/messages$/, async route => {
    if (route.request().method() !== 'POST') return route.fallback()
    attempts += 1
    if (attempts === 1) return fulfillError(route, 503, 'message_unavailable', '消息服务暂时不可用')
    await route.fallback()
  })
  await page.goto(`/conversation?team=${ids.team}&project=${ids.project}&conversation=${ids.conversation}`)
  const composer = page.getByLabel('消息内容')

  await composer.fill('需要可靠重试的消息。')
  await composer.press('Enter')
  await expect(page.getByText('发送失败', { exact: true })).toBeVisible()
  await expect(composer).toHaveValue('需要可靠重试的消息。')

  await composer.fill('发送期间继续保留的新草稿。')
  await page.getByRole('button', { name: '重试发送' }).click()
  await expect(page.getByText('需要可靠重试的消息。', { exact: true })).toBeVisible()
  await expect(page.getByText('发送失败', { exact: true })).toBeHidden()
  await expect(composer).toHaveValue('发送期间继续保留的新草稿。')
})

test('Conversation retains input when the Personal Agent invocation fails', async ({ page }) => {
  await page.route(/\/agent-invocations$/, async route => {
    if (route.request().method() !== 'POST') return route.fallback()
    await fulfillError(route, 503, 'agent_unavailable', 'Agent provider unavailable')
  })
  await page.goto(`/conversation?team=${ids.team}&project=${ids.project}&conversation=${ids.conversation}`)
  const composer = page.getByLabel('消息内容')
  const content = '请保留这份失败后可重试的 Agent 任务。'

  await composer.fill(content)
  await composer.press('Enter')

  await expect(page.getByRole('alert')).toBeVisible()
  await expect(composer).toHaveValue(content)
})

test('Conversation loads older history from its opaque Cursor without duplicates', async ({ page }) => {
  const history = [
    conversationMessage('message-1', ids.conversation, 1, 'USER_MESSAGE', ids.principal, '最早的范围确认。', '2026-08-08T01:00:00Z'),
    conversationMessage('message-2', ids.conversation, 2, 'AGENT_MESSAGE', ids.personalAgent, '第一轮 Agent 回复。', '2026-08-08T01:01:00Z'),
    conversationMessage('message-3', ids.conversation, 3, 'SYSTEM_NOTICE', null, '中间系统消息。', '2026-08-08T01:02:00Z'),
    conversationMessage('message-4', ids.conversation, 4, 'USER_MESSAGE', ids.principal, '最新消息。', '2026-08-08T01:03:00Z'),
  ]
  await page.route(/\/messages(?:\?.*)?$/, async route => {
    if (route.request().method() !== 'GET') return route.fallback()
    const after = new URL(route.request().url()).searchParams.get('after')
    await fulfillJson(route, after
      ? { items: [history[2], history[1], history[0]], nextCursor: null }
      : { items: [history[3], history[2]], nextCursor: 'opaque+/older' })
  })
  await page.goto(`/conversation?team=${ids.team}&project=${ids.project}&conversation=${ids.conversation}`)

  await page.getByRole('button', { name: '加载更早消息' }).click()

  await expect(page.getByText('最早的范围确认。', { exact: true })).toBeVisible()
  await expect(page.getByText('中间系统消息。', { exact: true })).toHaveCount(1)
})

test('Conversation streams only public Agent text and reconciles the committed reply', async ({ page }) => {
  const history = [conversationMessage('stream-base', ids.conversation, 1, 'USER_MESSAGE', ids.principal, '开始流式验证。', '2026-08-08T01:00:00Z')]
  let invocationBody: unknown
  await page.route(/\/messages(?:\?.*)?$/, route => fulfillJson(route, { items: [...history].reverse(), nextCursor: null }))
  await page.route(/\/agent-invocations$/, async route => {
    invocationBody = route.request().postDataJSON()
    const input = invocationBody as { message: string }
    history.push(conversationMessage('stream-user', ids.conversation, 2, 'USER_MESSAGE', ids.principal, input.message, '2026-08-08T04:00:00Z'))
    history.push(conversationMessage('stream-agent', ids.conversation, 3, 'AGENT_MESSAGE', ids.personalAgent, '只展示公开回复。', '2026-08-08T04:00:01Z'))
    await fulfillSse(route, [
      realtimeEvent('RUN_STARTED', { segmentId: 'segment-stream' }),
      realtimeEvent('TEXT_MESSAGE_CONTENT', { delta: '只展示' }),
      realtimeEvent('REASONING_CONTENT', { reasoning: 'internal chain must stay hidden' }),
      realtimeEvent('TEXT_MESSAGE_CONTENT', { delta: '公开回复。' }),
      realtimeEvent('RUN_FINISHED', { status: 'COMPLETED' }),
    ], { 'X-CrewScope-Invocation-Id': 'invocation-stream' })
  })
  await page.goto(`/conversation?team=${ids.team}&project=${ids.project}&conversation=${ids.conversation}`)

  await page.getByLabel('消息内容').fill('请生成公开回复。')
  await page.getByLabel('消息内容').press('Enter')

  await expect(page.getByText('只展示公开回复。', { exact: true })).toBeVisible()
  await expect(page.getByText('internal chain must stay hidden', { exact: true })).toHaveCount(0)
  expect(invocationBody).toEqual({ message: '请生成公开回复。' })
})

test('Conversation answers a structured clarification without exposing runtime Tool coordinates', async ({ page }) => {
  let resumeBody: unknown
  await page.route(/\/agent-invocations$/, route => fulfillSse(route, [
    realtimeEvent('RUN_INTERRUPTED', {
      safePrompt: 'Additional information is required to continue.',
      clarification: {
        schemaVersion: '1',
        summary: '需要确定仓库和目标分支。',
        questions: [
          { fieldKey: 'repository', question: '使用哪个仓库？', context: '选择 Team 已授权的仓库', required: true, choices: ['crewscope-java', 'agentscope-java'] },
          { fieldKey: 'branch', question: '使用哪个分支？', context: null, required: true, choices: [] },
        ],
      },
    }, { eventId: 'clarification-interrupted' }),
  ], { 'X-CrewScope-Invocation-Id': 'invocation-clarification' }))
  await page.route(/\/agent-invocations\/invocation-clarification\/resume$/, async route => {
    resumeBody = route.request().postDataJSON()
    await fulfillSse(route, [
      realtimeEvent('RUN_STARTED', {}, { eventId: 'clarification-resumed' }),
      realtimeEvent('RUN_FINISHED', { status: 'COMPLETED' }, { eventId: 'clarification-finished' }),
    ], { 'X-CrewScope-Invocation-Id': 'invocation-clarification' })
  })
  await page.goto(`/conversation?team=${ids.team}&project=${ids.project}&conversation=${ids.conversation}`)
  await page.getByLabel('消息内容').fill('请规划仓库改造。')
  await page.getByLabel('消息内容').press('Enter')

  await page.getByLabel('crewscope-java').check()
  await page.getByLabel('使用哪个分支？').fill('main')
  await page.getByRole('button', { name: '提交并继续' }).click()

  expect(resumeBody).toEqual({ answers: { repository: 'crewscope-java', branch: 'main' } })
  expect(JSON.stringify(resumeBody)).not.toContain('toolCallId')
  expect(JSON.stringify(resumeBody)).not.toContain('interruptToken')
})

test('Conversation reviews the latest TaskIntent and confirms with an empty request body', async ({ page }) => {
  let intent = taskIntent('READY', 2)
  let confirmationBody: string | null = 'not-called'
  let confirmedAssociationVisible = false
  const proposed = realtimeEvent('TASK_INTENT_PROPOSED', {}, {
    eventId: 'task-intent-event', domainEventId: 'task-intent-domain', streamType: 'CONVERSATION', aggregateVersion: 2,
  })
  proposed.aggregateType = 'TASK_INTENT'
  proposed.aggregateId = ids.taskIntent
  await page.route(/\/events(?:\?.*)?$/, route => fulfillSse(route, [proposed]))
  await page.route(new RegExp(`/task-intents/${ids.taskIntent}$`), route => route.fulfill({
    status: 200,
    contentType: 'application/json',
    headers: { ETag: `"${intent.version}"`, 'Cache-Control': 'no-store' },
    body: JSON.stringify(intent),
  }))
  await page.route(/\/confirmation-previews$/, route => route.fulfill({
    status: 200,
    contentType: 'application/json',
    headers: { ETag: `"${intent.version}"` },
    body: JSON.stringify({ confirmable: true, taskIntentId: intent.id, proposalRevision: intent.proposalRevision, version: intent.version, confirmingPrincipalId: ids.principal, proposal: intent.proposal }),
  }))
  await page.route(/\/confirmations$/, async route => {
    confirmationBody = route.request().postData()
    confirmedAssociationVisible = true
    intent = { ...intent, status: 'CONFIRMED', version: 3, decision: { status: 'CONFIRMED', decidedByPrincipalId: ids.principal, decidedAt: '2026-08-08T04:10:00Z', reason: null } }
    await fulfillReceipt(route, 3)
  })
  await page.route(new RegExp(`/conversations/${ids.conversation}/work-items$`), route => (
    fulfillJson(route, confirmedAssociationVisible ? [conversationWorkItemAssociation()] : [])
  ))
  await page.goto(`/conversation?team=${ids.team}&project=${ids.project}&conversation=${ids.conversation}`)

  await expect(page.getByRole('heading', { name: '结构化任务提案' })).toBeVisible()
  await expect(page.getByText('关键操作进入审计记录', { exact: true })).toBeVisible()
  await page.getByRole('button', { name: '预检并确认' }).click()

  await expect(page.getByText('已确认', { exact: true }).first()).toBeVisible()
  expect(confirmationBody).toBeNull()

  await page.getByRole('button', { name: '查看工作项 CRW-18' }).click()
  await expect(page).toHaveURL(/\/work\?/)
  expect(new URL(page.url()).searchParams.get('workItem')).toBe(ids.workItem)
  expect(new URL(page.url()).searchParams.get('conversation')).toBe(ids.conversation)
  const drawer = page.getByRole('dialog', { name: 'CRW-18 工作项详情' })
  await expect(drawer.getByText('规划 GitHub Provider 接入', { exact: true })).toBeVisible()
  await expect(drawer.getByText('张凯旋', { exact: true }).first()).toBeVisible()

  await drawer.getByRole('button', { name: '返回对话 规划 GitHub Provider 接入' }).click()
  await expect(page).toHaveURL(/\/conversation\?/)
  expect(new URL(page.url()).searchParams.get('conversation')).toBe(ids.conversation)
  await page.reload()
  await expect(page.getByRole('button', { name: '查看工作项 CRW-18' })).toBeVisible()
})

test('Conversation replays a disconnected invocation with the same key and removes duplicate deltas', async ({ page }) => {
  const history: Array<ReturnType<typeof conversationMessage>> = []
  const keys: string[] = []
  let attempts = 0
  const started = realtimeEvent('RUN_STARTED', { segmentId: 'segment-replay' }, { eventId: 'replay-started' })
  const first = realtimeEvent('TEXT_MESSAGE_CONTENT', { delta: '第一段' }, { eventId: 'replay-first' })
  await page.route(/\/messages(?:\?.*)?$/, route => fulfillJson(route, { items: [...history].reverse(), nextCursor: null }))
  await page.route(/\/agent-invocations$/, async route => {
    attempts += 1
    keys.push(route.request().headers()['idempotency-key']!)
    if (attempts === 1) return fulfillSse(route, [started, first], { 'X-CrewScope-Invocation-Id': 'invocation-replay' })
    history.push(conversationMessage('replay-user', ids.conversation, 1, 'USER_MESSAGE', ids.principal, '验证断线重放。', '2026-08-08T04:00:00Z'))
    history.push(conversationMessage('replay-agent', ids.conversation, 2, 'AGENT_MESSAGE', ids.personalAgent, '第一段第二段', '2026-08-08T04:00:01Z'))
    await fulfillSse(route, [
      started,
      first,
      realtimeEvent('TEXT_MESSAGE_CONTENT', { delta: '第二段' }, { eventId: 'replay-second' }),
      realtimeEvent('RUN_FINISHED', { status: 'COMPLETED' }, { eventId: 'replay-finished' }),
    ], { 'X-CrewScope-Invocation-Id': 'invocation-replay', 'Idempotency-Replayed': 'true' })
  })
  await page.goto(`/conversation?team=${ids.team}&project=${ids.project}&conversation=${ids.conversation}`)

  await page.getByLabel('消息内容').fill('验证断线重放。')
  await page.getByLabel('消息内容').press('Enter')

  await expect(page.getByText('第一段第二段', { exact: true })).toBeVisible()
  expect(attempts).toBe(2)
  expect(new Set(keys).size).toBe(1)
  await expect(page.getByText('第一段第一段第二段', { exact: true })).toHaveCount(0)
})

test('Conversation restores an in-flight invocation after refresh', async ({ page }) => {
  const history: Array<ReturnType<typeof conversationMessage>> = []
  const keys: string[] = []
  let attempts = 0
  await page.route(/\/messages(?:\?.*)?$/, route => fulfillJson(route, { items: [...history].reverse(), nextCursor: null }))
  await page.route(/\/agent-invocations$/, async route => {
    attempts += 1
    keys.push(route.request().headers()['idempotency-key']!)
    if (attempts === 1) {
      return fulfillSse(route, [
        realtimeEvent('RUN_STARTED', {}, { eventId: 'refresh-started' }),
        realtimeEvent('TEXT_MESSAGE_CONTENT', { delta: '刷新前' }, { eventId: 'refresh-first' }),
      ], { 'X-CrewScope-Invocation-Id': 'invocation-refresh' })
    }
    history.push(conversationMessage('refresh-user', ids.conversation, 1, 'USER_MESSAGE', ids.principal, '刷新后继续。', '2026-08-08T04:00:00Z'))
    history.push(conversationMessage('refresh-agent', ids.conversation, 2, 'AGENT_MESSAGE', ids.personalAgent, '刷新前刷新后', '2026-08-08T04:00:01Z'))
    return fulfillSse(route, [
      realtimeEvent('RUN_STARTED', {}, { eventId: 'refresh-started' }),
      realtimeEvent('TEXT_MESSAGE_CONTENT', { delta: '刷新前' }, { eventId: 'refresh-first' }),
      realtimeEvent('TEXT_MESSAGE_CONTENT', { delta: '刷新后' }, { eventId: 'refresh-second' }),
      realtimeEvent('RUN_FINISHED', { status: 'COMPLETED' }, { eventId: 'refresh-finished' }),
    ], { 'X-CrewScope-Invocation-Id': 'invocation-refresh', 'Idempotency-Replayed': 'true' })
  })
  await page.goto(`/conversation?team=${ids.team}&project=${ids.project}&conversation=${ids.conversation}`)
  await page.getByLabel('消息内容').fill('刷新后继续。')
  await page.getByLabel('消息内容').press('Enter')
  await expect(page.getByText('连接中断，正在安全重连', { exact: true })).toBeVisible()

  await page.reload()

  await expect(page.getByText('刷新前刷新后', { exact: true })).toBeVisible()
  expect(attempts).toBe(2)
  expect(new Set(keys).size).toBe(1)
})

test('Conversation resumes its durable event stream from the last opaque Cursor', async ({ page }) => {
  const resumes: Array<string | null> = []
  await page.route(new RegExp(`/conversations/${ids.conversation}/events(?:\\?.*)?$`), async route => {
    const after = new URL(route.request().url()).searchParams.get('after')
    resumes.push(after)
    if (resumes.length === 1) {
      const event = realtimeEvent('CONVERSATION_MESSAGE_POSTED', { messageId: 'durable-message' }, {
        eventId: 'durable-event-id', domainEventId: 'durable-domain-id', streamType: 'CONVERSATION', aggregateVersion: 5,
      })
      return route.fulfill({ status: 200, contentType: 'text/event-stream', body: `id:opaque+/conversation-cursor\nevent:CONVERSATION_MESSAGE_POSTED\ndata:${JSON.stringify(event)}\n\n` })
    }
    return fulfillSse(route, [])
  })
  await page.goto(`/conversation?team=${ids.team}&project=${ids.project}&conversation=${ids.conversation}`)

  await expect.poll(() => resumes.length).toBeGreaterThanOrEqual(2)
  expect(resumes[1]).toBe('opaque+/conversation-cursor')
})

test('Conversation cancels an active Agent invocation explicitly', async ({ page }) => {
  let invocationAttempts = 0
  let cancelled = false
  await page.route(/\/agent-invocations$/, async route => {
    invocationAttempts += 1
    if (invocationAttempts === 1) {
      return fulfillSse(route, [realtimeEvent('RUN_STARTED', {}, { eventId: 'cancel-started' })], { 'X-CrewScope-Invocation-Id': 'invocation-cancel' })
    }
    await expect.poll(() => cancelled).toBe(true)
    return fulfillSse(route, [realtimeEvent('RUN_FINISHED', { status: 'CANCELED' }, { eventId: 'cancel-finished' })], { 'X-CrewScope-Invocation-Id': 'invocation-cancel', 'Idempotency-Replayed': 'true' })
  })
  await page.route(/\/agent-invocations\/invocation-cancel\/cancel$/, async route => {
    cancelled = true
    await route.fulfill({ status: 202, contentType: 'application/json', body: JSON.stringify({ invocationId: 'invocation-cancel', result: 'ACCEPTED', correlationId: 'cancel-correlation' }) })
  })
  await page.goto(`/conversation?team=${ids.team}&project=${ids.project}&conversation=${ids.conversation}`)
  await page.getByLabel('消息内容').fill('请取消这次调用。')
  await page.getByLabel('消息内容').press('Enter')
  await page.getByRole('button', { name: '取消', exact: true }).click()

  await expect(page.getByText('本次 Agent 调用已取消', { exact: true })).toBeVisible()
  expect(cancelled).toBe(true)
})

test('Conversation exposes loading and empty states without inventing local facts', async ({ page }) => {
  let releaseRequest!: () => void
  const requestGate = new Promise<void>(resolve => { releaseRequest = resolve })
  await page.route(/\/conversations(?:\?.*)?$/, async route => {
    await requestGate
    await fulfillJson(route, { items: [], nextCursor: null })
  })

  await page.goto(`/conversation?team=${ids.team}&project=${ids.project}`)
  // Vite may still be warming the Conversation chunk on the first desktop test in a full run.
  await expect(page.getByText('正在加载对话', { exact: true })).toBeVisible({ timeout: 15_000 })

  releaseRequest()
  await expect(page.getByText('这个 Team 还没有对话', { exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: '创建第一个对话' })).toBeVisible()
})

test('Conversation redirects API-level forbidden responses to the access boundary', async ({ page }) => {
  await page.route(/\/conversations(?:\?.*)?$/, route => fulfillError(route, 403, 'conversation_forbidden', 'Conversation access denied'))

  await page.goto(`/conversation?team=${ids.team}&project=${ids.project}`)

  // Keep the assertion local: API denial redirects after the cold-start request settles.
  await expect(page).toHaveURL(/\/access-denied\?from=/, { timeout: 15_000 })
  expect(new URL(page.url()).searchParams.get('from')).toContain('/conversation?')
})

test('Conversation list remains operable at the configured viewport', async ({ page }) => {
  await page.goto(`/conversation?team=${ids.team}&project=${ids.project}`)

  await expect(page.getByRole('region', { name: '对话列表' })).toBeVisible()
  await expect(page.getByRole('button', { name: '打开对话 规划 GitHub Provider 接入' })).toBeVisible()
  await expect(page.getByRole('button', { name: '新建对话', exact: true }).last()).toBeVisible()
})

test('desktop rail pins the account area while navigation scrolls independently', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 640 })
  await page.goto(`/today?team=${ids.team}&project=${ids.project}`)

  const rail = page.locator('.app-shell__rail')
  const navigation = rail.locator('.rail-navigation')
  const profile = rail.locator('.rail-profile')
  await expect(profile.getByRole('button', { name: /账号菜单/ })).toBeVisible()

  const layout = await navigation.evaluate(element => ({
    clientHeight: element.clientHeight,
    scrollHeight: element.scrollHeight,
    overflowY: getComputedStyle(element).overflowY,
  }))
  expect(layout.overflowY).toBe('auto')
  expect(layout.scrollHeight).toBeGreaterThan(layout.clientHeight)

  const profileBeforeScroll = await profile.boundingBox()
  await navigation.evaluate(element => { element.scrollTop = element.scrollHeight })
  await expect(navigation.getByText('System', { exact: true })).toBeVisible()
  const profileAfterScroll = await profile.boundingBox()
  const railBox = await rail.boundingBox()

  expect(profileBeforeScroll).not.toBeNull()
  expect(profileAfterScroll).not.toBeNull()
  expect(railBox).not.toBeNull()
  expect(Math.abs(profileAfterScroll!.y - profileBeforeScroll!.y)).toBeLessThanOrEqual(1)
  expect(profileAfterScroll!.y + profileAfterScroll!.height).toBeLessThanOrEqual(railBox!.y + railBox!.height)

  await profile.getByRole('button', { name: /账号菜单/ }).click()
  await expect(page.getByRole('menu', { name: '账号菜单' })).toBeVisible()
})

test('Conversation preserves its draft offline and resumes submission online', async ({ page, context }) => {
  await page.goto(`/conversation?team=${ids.team}&project=${ids.project}&conversation=${ids.conversation}`)
  const composer = page.getByRole('form', { name: '发送消息' })
  const message = composer.getByLabel('消息内容')
  await expect(message).toBeEditable()
  await message.fill('保留这份离线草稿')

  await context.setOffline(true)

  await expect(page.getByText(/当前离线：已加载事实和草稿已保留/)).toBeVisible()
  await expect(message).toBeEditable()
  await expect(message).toHaveValue('保留这份离线草稿')
  await expect(composer.getByRole('button', { name: '发送' })).toBeDisabled()
  await expect(message).toHaveAttribute('placeholder', /当前离线，可继续编辑草稿/)

  await context.setOffline(false)

  await expect(page.getByText(/当前离线：已加载事实和草稿已保留/)).toBeHidden()
  await expect(message).toHaveValue('保留这份离线草稿')
  await expect(composer.getByRole('button', { name: '发送' })).toBeEnabled()
})

test('Conversation manages detail and create-dialog focus for keyboard users', async ({ page }, testInfo) => {
  await page.goto(`/conversation?team=${ids.team}&project=${ids.project}`)
  const skipLink = page.getByRole('link', { name: '跳到主要内容' })
  await skipLink.focus()
  await expect(skipLink).toBeFocused()
  await page.keyboard.press('Enter')
  await expect(page.locator('#main-workspace')).toBeFocused()

  const conversationButton = page.getByRole('button', { name: '打开对话 规划 GitHub Provider 接入' })
  await conversationButton.click()

  const detailHeading = page.getByRole('heading', { name: '规划 GitHub Provider 接入', exact: true, level: 2 })
  await expect(detailHeading).toBeFocused()
  if (testInfo.project.name === 'narrow-chromium') {
    await page.getByRole('button', { name: '返回对话列表' }).click()
    await expect(conversationButton).toBeFocused()
  }

  const createTrigger = page.getByRole('button', { name: '新建对话', exact: true }).last()
  await createTrigger.click()
  const dialog = page.getByRole('dialog', { name: '新建对话' })
  await expect(dialog.getByLabel('标题')).toBeFocused()

  const closeButton = dialog.getByRole('button', { name: '关闭新建对话' })
  await closeButton.focus()
  await page.keyboard.press('Shift+Tab')
  await expect(dialog.getByRole('button', { name: '创建对话' })).toBeFocused()
  await page.keyboard.press('Tab')
  await expect(closeButton).toBeFocused()
  await page.keyboard.press('Escape')
  await expect(dialog).toBeHidden()
  await expect(createTrigger).toBeFocused()
})

test('Conversation loading animation honors reduced-motion preference', async ({ page }) => {
  let releaseRequest!: () => void
  const requestGate = new Promise<void>(resolve => { releaseRequest = resolve })
  await page.route(/\/conversations(?:\?.*)?$/, async route => {
    await requestGate
    await fulfillJson(route, { items: [], nextCursor: null })
  })
  await page.goto(`/conversation?team=${ids.team}&project=${ids.project}`)
  const spinner = page.locator('.spinning').first()
  await expect(spinner).toBeVisible({ timeout: 15_000 })
  await expect(spinner).toHaveCSS('animation-name', 'none')
  releaseRequest()
})

test('Conversation creates a server-backed Team conversation and opens it', async ({ page }) => {
  await page.goto(`/conversation?team=${ids.team}&project=${ids.project}`)
  await page.getByRole('button', { name: '新建对话', exact: true }).first().click()

  const dialog = page.getByRole('dialog', { name: '新建对话' })
  await dialog.getByLabel('标题').fill('检查下一阶段发布边界')
  await dialog.getByLabel('团队对话').check()
  await dialog.getByRole('button', { name: '创建对话' }).click()

  await expect(dialog).toBeHidden()
  const createdHeading = page.getByRole('heading', { name: '检查下一阶段发布边界', exact: true, level: 2 })
  await expect(createdHeading).toBeVisible()
  await expect(createdHeading).toBeFocused()
  await expect(page.getByText('开始这个对话', { exact: true })).toBeVisible()
  expect(new URL(page.url()).searchParams.get('conversation')).toBeTruthy()
})

test('Conversation clears an incompatible deep link when switching Team Scope', async ({ page }) => {
  await page.goto(`/conversation?team=${ids.team}&project=${ids.project}&conversation=${ids.conversation}`)
  await expect(page.getByRole('heading', { name: '规划 GitHub Provider 接入', exact: true }).first()).toBeVisible()

  await page.getByRole('button', { name: /Platform Engineering/ }).click()
  await page.getByRole('region', { name: '切换团队和项目' }).getByRole('button', { name: /Security Engineering/ }).click()

  await expect(page).not.toHaveURL(/conversation=00000000-0000-0000-0000-000000001101/)
  await expect(page.getByRole('button', { name: /打开对话 检查 Runtime 身份边界/ })).toBeVisible()
})

test('Work restores URL scope and ScopeSwitcher changes the Team', async ({ page }) => {
  await page.goto(`/work?team=${ids.secondTeam}&project=${ids.secondProject}`)
  await expect(page.getByRole('heading', { name: 'Runtime Security', exact: true, level: 1 })).toBeVisible()

  await page.getByRole('button', { name: /Security Engineering/ }).click()
  await page.getByRole('region', { name: '切换团队和项目' }).getByRole('button', { name: /Platform Engineering/ }).click()

  await expect(page).toHaveURL(new RegExp(`/work\\?.*team=${ids.team}.*project=${ids.project}`))
  await expect(page.getByRole('heading', { name: 'CrewScope', exact: true, level: 1 })).toBeVisible()
})

test('member management remains usable at the configured viewport', async ({ page }) => {
  await page.goto(`/team/members?team=${ids.team}&project=${ids.project}`)

  await expect(page.getByRole('heading', { name: 'Platform Engineering 成员' })).toBeVisible()
  await expect(page.getByRole('table', { name: '团队成员列表' })).toBeVisible()
  await expect(page.getByRole('button', { name: '添加成员', exact: true }).first()).toBeVisible()
})

test('Agent Center restores a deep link and remains keyboard-operable at desktop and narrow viewports', async ({ page }) => {
  await page.goto(`/settings/agents?team=${ids.team}&agent=${ids.agentCoding}&configurationRevision=2`)

  await expect(page.getByRole('heading', { name: 'Platform Engineering · Agent 中心' })).toBeVisible()
  await expect(page.getByRole('heading', { name: '默认 Personal Agent' })).toBeVisible()
  await expect(page.getByRole('heading', { name: '我的 Specialist' })).toBeVisible()
  await expect(page.locator('#team-agent-directory').getByRole('heading', { name: '团队 Agent' })).toBeVisible()
  const selected = page.locator(`.agent-card[href*="agent=${ids.agentCoding}"]`)
  await expect(selected).toHaveAttribute('aria-current', 'page')
  await expect(selected).toContainText('deepseek-v4-flash')
  await expect(page.getByText('已禁用', { exact: true })).toBeVisible()
  await expect(page.getByText('已归档', { exact: true })).toBeVisible()

  await selected.focus()
  await expect(selected).toBeFocused()
  await page.keyboard.press('Enter')
  expect(new URL(page.url()).searchParams.get('agent')).toBe(ids.agentCoding)
  expect(new URL(page.url()).searchParams.get('configurationRevision')).toBe('2')
  expect(await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth)).toBeLessThanOrEqual(1)
  await expect(page.locator('body')).not.toContainText(/sk-|credential|system prompt/i)

  await page.getByRole('button', { name: /Platform Engineering/ }).click()
  await page.getByRole('region', { name: '切换团队和项目' }).getByRole('button', { name: /Security Engineering/ }).click()
  await expect(page).not.toHaveURL(/agent=/)
  expect(new URL(page.url()).searchParams.get('configurationRevision')).toBeNull()
  await expect(page.getByRole('heading', { name: 'Security Engineering · Agent 中心' })).toBeVisible()
  await expect(page.getByText('这个 Team 还没有团队 Agent')).toBeVisible()
})

test('Model settings verifies and rotates a Team credential without retaining its Key', async ({ page }) => {
  await page.goto(`/settings/models?team=${ids.team}&provider=deepseek&ownerType=TEAM&connection=${ids.teamModelConnection}`)

  await expect(page.getByRole('heading', { name: 'Platform Engineering · 模型与凭证' })).toBeVisible()
  await expect(page.getByRole('heading', { name: 'DeepSeek V4 Flash' })).toBeVisible()
  await expect(page.getByText('身份验证失败')).toBeVisible()
  await page.getByRole('button', { name: '验证健康' }).click()
  await expect(page.locator('.connection-detail').getByText('HEALTHY', { exact: true })).toBeVisible()
  await expect(page.getByText(/Correlation/)).toBeVisible()

  await page.getByRole('button', { name: '轮换凭证' }).click()
  const rotate = page.getByRole('dialog', { name: '轮换模型凭证' })
  await expect(rotate.getByLabel('API Key')).toBeFocused()
  await rotate.getByLabel('API Key').fill('e2e-one-way-secret')
  await rotate.getByRole('button', { name: '轮换凭证' }).click()

  await expect(rotate).toHaveCount(0)
  await expect(page.locator('.connection-detail')).toContainText('Credential Version3')
  await expect(page.locator('.connection-list')).toContainText('Credential v3')
  await expect(page.locator('body')).not.toContainText('e2e-one-way-secret')
  expect(new URL(page.url()).search).not.toContain('e2e-one-way-secret')
  expect(await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth)).toBeLessThanOrEqual(1)
})

test('Model settings visual baseline', async ({ page }, testInfo) => {
  await page.goto(`/settings/models?team=${ids.team}&provider=deepseek&ownerType=TEAM&connection=${ids.teamModelConnection}`)
  await expect(page.getByText('身份验证失败')).toBeVisible()
  await expect(page.getByRole('heading', { name: 'DeepSeek V4 Flash' })).toBeVisible()
  await expect(page).toHaveScreenshot(`model-settings-${testInfo.project.name}.png`, { fullPage: true })

  await page.getByRole('button', { name: '创建连接' }).first().click()
  await expect(page.getByRole('dialog', { name: '创建模型连接' })).toBeVisible()
  await expect(page).toHaveScreenshot(`model-credential-create-${testInfo.project.name}.png`, { fullPage: true })
})

test('Repository settings preflights and transitions a binding at desktop and narrow viewports', async ({ page }) => {
  await page.goto(`/settings/repositories?team=${ids.team}&project=${ids.project}`)

  await expect(page.getByRole('heading', { name: 'CrewScope 仓库设置' })).toBeVisible()
  await expect(page.getByText('crewscope-java', { exact: true }).first()).toBeVisible()
  await page.getByRole('button', { name: 'Preflight', exact: true }).click()
  await expect(page.getByText(/Preflight 通过/)).toBeVisible()

  await page.getByRole('button', { name: '停用', exact: true }).click()
  await expect(page.getByText('DISABLED', { exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: '启用', exact: true })).toBeVisible()

  await page.getByRole('button', { name: '绑定仓库', exact: true }).last().click()
  await expect(page.getByRole('heading', { name: '绑定受管仓库' })).toBeVisible()
  await expect(page.getByLabel('Repository Key')).toHaveValue('agentscope-java')
  await expect(page.locator('body')).not.toContainText('/private/')
})

test('Repository settings restores create focus and closes writes while offline', async ({ page, context }) => {
  await page.goto(`/settings/repositories?team=${ids.team}&project=${ids.project}`)
  await expect(page.getByText('crewscope-java', { exact: true }).first()).toBeVisible()
  const opener = page.getByRole('button', { name: '绑定仓库', exact: true }).last()

  await opener.click()
  await expect(page.getByLabel('Repository Key')).toBeFocused()
  await page.keyboard.press('Escape')
  await expect(page.getByRole('heading', { name: '绑定受管仓库' })).toHaveCount(0)
  await expect(opener).toBeFocused()

  try {
    await context.setOffline(true)
    await expect(page.getByText('仓库写操作已暂停')).toBeVisible()
    await expect(page.getByText('crewscope-java', { exact: true }).first()).toBeVisible()
    await expect(page.getByRole('button', { name: 'Preflight', exact: true })).toBeDisabled()
    await expect(page.getByRole('button', { name: '停用', exact: true })).toBeDisabled()
    await expect(page.getByRole('button', { name: '绑定仓库', exact: true }).first()).toBeDisabled()
  } finally {
    // 即使断言失败也恢复 BrowserContext，避免离线状态污染同一 Worker 的后续用例。
    await context.setOffline(false)
  }
})

test('Work restores filters and groups matching items on the Board', async ({ page }) => {
  await page.goto(`/work?team=${ids.team}&project=${ids.project}&view=board&status=all&type=FEATURE&priority=HIGH`)

  await expect(page.getByLabel('工作项看板')).toBeVisible()
  await expect(page.getByLabel('进行中').getByRole('button', { name: '打开 CRW-18 共享范围与筛选状态' })).toBeVisible()
  await expect(page.getByLabel('审查中').getByRole('button', { name: '打开 CRW-21 审核协作入口' })).toBeVisible()
  await expect(page.getByText('修复工作项游标')).toHaveCount(0)

  await page.getByRole('button', { name: '列表视图' }).click()
  await expect(page).toHaveURL(/view=list/)
  await page.reload()
  await expect(page.getByLabel('工作项列表')).toBeVisible()
  await expect(page.getByRole('combobox').nth(1)).toHaveValue('FEATURE')
})

test('Work clears local type and priority filters in one route update', async ({ page }) => {
  await page.goto(`/work?team=${ids.team}&project=${ids.project}&view=list&status=all&type=BUG&priority=HIGH`)
  await expect(page.getByText('没有符合筛选条件的工作项')).toBeVisible()

  await page.getByRole('button', { name: '清除本地筛选' }).click()

  await expect(page.getByLabel('工作项列表')).toBeVisible()
  await expect(page.getByRole('combobox').nth(1)).toHaveValue('all')
  await expect(page.getByRole('combobox').nth(2)).toHaveValue('all')
  const query = new URL(page.url()).searchParams
  expect(query.get('type')).toBe('all')
  expect(query.get('priority')).toBe('all')
})

test('Work continues from the opaque Cursor', async ({ page }) => {
  await page.goto(`/work?team=${ids.team}&project=${ids.project}`)
  await expect(page.getByText('归档测试证据')).toHaveCount(0)

  await page.getByRole('button', { name: '加载更多工作项' }).click()

  await expect(page.getByRole('button', { name: '打开 CRW-22 归档测试证据' })).toBeVisible()
})

test('Work creates a native WorkItem and refreshes the active query', async ({ page }) => {
  await page.goto(`/work?team=${ids.team}&project=${ids.project}`)
  await page.getByRole('button', { name: '新建工作项' }).click()

  const dialog = page.getByRole('dialog', { name: '新建工作项' })
  await dialog.getByLabel('标题').fill('补充团队发布检查项')
  await dialog.getByLabel('优先级').selectOption('HIGH')
  await dialog.getByLabel('标签').fill('release, team')
  await dialog.getByRole('button', { name: '创建工作项' }).click()

  await expect(dialog).toBeHidden()
  await expect(page.getByText('补充团队发布检查项')).toBeVisible()
})

test('WorkItem detail supports deep links, Escape and focus restoration', async ({ page }) => {
  await page.goto(`/work?team=${ids.team}&project=${ids.project}&workItem=${ids.workItem}`)
  const dialog = page.getByRole('dialog', { name: 'CRW-18 工作项详情' })

  await expect(dialog).toBeVisible()
  await expect(page).toHaveURL(/focus=CRW-18/)
  await expect(dialog.getByRole('button', { name: '关闭工作项详情' })).toBeFocused()
  await page.keyboard.press('Shift+Tab')
  await expect(dialog.getByRole('button', { name: '交给 Agent 处理' })).toBeFocused()
  await page.keyboard.press('Tab')
  await expect(dialog.getByRole('button', { name: '关闭工作项详情' })).toBeFocused()
  await page.keyboard.press('Escape')
  await expect(dialog).toBeHidden()
  await expect(page).not.toHaveURL(/workItem=/)

  const card = page.getByRole('button', { name: '打开 CRW-18 共享范围与筛选状态' })
  await card.click()
  await page.getByRole('button', { name: '关闭工作项详情' }).click()
  await expect(card).toBeFocused()
})

test('WorkItem detail transitions, comments, links and continues in Conversation', async ({ page }) => {
  await page.goto(`/work?team=${ids.team}&project=${ids.project}&workItem=${ids.workItem}`)
  const dialog = page.getByRole('dialog', { name: 'CRW-18 工作项详情' })

  await dialog.getByRole('button', { name: '提交流转' }).click()
  await expect(dialog.getByText('审查中', { exact: true }).first()).toBeVisible()
  await dialog.getByLabel('添加评论').fill('补充端到端验收结论')
  await dialog.getByRole('button', { name: '发送评论' }).click()
  await expect(dialog.getByText('补充端到端验收结论')).toBeVisible()

  await dialog.getByLabel('引用').fill('https://example.com/evidence')
  await dialog.getByLabel('显示名称').fill('端到端证据')
  await dialog.getByRole('button', { name: '关联资源' }).click()
  await expect(dialog.getByText('端到端证据')).toBeVisible()

  await dialog.getByRole('button', { name: '与 Personal Agent 讨论' }).click()
  await expect(page).toHaveURL(/\/conversation\?/)
  expect(new URL(page.url()).searchParams.get('focus')).toBe('CRW-18')
  expect(new URL(page.url()).searchParams.get('workItem')).toBe(ids.workItem)
  await expect(page.getByRole('heading', { name: /CRW-18/ })).toBeVisible()
})

test('WorkItem responsibility management and Timeline keep server policy boundaries visible', async ({ page }) => {
  await page.goto(`/work?team=${ids.team}&project=${ids.project}&workItem=${ids.workItem}`)
  const dialog = page.getByRole('dialog', { name: 'CRW-18 工作项详情' })

  await expect(dialog.getByText('Architecture Reviewer')).toBeVisible()
  await expect(dialog.getByText('不具有 Gate 效力')).toBeVisible()
  await dialog.getByLabel('新 Owner').selectOption(ids.thirdPrincipal)
  await dialog.getByRole('button', { name: '替换', exact: true }).click()
  await expect(dialog.getByText('周宁').first()).toBeVisible()

  await dialog.getByLabel('Gate Reviewer', { exact: true }).selectOption(ids.secondPrincipal)
  await expect(dialog.getByText(/默认职责分离策略会拒绝/)).toBeVisible()
  await dialog.getByLabel('Gate Reviewer', { exact: true }).selectOption(ids.thirdPrincipal)
  await dialog.locator('.responsibility-actions > form').nth(2).getByRole('button', { name: '添加' }).click()
  await expect(dialog.getByText('具备 Gate 审查效力')).toBeVisible()

  await dialog.getByRole('button', { name: /释放 Architecture Reviewer/ }).click()
  await expect(dialog.getByText('Architecture Reviewer')).toHaveCount(0)
  await expect(dialog.getByText('分配责任').first()).toBeVisible()
  await dialog.getByRole('button', { name: '加载更早活动' }).click()
  await expect(dialog.getByLabel('工作项时间线').getByText('添加评论')).toBeVisible()
})

test('WorkItem delegates to its assigned Agent and refreshes the Task deep link', async ({ page }) => {
  await page.goto(`/work?team=${ids.team}&project=${ids.project}&workItem=${ids.workItem}`)
  const dialog = page.getByRole('dialog', { name: 'CRW-18 工作项详情' })

  await dialog.getByRole('button', { name: '交给 Agent 处理' }).click()
  const delegate = page.getByRole('dialog', { name: '交给 Agent 处理' })
  await expect(delegate.getByText('Owner · 张凯旋')).toBeVisible()
  await expect(delegate.getByText('Executor · 张凯旋的 Personal Agent')).toBeVisible()
  await delegate.getByLabel('执行目标').fill('由 Personal Agent 验证 M3-F02')
  await delegate.getByRole('button', { name: '验证 Ref' }).click()
  await expect(delegate.getByText(/^Preflight 通过 ·/)).toBeVisible()
  await delegate.getByRole('button', { name: '创建 Task' }).click()

  await expect(delegate).toBeHidden()
  await expect(page.getByRole('heading', { name: '由 Personal Agent 验证 M3-F02', exact: true })).toBeVisible()
  expect(new URL(page.url()).searchParams.get('task')).toBeTruthy()
  expect(new URL(page.url()).searchParams.get('workItem')).toBe(ids.workItem)
})

test('Task delegation preflight remains accessible and visually stable', async ({ page }, testInfo) => {
  await page.goto(`/work?team=${ids.team}&project=${ids.project}&workItem=${ids.workItem}`)
  await page.getByRole('dialog', { name: 'CRW-18 工作项详情' }).getByRole('button', { name: '交给 Agent 处理' }).click()
  const delegate = page.getByRole('dialog', { name: '交给 Agent 处理' })

  await expect(delegate.getByText('PolicySnapshot Preflight 通过')).toBeVisible()
  await expect(delegate.locator('.preflight-header').getByText('PERSONAL', { exact: true })).toBeVisible()
  await expect(delegate.getByText(/deepseek \/ deepseek-v4-flash/)).toBeVisible()
  await expect(delegate.getByText(/当前 Preflight API 不披露 Billing Subject/)).toBeVisible()
  expect((await new AxeBuilder({ page }).include('.delegate-dialog').analyze()).violations).toEqual([])
  await expect(delegate).toHaveScreenshot(`task-delegation-preflight-${testInfo.project.name}.png`, { animations: 'disabled' })
})

test('CodingTarget loading indicator honors reduced motion', async ({ page }) => {
  let release!: () => void
  const gate = new Promise<void>(resolve => { release = resolve })
  await page.route(/\/coding-target\/build-profiles$/, async route => {
    await gate
    await route.fallback()
  })
  await page.goto(`/work?team=${ids.team}&project=${ids.project}&workItem=${ids.workItem}`)
  await page.getByRole('dialog', { name: 'CRW-18 工作项详情' }).getByRole('button', { name: '交给 Agent 处理' }).click()

  const spinner = page.getByRole('dialog', { name: '交给 Agent 处理' }).locator('.coding-target .spin')
  await expect(spinner).toBeVisible()
  expect(await spinner.evaluate(element => getComputedStyle(element).animationName)).toBe('none')
  release()
  await expect(page.getByRole('dialog', { name: '交给 Agent 处理' }).locator('.coding-target select').first()).toBeVisible()
})

test('TaskIntent WorkItem handoff creates a Conversation-linked Task and restores its card', async ({ page }) => {
  await page.goto(`/conversation?team=${ids.team}&project=${ids.project}&conversation=${ids.conversation}`)
  await page.getByRole('region', { name: '已确认工作项' }).getByRole('button', { name: '为工作项 CRW-18 配置 Coding Task' }).click()
  await expect(page).toHaveURL(/sourceMessage=/)
  expect(new URL(page.url()).searchParams.get('sourceMessage')).toBe('00000000-0000-0000-0000-000000001304')

  const delegate = page.getByRole('dialog', { name: '交给 Agent 处理' })
  await expect(delegate.getByText('来源保留为当前 Conversation 消息')).toBeVisible()
  await delegate.getByLabel('执行目标').fill('从 TaskIntent 上下文创建耐久 Task')
  await delegate.getByRole('button', { name: '验证 Ref' }).click()
  await delegate.getByRole('button', { name: '创建 Task' }).click()

  const taskDialog = page.getByRole('dialog', { name: /从 TaskIntent 上下文创建耐久 Task Task 详情/ })
  await expect(taskDialog).toBeVisible()
  await taskDialog.getByRole('button', { name: /规划 GitHub Provider 接入/ }).click()
  const cards = page.getByTestId('conversation-task-cards')
  await expect(cards.getByText('从 TaskIntent 上下文创建耐久 Task', { exact: true })).toBeVisible()
  await page.reload()
  await expect(cards.getByText('从 TaskIntent 上下文创建耐久 Task', { exact: true })).toBeVisible()
})

test('Task creation retries with the same idempotency key after a transient failure', async ({ page }) => {
  const keys: string[] = []
  let attempts = 0
  await page.route(new RegExp(`/work-items/${ids.workItem}/tasks$`), async route => {
    if (route.request().method() !== 'POST') return route.fallback()
    attempts += 1
    keys.push(route.request().headers()['idempotency-key']!)
    if (attempts === 1) return fulfillError(route, 503, 'task_temporarily_unavailable', 'Task 服务暂时不可用')
    await route.fallback()
  })
  await page.goto(`/work?team=${ids.team}&project=${ids.project}&workItem=${ids.workItem}`)
  await page.getByRole('dialog', { name: 'CRW-18 工作项详情' }).getByRole('button', { name: '交给 Agent 处理' }).click()
  const delegate = page.getByRole('dialog', { name: '交给 Agent 处理' })

  await delegate.getByRole('button', { name: '验证 Ref' }).click()
  await delegate.getByRole('button', { name: '创建 Task' }).click()
  await expect(delegate.getByText('Task 服务暂时不可用')).toBeVisible()
  await delegate.getByRole('button', { name: '使用原请求重试' }).click()

  await expect(delegate).toBeHidden()
  expect(attempts).toBe(2)
  expect(keys[1]).toBe(keys[0])
})

test('Control Mode Task list restores status, Owner and Task deep links on narrow and desktop layouts', async ({ page }) => {
  await page.goto(`/work?team=${ids.team}&project=${ids.project}&taskStatus=WAITING&taskOwner=${ids.principal}`)
  const panel = page.getByRole('region', { name: 'Agent Tasks' })

  await expect(panel.getByText('等待原因 · CAPACITY')).toBeVisible()
  await expect(panel.getByText('Attempt 2 · WAITING')).toBeVisible()
  await expect(panel.getByLabel('Task 筛选').getByRole('combobox').first()).toHaveValue('WAITING')
  await expect(panel.getByLabel('Task 筛选').getByRole('combobox').last()).toHaveValue(ids.principal)
  await panel.getByRole('button', { name: '查看 Task：完成 Agent Task 列表与委托入口' }).click()

  expect(new URL(page.url()).searchParams.get('task')).toBe(ids.task)
  await page.reload()
  await expect(panel.getByRole('button', { name: '查看 Task：完成 Agent Task 列表与委托入口' })).toBeVisible()
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth)
  expect(overflow).toBeLessThanOrEqual(1)
})

test('Control Mode Task list exposes loading, empty, error and retry states', async ({ page }) => {
  let release!: () => void
  const loadingGate = new Promise<void>(resolve => { release = resolve })
  let requests = 0
  await page.route(/\/teams\/[^/]+\/tasks(?:\?.*)?$/, async route => {
    requests += 1
    if (requests === 1) {
      await loadingGate
      await fulfillJson(route, { items: [], nextCursor: null })
      return
    }
    if (requests === 2) {
      await fulfillError(route, 503, 'task_query_unavailable', 'Task 状态服务暂时不可用')
      return
    }
    await fulfillJson(route, { items: [], nextCursor: null })
  })

  await page.goto(`/work?team=${ids.team}&project=${ids.project}`)
  const panel = page.getByRole('region', { name: 'Agent Tasks' })
  await expect(panel.getByText('正在加载 Agent Tasks')).toBeVisible()
  release()
  await expect(panel.getByText('当前筛选下没有 Task')).toBeVisible()

  await page.reload()
  await expect(panel.getByText('Task 状态服务暂时不可用')).toBeVisible()
  await panel.getByRole('button', { name: '刷新事实' }).click()
  await expect(panel.getByText('当前筛选下没有 Task')).toBeVisible()
})

test('Task API forbidden responses enter the shared access boundary', async ({ page }) => {
  await page.route(new RegExp(`/tasks/${ids.task}$`), route => (
    fulfillError(route, 403, 'task_forbidden', 'Task access denied')
  ))

  await page.goto(`/work?team=${ids.team}&project=${ids.project}&task=${ids.task}`)

  await expect(page).toHaveURL(/\/access-denied/)
  await expect(page.getByRole('heading', { name: '需要额外的团队权限' })).toBeVisible()
  expect(new URL(page.url()).searchParams.get('from')).toContain('/work')
})

test('Execution Studio restores the Coding attempt and Workspace from both Task entry modes', async ({ page }) => {
  await page.goto(`/work?team=${ids.team}&project=${ids.project}&task=${ids.task}&workItem=${ids.workItem}`)
  const dialog = page.getByRole('dialog', { name: /完成 Agent Task 列表与委托入口 Task 详情/ })
  const studio = dialog.getByTestId('execution-studio')

  await expect(studio.getByRole('heading', { name: 'Execution Studio' })).toBeVisible()
  await expect(studio.getByText('crewscope-java', { exact: true })).toBeVisible()
  await expect(studio.getByText('Workspace 正在恢复')).toBeVisible()
  await expect(studio.getByText('恢复代次 1', { exact: true })).toBeVisible()
  await expect(studio.getByText('coding.maven.test', { exact: true })).toBeVisible()
  await expect(studio.getByText('2 / 20', { exact: true })).toBeVisible()
  await expect(studio.getByText('5 / 100', { exact: true })).toBeVisible()
  await expect(studio.getByText(/private|container-secret|task-token|typedArgv/)).toHaveCount(0)
  await expect.poll(() => new URL(page.url()).searchParams.get('attempt')).toBe(ids.taskExecution)
  expect(new URL(page.url()).searchParams.get('workspace')).toBe(ids.codingWorkspace)

  await dialog.locator('.attempt-list button').filter({ hasText: 'Attempt 1' }).click()
  await expect(studio.getByText('Attempt 1 · FAILED', { exact: true })).toBeVisible()
  await expect(studio.locator('.studio-card--command').getByText('测试超时', { exact: false })).toBeVisible()
  await expect.poll(() => new URL(page.url()).searchParams.get('workspace')).toBe(ids.previousCodingWorkspace)
})

test('Execution Studio keeps non-Coding empty semantics and closes forbidden Coding facts', async ({ page }) => {
  await page.route(new RegExp(`/tasks/${ids.task}/coding$`), route => fulfillJson(route, {
    taskId: ids.task, currentAttempt: null,
  }))
  await page.route(new RegExp(`/tasks/${ids.task}/coding-attempts$`), route => fulfillJson(route, []))
  await page.goto(`/work?team=${ids.team}&project=${ids.project}&task=${ids.task}`)
  await expect(page.getByTestId('execution-studio').getByText('这是通用 Agent Task')).toBeVisible()

  await page.route(new RegExp(`/tasks/${ids.task}/coding$`), route => (
    fulfillError(route, 403, 'coding_attempt_forbidden', 'Coding attempt access denied')
  ))
  await page.reload()
  await expect(page).toHaveURL(/\/access-denied/)
  await expect(page.getByRole('heading', { name: '需要额外的团队权限' })).toBeVisible()
})

test('Diff Explorer replays RESET and DELTA, reads a single-file Patch and keeps responsive reading order', async ({ page }, testInfo) => {
  await page.goto(`/work?team=${ids.team}&project=${ids.project}&task=${ids.task}&workItem=${ids.workItem}`)
  const dialog = page.getByRole('dialog', { name: /完成 Agent Task 列表与委托入口 Task 详情/ })
  const explorer = dialog.getByTestId('coding-diff-explorer')

  await expect(explorer.getByRole('heading', { name: 'Diff Explorer' })).toBeVisible()
  await expect(explorer.getByText(/实时流(已连接|正在续传)/)).toBeVisible()
  await expect(explorer.getByText('Changed').locator('..').getByText('5', { exact: true })).toBeVisible()
  await expect(explorer.getByText('+28', { exact: true })).toBeVisible()
  await expect(explorer.getByText('-6', { exact: true })).toBeVisible()
  await expect(explorer.getByText(/private|container-secret|typedArgv|must-not-enter-browser-state/)).toHaveCount(0)

  await explorer.getByRole('button', { name: /NewFeature.java/ }).click()
  await explorer.getByRole('button', { name: '读取单文件 Patch' }).click()
  await expect(explorer.getByRole('region', { name: 'src/NewFeature.java Patch' })).toContainText('+public final class NewFeature')

  await explorer.getByRole('button', { name: /logo.png/ }).click()
  await expect(explorer.getByText('Binary 变更', { exact: true })).toBeVisible()

  const tree = explorer.locator('.diff-tree')
  const patch = explorer.locator('.patch-view')
  if (testInfo.project.name === 'narrow-chromium') {
    expect((await patch.boundingBox())!.y).toBeGreaterThan((await tree.boundingBox())!.y)
  } else {
    expect((await patch.boundingBox())!.x).toBeGreaterThan((await tree.boundingBox())!.x)
  }
})

test('Diff Explorer closes a forbidden Patch read at the shared access boundary', async ({ page }) => {
  await page.route(/\/coding\/artifacts\/patch(?:\?.*)?$/, route => (
    fulfillError(route, 403, 'coding_artifact_forbidden', 'Patch access denied')
  ))
  await page.goto(`/work?team=${ids.team}&project=${ids.project}&task=${ids.task}`)
  const explorer = page.getByTestId('coding-diff-explorer')

  await explorer.getByRole('button', { name: /NewFeature.java/ }).click()
  await explorer.getByRole('button', { name: '读取单文件 Patch' }).click()

  await expect(page).toHaveURL(/\/access-denied/)
  await expect(page.getByRole('heading', { name: '需要额外的团队权限' })).toBeVisible()
})

test('Evidence panel presents bounded command, test and acceptance artifacts as read-only text', async ({ page }, testInfo) => {
  await page.goto(`/work?team=${ids.team}&project=${ids.project}&task=${ids.task}`)
  const panel = page.getByTestId('coding-evidence-panel')

  // 窄屏冷启动需要加载完整 Work 与 Coding 依赖图，等待服务端事实完成首次收敛。
  await expect(panel.getByRole('heading', { name: '命令、测试与验收证据' })).toBeVisible({ timeout: 15_000 })
  await expect(panel.getByText('Exit Code')).toBeVisible()
  await expect(panel.getByText('207', { exact: true })).toBeVisible()
  await expect(panel.getByText('关键测试通过')).toBeVisible()
  await expect(panel.locator('input, textarea, [contenteditable="true"]')).toHaveCount(0)

  await panel.getByRole('button', { name: '读取首个日志页' }).click()
  await expect(panel.getByRole('region', { name: '只读命令日志' })).toContainText('Bearer [REDACTED]')
  await expect(panel.getByText('browser-must-hide')).toHaveCount(0)
  await expect(panel.getByRole('button', { name: '下载日志' })).toBeVisible()

  await panel.getByRole('button', { name: '读取首个报告页' }).click()
  await expect(panel.getByRole('region', { name: '只读测试报告' })).toContainText('"passed": 207')
  await expect(panel.getByRole('button', { name: '下载报告' })).toBeVisible()
  await panel.getByRole('region', { name: '只读测试报告' }).focus()
  await expect(panel.getByRole('region', { name: '只读测试报告' })).toBeFocused()

  if (testInfo.project.name === 'narrow-chromium') {
    const commandTop = (await panel.locator('.command-column').boundingBox())!.y
    const testsTop = (await panel.locator('.test-section').boundingBox())!.y
    expect(testsTop).toBeGreaterThan(commandTop)
  }
})

test('Evidence panel closes a forbidden command log at the shared access boundary', async ({ page }) => {
  await page.route(/\/coding\/commands\/[^/]+\/log(?:\?.*)?$/, route => (
    fulfillError(route, 403, 'coding_artifact_forbidden', 'Command log access denied')
  ))
  await page.goto(`/work?team=${ids.team}&project=${ids.project}&task=${ids.task}`)

  await page.getByTestId('coding-evidence-panel').getByRole('button', { name: '读取首个日志页' }).click()

  await expect(page).toHaveURL(/\/access-denied/)
  await expect(page.getByRole('heading', { name: '需要额外的团队权限' })).toBeVisible()
})

test('Coding progress integrates stages, Todo, checkpoints, repair budget and current controls', async ({ page }) => {
  await page.goto(`/work?team=${ids.team}&project=${ids.project}&task=${ids.task}&workItem=${ids.workItem}`)
  const dialog = page.getByRole('dialog', { name: /完成 Agent Task 列表与委托入口 Task 详情/ })
  const progress = dialog.getByTestId('coding-progress-control')

  await expect(progress.getByRole('heading', { name: 'Coding 进度与执行控制' })).toBeVisible()
  await expect(progress.getByRole('list', { name: 'Coding 阶段' }).getByText('测试与修复')).toBeVisible()
  await expect(progress.locator('[aria-current="step"]')).toContainText('Test #1')
  await expect(progress.getByText('实现 Runtime 详情', { exact: true })).toBeVisible()
  await expect(progress.getByText('#1 · SAFE_POINT')).toBeVisible()
  await expect(progress.getByText('#1 · checkpoint 1')).toBeVisible()
  await expect(progress.getByText('3 轮')).toBeVisible()
  await expect(progress.getByText(/当前公开事实未单独披露已用修复轮次/)).toBeVisible()
  await expect(progress.getByText(/Checkpoint 连续性缺口/)).toBeVisible()
  await expect(progress.getByRole('button', { name: '取消当前 Task' })).toBeVisible()
  await expect(progress.getByText(/stateReference|checkpointHash|secret-runtime-credential/)).toHaveCount(0)

  await dialog.locator('.attempt-list button').filter({ hasText: 'Attempt 1' }).click()
  await expect(progress.getByText('历史 Attempt 保持只读')).toBeVisible()
  await expect(progress.getByRole('button', { name: '取消当前 Task' })).toHaveCount(0)
})

test('Task detail switches attempts and explains Plan, Step, AgentRun, Lease and degraded Runtime facts', async ({ page }, testInfo) => {
  await page.goto(`/work?team=${ids.team}&project=${ids.project}&task=${ids.task}&workItem=${ids.workItem}`)
  const dialog = page.getByRole('dialog', { name: /完成 Agent Task 列表与委托入口 Task 详情/ })

  await expect(dialog.getByText('责任快照')).toBeVisible()
  await expect(dialog.getByText('Revision 2', { exact: true }).first()).toBeVisible()
  await expect(dialog.getByText('步骤进度 0/1')).toBeVisible()
  await expect(dialog.getByText('WAITING_RUNTIME', { exact: true })).toBeVisible()
  await expect(dialog.getByText('1 个 Worker 失联')).toBeVisible()
  await expect(dialog.getByText('WORKER_LOST', { exact: true }).first()).toBeVisible()
  await expect(dialog.getByText('secret-runtime-credential', { exact: true })).toHaveCount(0)

  await dialog.locator('.attempt-list button').filter({ hasText: 'Attempt 1' }).click()
  await expect(dialog.getByText('历史 attempt 的初始计划。', { exact: true })).toBeVisible()
  await expect(dialog.getByText('Agent Runs 0')).toBeVisible()

  if (testInfo.project.name === 'narrow-chromium') {
    const contextBottom = (await dialog.locator('.fleet-card').boundingBox())!.y
    const runtimeTop = (await dialog.locator('.plan-card').boundingBox())!.y
    expect(runtimeTop).toBeGreaterThan(contextBottom)
  } else {
    const contextX = (await dialog.locator('.task-detail-column--context').boundingBox())!.x
    const runtimeX = (await dialog.locator('.task-detail-column--runtime').boundingBox())!.x
    expect(runtimeX).toBeGreaterThan(contextX)
  }

  await dialog.getByLabel('关闭 Task 详情').click()
  expect(new URL(page.url()).searchParams.get('task')).toBeNull()
  expect(new URL(page.url()).searchParams.get('workItem')).toBe(ids.workItem)
  await expect(page.getByRole('dialog', { name: 'CRW-18 工作项详情' })).toBeVisible()
})

test('Task detail traps keyboard focus above its WorkItem drawer and restores the remaining modal', async ({ page }) => {
  await page.goto(`/work?team=${ids.team}&project=${ids.project}`)
  const trigger = page.getByRole('button', { name: '查看 Task：完成 Agent Task 列表与委托入口' })
  await trigger.click()
  const taskDialog = page.getByRole('dialog', { name: /完成 Agent Task 列表与委托入口 Task 详情/ })

  await expect(taskDialog.getByLabel('关闭 Task 详情')).toBeFocused()
  await page.keyboard.press('Shift+Tab')
  await expect(taskDialog.getByRole('button', { name: '查看工作项' })).toBeFocused()
  await page.keyboard.press('Tab')
  await expect(taskDialog.getByLabel('关闭 Task 详情')).toBeFocused()
  await page.keyboard.press('Escape')

  await expect(taskDialog).toBeHidden()
  const workItemDialog = page.getByRole('dialog', { name: 'CRW-18 工作项详情' })
  await expect(workItemDialog).toBeVisible()
  await expect(workItemDialog.getByLabel('关闭工作项详情')).toBeFocused()
})

test('Task Timeline catches up history, recovers an expired Cursor and merges public Agent progress', async ({ page }) => {
  const streamAfter: Array<string | null> = []
  let streamCalls = 0
  const history = [
    taskEventItem('history-start', 'history-domain-start', 'WORKER_TASK_START_ACCEPTED', { operation: 'START', attempt: 2 }, 'cursor-history-start', '2026-08-08T04:02:00Z'),
    taskEventItem('history-progress', 'history-domain-progress', 'AGENT_RUN_EVENT_RECORDED', { eventKind: 'PROGRESS', safeText: 'Agent 正在验证', progressPercent: 60, reasoning: 'private' }, 'cursor-history-progress', '2026-08-08T04:01:00Z'),
  ]
  await page.route(/\/tasks\/[^/]+\/events(?:\?.*)?$/, async route => {
    const request = route.request()
    const url = new URL(request.url())
    if (!request.headers().accept?.includes('text/event-stream')) {
      await fulfillJson(route, { items: history, hasMore: false, taskTerminal: false, nextCursor: 'cursor-history-progress' })
      return
    }
    streamAfter.push(url.searchParams.get('after'))
    streamCalls += 1
    if (streamCalls === 1) {
      await fulfillError(route, 410, 'cursor_expired', 'Cursor 已过期')
      return
    }
    if (streamCalls === 2) {
      await fulfillSse(route, [
        ...history,
        taskEventItem('replayed-progress', 'history-domain-progress', 'AGENT_RUN_EVENT_RECORDED', { eventKind: 'PROGRESS', safeText: '不应重复', progressPercent: 60 }, 'cursor-replay'),
        taskEventItem('live-progress', 'live-domain-progress', 'WORKER_TASK_PROGRESS_ACCEPTED', { operation: 'PROGRESS', attempt: 2, safeSummary: '正在收口验收项', progressPercent: 80, credential: 'private' }, 'cursor-live-progress'),
        taskEventItem('live-recovery', 'live-domain-recovery', 'TASK_EXECUTION_RECOVERY_STARTED', { attempt: 2, expiredPhase: 'RUN', fencingToken: 'private' }, 'cursor-live-recovery'),
      ])
      return
    }
    await fulfillSse(route, [])
  })

  await page.emulateMedia({ reducedMotion: 'reduce' })
  await page.goto(`/work?team=${ids.team}&project=${ids.project}&task=${ids.task}&workItem=${ids.workItem}`)
  const timeline = page.getByRole('dialog').locator('.timeline-card')

  await expect(timeline.getByText('80%', { exact: true }).first()).toBeVisible()
  await expect(timeline.getByText('正在收口验收项').first()).toBeVisible()
  await expect(timeline.getByText('执行正在恢复')).toBeVisible()
  await expect(timeline.getByText('Agent 正在验证')).toHaveCount(1)
  await expect(timeline.getByText('不应重复')).toHaveCount(0)
  await expect(timeline.getByText(/private/)).toHaveCount(0)
  expect(streamAfter.slice(0, 2)).toEqual(['cursor-history-progress', null])
  await expect.poll(() => streamAfter.length).toBeGreaterThanOrEqual(3)
  expect(streamAfter[2]).toBe('cursor-live-recovery')
  expect(await timeline.locator('.progress-fill').evaluate(element => getComputedStyle(element).transitionDuration)).toBe('0s')
})

test('Task Timeline closes its live stream after authoritative terminal convergence', async ({ page }) => {
  let terminalEventSent = false
  let streamCalls = 0
  await page.route(new RegExp(`/tasks/${ids.task}$`), async route => {
    const selected = task(ids.task, ids.workItem, '完成 Agent Task 列表与委托入口', terminalEventSent ? 'COMPLETED' : 'WAITING', terminalEventSent ? 'COMPLETED' : 'WAITING', terminalEventSent ? null : 'CAPACITY', 2)
    await fulfillJson(route, taskDetails(selected))
  })
  await page.route(new RegExp(`/tasks/${ids.task}/events(?:\\?.*)?$`), async route => {
    if (!route.request().headers().accept?.includes('text/event-stream')) {
      await fulfillJson(route, { items: [], hasMore: false, taskTerminal: false, nextCursor: null })
      return
    }
    streamCalls += 1
    terminalEventSent = true
    await fulfillSse(route, [
      taskEventItem('live-complete', 'live-domain-complete', 'WORKER_TASK_COMPLETE_ACCEPTED', { operation: 'COMPLETE', attempt: 2, safeSummary: '验收完成' }, 'cursor-live-complete'),
    ])
  })

  await page.goto(`/work?team=${ids.team}&project=${ids.project}&task=${ids.task}&workItem=${ids.workItem}`)
  const dialog = page.getByRole('dialog')
  await expect(dialog.getByText('执行已完成')).toBeVisible()
  await expect(dialog.getByText('COMPLETED', { exact: true }).first()).toBeVisible()
  // 有限 SSE 结束到权威投影收敛之间可以短暂重连；终态可见后调用数必须保持稳定。
  const callsAtTerminalConvergence = streamCalls
  expect(callsAtTerminalConvergence).toBeGreaterThanOrEqual(1)
  await page.waitForTimeout(1_500)
  expect(streamCalls).toBe(callsAtTerminalConvergence)
})

test('Task Cancel keeps server facts stable while pending and restores confirmation focus', async ({ page }) => {
  let release!: () => void
  const gate = new Promise<void>(resolve => { release = resolve })
  let commandRequests = 0
  await page.route(/\/tasks\/[^/]+\/attempts\/[^/]+\/cancel$/, async route => {
    commandRequests += 1
    await gate
    await route.fallback()
  })
  await page.goto(`/work?team=${ids.team}&project=${ids.project}&task=${ids.task}&workItem=${ids.workItem}`)
  const taskDialog = page.getByRole('dialog', { name: /完成 Agent Task 列表与委托入口 Task 详情/ })
  const cancel = taskDialog.getByRole('button', { name: '取消当前 Task' })

  await cancel.click()
  let confirm = page.getByRole('dialog', { name: '取消当前 Task' })
  await confirm.getByLabel('关闭 Task 控制确认').click()
  await expect(cancel).toBeFocused()

  await cancel.click()
  confirm = page.getByRole('dialog', { name: '取消当前 Task' })
  await confirm.getByRole('textbox').fill('团队决定停止本次执行')
  await confirm.getByRole('button', { name: '确认取消' }).click()
  await expect.poll(() => commandRequests).toBe(1)
  await expect(confirm.getByRole('button', { name: '确认取消' })).toBeDisabled()
  await expect(taskDialog.getByText('WAITING', { exact: true }).first()).toBeVisible()

  release()
  await expect(confirm).toBeHidden()
  await expect(taskDialog.getByText('CANCELLED', { exact: true }).first()).toBeVisible()
  await expect(taskDialog.getByRole('button', { name: '取消当前 Task' })).toHaveCount(0)
})

test('Task command conflict refreshes a terminal race and removes stale controls', async ({ page }) => {
  const serverTask = task(ids.task, ids.workItem, '完成 Agent Task 列表与委托入口', 'WAITING', 'WAITING', 'CAPACITY', 2)
  let raced = false
  await page.route(/\/tasks\//, async route => {
    const request = route.request()
    const path = new URL(request.url()).pathname
    if (request.method() === 'POST' && path.endsWith('/cancel')) {
      raced = true
      serverTask.status = 'CANCELLED'
      serverTask.currentExecutionStatus = 'CANCELLED'
      serverTask.currentWaitingReason = null
      serverTask.executionVersion = 1
      return fulfillError(route, 409, 'optimistic_lock_conflict', 'Task execution version changed', 1)
    }
    if (raced && request.method() === 'GET' && path.endsWith(`/tasks/${ids.task}`)) {
      return fulfillJson(route, taskDetails(serverTask))
    }
    if (raced && request.method() === 'GET' && path.endsWith(`/tasks/${ids.task}/attempts`)) {
      return fulfillJson(route, [taskExecution(serverTask), historicalTaskExecution(serverTask)])
    }
    if (raced && request.method() === 'GET' && path.includes(`/tasks/${ids.task}/attempts/`) && path.endsWith('/runtime-facts')) {
      return fulfillJson(route, taskRuntimeFacts(serverTask, serverTask.currentExecutionId))
    }
    await route.fallback()
  })
  await page.goto(`/work?team=${ids.team}&project=${ids.project}&task=${ids.task}&workItem=${ids.workItem}`)
  const taskDialog = page.getByRole('dialog', { name: /完成 Agent Task 列表与委托入口 Task 详情/ })

  await taskDialog.getByRole('button', { name: '取消当前 Task' }).click()
  const confirm = page.getByRole('dialog', { name: '取消当前 Task' })
  await confirm.getByRole('textbox').fill('已由其他成员处理')
  await confirm.getByRole('button', { name: '确认取消' }).click()

  await expect(taskDialog.getByText('执行事实已变化')).toBeVisible()
  await expect(taskDialog.getByText(/服务端当前版本为 v1/)).toBeVisible()
  await expect(taskDialog.getByText('CANCELLED', { exact: true }).first()).toBeVisible()
  await expect(taskDialog.getByRole('button', { name: '取消当前 Task' })).toHaveCount(0)
})

test('Task Retry preserves the failed attempt and selects the server-created successor', async ({ page }) => {
  const serverTask = task(ids.task, ids.workItem, '完成 Agent Task 列表与委托入口', 'FAILED', 'FAILED', null, 2)
  serverTask.executionVersion = 4
  await page.route(/\/tasks\//, async route => {
    const request = route.request()
    const path = new URL(request.url()).pathname
    if (request.method() === 'GET' && path.endsWith(`/tasks/${ids.task}`)) {
      return fulfillJson(route, taskDetails(serverTask))
    }
    if (request.method() === 'GET' && path.endsWith(`/tasks/${ids.task}/attempts`)) {
      return fulfillJson(route, serverTask.currentAttempt > 1
        ? [taskExecution(serverTask), historicalTaskExecution(serverTask)]
        : [taskExecution(serverTask)])
    }
    if (request.method() === 'GET' && path.endsWith(`/tasks/${ids.task}/coding`)) {
      return fulfillJson(route, { taskId: serverTask.id, currentAttempt: codingAttempt(serverTask, serverTask.currentExecutionId) })
    }
    if (request.method() === 'GET' && path.endsWith(`/tasks/${ids.task}/coding-attempts`)) {
      return fulfillJson(route, serverTask.currentAttempt > 1
        ? [codingAttempt(serverTask, serverTask.currentExecutionId), codingAttempt(serverTask, serverTask.previousExecutionId)]
        : [codingAttempt(serverTask, serverTask.currentExecutionId)])
    }
    const selectedCoding = path.match(new RegExp(`/tasks/${ids.task}/attempts/([^/]+)/coding$`))
    if (request.method() === 'GET' && selectedCoding) {
      return fulfillJson(route, codingAttempt(serverTask, selectedCoding[1]!))
    }
    if (request.method() === 'GET' && path.includes(`/tasks/${ids.task}/attempts/`) && path.endsWith('/runtime-facts')) {
      const executionId = path.split('/attempts/')[1]!.split('/')[0]!
      return fulfillJson(route, taskRuntimeFacts(serverTask, executionId))
    }
    if (request.method() === 'POST' && path.endsWith('/retry')) {
      expect(request.headers()['if-match']).toBe('"4"')
      expect(request.headers()['idempotency-key']).toBeTruthy()
      expect(request.postDataJSON()).toEqual({ agentConfigurationRevision: 4 })
      serverTask.previousExecutionId = serverTask.currentExecutionId
      serverTask.previousAttempt = serverTask.currentAttempt
      serverTask.currentExecutionId = crypto.randomUUID()
      serverTask.currentAttempt += 1
      serverTask.currentExecutionStatus = 'READY'
      serverTask.status = 'ACTIVE'
      serverTask.executionVersion = 2
      serverTask.version += 1
      return fulfillReceipt(route, serverTask.version)
    }
    await route.fallback()
  })
  await page.goto(`/work?team=${ids.team}&project=${ids.project}&task=${ids.task}&workItem=${ids.workItem}`)
  const taskDialog = page.getByRole('dialog', { name: /完成 Agent Task 列表与委托入口 Task 详情/ })

  await taskDialog.getByRole('button', { name: '重试当前 Task' }).click()
  const confirm = page.getByRole('dialog', { name: '创建新的执行 Attempt' })
  await expect(confirm.getByText(/留空会沿用父 attempt 固定配置/)).toBeVisible()
  await confirm.getByLabel(/切换 Configuration Revision/).fill('4')
  await confirm.getByRole('button', { name: '确认重试' }).click()

  await expect(confirm).toBeHidden()
  await expect(taskDialog.getByText('当前 Attempt 3')).toBeVisible()
  await expect(taskDialog.locator('.attempt-list button').filter({ hasText: 'Attempt 3' })).toHaveAttribute('aria-pressed', 'true')
  await expect(taskDialog.locator('.attempt-list button').filter({ hasText: 'Attempt 2' })).toContainText('FAILED')
})

test('Task controls fail closed for offline and read-only members', async ({ page, context }) => {
  await page.goto(`/work?team=${ids.team}&project=${ids.project}&task=${ids.task}&workItem=${ids.workItem}`)
  let taskDialog = page.getByRole('dialog', { name: /完成 Agent Task 列表与委托入口 Task 详情/ })
  await expect(taskDialog.getByRole('button', { name: '取消当前 Task' })).toBeVisible()

  await context.setOffline(true)
  await expect(page.getByText(/当前离线：已加载事实和草稿已保留/)).toBeVisible()
  await expect(taskDialog.getByText('当前离线，控制命令将在恢复网络后才可提交。')).toBeVisible()
  await expect(taskDialog.getByRole('button', { name: '取消当前 Task' })).toBeDisabled()
  await context.setOffline(false)

  await page.route(/\/work-items\/[^/]+\/responsibilities$/, async route => {
    await fulfillJson(route, [responsibility(crypto.randomUUID(), 'EXECUTOR', ids.secondPrincipal, 'USER', '林晨')])
  })
  await page.reload()
  taskDialog = page.getByRole('dialog', { name: /完成 Agent Task 列表与委托入口 Task 详情/ })
  await expect(taskDialog.getByText('当前成员没有这个 Task 的 Owner 或 Executor 控制责任。')).toBeVisible()
  await expect(taskDialog.getByRole('button', { name: '取消当前 Task' })).toHaveCount(0)
})

test('Responsibility and Timeline facts remain consistent after reload and Cursor replay', async ({ page }) => {
  const url = `/work?team=${ids.team}&project=${ids.project}&workItem=${ids.workItem}`
  await page.goto(url)
  let dialog = page.getByRole('dialog', { name: 'CRW-18 工作项详情' })

  await dialog.getByLabel('新 Owner').selectOption(ids.thirdPrincipal)
  await dialog.getByRole('button', { name: '替换', exact: true }).click()
  await dialog.getByRole('button', { name: '加载更早活动' }).click()
  await expect(dialog.getByLabel('工作项时间线').getByText('添加评论')).toBeVisible()

  await page.reload()
  dialog = page.getByRole('dialog', { name: 'CRW-18 工作项详情' })
  await expect(dialog.getByText('周宁').first()).toBeVisible()
  await expect(dialog.getByText('分配责任').first()).toBeVisible()
  await dialog.getByRole('button', { name: '加载更早活动' }).click()
  await expect(dialog.getByLabel('工作项时间线').getByText('添加评论')).toHaveCount(1)
})

test('WorkItem detail refreshes after an optimistic version conflict', async ({ page }) => {
  await page.goto(`/work?team=${ids.team}&project=${ids.project}&workItem=${ids.workItem}`)
  const dialog = page.getByRole('dialog', { name: 'CRW-18 工作项详情' })

  await dialog.getByLabel('目标状态').selectOption('BLOCKED')
  await dialog.getByRole('button', { name: '提交流转' }).click()

  await expect(dialog.getByText('检测到并发更新')).toBeVisible()
  await expect(dialog.getByText(/服务端当前版本为 v2/)).toBeVisible()
  await expect(dialog.getByText('v2', { exact: true })).toBeVisible()
})

test('Review Workbench binds Context, Diff, Test and Acceptance before Reviewer execution and human Gate', async ({ page }) => {
  await page.route(/\/work-items\/[^/]+\/responsibilities$/, route => fulfillJson(route, gateEligibleResponsibilities()))
  await page.goto(`/work?team=${ids.team}&project=${ids.project}&workItem=${ids.workItem}&task=${ids.task}&review=${ids.reviewRequest}`)
  const taskDialog = page.getByRole('dialog', { name: /完成 Agent Task 列表与委托入口 Task 详情/ })
  const workbench = taskDialog.getByTestId('review-workbench')

  await expect(workbench.getByRole('heading', { name: 'Review Workbench' })).toBeVisible()
  await expect(workbench.getByRole('heading', { name: 'ContextPackage 摘要' })).toBeVisible()
  await expect(workbench.getByText('SELF_REVIEW · Advisory only')).toBeVisible()
  await expect(workbench.getByText('ADVISORY', { exact: true })).toBeVisible()
  await expect(workbench.getByText('207', { exact: true })).toBeVisible()
  await expect(workbench.getByText('关键测试通过', { exact: true })).toBeVisible()
  await expect(workbench.getByText('项目可编译', { exact: true })).toBeVisible()
  await expect(workbench).not.toContainText(/private|credential|typedArgv|browser-must-hide/)

  await workbench.locator('.finding-locations button').click()
  const explorer = taskDialog.getByTestId('coding-diff-explorer')
  await expect(explorer.getByText(/Review Finding 定位：src\/Main.java · L2–3/)).toBeVisible()
  await expect(explorer.getByRole('button', { name: /Main.java/ })).toHaveAttribute('aria-pressed', 'true')

  await workbench.getByRole('button', { name: '运行 Reviewer' }).click()
  await expect(workbench.getByText('COMPLETED', { exact: true }).first()).toBeVisible()
  await workbench.getByRole('button', { name: '提交成员结论' }).click()
  const gate = page.getByRole('dialog', { name: '提交成员 Review 结论' })
  await expect(gate).toBeVisible()
  const gateAxe = await new AxeBuilder({ page })
    .include('.gate-dialog')
    .withTags(['wcag2a', 'wcag2aa', 'wcag21aa', 'wcag22aa'])
    .analyze()
  expect(gateAxe.violations, formatAxeViolations(gateAxe.violations)).toEqual([])

  await gate.getByRole('button', { name: '关闭 Gate Decision' }).focus()
  await page.keyboard.press('Shift+Tab')
  await expect(gate.getByRole('button', { name: '确认提交' })).toBeFocused()
  await page.keyboard.press('Escape')
  await expect(gate).toBeHidden()

  await workbench.getByRole('button', { name: '提交成员结论' }).click()
  await gate.getByLabel('结论').selectOption('CHANGES_REQUESTED')
  await gate.getByLabel('理由').fill('补齐空值分支测试后重新交付。')
  await gate.getByRole('button', { name: '确认提交' }).click()
  await expect(workbench.getByText('CHANGES_REQUESTED', { exact: true }).first()).toBeVisible()
  await expect(workbench.getByText('Round 1', { exact: false }).last()).toBeVisible()
  await expect(workbench.getByText('补齐空值分支测试后重新交付。')).toBeVisible()
})

test('Review Workbench keeps DIFF_CHANGED history read-only and explains missing human eligibility', async ({ page }) => {
  await page.route(/\/work-items\/[^/]+\/responsibilities$/, route => fulfillJson(route, [
    responsibility('00000000-0000-0000-0000-000000000901', 'OWNER', ids.principal, 'USER', '张凯旋'),
    responsibility('00000000-0000-0000-0000-000000000903', 'REVIEWER', ids.specialistAgent, 'SPECIALIST_AGENT', 'Architecture Reviewer'),
  ]))
  await page.goto(`/work?team=${ids.team}&project=${ids.project}&task=${ids.task}&review=${ids.reviewRequest}`)
  const workbench = page.getByTestId('review-workbench')

  await expect(workbench.getByText('当前成员不持有可用于 Gate 的 Active USER Reviewer 责任')).toBeVisible()
  await workbench.getByRole('button', { name: /Review r1/ }).click()
  await expect(workbench.getByText('旧 Review 已失效')).toBeVisible()
  await expect(workbench.getByText(/DIFF_CHANGED/)).toBeVisible()
  await expect(workbench.getByRole('button', { name: /运行 Reviewer|恢复 Reviewer|提交成员结论/ })).toHaveCount(0)
})

test('Review Gate fails closed when the server rejects Reviewer eligibility', async ({ page }) => {
  await page.route(/\/work-items\/[^/]+\/responsibilities$/, route => fulfillJson(route, gateEligibleResponsibilities()))
  await page.route(new RegExp(`/reviews/${ids.reviewRequest}$`), async route => {
    const detail = reviewDetails({ status: 'COMPLETED', version: 4 })
    await route.fulfill({ status: 200, contentType: 'application/json', headers: { ETag: '"4"' }, body: JSON.stringify(detail) })
  })
  await page.route(new RegExp(`/reviews/${ids.reviewRequest}/decisions$`), route => (
    fulfillError(route, 403, 'reviewer_not_eligible', 'Reviewer eligibility changed')
  ))
  await page.goto(`/work?team=${ids.team}&project=${ids.project}&task=${ids.task}&review=${ids.reviewRequest}`)
  const workbench = page.getByTestId('review-workbench')

  await workbench.getByRole('button', { name: '提交成员结论' }).click()
  const gate = page.getByRole('dialog', { name: '提交成员 Review 结论' })
  await gate.getByLabel('结论').selectOption('APPROVED')
  await gate.getByLabel('理由').fill('证据完整，可以进入交付 Gate。')
  await gate.getByRole('button', { name: '确认提交' }).click()

  await expect(page).toHaveURL(/\/access-denied/)
  await expect(page.getByRole('heading', { name: '需要额外的团队权限' })).toBeVisible()
})

test('M5 GitHub Delivery confirms an exact ActionBundle and reconciles partial delivery', async ({ page }) => {
  await page.route(/\/work-items\/[^/]+\/responsibilities$/, route => fulfillJson(route, gateEligibleResponsibilities()))
  await page.route(new RegExp(`/reviews/${ids.reviewRequest}$`), async route => {
    const detail = reviewDetails({
      status: 'COMPLETED', version: 4, reviewerRelationship: 'INDEPENDENT',
      decisions: [reviewDecision('APPROVED', '证据完整且验收通过。', 1)],
    })
    await route.fulfill({ status: 200, contentType: 'application/json', headers: { ETag: '"4"' }, body: JSON.stringify(detail) })
  })
  await page.goto(`/work?team=${ids.team}&project=${ids.project}&workItem=${ids.workItem}&task=${ids.task}&review=${ids.reviewRequest}`)
  const workbench = page.getByTestId('action-delivery-workbench')

  await expect(workbench.getByLabel('GitHub Connection')).toHaveValue(ids.githubConnection)
  await workbench.getByRole('button', { name: 'Remote Preflight' }).click()
  await expect(workbench.getByText(/权限 111111/)).toBeVisible()
  await workbench.getByRole('button', { name: '生成 ActionBundle' }).click()

  await expect(workbench.getByText('HIGH_RISK_WRITE')).toBeVisible()
  await expect(workbench.getByText('refs/heads/crewscope/tasks/crw-18/attempt-2', { exact: true })).toBeVisible()
  const confirmationOpener = workbench.getByRole('button', { name: '审查并确认' })
  await confirmationOpener.click()
  let confirm = page.getByRole('dialog', { name: '确认执行 GitHub 写操作' })
  await expect(confirm.getByRole('button', { name: '关闭确认对话框' })).toBeFocused()
  await page.keyboard.press('Shift+Tab')
  await expect(confirm.getByRole('button', { name: '取消' })).toBeFocused()
  await page.keyboard.press('Escape')
  await expect(confirm).toBeHidden()
  await expect(confirmationOpener).toBeFocused()

  await confirmationOpener.click()
  confirm = page.getByRole('dialog', { name: '确认执行 GitHub 写操作' })
  await expect(confirm.getByText('a'.repeat(64))).toBeVisible()
  await confirm.getByRole('checkbox').check()
  await confirm.getByRole('button', { name: '精确确认' }).click()

  const stages = workbench.locator('.action-stage')
  await expect(stages.nth(0)).toContainText('SUCCEEDED')
  await expect(stages.nth(1)).toContainText('FAILED')
  await workbench.getByRole('button', { name: '刷新结果' }).click()
  await expect(stages.nth(1)).toContainText('OPEN')
  await expect(stages.nth(1)).toContainText('WEBHOOK')
  await expect(stages.nth(0)).toContainText('SUCCEEDED')
})

test('M5 GitHub Delivery visual baseline', async ({ page }, testInfo) => {
  await page.route(/\/work-items\/[^/]+\/responsibilities$/, route => fulfillJson(route, gateEligibleResponsibilities()))
  await page.route(new RegExp(`/reviews/${ids.reviewRequest}$`), async route => {
    const detail = reviewDetails({
      status: 'COMPLETED', version: 4, reviewerRelationship: 'INDEPENDENT',
      decisions: [reviewDecision('APPROVED', '证据完整且验收通过。', 1)],
    })
    await route.fulfill({ status: 200, contentType: 'application/json', headers: { ETag: '"4"' }, body: JSON.stringify(detail) })
  })
  await page.goto(`/work?team=${ids.team}&project=${ids.project}&workItem=${ids.workItem}&task=${ids.task}&review=${ids.reviewRequest}`)
  const workbench = page.getByTestId('action-delivery-workbench')
  await workbench.getByRole('button', { name: 'Remote Preflight' }).click()
  await workbench.getByRole('button', { name: '生成 ActionBundle' }).click()
  await expect(workbench.getByText('HIGH_RISK_WRITE')).toBeVisible()
  // Remove the fixed drawer clip so the baseline captures the complete responsive delivery graph.
  const viewport = page.viewportSize()!
  await page.setViewportSize({ width: viewport.width, height: 2600 })
  await page.locator('.task-detail-header, .task-detail-footer, .skip-link').evaluateAll(elements => elements.forEach(element => element.remove()))
  await page.addStyleTag({ content: `
    .task-detail-backdrop { position: absolute !important; min-height: 5000px !important; }
    .task-detail-drawer { position: absolute !important; inset: 0 0 auto auto !important; display: block !important; }
    .task-detail-content { overflow: visible !important; }
    .task-detail-header, .task-detail-footer, .skip-link { display: none !important; }
  ` })
  await workbench.scrollIntoViewIfNeeded()
  await expect(workbench).toHaveScreenshot(`action-delivery-workbench-${testInfo.project.name}.png`)
})

test('M5 Review Workbench visual baseline', async ({ page }, testInfo) => {
  await page.route(/\/work-items\/[^/]+\/responsibilities$/, route => fulfillJson(route, gateEligibleResponsibilities()))
  await page.goto(`/work?team=${ids.team}&project=${ids.project}&workItem=${ids.workItem}&task=${ids.task}&review=${ids.reviewRequest}`)
  const workbench = page.getByTestId('review-workbench')
  await expect(workbench.getByText('SELF_REVIEW · Advisory only')).toBeVisible()
  if (testInfo.project.name === 'narrow-chromium') {
    // Remove the drawer viewport clip so the baseline captures the entire responsive Workbench,
    // including its heading, revision selector and Gate section.
    await page.addStyleTag({ content: `
      .task-detail-backdrop { position: absolute !important; min-height: 5000px !important; }
      .task-detail-drawer { position: absolute !important; inset: 0 0 auto auto !important; display: block !important; }
      .task-detail-content { overflow: visible !important; }
      .task-detail-header, .task-detail-footer { display: none !important; }
    ` })
  }
  await workbench.scrollIntoViewIfNeeded()
  await expect(workbench).toHaveScreenshot(`review-workbench-${testInfo.project.name}.png`)
})

test('AppShell visual baseline', async ({ page }, testInfo) => {
  await page.goto(`/conversation?focus=CRW-18&team=${ids.team}&project=${ids.project}&conversation=${ids.conversation}`)
  await expect(page.getByRole('heading', { name: '规划 GitHub Provider 接入', exact: true }).first()).toBeVisible()
  await expect(page).toHaveScreenshot(`conversation-${testInfo.project.name}.png`, { fullPage: true })

  await page.goto(`/today?team=${ids.team}&project=${ids.project}`)
  await expect(page.getByText('先确认范围，再推进今天的团队工作。')).toBeVisible()
  await expect(page).toHaveScreenshot(`control-${testInfo.project.name}.png`, { fullPage: true })
})

test('M1 Work visual baseline', async ({ page }, testInfo) => {
  await page.goto(`/work?team=${ids.team}&project=${ids.project}&view=list&status=all&type=all&priority=all`)
  await expect(page.getByLabel('工作项列表')).toBeVisible()
  await expect(page).toHaveScreenshot(`work-list-${testInfo.project.name}.png`, { fullPage: true })

  await page.getByRole('button', { name: '看板视图' }).click()
  await expect(page.getByLabel('工作项看板')).toBeVisible()
  await expect(page).toHaveScreenshot(`work-board-${testInfo.project.name}.png`, { fullPage: true })

  await page.getByLabel('进行中').getByRole('button', { name: /打开 CRW-18/ }).click()
  await expect(page.getByRole('dialog', { name: 'CRW-18 工作项详情' }).getByText('Architecture Reviewer')).toBeVisible()
  await expect(page).toHaveScreenshot(`work-detail-${testInfo.project.name}.png`, { fullPage: true })

  await page.goto(`/work?team=${ids.team}&project=${ids.project}&workItem=${ids.workItem}&task=${ids.task}`)
  const taskDialog = page.getByRole('dialog', { name: /完成 Agent Task 列表与委托入口 Task 详情/ })
  await expect(taskDialog.getByText('Revision 2', { exact: true }).first()).toBeVisible()
  await expect(taskDialog.getByTestId('execution-studio').getByText('Workspace 正在恢复')).toBeVisible()
  await taskDialog.locator('.task-detail-content').evaluate(element => { element.scrollTop = 0 })
  await expect(page).toHaveScreenshot(`task-detail-${testInfo.project.name}.png`)
  await taskDialog.getByTestId('coding-progress-control').scrollIntoViewIfNeeded()
  await expect(page).toHaveScreenshot(`coding-progress-${testInfo.project.name}.png`)
})

test('M4 Repository and Execution Studio visual baseline', async ({ page }, testInfo) => {
  await page.goto(`/settings/repositories?team=${ids.team}&project=${ids.project}`)
  await expect(page.getByText('crewscope-java', { exact: true }).first()).toBeVisible()
  await expect(page).toHaveScreenshot(`repository-settings-${testInfo.project.name}.png`, { fullPage: true })

  await page.goto(`/work?team=${ids.team}&project=${ids.project}&workItem=${ids.workItem}&task=${ids.task}`)
  const studio = page.getByTestId('execution-studio')
  await expect(studio.getByRole('heading', { name: 'Execution Studio' })).toBeVisible()
  await studio.scrollIntoViewIfNeeded()
  await expect(page).toHaveScreenshot(`execution-studio-${testInfo.project.name}.png`)
})

test('M5 Agent creation and configuration preserve server-owned boundaries', async ({ page }) => {
  await page.goto(`/settings/agents?team=${ids.team}`)
  await page.getByRole('button', { name: '创建个人 Agent' }).first().click()
  const createDialog = page.getByRole('dialog', { name: '创建执行 Agent' })
  await expect(createDialog).toBeVisible()
  await createDialog.getByText('Coding Agent', { exact: true }).click()
  await createDialog.getByPlaceholder('例如：我的 Java Coding Agent').fill('我的 Java Coding Agent')
  await createDialog.getByRole('button', { name: '创建 Agent', exact: true }).click()

  await expect(page).toHaveURL(new RegExp(`agent=${ids.agentCreated}`))
  const configuration = page.locator('.agent-configuration')
  await expect(configuration.getByRole('heading', { name: '我的 Java Coding Agent' })).toBeVisible()
  const personalBinding = configuration.locator('.binding-editor').filter({ hasText: 'PERSONAL' })
  await personalBinding.getByLabel('主模型').selectOption({ index: 1 })
  await personalBinding.getByLabel('Fallback').selectOption({ index: 1 })
  await configuration.getByLabel(/补充指令/).fill('遵循团队代码规范并保留验证证据。')
  await configuration.getByText('coding-baseline', { exact: true }).click()
  await configuration.getByRole('button', { name: '保存并预检' }).click()

  await expect(page).toHaveURL(new RegExp('configurationRevision=1'))
  await expect(page.getByText('Revision 1', { exact: true }).first()).toBeVisible()
  await expect(page.locator('body')).not.toContainText('sk-private')
  await expect(page.locator('html')).toHaveJSProperty('scrollWidth', await page.locator('html').evaluate(element => element.clientWidth))
})

test('M5 Agent Center visual baseline', async ({ page }, testInfo) => {
  await page.goto(`/settings/agents?team=${ids.team}`)
  await expect(page.getByRole('heading', { name: '我的 Specialist' })).toBeVisible()
  await expect(page.locator(`.agent-card[href*="agent=${ids.agentCoding}"]`)).toContainText('deepseek-v4-flash')
  await expect(page).toHaveScreenshot(`agent-settings-${testInfo.project.name}.png`, { fullPage: true })

  await page.getByRole('button', { name: '创建个人 Agent' }).first().click()
  await expect(page.getByRole('dialog', { name: '创建执行 Agent' })).toBeVisible()
  await expect(page).toHaveScreenshot(`agent-create-${testInfo.project.name}.png`, { fullPage: true })
  await page.getByRole('button', { name: '关闭创建 Agent' }).click()

  await page.locator(`.agent-card[href*="agent=${ids.agentCoding}"]`).click()
  await expect(page.locator('.agent-configuration').getByRole('heading', { name: 'CrewScope Coding Agent' })).toBeVisible()
  await expect(page).toHaveScreenshot(`agent-configuration-${testInfo.project.name}.png`, { fullPage: true })
})

test('M1 through M5 primary pages meet automated WCAG 2.2 AA checks', async ({ page }) => {
  // This gate intentionally visits every primary page and several dialogs in one browser context.
  test.setTimeout(60_000)
  const routes = [
    { path: `/conversation?team=${ids.team}&project=${ids.project}&conversation=${ids.conversation}`, ready: () => page.getByRole('heading', { name: '规划 GitHub Provider 接入', exact: true }).first() },
    { path: `/today?team=${ids.team}&project=${ids.project}`, ready: () => page.getByText('先确认范围，再推进今天的团队工作。') },
    { path: `/work?team=${ids.team}&project=${ids.project}`, ready: () => page.getByLabel('工作项列表') },
    { path: `/team/members?team=${ids.team}&project=${ids.project}`, ready: () => page.getByRole('table', { name: '团队成员列表' }) },
    { path: `/work?team=${ids.team}&project=${ids.project}&workItem=${ids.workItem}`, ready: () => page.getByRole('dialog', { name: 'CRW-18 工作项详情' }) },
    { path: `/conversation?team=${ids.team}&project=${ids.project}&conversation=${ids.conversation}`, ready: () => page.getByTestId('conversation-task-cards') },
    { path: `/work?team=${ids.team}&project=${ids.project}&task=${ids.task}`, ready: () => page.getByRole('region', { name: 'Agent Tasks' }) },
    { path: `/work?team=${ids.team}&project=${ids.project}&workItem=${ids.workItem}&task=${ids.task}`, ready: () => page.getByRole('dialog', { name: /Task 详情/ }) },
    { path: `/settings/repositories?team=${ids.team}&project=${ids.project}`, ready: () => page.getByRole('heading', { name: 'CrewScope 仓库设置' }) },
    { path: `/settings/agents?team=${ids.team}&agent=${ids.agentCoding}`, ready: () => page.getByRole('heading', { name: '我的 Specialist' }) },
    { path: `/settings/models?team=${ids.team}&provider=deepseek&ownerType=TEAM&connection=${ids.teamModelConnection}`, ready: () => page.getByRole('heading', { name: '模型连接详情' }) },
  ]

  for (const route of routes) {
    await page.goto(route.path)
    await expect(route.ready()).toBeVisible()
    const result = await new AxeBuilder({ page })
      .withTags(['wcag2a', 'wcag2aa', 'wcag21aa', 'wcag22aa'])
      .analyze()
    expect(result.violations, `${route.path}\n${formatAxeViolations(result.violations)}`).toEqual([])
  }

  await page.goto(`/settings/agents?team=${ids.team}`)
  await page.getByRole('button', { name: '创建个人 Agent' }).first().click()
  const createResult = await new AxeBuilder({ page })
    .include('[role="dialog"][aria-labelledby="agent-create-title"]')
    .withTags(['wcag2a', 'wcag2aa', 'wcag21aa', 'wcag22aa'])
    .analyze()
  expect(createResult.violations, formatAxeViolations(createResult.violations)).toEqual([])

  await page.goto(`/settings/models?team=${ids.team}`)
  await page.getByRole('button', { name: '创建连接' }).first().click()
  const credentialResult = await new AxeBuilder({ page })
    .include('[role="dialog"][aria-labelledby="credential-create-title"]')
    .withTags(['wcag2a', 'wcag2aa', 'wcag21aa', 'wcag22aa'])
    .analyze()
  expect(credentialResult.violations, formatAxeViolations(credentialResult.violations)).toEqual([])
})

function team(id: string, name: string, workspaceId: string) {
  return { id, organizationId: ids.organization, name, status: 'ACTIVE', initializationStatus: 'READY', ownerMemberId: ids.member, defaultWorkspaceId: workspaceId, version: 0 }
}

function project(id: string, teamId: string, workspaceId: string, key: string, name: string) {
  return { id, organizationId: ids.organization, teamId, workspaceId, key, name, status: 'ACTIVE', version: 0, createdAt: '2026-08-08T01:00:00Z', createdByPrincipalId: ids.principal, updatedAt: '2026-08-08T02:00:00Z', updatedByPrincipalId: ids.principal }
}

function githubConnection() {
  return {
    id: ids.githubConnection, ownerType: 'TEAM', teamId: ids.team,
    authenticationType: 'APP_INSTALLATION', executionIdentity: 'TEAM', externalAccountLogin: 'crewscope-labs',
    status: 'ACTIVE', version: 3, repositoryAllowlist: ['github:repository:101'],
    credentialStatus: 'ACTIVE', expiresAt: null, verifiedAt: '2026-08-08T03:00:00Z',
    createdAt: '2026-08-07T01:00:00Z', updatedAt: '2026-08-08T03:00:00Z',
  }
}

function githubProviderBinding() {
  return {
    id: ids.githubBinding, teamId: ids.team, workspaceId: ids.workspace,
    connectionId: ids.githubConnection, connectionVersion: 3, executionIdentity: 'TEAM',
    repositoryAllowlist: ['github:repository:101'], status: 'ACTIVE', defaultUsage: true, version: 1,
  }
}

function githubRepository() {
  return {
    externalRepositoryId: '101', fullName: 'crewscope/crewscope-java', defaultBranch: 'main',
    visibility: 'PRIVATE', discoveredAt: '2026-08-08T03:00:00Z', cacheExpiresAt: '2026-08-08T04:15:00Z',
  }
}

function githubHealth() {
  return {
    authorizationStatus: 'HEALTHY', connectionUsable: true, grantUsable: true,
    credentialUsable: true, profileCurrent: true, deliverableRepositoryCount: 1,
    webhookStatus: 'READY', rateLimit: {
      resource: 'core', limit: 5000, remaining: 4980,
      resetsAt: '2026-08-08T05:00:00Z', observedAt: '2026-08-08T04:00:00Z',
    },
  }
}

function githubActionBundle(title: string, body: string) {
  return {
    id: ids.actionBundle, version: 0, digest: 'a'.repeat(64), validity: 'CURRENT', staleReason: null,
    taskId: ids.task, taskExecutionId: ids.taskExecution, reviewDecisionId: ids.reviewDecision,
    repositoryBindingId: ids.repositoryBinding, repositoryKey: 'crewscope/crewscope-java',
    baselineCommit: 'b'.repeat(40), deliveryCommit: 'c'.repeat(40), confirmation: null as null | Record<string, unknown>,
    actions: [
      {
        id: ids.pushAction, sequence: 1, kind: 'PUSH_BRANCH', risk: 'HIGH_RISK_WRITE',
        digest: 'd'.repeat(64), validUntil: '2026-08-08T05:00:00Z', dependencyActionIds: [],
        parameters: {
          repositoryId: '101', branch: 'refs/heads/crewscope/tasks/crw-18/attempt-2',
          deliveryHead: 'c'.repeat(40), expectedRemoteHead: null, pullRequestHead: null,
          pullRequestBase: null, pullRequestHeadSha: null, title: null, body: null, draft: null,
        }, dispatch: null as null | ReturnType<typeof actionDispatch>,
        receipt: null as null | ReturnType<typeof actionReceipt>, externalResult: null as null | Record<string, unknown>,
      },
      {
        id: ids.pullRequestAction, sequence: 2, kind: 'CREATE_DRAFT_PR', risk: 'LOW_RISK_WRITE',
        digest: 'e'.repeat(64), validUntil: '2026-08-08T05:00:00Z', dependencyActionIds: [ids.pushAction],
        parameters: {
          repositoryId: '101', branch: null, deliveryHead: null, expectedRemoteHead: null,
          pullRequestHead: 'crewscope/tasks/crw-18/attempt-2', pullRequestBase: 'main',
          pullRequestHeadSha: 'c'.repeat(40), title, body, draft: true,
        }, dispatch: null as null | ReturnType<typeof actionDispatch>,
        receipt: null as null | ReturnType<typeof actionReceipt>, externalResult: null as null | Record<string, unknown>,
      },
    ],
  }
}

function actionDispatch(id: string, status: string, reconciliationAttempts: number) {
  return {
    id, version: 2 + reconciliationAttempts, status, claimAttempts: 1, reconciliationAttempts,
    nextAttemptAt: '2026-08-08T04:03:00Z', cancellationReason: null, compensationDisposition: 'NONE',
  }
}

function actionReceipt(result: string, externalObjectType: string | null, evidenceCode: string) {
  return {
    id: crypto.randomUUID(), result, source: 'WORKER', externalObjectType,
    externalIdentityHash: externalObjectType ? '2'.repeat(64) : null, targetVersion: null,
    evidenceCode, manualReason: null, receivedAt: '2026-08-08T04:01:00Z',
  }
}

function modelProvider() {
  return {
    key: 'deepseek', displayName: 'DeepSeek', availableRegions: ['cn', 'sg'], retentionMode: 'NONE',
    maximumRetentionSeconds: null, trainingUsagePolicy: 'DISABLED', status: 'ACTIVE', version: 2,
  }
}

function modelCatalog() {
  return [{
    id: '00000000-0000-0000-0000-000000002101', providerKey: 'deepseek', modelId: 'deepseek-v4-flash',
    catalogRevision: 4, modelRevision: 'DeepSeek-V4-Flash-0801', displayName: 'DeepSeek V4 Flash',
    contextWindowTokens: 128000, maximumOutputTokens: 120000, capabilities: ['TOOLS', 'STRUCTURED_OUTPUT'],
    availableRegions: ['cn', 'sg'], status: 'ACTIVE', version: 2,
    effectivePrice: {
      revision: 2, effectiveFrom: '2026-08-01T00:00:00Z', inputPerMillionTokens: '0.1',
      outputPerMillionTokens: '0.2', cachedInputPerMillionTokens: '0.02', currencyCode: 'USD',
    },
  }]
}

function modelConnection(id: string, ownerType: string, ownerId: string, healthStatus: string) {
  return {
    id, organizationId: ids.organization, providerKey: 'deepseek', ownerType, ownerId, region: 'cn',
    billingSubjectType: ownerType, billingSubjectId: ownerId, credentialVersion: 2, status: 'ACTIVE',
    healthStatus, healthFailureCode: (healthStatus === 'UNHEALTHY' ? 'AUTHENTICATION_FAILED' : null) as string | null,
    checkedAt: '2026-08-08T03:30:00Z', lastHealthyAt: (healthStatus === 'HEALTHY' ? '2026-08-08T03:30:00Z' : null) as string | null,
    consecutiveFailures: healthStatus === 'UNHEALTHY' ? 2 : 0, revocationReason: null as string | null,
    createdAt: '2026-08-07T01:00:00Z', updatedAt: '2026-08-08T03:30:00Z', version: 4,
  }
}

function agentDirectory() {
  return [
    agentProfile(ids.agentProfile, '张凯旋的 Personal Agent', 'USER', 'PERSONAL', 'personal-assistant', true, 'ACTIVE'),
    agentProfile(ids.agentCoding, 'CrewScope Coding Agent', 'USER', 'CODING', 'coding-specialist', false, 'ACTIVE', 3),
    agentProfile(ids.agentReviewer, 'Architecture Reviewer', 'USER', 'REVIEWER', 'reviewer-specialist', false, 'DISABLED', 2),
    agentProfile(ids.agentTeam, 'Team Delivery Agent', 'TEAM', 'ORCHESTRATOR', 'team-orchestrator', false, 'ARCHIVED'),
  ]
}

function agentProfile(id: string, displayName: string, ownershipType: string, runtimeRole: string, templateKey: string, defaultProfile: boolean, status: string, templateVersion = 1, currentRevision: number | null = 2) {
  return {
    id, principalId: crypto.randomUUID(), displayName, principalStatus: status,
    organizationId: ids.organization, teamId: ids.team, workspaceId: ids.workspace,
    ownershipType, ownerMemberId: ownershipType === 'USER' ? ids.member : null,
    runtimeRole, templateKey, templateVersion, defaultProfile, status,
    currentConfigurationRevision: currentRevision, currentConfigurationHash: currentRevision ? 'a'.repeat(64) : null,
    createdAt: '2026-08-08T01:00:00Z', updatedAt: '2026-08-08T04:00:00Z', version: 2,
  }
}

function agentConfiguration(profileId: string, revision = 2) {
  const profile = agentDirectory().find(agent => agent.id === profileId)
    ?? (profileId === ids.agentCreated
      ? agentProfile(ids.agentCreated, '我的 Java Coding Agent', 'USER', 'CODING', 'coding-specialist', false, 'ACTIVE', 3, revision)
      : undefined)
  const binding = (executionScope: string) => ({
    executionScope, kind: 'EXPLICIT',
    primary: { connectionId: crypto.randomUUID(), providerKey: 'deepseek', catalogEntryId: crypto.randomUUID(), modelId: 'deepseek-v4-flash', catalogRevision: 4 },
    fallback: { connectionId: crypto.randomUUID(), providerKey: 'deepseek', catalogEntryId: crypto.randomUUID(), modelId: 'deepseek-chat', catalogRevision: 3 },
  })
  return {
    revision, previousRevision: revision > 1 ? revision - 1 : null,
    templateKey: profile?.templateKey ?? 'coding-specialist', templateVersion: profile?.templateVersion ?? 1,
    templateContentHash: 'b'.repeat(64),
    personalBinding: profileId === ids.agentTeam ? null : binding('PERSONAL'),
    teamBinding: profileId === ids.agentProfile ? null : binding('TEAM'),
    supplementalInstructions: null, approvedSkillKeys: [], memoryPolicy: null, budgetPolicy: null,
    generateOptions: { temperature: null, topP: null, maximumOutputTokens: 120000, reasoningMode: 'DEFAULT', cacheEnabled: true, parallelToolCalls: true, seed: null, maximumAttempts: 2 },
    policyPackId: 'default', policyPackVersion: 1, configurationHash: 'c'.repeat(64), createdAt: '2026-08-08T04:00:00Z',
  }
}

function agentTemplates(ownershipType: 'USER' | 'TEAM') {
  const definition = (
    key: string,
    version: number,
    runtimeRole: string,
    allowedExecutionScopes: string[],
    approvedSkillKeys: string[] = [],
  ) => ({
    publisherType: 'ORGANIZATION', publisherId: ids.organization, key, version, runtimeRole,
    allowedOwnershipTypes: [ownershipType], allowedExecutionScopes,
    declaredCapabilities: runtimeRole === 'CODING' ? ['coding', 'repository'] : ['collaboration'],
    requiredModelCapabilities: ['TOOLS'], approvedSkillKeys,
    memberConfigurableSlots: ['MODEL_BINDING', 'SUPPLEMENTAL_INSTRUCTIONS', 'APPROVED_SKILLS', 'OUTPUT_PREFERENCE'],
    administratorConfigurableSlots: ['BUDGET'], contentHash: 'd'.repeat(64), status: 'ACTIVE', lifecycleVersion: 1,
  })
  if (ownershipType === 'TEAM') return [definition('team-orchestrator', 1, 'ORCHESTRATOR', ['TEAM'])]
  return [
    definition('coding-specialist', 3, 'CODING', ['PERSONAL', 'TEAM'], ['coding-baseline']),
    definition('reviewer-specialist', 2, 'REVIEWER', ['PERSONAL'], ['review-baseline']),
    definition('personal-assistant', 1, 'PERSONAL_ASSISTANT', ['PERSONAL', 'TEAM']),
  ]
}

function configurationHistory(profileId: string, currentRevision: number) {
  const current = agentConfiguration(profileId, currentRevision)
  const item = (revision: number) => ({
    revision, previousRevision: revision > 1 ? revision - 1 : null,
    templateKey: current.templateKey, templateVersion: current.templateVersion,
    templateContentHash: current.templateContentHash,
    personalBinding: current.personalBinding, teamBinding: current.teamBinding,
    configurationHash: revision === currentRevision ? current.configurationHash : 'e'.repeat(64),
    createdAt: revision === currentRevision ? '2026-08-08T04:00:00Z' : '2026-08-07T04:00:00Z',
    createdBy: ids.principal,
  })
  return Array.from({ length: currentRevision }, (_, index) => item(currentRevision - index))
}

function selectableAgentModels(executionScope: string) {
  const model = (modelId: string, suffix: string) => ({
    connectionId: `00000000-0000-0000-0000-0000000020${suffix}`,
    connectionOwnerType: executionScope === 'TEAM' ? 'TEAM' : 'USER',
    connectionOwnerId: executionScope === 'TEAM' ? ids.team : ids.principal,
    providerKey: 'deepseek', providerDisplayName: 'DeepSeek',
    catalogEntryId: `00000000-0000-0000-0000-0000000021${suffix}`,
    modelId, catalogRevision: 4, modelDisplayName: modelId, region: 'cn',
    contextWindowTokens: 128000, maximumOutputTokens: 120000, capabilities: ['TOOLS'],
    price: { inputPerMillionTokens: '0.1', outputPerMillionTokens: '0.2', cachedInputPerMillionTokens: '0.02', currencyCode: 'USD' },
  })
  return [model('deepseek-v4-flash', '01'), model('deepseek-chat', '02')]
}

function agentPreflight(profileId: string, executionScope: string, revision: number) {
  const primary = selectableAgentModels(executionScope)[0]!
  return {
    agentProfileId: profileId, agentProfileVersion: 2, configurationRevision: revision,
    configurationHash: 'c'.repeat(64), executionScope, bindingSource: executionScope === 'TEAM' ? 'TEAM_DEFAULT' : 'DIRECT',
    modelDefault: null,
    primary: {
      role: 'PRIMARY', providerKey: primary.providerKey, connectionId: primary.connectionId,
      connectionOwnerType: primary.connectionOwnerType, connectionOwnerId: primary.connectionOwnerId,
      region: primary.region, catalogEntryId: primary.catalogEntryId, modelId: primary.modelId,
      catalogRevision: primary.catalogRevision, modelRevision: 'deepseek-v4-flash-2026-08', priceRevision: 3,
      price: primary.price,
    },
    fallback: null, resolutionHash: 'f'.repeat(64),
  }
}

function workItem(id: string, key: string, title: string, type: string, status: string, priority: string) {
  return { id, organizationId: ids.organization, teamId: ids.team, workspaceId: ids.workspace, projectId: ids.project, key, type, title, description: `${title}的协作说明`, status, priority, labels: ['team-work'], dueAt: null, source: 'CREWSCOPE', sourceReference: null, version: 0, createdAt: '2026-08-08T01:00:00Z', createdByPrincipalId: ids.principal, updatedAt: '2026-08-08T02:00:00Z', updatedByPrincipalId: ids.principal }
}

function conversation(id: string, teamId: string, workspaceId: string, title: string, visibility: string, lastMessageSequence: number | null) {
  return { id, organizationId: ids.organization, teamId, workspaceId, ownerMemberId: ids.member, ownerPrincipalId: ids.principal, personalAgentPrincipalId: ids.personalAgent, title, visibility, status: 'ACTIVE', lastMessageSequence, version: 0, createdAt: '2026-08-08T01:00:00Z', updatedAt: '2026-08-08T03:00:00Z' }
}

function participant(id: string, conversationId: string, principalId: string, teamMemberId: string | null, role: string) {
  return { id, conversationId, principalId, teamMemberId, role, status: 'ACTIVE', joinedByPrincipalId: ids.principal, joinedAt: '2026-08-08T01:00:00Z', leftAt: null, version: 0 }
}

function conversationMessage(id: string, conversationId: string, sequence: number, type: string, authorPrincipalId: string | null, content: string, createdAt: string) {
  return { id, conversationId, sequence, type, participantId: type === 'SYSTEM_NOTICE' ? null : crypto.randomUUID(), authorPrincipalId, content, createdAt }
}

function taskIntent(status: 'READY' | 'CONFIRMED', version: number) {
  return {
    id: ids.taskIntent,
    conversationId: ids.conversation,
    proposedByPrincipalId: ids.personalAgent,
    schemaVersion: 1,
    proposalRevision: 1,
    status,
    version,
    proposal: {
      workProjectId: ids.project,
      objective: '完成 GitHub Provider 接入并验证团队协作流程',
      acceptanceCriteria: ['能够读取仓库元数据', '关键操作进入审计记录'],
      owner: { role: 'OWNER', principalId: ids.principal, principalType: 'HUMAN', teamMemberId: ids.member },
      executor: null,
      gateReviewer: null,
    },
    decision: null as null | { status: string; decidedByPrincipalId: string; decidedAt: string; reason: string | null },
    createdAt: '2026-08-08T04:00:00Z',
    updatedAt: '2026-08-08T04:00:00Z',
  }
}

function conversationWorkItemAssociation() {
  return {
    linkId: '00000000-0000-0000-0000-000000001401',
    origin: 'TASK_INTENT_CONFIRMATION',
    createdAt: '2026-08-08T04:10:00Z',
    conversation: { id: ids.conversation, title: '规划 GitHub Provider 接入', visibility: 'PRIVATE', status: 'ACTIVE' },
    workItem: { id: ids.workItem, projectId: ids.project, key: 'CRW-18', title: '共享范围与筛选状态', status: 'IN_PROGRESS' },
  }
}

function responsibility(id: string, role: string, actorPrincipalId: string, actorType: string, actorDisplayName: string, actorAgentProfileId: string | null = null) {
  return { id, workItemId: ids.workItem, role, actorPrincipalId, actorType, actorMemberId: actorType === 'USER' ? crypto.randomUUID() : null, actorDisplayName, actorAgentProfileId, status: 'ACTIVE', assignedByPrincipalId: ids.principal, assignedAt: '2026-08-08T03:20:00Z', acceptedAt: '2026-08-08T03:20:00Z', version: 0 }
}

function gateEligibleResponsibilities() {
  return [
    responsibility('00000000-0000-0000-0000-000000000901', 'OWNER', ids.principal, 'USER', '张凯旋'),
    responsibility('00000000-0000-0000-0000-000000000903', 'REVIEWER', ids.specialistAgent, 'SPECIALIST_AGENT', 'Architecture Reviewer'),
    responsibility('00000000-0000-0000-0000-000000000905', 'REVIEWER', ids.principal, 'USER', '张凯旋'),
  ]
}

function task(id: string, workItemId: string, objective: string, status: string, executionStatus: string, waitingReason: string | null, currentAttempt = 1) {
  return {
    id, workspaceId: ids.workspace, projectId: ids.project, workItemId, objective,
    acceptanceCriteria: ['通过自动化测试'], status, currentExecutionId: ids.taskExecution,
    currentAttempt, currentExecutionStatus: executionStatus, currentWaitingReason: waitingReason,
    previousExecutionId: ids.previousTaskExecution, previousAttempt: Math.max(1, currentAttempt - 1), executionVersion: 0,
    ownerPrincipalId: ids.principal, version: 0, createdAt: '2026-08-08T03:30:00Z', updatedAt: '2026-08-08T03:40:00Z',
  }
}

function taskAssociation(value: ReturnType<typeof task>, origin = 'WORK_ITEM_ROOT') {
  return {
    origin,
    associatedAt: value.createdAt,
    task: {
      ...value,
      href: `/work?team=${ids.team}&project=${value.projectId}&workItem=${value.workItemId}&task=${value.id}`,
    },
  }
}

function taskAssociations(value: ReturnType<typeof task>, conversationVisible = true) {
  return {
    task: { id: value.id, projectId: value.projectId, workItemId: value.workItemId, status: value.status, objective: value.objective, href: `/work?task=${value.id}` },
    workItem: { id: value.workItemId, projectId: value.projectId, key: 'CRW-18', title: '共享范围与筛选状态', status: 'IN_PROGRESS', href: `/work?workItem=${value.workItemId}` },
    conversations: {
      items: conversationVisible
        ? [{ id: ids.conversation, title: '规划 GitHub Provider 接入', visibility: 'PRIVATE', status: 'ACTIVE', origin: 'CONVERSATION_SOURCE', associatedAt: value.createdAt, href: `/conversation?conversation=${ids.conversation}` }]
        : [],
      nextCursor: null,
    },
  }
}

function taskExecution(value: ReturnType<typeof task>) {
  return {
    id: value.currentExecutionId, attempt: value.currentAttempt, maxAttempts: 3, parentExecutionId: null,
    priority: 50, notBefore: value.createdAt, status: value.currentExecutionStatus,
    waiting: value.currentWaitingReason ? { reason: value.currentWaitingReason, waitingSince: value.updatedAt } : null,
    controlRequest: ['PAUSE_REQUESTED', 'CANCEL_REQUESTED'].includes(value.currentExecutionStatus)
      ? { type: value.currentExecutionStatus === 'PAUSE_REQUESTED' ? 'PAUSE' : 'CANCEL', requestedByPrincipalId: ids.principal, requestedAt: value.updatedAt, reason: '团队控制命令' }
      : null,
    terminal: value.currentExecutionStatus === 'CANCELLED'
      ? { status: 'CANCELLED', decidedByPrincipalId: ids.principal, decidedAt: value.updatedAt, failureClass: null, failureCode: null }
      : value.currentExecutionStatus === 'FAILED'
        ? { status: 'FAILED', decidedByPrincipalId: ids.principal, decidedAt: value.updatedAt, failureClass: 'TRANSIENT', failureCode: 'WORKER_LOST' }
        : null,
    executorPrincipalId: ids.personalAgent,
    currentPlanVersionId: value.currentAttempt > 1 ? ids.taskPlan : null, version: value.executionVersion,
    audit: { createdByPrincipalId: ids.principal, createdAt: value.createdAt, updatedByPrincipalId: ids.principal, updatedAt: value.updatedAt },
  }
}

function repositoryBinding(status: string, version: number) {
  return {
    id: ids.repositoryBinding, organizationId: ids.organization, teamId: ids.team,
    workspaceId: ids.workspace, projectId: ids.project, kind: 'LOCAL_MANAGED',
    repositoryKey: 'crewscope-java', defaultBranch: 'main', status, version,
    createdAt: '2026-08-08T01:00:00Z', createdByPrincipalId: ids.principal,
    updatedAt: '2026-08-08T04:00:00Z', updatedByPrincipalId: ids.principal,
  }
}

function historicalTaskExecution(value: ReturnType<typeof task>) {
  return {
    ...taskExecution(value), id: value.previousExecutionId, attempt: value.previousAttempt, status: 'FAILED', waiting: null,
    currentPlanVersionId: ids.previousTaskPlan,
    terminal: { status: 'FAILED', decidedByPrincipalId: ids.principal, decidedAt: '2026-08-08T03:28:00Z', failureClass: 'TRANSIENT', failureCode: 'WORKER_LOST' },
  }
}

function codingAttempt(value: ReturnType<typeof task>, executionId: string) {
  const current = executionId === value.currentExecutionId
  const attemptNumber = current ? value.currentAttempt : value.previousAttempt
  const workspaceId = current ? ids.codingWorkspace : ids.previousCodingWorkspace
  const executionStatus = current ? value.currentExecutionStatus : 'FAILED'
  const workspaceStatus = current
    ? (['FAILED', 'CANCELLED', 'COMPLETED'].includes(executionStatus) ? executionStatus : 'RECOVERING')
    : 'FAILED'
  const artifact = (kind: string, suffix: string) => ({
    artifactId: `00000000-0000-0000-0000-00000000${suffix}`,
    kind, contentType: 'text/plain', sizeBytes: 2048, contentHash: suffix.repeat(64).slice(0, 64),
  })
  return {
    executionId,
    attempt: attemptNumber,
    executionStatus,
    current,
    coding: true,
    details: {
      executionId,
      attempt: attemptNumber,
      workspace: {
        id: workspaceId, repositoryKey: 'crewscope-java', baselineCommit: '1'.repeat(40),
        managedBranch: `crewscope/tasks/${value.id}/attempt-${attemptNumber}`,
        status: workspaceStatus, recoveryGeneration: current ? 1 : 0,
        completionReason: executionStatus === 'COMPLETED' ? 'DELIVERED' : null,
        failureCode: executionStatus === 'FAILED' ? 'WORKER_LOST' : null,
        fingerprint: '2'.repeat(64), version: 2, retainUntil: '2026-09-08T03:40:00Z',
        createdAt: value.createdAt, updatedAt: value.updatedAt,
        hostPath: '/private/worktree-must-not-render', containerId: 'container-secret',
      },
      sandbox: {
        networkMode: 'NONE', cpuCount: 2, memoryMiB: 2048, pids: 256,
        maxCommandDurationSeconds: 300, maxCommandOutputBytes: 1048576,
        readOnlyRootFilesystem: true, maxCommandCalls: 20, maxChangedFiles: 100,
        maxSingleFileBytes: 1048576, maxWriteOperations: 200, maxWrittenBytes: 5242880,
        maxDiffBytes: 10485760, maxTestRepairRounds: 3,
        buildProfileKey: 'maven-java-21', buildProfileVersion: 2,
        image: 'private-image', taskToken: 'task-token',
      },
      diffManifest: {
        artifactId: '00000000-0000-0000-0000-000000001921', generation: 2,
        manifestHash: '3'.repeat(64), fileCount: 5, additions: 28, deletions: 6,
        baselineCommit: '1'.repeat(40), deliveryCommit: null, finalHash: '4'.repeat(64),
        patch: {
          ...artifact('PATCH', '21'), contentType: 'text/x-diff;charset=utf-8',
          sizeBytes: Buffer.byteLength(codingPatch(), 'utf8'),
          contentHash: createHash('sha256').update(codingPatch(), 'utf8').digest('hex'),
        },
        files: codingDiffFiles(), createdAt: value.updatedAt,
      },
      codingResult: null,
      commandEvidenceCount: current ? 2 : 1,
      testEvidenceCount: current ? 1 : 0,
    },
  }
}

function codingDiffFiles() {
  return [
    codingDiffFile(0, 'assets/logo.png', null, 'MODIFIED', 0, 0, true),
    codingDiffFile(1, 'docs/Guide.md', 'docs/README.md', 'RENAMED', 6, 1),
    codingDiffFile(2, 'docs/obsolete.md', null, 'DELETED', 0, 3),
    codingDiffFile(3, 'src/Main.java', null, 'MODIFIED', 17, 2),
    codingDiffFile(4, 'src/NewFeature.java', null, 'ADDED', 5, 0),
  ]
}

function codingDiffFile(
  ordinal: number,
  path: string,
  oldPath: string | null,
  changeKind: string,
  additions: number,
  deletions: number,
  binary = false,
) {
  return {
    ordinal, path, oldPath, changeKind, additions, deletions, binary,
    patchTruncated: binary, patchHash: String(ordinal + 1).repeat(64).slice(0, 64),
  }
}

function codingDiffTaskEvents() {
  const raw = (file: ReturnType<typeof codingDiffFile>) => ({
    path: file.path, oldPath: file.oldPath, changeType: file.changeKind,
    additions: file.additions, deletions: file.deletions, binary: file.binary,
    patchTruncated: file.patchTruncated, patchSha256: file.patchHash,
    patchPreview: 'must-not-enter-browser-state', hostPath: '/private/worktree',
  })
  const files = codingDiffFiles()
  const reset = taskEventItem(
    'coding-diff-event-1', 'coding-diff-domain-1', 'WORKSPACE_DIFF_RESET',
    {
      workspaceId: ids.codingWorkspace, streamEpoch: 'coding-diff-epoch-1', sequence: 1,
      diffGeneration: 1, changeKind: 'RESET', manifestHash: '2'.repeat(64),
      upserts: files.filter(file => file.path !== 'src/NewFeature.java').map(file => raw({
        ...file,
        additions: file.path === 'src/Main.java' ? 12 : file.additions,
        deletions: file.path === 'src/Main.java' ? 1 : file.deletions,
      })),
      removals: [], containerId: 'private-container',
    },
    'coding-diff-cursor-1',
  )
  const delta = taskEventItem(
    'coding-diff-event-2', 'coding-diff-domain-2', 'WORKSPACE_DIFF_DELTA',
    {
      workspaceId: ids.codingWorkspace, streamEpoch: 'coding-diff-epoch-1', sequence: 2,
      diffGeneration: 2, changeKind: 'DELTA', manifestHash: '3'.repeat(64),
      upserts: files.filter(file => ['src/Main.java', 'src/NewFeature.java'].includes(file.path)).map(raw),
      removals: [], typedArgv: ['git', 'diff'],
    },
    'coding-diff-cursor-2',
  )
  return [reset, delta]
}

function codingPatch(): string {
  return [
    'diff --git a/assets/logo.png b/assets/logo.png\nindex 111..222 100644\nBinary files a/assets/logo.png and b/assets/logo.png differ\n',
    'diff --git a/docs/README.md b/docs/Guide.md\nsimilarity index 90%\nrename from docs/README.md\nrename to docs/Guide.md\n',
    'diff --git a/docs/obsolete.md b/docs/obsolete.md\ndeleted file mode 100644\n--- a/docs/obsolete.md\n+++ /dev/null\n@@ -1,3 +0,0 @@\n-old one\n-old two\n-old three\n',
    'diff --git a/src/Main.java b/src/Main.java\n--- a/src/Main.java\n+++ b/src/Main.java\n@@ -1,2 +1,3 @@\n-old line\n+public final class Main {\n+    // CrewScope controlled change\n+}\n',
    'diff --git a/src/NewFeature.java b/src/NewFeature.java\nnew file mode 100644\n--- /dev/null\n+++ b/src/NewFeature.java\n@@ -0,0 +1,2 @@\n+public final class NewFeature {\n+}\n',
  ].join('')
}

function codingCommandEvidence(executionId: string) {
  const historical = executionId === ids.previousTaskExecution
  return {
    id: historical ? '00000000-0000-0000-0000-000000001932' : '00000000-0000-0000-0000-000000001931',
    sequence: historical ? 1 : 2,
    commandKind: 'TEST', toolKey: 'coding.maven.test', timeoutSeconds: 180,
    startedAt: '2026-08-08T03:36:00Z', finishedAt: '2026-08-08T03:38:00Z',
    termination: historical ? 'TIMED_OUT' : 'EXITED', exitCode: historical ? null : 0,
    summary: historical ? '测试超时' : '207 项测试通过',
    failureClassification: historical ? 'TRANSIENT' : null, evidenceHash: '5'.repeat(64),
    commandLog: {
      artifactId: '00000000-0000-0000-0000-000000001941', kind: 'COMMAND_LOG',
      contentType: 'text/plain', sizeBytes: Buffer.byteLength(codingCommandLog(), 'utf8'),
      contentHash: createHash('sha256').update(codingCommandLog(), 'utf8').digest('hex'),
      storageUri: 'file:///private/command.log', typedArgv: ['mvn', 'test'],
    },
  }
}

function codingTestEvidence(executionId: string) {
  const historical = executionId === ids.previousTaskExecution
  return {
    id: historical ? '00000000-0000-0000-0000-000000001952' : '00000000-0000-0000-0000-000000001951',
    sequence: 1, diffGeneration: 2, diffManifestHash: '3'.repeat(64), total: 210,
    passed: historical ? 180 : 207, failed: historical ? 20 : 1, errors: historical ? 5 : 0,
    skipped: historical ? 5 : 2, summary: historical ? '测试超时并失败' : '207 项测试通过',
    failureClassification: historical ? 'TRANSIENT' : 'TEST_FAILED', evidenceHash: '7'.repeat(64),
    commandEvidenceIds: [codingCommandEvidence(executionId).id],
    acceptance: [
      { criterionIndex: 1, criterion: '关键测试通过', status: historical ? 'FAILED' : 'PASSED', summary: historical ? '测试超时' : '关键测试已验证', commandEvidenceIds: [] },
      { criterionIndex: 0, criterion: '项目可编译', status: 'PASSED', summary: 'Maven 编译完成', commandEvidenceIds: [] },
    ],
    testReport: {
      artifactId: '00000000-0000-0000-0000-000000001961', kind: 'TEST_REPORT',
      contentType: 'application/json', sizeBytes: Buffer.byteLength(codingTestReport(), 'utf8'),
      contentHash: createHash('sha256').update(codingTestReport(), 'utf8').digest('hex'),
      storageUri: 'file:///private/test-report.json',
    },
    createdAt: '2026-08-08T03:38:00Z',
  }
}

function reviewSummary(
  id: string,
  revision: number,
  version: number,
  status: string,
  invalidationReason: string | null,
  latestDecisionType: string | null,
  modificationRound: number,
) {
  return {
    id, revision, version, status, invalidationReason, contextHash: '8'.repeat(64),
    findingCount: 1, blockerCount: 0, highCount: 1, latestDecisionType, modificationRound,
  }
}

function reviewDetails(overrides: Record<string, unknown> = {}) {
  return {
    id: ids.reviewRequest,
    revision: 2,
    version: 1,
    status: 'OPEN',
    invalidationReason: null,
    reviewerRelationship: 'SELF_REVIEW',
    reviewerAgentProfileId: ids.agentReviewer,
    contextPackageId: ids.reviewContext,
    contextHash: '8'.repeat(64),
    diffArtifactId: ids.reviewDiffArtifact,
    diffArtifactHash: '9'.repeat(64),
    baselineCommit: 'a'.repeat(40),
    deliveryCommit: 'b'.repeat(40),
    changedPaths: ['src/Main.java', 'src/NewFeature.java'],
    testEvidenceId: codingTestEvidence(ids.taskExecution).id,
    testEvidenceHash: codingTestEvidence(ids.taskExecution).evidenceHash,
    findings: [{
      id: ids.reviewFinding,
      severity: 'HIGH',
      category: 'CORRECTNESS',
      title: '空值分支缺少防护',
      claim: 'Main 在配置缺失时会继续进入执行分支。',
      suggestedFix: '在进入执行分支前校验配置并返回明确错误。',
      relationship: 'SELF_REVIEW',
      fingerprint: 'c'.repeat(64),
      evidence: [{ path: 'src/Main.java', startLine: 2, endLine: 3, acceptanceCriterionIndex: 0 }],
    }],
    decisions: [],
    modificationRounds: [],
    ...overrides,
  }
}

function reviewDecision(type: string, rationale: string, revision: number) {
  return {
    id: revision === 1 ? ids.reviewDecision : crypto.randomUUID(),
    revision,
    type,
    rationale,
    reviewerMemberId: ids.member,
    eligibilityMode: 'INDEPENDENT_USER_REVIEWER',
    decidedAt: '2026-08-08T04:10:00Z',
  }
}

function reviewModificationRound(triggerDecisionId: string) {
  return {
    id: ids.reviewModificationRound,
    roundNumber: 1,
    sourceReviewRequestId: ids.reviewRequest,
    triggerDecisionId,
    createdAt: '2026-08-08T04:12:00Z',
  }
}

function codingCommandLog(): string {
  return '[INFO] 207 tests passed\nAuthorization: Bearer browser-must-hide\n[INFO] BUILD SUCCESS\n'
}

function codingTestReport(): string {
  return '{\n  "total": 210,\n  "passed": 207,\n  "failed": 1,\n  "skipped": 2\n}\n'
}

async function fulfillArtifactPage(
  route: Route,
  url: URL,
  source: Buffer,
  contentType: string,
  filename: string,
) {
  const offset = Number(url.searchParams.get('offset') ?? '0')
  const limit = Number(url.searchParams.get('limit') ?? String(source.byteLength))
  const end = Math.min(source.byteLength, offset + limit)
  await route.fulfill({ status: 206, contentType, headers: {
    'Content-Range': `bytes ${offset}-${end - 1}/${source.byteLength}`,
    'Content-Disposition': `attachment; filename="${filename}"`, ETag: '"evidence-v1"', 'Cache-Control': 'no-store',
  }, body: source.subarray(offset, end) })
}

function taskRuntimeFacts(value: ReturnType<typeof task>, executionId: string) {
  const audit = { createdByPrincipalId: ids.principal, createdAt: value.createdAt, updatedByPrincipalId: ids.principal, updatedAt: value.updatedAt }
  if (executionId !== value.currentExecutionId) {
    return {
      execution: historicalTaskExecution(value),
      planVersions: [{ id: ids.previousTaskPlan, revision: 1, parentVersionId: null, changeReason: '初始计划', markdown: '历史 attempt 的初始计划。', steps: [], todoSummary: [], publishedByPrincipalId: ids.principal, publishedAt: value.createdAt }],
      steps: [], sessions: [], agentRuns: [], interrupts: [], snapshots: [], leases: [],
      credential: 'secret-runtime-credential',
    }
  }
  return {
    execution: taskExecution(value),
    planVersions: [
      { id: ids.previousTaskPlan, revision: 1, parentVersionId: null, changeReason: '初始计划', markdown: '先建立 Task 详情契约。', steps: [], todoSummary: [], publishedByPrincipalId: ids.principal, publishedAt: value.createdAt },
      {
        id: ids.taskPlan, revision: 2, parentVersionId: ids.previousTaskPlan, changeReason: '补充 Runtime 安全事实',
        markdown: '展示团队可理解、可审计的执行进度。',
        steps: [{ key: 'runtime-view', sequence: 1, title: '实现 Runtime 详情', type: 'ACTION', dependencyKeys: [], requiredCapabilities: ['CODE'], requiredTools: ['github'], critical: true }],
        todoSummary: [{ content: '实现 Runtime 详情', status: 'IN_PROGRESS', priority: 'HIGH', planStepKey: 'runtime-view' }],
        publishedByPrincipalId: ids.principal, publishedAt: value.updatedAt,
      },
    ],
    steps: [{ id: ids.taskStep, planVersionId: ids.taskPlan, planStepKey: 'runtime-view', sequence: 1, critical: true, runAttempt: 1, maxRunAttempts: 2, status: 'WAITING', waitReason: 'WAITING_RUNTIME', checkpoint: { sequence: 1, code: 'SAFE_POINT', recordedByPrincipalId: ids.principal, recordedAt: value.updatedAt }, failureClass: null, failureCode: null, version: 1, audit }],
    sessions: [{ id: 'runtime-session', stepExecutionId: ids.taskStep, purpose: '实现 Runtime 详情', agentPrincipalId: ids.personalAgent, agentProfileId: ids.agentProfile, agentProfileVersion: 2, status: 'INTERRUPTED', version: 1, audit }],
    agentRuns: [{
      id: ids.taskRun, stepExecutionId: ids.taskStep, runtimeSessionId: 'runtime-session', agentPrincipalId: ids.personalAgent,
      agentProfileId: ids.agentProfile, agentProfileVersion: 2, runSequence: 1, status: 'INTERRUPTED',
      segments: [{ sequence: 1, kind: 'PRIMARY', resumedFromInterruptId: null, status: 'ENDED', startedAt: value.createdAt, endedAt: value.updatedAt }],
      continuityGap: { previousRunId: 'previous-run', lastValidSnapshotId: null, firstMissingCheckpoint: 2, lastMissingCheckpoint: 3, reason: 'WORKER_LOST', detectedAt: value.updatedAt },
      terminal: null, version: 1, audit,
    }],
    interrupts: [{ id: 'interrupt-safe', agentRunId: ids.taskRun, segmentSequence: 1, kind: 'RUNTIME_LOST', status: 'OPEN', resolvedByPrincipalId: null, resolvedAt: null, version: 0, audit }],
    snapshots: [{ id: 'snapshot-safe', agentRunId: ids.taskRun, runtimeSessionId: 'runtime-session', agentProfileId: ids.agentProfile, agentProfileVersion: 2, snapshotSequence: 1, checkpointSequence: 1, sizeBytes: 2048, status: 'VALID', invalidReasonCode: null, version: 0, audit }],
    leases: [{ id: ids.taskLease, environment: 'production', runtimeId: 'runtime-public', workerId: 'worker-public', phase: 'EXECUTING', status: 'EXPIRED', acquiredAt: value.createdAt, lastHeartbeatAt: value.updatedAt, expiresAt: value.updatedAt, releaseReason: 'WORKER_LOST', releasedAt: value.updatedAt, version: 1 }],
    credential: 'secret-runtime-credential',
  }
}

function runtimeFleetSummary() {
  return {
    environment: 'production', observedAt: '2026-08-08T03:41:00Z', health: 'DEGRADED', runtimeCount: 2,
    workerCount: 3, activeWorkerCount: 2, staleWorkerCount: 1, drainingWorkerCount: 0,
    capacity: { maximum: 6, active: 5, available: 1 }, waitingRuntimeExecutions: 1,
    waitingCauses: [{ cause: 'CAPACITY', count: 1 }], workers: [{ credential: 'secret-runtime-credential' }],
  }
}

function taskDetails(value: ReturnType<typeof task>) {
  return {
    id: value.id, teamId: ids.team, workspaceId: value.workspaceId, projectId: value.projectId,
    workItemId: value.workItemId, objective: value.objective, acceptanceCriteria: value.acceptanceCriteria,
    source: { type: 'WORK_ITEM', workItemVersion: 0, conversationId: null, inputType: null, inputId: null, inputVersion: null },
    responsibilitySnapshot: [
      { assignmentId: 'owner', assignmentVersion: 0, role: 'OWNER', principalId: ids.principal, principalType: 'USER', memberId: ids.member, assignedAt: value.createdAt, acceptedAt: value.createdAt },
      { assignmentId: 'executor', assignmentVersion: 0, role: 'EXECUTOR', principalId: ids.personalAgent, principalType: 'PERSONAL_AGENT', memberId: null, assignedAt: value.createdAt, acceptedAt: value.createdAt },
    ],
    responsibilityCapturedAt: value.createdAt, status: value.status, currentExecutionId: value.currentExecutionId,
    cancellation: null, version: value.version,
    audit: { createdByPrincipalId: ids.principal, createdAt: value.createdAt, updatedByPrincipalId: ids.principal, updatedAt: value.updatedAt },
    attempts: [taskExecution(value)],
  }
}

function timelineEvent(eventId: string, eventType: string, occurredAt: string, actorDisplayName: string) {
  return { eventId, domainEventId: eventId, source: 'DOMAIN_EVENT', eventType, schemaVersion: '1', aggregateType: 'WorkItem', aggregateId: ids.workItem, aggregateVersion: 0, actorType: 'USER', actorPrincipalId: ids.principal, actorDisplayName, correlationId: crypto.randomUUID(), causationId: null, occurredAt, outcome: 'SUCCEEDED', payload: { workItemId: ids.workItem } }
}

function workItemActivity(eventId: string, workItemId: string) {
  return {
    eventId,
    domainEventId: eventId,
    teamSequence: 18,
    eventType: 'TASK_EXECUTION_COMPLETED',
    category: 'EXECUTION',
    visibility: 'TEAM',
    subject: { type: 'WORK_ITEM', id: workItemId },
    actor: { type: 'PERSONAL_AGENT', principalId: ids.personalAgent },
    references: [
      { type: 'WORK_ITEM', id: workItemId },
      { type: 'TASK', id: ids.task },
    ],
    occurredAt: '2026-08-08T03:42:00Z',
    payload: { schemaName: 'task-execution-completed', schemaVersion: 1, values: { outcome: 'COMPLETED' } },
  }
}

function taskEventItem(
  eventId: string,
  domainEventId: string,
  eventType: string,
  payload: Record<string, unknown>,
  cursor: string,
  occurredAt = '2026-08-08T04:00:00Z',
) {
  return {
    cursor,
    context: { taskId: ids.task, taskExecutionId: ids.taskExecution, stepExecutionId: null, agentRunId: ids.taskRun, executionLeaseId: null },
    projectionGap: false,
    event: {
      eventId, domainEventId, streamType: 'TASK', eventType, schemaVersion: '1', aggregateType: 'TaskExecution',
      aggregateId: ids.taskExecution, aggregateVersion: 2, correlationId: 'task-timeline-correlation',
      causationId: null, occurredAt, payload,
    },
  }
}

function fulfillJson(route: Route, value: unknown): Promise<void> {
  return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(value) })
}

function fulfillReceipt(route: Route, committedVersion: number): Promise<void> {
  return route.fulfill({ status: 202, contentType: 'application/json', body: JSON.stringify(commandReceipt(committedVersion)) })
}

function fulfillCommandReceipt(route: Route, committedVersion: number): Promise<void> {
  return route.fulfill({ status: 202, contentType: 'application/json', body: JSON.stringify(commandReceipt(committedVersion)) })
}

function commandReceipt(committedVersion: number) {
  return { commandId: crypto.randomUUID(), domainEventId: crypto.randomUUID(), committedVersion, correlationId: crypto.randomUUID() }
}

function fulfillSse(route: Route, events: unknown[], headers: Record<string, string> = {}): Promise<void> {
  const body = events.map((event, index) => {
    const envelope = event as { eventId?: string; eventType?: string }
    return `id:${envelope.eventId ?? `event-${index}`}\nevent:${envelope.eventType ?? 'message'}\ndata:${JSON.stringify(event)}\n\n`
  }).join('')
  return route.fulfill({ status: 200, contentType: 'text/event-stream', headers: { 'Cache-Control': 'no-store', ...headers }, body })
}

function realtimeEvent(eventType: string, payload: Record<string, unknown>, options: { eventId?: string; domainEventId?: string | null; streamType?: string; aggregateVersion?: number | null } = {}) {
  const streamType = options.streamType ?? 'AG_UI'
  return {
    eventId: options.eventId ?? crypto.randomUUID(),
    domainEventId: options.domainEventId ?? null,
    streamType,
    eventType,
    schemaVersion: 'v1',
    aggregateType: streamType === 'CONVERSATION' ? 'CONVERSATION' : null,
    aggregateId: streamType === 'CONVERSATION' ? ids.conversation : null,
    aggregateVersion: options.aggregateVersion ?? null,
    correlationId: crypto.randomUUID(),
    causationId: null,
    occurredAt: '2026-08-08T04:00:00Z',
    payload,
  }
}

function fulfillError(route: Route, status: number, code: string, message: string, currentVersion: number | null = null): Promise<void> {
  return route.fulfill({ status, contentType: 'application/json', body: JSON.stringify({ code, message, correlationId: crypto.randomUUID(), retryable: status >= 500 || status === 409, currentVersion, details: {} }) })
}

function formatAxeViolations(violations: Array<{ id: string; impact?: string | null; nodes: Array<{ target: unknown }> }>): string {
  return violations
    .map(violation => `${violation.id} (${violation.impact ?? 'unknown'}): ${violation.nodes.map(node => JSON.stringify(node.target)).join(', ')}`)
    .join('\n')
}
