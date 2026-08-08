import { apiClient, type CrewScopeApiClient } from '../../api/client'
import type {
  AddWorkItemCommentInput,
  AssignResponsibilityInput,
  CreateWorkItemInput,
  LinkWorkItemResourceInput,
  ReplaceOwnerInput,
  ResponsibilityAssignment,
  WorkItemCommandReceipt,
  WorkItemDetails,
  WorkItemListQuery,
  WorkItemPage,
  WorkItemScope,
  WorkItemStatus,
  WorkItemTimelinePage,
} from './types'

export interface WorkItemGateway {
  listWorkItems(query: WorkItemListQuery, signal?: AbortSignal): Promise<WorkItemPage>
  createWorkItem(
    scope: WorkItemScope,
    input: CreateWorkItemInput,
    idempotencyKey: string,
  ): Promise<WorkItemCommandReceipt>
  getWorkItem(scope: WorkItemScope, workItemId: string, signal?: AbortSignal): Promise<WorkItemDetails>
  transitionWorkItem(
    scope: WorkItemScope,
    workItemId: string,
    targetStatus: WorkItemStatus,
    expectedVersion: number,
    idempotencyKey: string,
  ): Promise<WorkItemCommandReceipt>
  addComment(
    scope: WorkItemScope,
    workItemId: string,
    input: AddWorkItemCommentInput,
    idempotencyKey: string,
  ): Promise<WorkItemCommandReceipt>
  linkResource(
    scope: WorkItemScope,
    workItemId: string,
    input: LinkWorkItemResourceInput,
    idempotencyKey: string,
  ): Promise<WorkItemCommandReceipt>
  listResponsibilities(
    scope: WorkItemScope,
    workItemId: string,
    signal?: AbortSignal,
  ): Promise<ResponsibilityAssignment[]>
  replaceOwner(
    scope: WorkItemScope,
    workItemId: string,
    input: ReplaceOwnerInput,
    idempotencyKey: string,
  ): Promise<WorkItemCommandReceipt>
  assignExecutor(
    scope: WorkItemScope,
    workItemId: string,
    input: AssignResponsibilityInput,
    idempotencyKey: string,
  ): Promise<WorkItemCommandReceipt>
  assignGateReviewer(
    scope: WorkItemScope,
    workItemId: string,
    input: AssignResponsibilityInput,
    idempotencyKey: string,
  ): Promise<WorkItemCommandReceipt>
  assignAdvisoryReviewer(
    scope: WorkItemScope,
    workItemId: string,
    input: AssignResponsibilityInput,
    idempotencyKey: string,
  ): Promise<WorkItemCommandReceipt>
  releaseResponsibility(
    scope: WorkItemScope,
    workItemId: string,
    assignmentId: string,
    expectedVersion: number,
    idempotencyKey: string,
  ): Promise<WorkItemCommandReceipt>
  listTimeline(
    scope: WorkItemScope,
    workItemId: string,
    after?: string,
    limit?: number,
    signal?: AbortSignal,
  ): Promise<WorkItemTimelinePage>
}

/** HTTP adapter for the M1 WorkItem command and query contracts. */
export class HttpWorkItemGateway implements WorkItemGateway {
  constructor(private readonly client: CrewScopeApiClient = apiClient) {}

  listWorkItems(query: WorkItemListQuery, signal?: AbortSignal): Promise<WorkItemPage> {
    const search = new URLSearchParams()
    if (query.status) search.set('status', query.status)
    if (query.after) search.set('after', query.after)
    search.set('limit', String(query.limit ?? 50))
    return this.client.get(`${root(query)}?${search.toString()}`, { signal })
  }

  createWorkItem(
    scope: WorkItemScope,
    input: CreateWorkItemInput,
    idempotencyKey: string,
  ): Promise<WorkItemCommandReceipt> {
    return this.client.post(root(scope), input, { idempotencyKey })
  }

  getWorkItem(scope: WorkItemScope, workItemId: string, signal?: AbortSignal): Promise<WorkItemDetails> {
    return this.client.get(`${root(scope)}/${segment(workItemId)}`, { signal })
  }

  transitionWorkItem(
    scope: WorkItemScope,
    workItemId: string,
    targetStatus: WorkItemStatus,
    expectedVersion: number,
    idempotencyKey: string,
  ): Promise<WorkItemCommandReceipt> {
    return this.client.post(
      `${root(scope)}/${segment(workItemId)}/transitions`,
      { targetStatus },
      { idempotencyKey, expectedVersion },
    )
  }

  addComment(
    scope: WorkItemScope,
    workItemId: string,
    input: AddWorkItemCommentInput,
    idempotencyKey: string,
  ): Promise<WorkItemCommandReceipt> {
    return this.client.post(`${root(scope)}/${segment(workItemId)}/comments`, input, { idempotencyKey })
  }

  linkResource(
    scope: WorkItemScope,
    workItemId: string,
    input: LinkWorkItemResourceInput,
    idempotencyKey: string,
  ): Promise<WorkItemCommandReceipt> {
    return this.client.post(`${root(scope)}/${segment(workItemId)}/resource-links`, input, { idempotencyKey })
  }

  listResponsibilities(
    scope: WorkItemScope,
    workItemId: string,
    signal?: AbortSignal,
  ): Promise<ResponsibilityAssignment[]> {
    return this.client.get(`${responsibilityRoot(scope, workItemId)}`, { signal })
  }

  replaceOwner(
    scope: WorkItemScope,
    workItemId: string,
    input: ReplaceOwnerInput,
    idempotencyKey: string,
  ): Promise<WorkItemCommandReceipt> {
    return this.client.post(`${responsibilityRoot(scope, workItemId)}/owner`, input, { idempotencyKey })
  }

  assignExecutor(
    scope: WorkItemScope,
    workItemId: string,
    input: AssignResponsibilityInput,
    idempotencyKey: string,
  ): Promise<WorkItemCommandReceipt> {
    return this.assign(scope, workItemId, 'executors', input, idempotencyKey)
  }

  assignGateReviewer(
    scope: WorkItemScope,
    workItemId: string,
    input: AssignResponsibilityInput,
    idempotencyKey: string,
  ): Promise<WorkItemCommandReceipt> {
    return this.assign(scope, workItemId, 'gate-reviewers', input, idempotencyKey)
  }

  assignAdvisoryReviewer(
    scope: WorkItemScope,
    workItemId: string,
    input: AssignResponsibilityInput,
    idempotencyKey: string,
  ): Promise<WorkItemCommandReceipt> {
    return this.assign(scope, workItemId, 'advisory-reviewers', input, idempotencyKey)
  }

  releaseResponsibility(
    scope: WorkItemScope,
    workItemId: string,
    assignmentId: string,
    expectedVersion: number,
    idempotencyKey: string,
  ): Promise<WorkItemCommandReceipt> {
    return this.client.post(
      `${responsibilityRoot(scope, workItemId)}/${segment(assignmentId)}/releases`,
      undefined,
      { idempotencyKey, expectedVersion },
    )
  }

  listTimeline(
    scope: WorkItemScope,
    workItemId: string,
    after?: string,
    limit = 50,
    signal?: AbortSignal,
  ): Promise<WorkItemTimelinePage> {
    const search = new URLSearchParams({ limit: String(limit) })
    if (after) search.set('after', after)
    return this.client.get(`${root(scope)}/${segment(workItemId)}/timeline?${search.toString()}`, { signal })
  }

  private assign(
    scope: WorkItemScope,
    workItemId: string,
    route: string,
    input: AssignResponsibilityInput,
    idempotencyKey: string,
  ): Promise<WorkItemCommandReceipt> {
    return this.client.post(`${responsibilityRoot(scope, workItemId)}/${route}`, input, { idempotencyKey })
  }
}

function responsibilityRoot(scope: WorkItemScope, workItemId: string): string {
  return `${root(scope)}/${segment(workItemId)}/responsibilities`
}

function root(scope: WorkItemScope): string {
  return `/organizations/${segment(scope.organizationId)}/teams/${segment(scope.teamId)}/work-projects/${segment(scope.projectId)}/work-items`
}

function segment(value: string): string {
  return encodeURIComponent(value)
}
