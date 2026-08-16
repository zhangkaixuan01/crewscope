import { expect, test, type Route } from '@playwright/test'
import AxeBuilder from '@axe-core/playwright'

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
  await page.route(/\/api\/v1\//, async route => {
    const request = route.request()
    const url = new URL(request.url())
    const path = url.pathname
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
      const input = request.postDataJSON() as { objective: string; acceptanceCriteria: string[]; executorAgentProfileId: string; conversationSource: { conversationId: string, messageId: string } | null }
      expect(input.executorAgentProfileId).toBe(ids.agentProfile)
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
        await fulfillJson(route, { items: [], hasMore: false, taskTerminal: false, nextCursor: null })
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

  await dialog.getByLabel('Gate Reviewer').selectOption(ids.secondPrincipal)
  await expect(dialog.getByText(/默认职责分离策略会拒绝/)).toBeVisible()
  await dialog.getByLabel('Gate Reviewer').selectOption(ids.thirdPrincipal)
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
  await delegate.getByRole('button', { name: '创建 Task' }).click()

  await expect(delegate).toBeHidden()
  await expect(page.getByRole('heading', { name: '由 Personal Agent 验证 M3-F02', exact: true })).toBeVisible()
  expect(new URL(page.url()).searchParams.get('task')).toBeTruthy()
  expect(new URL(page.url()).searchParams.get('workItem')).toBe(ids.workItem)
})

test('TaskIntent WorkItem handoff creates a Conversation-linked Task and restores its card', async ({ page }) => {
  await page.goto(`/conversation?team=${ids.team}&project=${ids.project}&conversation=${ids.conversation}`)
  await page.getByRole('region', { name: '已确认工作项' }).getByRole('button', { name: '查看工作项 CRW-18' }).click()
  await expect(page).toHaveURL(/sourceMessage=/)
  expect(new URL(page.url()).searchParams.get('sourceMessage')).toBe('00000000-0000-0000-0000-000000001304')

  const workItemDialog = page.getByRole('dialog', { name: 'CRW-18 工作项详情' })
  await workItemDialog.getByRole('button', { name: '交给 Agent 处理' }).click()
  const delegate = page.getByRole('dialog', { name: '交给 Agent 处理' })
  await expect(delegate.getByText('来源保留为当前 Conversation 消息')).toBeVisible()
  await delegate.getByLabel('执行目标').fill('从 TaskIntent 上下文创建耐久 Task')
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
  await page.waitForTimeout(1_500)
  expect(streamCalls).toBe(1)
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
    if (request.method() === 'GET' && path.includes(`/tasks/${ids.task}/attempts/`) && path.endsWith('/runtime-facts')) {
      const executionId = path.split('/attempts/')[1]!.split('/')[0]!
      return fulfillJson(route, taskRuntimeFacts(serverTask, executionId))
    }
    if (request.method() === 'POST' && path.endsWith('/retry')) {
      expect(request.headers()['if-match']).toBe('"4"')
      expect(request.headers()['idempotency-key']).toBeTruthy()
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
  await expect(confirm.getByText(/失败 attempt 会作为历史证据保留/)).toBeVisible()
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
  await expect(page.getByRole('dialog', { name: /完成 Agent Task 列表与委托入口 Task 详情/ }).getByText('Revision 2', { exact: true }).first()).toBeVisible()
  await expect(page).toHaveScreenshot(`task-detail-${testInfo.project.name}.png`)
})

test('M2 and M3 primary pages meet automated WCAG 2.2 AA checks', async ({ page }) => {
  const routes = [
    { path: `/conversation?team=${ids.team}&project=${ids.project}&conversation=${ids.conversation}`, ready: () => page.getByRole('heading', { name: '规划 GitHub Provider 接入', exact: true }).first() },
    { path: `/today?team=${ids.team}&project=${ids.project}`, ready: () => page.getByText('先确认范围，再推进今天的团队工作。') },
    { path: `/work?team=${ids.team}&project=${ids.project}`, ready: () => page.getByLabel('工作项列表') },
    { path: `/team/members?team=${ids.team}&project=${ids.project}`, ready: () => page.getByRole('table', { name: '团队成员列表' }) },
    { path: `/work?team=${ids.team}&project=${ids.project}&workItem=${ids.workItem}`, ready: () => page.getByRole('dialog', { name: 'CRW-18 工作项详情' }) },
    { path: `/conversation?team=${ids.team}&project=${ids.project}&conversation=${ids.conversation}`, ready: () => page.getByTestId('conversation-task-cards') },
    { path: `/work?team=${ids.team}&project=${ids.project}&task=${ids.task}`, ready: () => page.getByRole('region', { name: 'Agent Tasks' }) },
    { path: `/work?team=${ids.team}&project=${ids.project}&workItem=${ids.workItem}&task=${ids.task}`, ready: () => page.getByRole('dialog', { name: /Task 详情/ }) },
  ]

  for (const route of routes) {
    await page.goto(route.path)
    await expect(route.ready()).toBeVisible()
    const result = await new AxeBuilder({ page })
      .withTags(['wcag2a', 'wcag2aa', 'wcag21aa', 'wcag22aa'])
      .analyze()
    expect(result.violations, `${route.path}\n${formatAxeViolations(result.violations)}`).toEqual([])
  }
})

function team(id: string, name: string, workspaceId: string) {
  return { id, organizationId: ids.organization, name, status: 'ACTIVE', initializationStatus: 'READY', ownerMemberId: ids.member, defaultWorkspaceId: workspaceId, version: 0 }
}

function project(id: string, teamId: string, workspaceId: string, key: string, name: string) {
  return { id, organizationId: ids.organization, teamId, workspaceId, key, name, status: 'ACTIVE', version: 0, createdAt: '2026-08-08T01:00:00Z', createdByPrincipalId: ids.principal, updatedAt: '2026-08-08T02:00:00Z', updatedByPrincipalId: ids.principal }
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

function historicalTaskExecution(value: ReturnType<typeof task>) {
  return {
    ...taskExecution(value), id: value.previousExecutionId, attempt: value.previousAttempt, status: 'FAILED', waiting: null,
    currentPlanVersionId: ids.previousTaskPlan,
    terminal: { status: 'FAILED', decidedByPrincipalId: ids.principal, decidedAt: '2026-08-08T03:28:00Z', failureClass: 'TRANSIENT', failureCode: 'WORKER_LOST' },
  }
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
    steps: [{ id: ids.taskStep, planVersionId: ids.taskPlan, planStepKey: 'runtime-view', sequence: 1, critical: true, runAttempt: 1, maxRunAttempts: 2, status: 'WAITING', waitReason: 'WAITING_RUNTIME', checkpoint: null, failureClass: null, failureCode: null, version: 1, audit }],
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
  return route.fulfill({ status: 202, contentType: 'application/json', body: JSON.stringify({ commandId: crypto.randomUUID(), domainEventId: crypto.randomUUID(), committedVersion, correlationId: crypto.randomUUID() }) })
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
