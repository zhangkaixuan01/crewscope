import type { WorkItemGateway } from '../domains/workitem/gateway'
import type {
  AddWorkItemCommentInput,
  AssignResponsibilityInput,
  CreateWorkItemInput,
  LinkWorkItemResourceInput,
  ReplaceOwnerInput,
  ResponsibilityAssignment,
  WorkItemCommandReceipt,
  WorkItemComment,
  WorkItemDetails,
  WorkItemListQuery,
  WorkItemPage,
  WorkItemScope,
  WorkItemStatus,
  WorkItemSummary,
  WorkItemResourceLink,
  WorkItemTimelineEvent,
  WorkItemTimelinePage,
} from '../domains/workitem/types'
import { fixtureIds } from './scopeFixtures'

export const workItemIds = {
  first: '00000000-0000-0000-0000-000000000601',
  second: '00000000-0000-0000-0000-000000000602',
  third: '00000000-0000-0000-0000-000000000603',
} as const

export const fixtureWorkItems: WorkItemSummary[] = [
  workItem(workItemIds.first, 'CRW-18', '建立团队看板', 'FEATURE', 'IN_PROGRESS', 'HIGH'),
  workItem(workItemIds.second, 'CRW-19', '修复 Cursor 重复项', 'BUG', 'READY', 'URGENT'),
  workItem(workItemIds.third, 'CRW-20', '整理发布说明', 'TASK', 'DONE', 'LOW'),
]

export const fixtureComments: WorkItemComment[] = [
  { id: '00000000-0000-0000-0000-000000000701', workItemId: workItemIds.first, authorPrincipalId: fixtureIds.principal, content: '已确认交付范围。', source: 'CREWSCOPE', externalId: null, createdAt: '2026-08-08T03:00:00Z' },
]

export const fixtureResources: WorkItemResourceLink[] = [
  { id: '00000000-0000-0000-0000-000000000801', workItemId: workItemIds.first, resourceType: 'REPOSITORY', resourceReference: 'crewscope-java', label: '主仓库', createdAt: '2026-08-08T03:10:00Z', createdByPrincipalId: fixtureIds.principal },
]

export const fixtureWorkItemDetails: WorkItemDetails = {
  workItem: fixtureWorkItems[0]!,
  comments: fixtureComments,
  resourceLinks: fixtureResources,
}

export const responsibilityIds = {
  owner: '00000000-0000-0000-0000-000000000901',
  executor: '00000000-0000-0000-0000-000000000902',
  reviewer: '00000000-0000-0000-0000-000000000903',
} as const

export const fixtureResponsibilities: ResponsibilityAssignment[] = [
  responsibility(responsibilityIds.owner, 'OWNER', fixtureIds.principal, 'USER', '张凯旋'),
  responsibility(responsibilityIds.executor, 'EXECUTOR', '00000000-0000-0000-0000-000000000102', 'USER', '林晨'),
  responsibility(responsibilityIds.reviewer, 'REVIEWER', '00000000-0000-0000-0000-000000000104', 'SPECIALIST_AGENT', 'Architecture Reviewer'),
]

export const fixtureTimeline: WorkItemTimelineEvent[] = [
  timelineEvent('00000000-0000-0000-0000-000000001001', 'RESPONSIBILITY_ASSIGNED', '2026-08-08T03:20:00Z', '林晨'),
  timelineEvent('00000000-0000-0000-0000-000000001002', 'WORK_ITEM_CREATED', '2026-08-08T01:00:00Z', '张凯旋'),
]

export class FixtureWorkItemGateway implements WorkItemGateway {
  readonly queries: WorkItemListQuery[] = []
  readonly creations: CreateWorkItemInput[] = []
  readonly transitions: Array<{ workItemId: string; targetStatus: WorkItemStatus; expectedVersion: number }> = []
  readonly commentCreations: AddWorkItemCommentInput[] = []
  readonly resourceCreations: LinkWorkItemResourceInput[] = []
  readonly ownerReplacements: ReplaceOwnerInput[] = []
  readonly executorAssignments: AssignResponsibilityInput[] = []
  readonly gateReviewerAssignments: AssignResponsibilityInput[] = []
  readonly advisoryReviewerAssignments: AssignResponsibilityInput[] = []
  readonly releases: Array<{ assignmentId: string; expectedVersion: number }> = []
  readonly timelineQueries: Array<{ after?: string; limit?: number }> = []
  items = structuredClone(fixtureWorkItems)
  comments: WorkItemComment[] = structuredClone(fixtureComments)
  resources: WorkItemResourceLink[] = structuredClone(fixtureResources)
  responsibilities: ResponsibilityAssignment[] = structuredClone(fixtureResponsibilities)
  timeline: WorkItemTimelineEvent[] = structuredClone(fixtureTimeline)

  async listWorkItems(query: WorkItemListQuery): Promise<WorkItemPage> {
    this.queries.push(structuredClone(query))
    const filtered = query.status ? this.items.filter(item => item.status === query.status) : this.items
    if (!query.after && filtered.length > 1) return { items: structuredClone(filtered.slice(0, 2)), nextCursor: 'next-page' }
    return { items: structuredClone(query.after ? filtered.slice(1) : filtered), nextCursor: null }
  }

  async createWorkItem(scope: WorkItemScope, input: CreateWorkItemInput): Promise<WorkItemCommandReceipt> {
    this.creations.push(structuredClone(input))
    this.items.unshift({
      ...workItem(crypto.randomUUID(), input.key, input.title, input.type, 'BACKLOG', input.priority),
      ...scope,
      description: input.description,
      labels: input.labels,
      dueAt: input.dueAt,
    })
    return receipt()
  }

  async getWorkItem(_scope: WorkItemScope, workItemId: string): Promise<WorkItemDetails> {
    const item = this.items.find(candidate => candidate.id === workItemId)
    if (!item) throw new Error('WorkItem not found')
    return {
      workItem: structuredClone(item),
      comments: structuredClone(this.comments.filter(comment => comment.workItemId === workItemId)),
      resourceLinks: structuredClone(this.resources.filter(resource => resource.workItemId === workItemId)),
    }
  }

  async transitionWorkItem(
    _scope: WorkItemScope,
    workItemId: string,
    targetStatus: WorkItemStatus,
    expectedVersion: number,
  ): Promise<WorkItemCommandReceipt> {
    this.transitions.push({ workItemId, targetStatus, expectedVersion })
    const item = this.items.find(candidate => candidate.id === workItemId)
    if (!item) throw new Error('WorkItem not found')
    item.status = targetStatus
    item.version += 1
    return receipt(item.version)
  }

  async addComment(
    _scope: WorkItemScope,
    workItemId: string,
    input: AddWorkItemCommentInput,
  ): Promise<WorkItemCommandReceipt> {
    this.commentCreations.push(structuredClone(input))
    this.comments.push({ id: crypto.randomUUID(), workItemId, authorPrincipalId: fixtureIds.principal, content: input.content, source: 'CREWSCOPE', externalId: null, createdAt: '2026-08-08T04:00:00Z' })
    return receipt()
  }

  async linkResource(
    _scope: WorkItemScope,
    workItemId: string,
    input: LinkWorkItemResourceInput,
  ): Promise<WorkItemCommandReceipt> {
    this.resourceCreations.push(structuredClone(input))
    this.resources.push({ id: crypto.randomUUID(), workItemId, ...input, createdAt: '2026-08-08T04:10:00Z', createdByPrincipalId: fixtureIds.principal })
    return receipt()
  }

  async listResponsibilities(): Promise<ResponsibilityAssignment[]> {
    return structuredClone(this.responsibilities)
  }

  async replaceOwner(
    _scope: WorkItemScope,
    workItemId: string,
    input: ReplaceOwnerInput,
  ): Promise<WorkItemCommandReceipt> {
    this.ownerReplacements.push(structuredClone(input))
    this.responsibilities = this.responsibilities.filter(item => item.role !== 'OWNER')
    this.responsibilities.unshift(responsibility(crypto.randomUUID(), 'OWNER', input.actorPrincipalId, 'USER', '新 Owner', workItemId))
    return receipt()
  }

  async assignExecutor(
    _scope: WorkItemScope,
    workItemId: string,
    input: AssignResponsibilityInput,
  ): Promise<WorkItemCommandReceipt> {
    this.executorAssignments.push(structuredClone(input))
    this.responsibilities.push(responsibility(crypto.randomUUID(), 'EXECUTOR', input.actorPrincipalId, 'USER', '新 Executor', workItemId))
    return receipt()
  }

  async assignGateReviewer(
    _scope: WorkItemScope,
    workItemId: string,
    input: AssignResponsibilityInput,
  ): Promise<WorkItemCommandReceipt> {
    this.gateReviewerAssignments.push(structuredClone(input))
    this.responsibilities.push(responsibility(crypto.randomUUID(), 'REVIEWER', input.actorPrincipalId, 'USER', 'Gate Reviewer', workItemId))
    return receipt()
  }

  async assignAdvisoryReviewer(
    _scope: WorkItemScope,
    workItemId: string,
    input: AssignResponsibilityInput,
  ): Promise<WorkItemCommandReceipt> {
    this.advisoryReviewerAssignments.push(structuredClone(input))
    this.responsibilities.push(responsibility(crypto.randomUUID(), 'REVIEWER', input.actorPrincipalId, 'SPECIALIST_AGENT', 'Advisory Reviewer', workItemId))
    return receipt()
  }

  async releaseResponsibility(
    _scope: WorkItemScope,
    _workItemId: string,
    assignmentId: string,
    expectedVersion: number,
  ): Promise<WorkItemCommandReceipt> {
    this.releases.push({ assignmentId, expectedVersion })
    this.responsibilities = this.responsibilities.filter(item => item.id !== assignmentId)
    return receipt()
  }

  async listTimeline(
    _scope: WorkItemScope,
    _workItemId: string,
    after?: string,
    limit?: number,
  ): Promise<WorkItemTimelinePage> {
    this.timelineQueries.push({ after, limit })
    if (!after) return { items: structuredClone(this.timeline), nextCursor: 'timeline-page-2' }
    return { items: [structuredClone(this.timeline.at(-1)!)], nextCursor: null }
  }
}

function workItem(
  id: string,
  key: string,
  title: string,
  type: WorkItemSummary['type'],
  status: WorkItemStatus,
  priority: WorkItemSummary['priority'],
): WorkItemSummary {
  return {
    id,
    organizationId: fixtureIds.organization,
    teamId: fixtureIds.teamPlatform,
    workspaceId: fixtureIds.workspacePlatform,
    projectId: fixtureIds.projectCrewScope,
    key,
    type,
    title,
    description: `${title}的说明`,
    status,
    priority,
    labels: ['frontend'],
    dueAt: '2026-08-18T10:00:00Z',
    source: 'CREWSCOPE',
    sourceReference: null,
    version: 0,
    createdAt: '2026-08-08T01:00:00Z',
    createdByPrincipalId: fixtureIds.principal,
    updatedAt: '2026-08-08T02:00:00Z',
    updatedByPrincipalId: fixtureIds.principal,
  }
}

function receipt(committedVersion = 0): WorkItemCommandReceipt {
  return { commandId: crypto.randomUUID(), domainEventId: crypto.randomUUID(), committedVersion, correlationId: crypto.randomUUID() }
}

function responsibility(
  id: string,
  role: ResponsibilityAssignment['role'],
  actorPrincipalId: string,
  actorType: string,
  actorDisplayName: string,
  workItemId: string = workItemIds.first,
): ResponsibilityAssignment {
  return {
    id,
    workItemId,
    role,
    actorPrincipalId,
    actorType,
    actorMemberId: actorType === 'USER' ? crypto.randomUUID() : null,
    actorDisplayName,
    status: 'ACTIVE',
    assignedByPrincipalId: fixtureIds.principal,
    assignedAt: '2026-08-08T03:20:00Z',
    acceptedAt: '2026-08-08T03:20:00Z',
    version: 0,
  }
}

function timelineEvent(
  eventId: string,
  eventType: string,
  occurredAt: string,
  actorDisplayName: string,
): WorkItemTimelineEvent {
  return {
    eventId,
    domainEventId: eventId,
    source: 'DOMAIN_EVENT',
    eventType,
    schemaVersion: '1',
    aggregateType: 'WorkItem',
    aggregateId: workItemIds.first,
    aggregateVersion: 0,
    actorType: 'USER',
    actorPrincipalId: fixtureIds.principal,
    actorDisplayName,
    correlationId: crypto.randomUUID(),
    causationId: null,
    occurredAt,
    outcome: 'SUCCEEDED',
    payload: { workItemId: workItemIds.first },
  }
}
