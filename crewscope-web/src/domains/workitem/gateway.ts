import { apiClient, type CrewScopeApiClient } from '../../api/client'
import { readAvailableTransition } from './availability'
import type {
  AddWorkItemCommentInput,
  AssignResponsibilityInput,
  CreateWorkItemInput,
  LinkWorkItemResourceInput,
  ReplaceOwnerInput,
  ResponsibilityAssignment,
  WorkItemAvailableTransition,
  WorkItemCommandReceipt,
  WorkItemDetails,
  WorkItemListQuery,
  WorkItemPage,
  WorkItemScope,
  WorkItemStatus,
  WorkItemSummary,
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
  listAvailableTransitions(
    scope: WorkItemScope,
    workItemId: string,
    signal?: AbortSignal,
  ): Promise<WorkItemAvailableTransition[]>
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

  async listWorkItems(query: WorkItemListQuery, signal?: AbortSignal): Promise<WorkItemPage> {
    const search = new URLSearchParams()
    if (query.status) search.set('status', query.status)
    if (query.after) search.set('after', query.after)
    search.set('limit', String(query.limit ?? 50))
    const response = await this.client.get<{ items?: unknown; nextCursor?: unknown }>(
      `${root(query)}?${search.toString()}`,
      { signal },
    )
    return {
      items: array(response.items).map(readSummary),
      nextCursor: typeof response.nextCursor === 'string' ? response.nextCursor : null,
    }
  }

  createWorkItem(
    scope: WorkItemScope,
    input: CreateWorkItemInput,
    idempotencyKey: string,
  ): Promise<WorkItemCommandReceipt> {
    return this.client.post(root(scope), input, { idempotencyKey })
  }

  async getWorkItem(scope: WorkItemScope, workItemId: string, signal?: AbortSignal): Promise<WorkItemDetails> {
    const response = await this.client.get<{ workItem?: unknown }>(
      `${root(scope)}/${segment(workItemId)}`,
      { signal },
    )
    return { ...(response as WorkItemDetails), workItem: readSummary(response.workItem) }
  }

  async listAvailableTransitions(
    scope: WorkItemScope,
    workItemId: string,
    signal?: AbortSignal,
  ): Promise<WorkItemAvailableTransition[]> {
    const response = await this.client.get<{ transitions?: unknown }>(
      `${root(scope)}/${segment(workItemId)}/transitions/availability`,
      { signal },
    )
    return Array.isArray(response.transitions)
      ? response.transitions.map(readAvailableTransition)
      : []
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

/**
 * Reads one WorkItem row, attaching the availability the list and detail responses inline.
 *
 * Every other field is passed through untouched: this gateway is not the place that validates the
 * WorkItem contract, and inventing a second parser here would let the two drift.
 */
function readSummary(value: unknown): WorkItemSummary {
  if (!value || typeof value !== 'object' || Array.isArray(value)) {
    throw new TypeError('Invalid WorkItem row')
  }
  const row = value as Record<string, unknown>
  return { ...row, availableActions: readActions(row.availableActions) } as WorkItemSummary
}

/**
 * Reads the availability a list row or detail carries.
 *
 * A missing array is read as "the server published no verdict" rather than as a malformed one, so a
 * degraded response leaves the rows without actions instead of taking the whole page down. A present
 * entry is parsed strictly: a disabled action with no reason would render as a silent dead button,
 * which is the exact defect M9-A05 exists to remove.
 */
function readActions(input: unknown): WorkItemAvailableTransition[] {
  if (input == null) return []
  return array(input).map(readAvailableTransition)
}

function array(value: unknown): unknown[] {
  if (!Array.isArray(value)) throw new TypeError('Invalid WorkItem collection')
  return value
}

function text(value: unknown): string {
  if (typeof value !== 'string' || value.length === 0) throw new TypeError('Invalid WorkItem text')
  return value
}

function root(scope: WorkItemScope): string {
  return `/organizations/${segment(scope.organizationId)}/teams/${segment(scope.teamId)}/work-projects/${segment(scope.projectId)}/work-items`
}

function segment(value: string): string {
  return encodeURIComponent(value)
}
