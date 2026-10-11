import { apiClient, type CrewScopeApiClient } from '../../api/client'
import type { CommandReceipt } from '../scope/types'
import { workItemStatuses } from '../workitem/types'
import type { WorkItemStatus } from '../workitem/types'
import type {
  BlockedWorkItemPage,
  BlockedWorkItemRow,
  BlockingChain,
  BlockerSummary,
  CreateWorkItemRelationInput,
  CriticalPath,
  RelationView,
  WorkGraphCommandReceipt,
  WorkGraphQueryScope,
  WorkItemRelationCommandScope,
  WorkItemRelationEdge,
  WorkItemRelations,
  WorkItemRelationType,
} from './types'
import { workItemRelationTypes } from './types'

export interface WorkGraphGateway {
  listBlockedWorkItems(
    scope: WorkItemRelationCommandScope,
    projectId?: string,
    after?: string,
    limit?: number,
    signal?: AbortSignal,
  ): Promise<BlockedWorkItemPage>
  listRelations(scope: WorkGraphQueryScope, workItemId: string, signal?: AbortSignal): Promise<WorkItemRelations>
  listBlockingChain(scope: WorkGraphQueryScope, workItemId: string, signal?: AbortSignal): Promise<BlockingChain>
  listCriticalPath(scope: WorkGraphQueryScope, workItemId: string, signal?: AbortSignal): Promise<CriticalPath>
  createRelation(
    scope: WorkItemRelationCommandScope,
    input: CreateWorkItemRelationInput,
    idempotencyKey: string,
  ): Promise<WorkGraphCommandReceipt>
  removeRelation(
    scope: WorkItemRelationCommandScope,
    relationId: string,
    idempotencyKey: string,
  ): Promise<WorkGraphCommandReceipt>
}

/**
 * HTTP adapter for the six M11 WorkGraph operations (docs/api/M11-WorkGraphAPI契约.md).
 *
 * Reads parse the contract's field whitelist one field at a time; an unknown extra field is
 * ignored, a malformed field fails the whole page loudly. Relation commands carry no If-Match —
 * a relation is an immutable version-0 fact (contract §2.1) — so only the idempotency key is set.
 */
export class HttpWorkGraphGateway implements WorkGraphGateway {
  constructor(private readonly client: CrewScopeApiClient = apiClient) {}

  async listBlockedWorkItems(
    scope: WorkItemRelationCommandScope,
    projectId?: string,
    after?: string,
    limit = 100,
    signal?: AbortSignal,
  ): Promise<BlockedWorkItemPage> {
    const search = new URLSearchParams({ limit: String(limit) })
    if (projectId) search.set('projectId', projectId)
    if (after) search.set('after', after)
    const response = await this.client.get<Record<string, unknown>>(
      `${blockedRoot(scope)}?${search.toString()}`,
      { signal },
    )
    return {
      items: readArray(response.items).map(readBlockedRow),
      nextCursor: typeof response.nextCursor === 'string' ? response.nextCursor : null,
    }
  }

  async listRelations(scope: WorkGraphQueryScope, workItemId: string, signal?: AbortSignal): Promise<WorkItemRelations> {
    const response = await this.client.get<Record<string, unknown>>(
      `${queryRoot(scope, workItemId)}/relations`,
      { signal },
    )
    return {
      anchor: readText(response.anchor, 'relations.anchor'),
      edges: readArray(response.edges).map(readRelationEdge),
      truncatedByRows: response.truncatedByRows === true,
    }
  }

  async listBlockingChain(scope: WorkGraphQueryScope, workItemId: string, signal?: AbortSignal): Promise<BlockingChain> {
    const response = await this.client.get<Record<string, unknown>>(
      `${queryRoot(scope, workItemId)}/blocking-chain`,
      { signal },
    )
    const steps = readArray(response.steps).map((value, index) => {
      const row = readObject(value, `blocking-chain.steps[${index}]`)
      return {
        workItemId: readText(row.workItemId, `blocking-chain.steps[${index}].workItemId`),
        depth: readNumber(row.depth, `blocking-chain.steps[${index}].depth`),
      }
    })
    return {
      anchor: readText(response.anchor, 'blocking-chain.anchor'),
      steps,
      truncatedByDepth: response.truncatedByDepth === true,
      truncatedByRows: response.truncatedByRows === true,
    }
  }

  async listCriticalPath(scope: WorkGraphQueryScope, workItemId: string, signal?: AbortSignal): Promise<CriticalPath> {
    const response = await this.client.get<Record<string, unknown>>(
      `${queryRoot(scope, workItemId)}/critical-path`,
      { signal },
    )
    return {
      chain: readArray(response.chain).map((value, index) => readText(value, `critical-path.chain[${index}]`)),
      truncatedByDepth: response.truncatedByDepth === true,
      truncatedByRows: response.truncatedByRows === true,
    }
  }

  createRelation(
    scope: WorkItemRelationCommandScope,
    input: CreateWorkItemRelationInput,
    idempotencyKey: string,
  ): Promise<WorkGraphCommandReceipt> {
    return this.client.post<Record<string, unknown>>(relationRoot(scope), {
      type: input.type,
      fromWorkItemId: input.fromWorkItemId,
      toWorkItemId: input.toWorkItemId,
    }, { idempotencyKey }).then(readReceipt)
  }

  removeRelation(
    scope: WorkItemRelationCommandScope,
    relationId: string,
    idempotencyKey: string,
  ): Promise<WorkGraphCommandReceipt> {
    return this.client.request<Record<string, unknown>>(
      `${relationRoot(scope)}/${encodeURIComponent(relationId)}`,
      { method: 'DELETE', idempotencyKey },
    ).then(readReceipt)
  }
}

function blockedRoot(scope: WorkItemRelationCommandScope): string {
  return `organizations/${encodeURIComponent(scope.organizationId)}/teams/${encodeURIComponent(scope.teamId)}/work-graph/blocked-work-items`
}

function queryRoot(scope: WorkGraphQueryScope, workItemId: string): string {
  return `organizations/${encodeURIComponent(scope.organizationId)}/teams/${encodeURIComponent(scope.teamId)}`
    + `/work-projects/${encodeURIComponent(scope.projectId)}/work-items/${encodeURIComponent(workItemId)}`
}

function relationRoot(scope: WorkItemRelationCommandScope): string {
  return `organizations/${encodeURIComponent(scope.organizationId)}/teams/${encodeURIComponent(scope.teamId)}/work-item-relations`
}

function readReceipt(value: Record<string, unknown>): WorkGraphCommandReceipt {
  const receipt: CommandReceipt = {
    commandId: readText(value.commandId, 'receipt.commandId'),
    domainEventId: readText(value.domainEventId, 'receipt.domainEventId'),
    committedVersion: readNumber(value.committedVersion, 'receipt.committedVersion'),
    correlationId: readText(value.correlationId, 'receipt.correlationId'),
  }
  return receipt
}

function readBlockedRow(value: unknown): BlockedWorkItemRow {
  const row = readObject(value, 'blocked row')
  return {
    workItemId: readText(row.workItemId, 'blocked.workItemId'),
    itemKey: readText(row.itemKey, 'blocked.itemKey'),
    title: readText(row.title, 'blocked.title'),
    status: readStatus(row.status, 'blocked.status'),
    projectId: readText(row.projectId, 'blocked.projectId'),
    blockedSince: readText(row.blockedSince, 'blocked.blockedSince'),
    blockerCount: readNumber(row.blockerCount, 'blocked.blockerCount'),
    crossesProject: row.crossesProject === true,
    blockers: readArray(row.blockers).map((blocker, index) => {
      const entry = readObject(blocker, `blocked.blockers[${index}]`)
      return {
        workItemId: readText(entry.workItemId, `blocked.blockers[${index}].workItemId`),
        title: readText(entry.title, `blocked.blockers[${index}].title`),
        status: readStatus(entry.status, `blocked.blockers[${index}].status`),
        projectId: readText(entry.projectId, `blocked.blockers[${index}].projectId`),
      } satisfies BlockerSummary
    }),
  }
}

function readRelationEdge(value: unknown): WorkItemRelationEdge {
  const row = readObject(value, 'relation edge')
  const direction = readText(row.direction, 'relation.direction')
  if (direction !== 'OUTGOING' && direction !== 'INCOMING') {
    throw new TypeError(`relation.direction is invalid: ${direction}`)
  }
  return {
    relationId: readText(row.relationId, 'relation.relationId'),
    direction,
    otherWorkItemId: readText(row.otherWorkItemId, 'relation.otherWorkItemId'),
    otherProjectId: readText(row.otherProjectId, 'relation.otherProjectId'),
    crossProject: row.crossProject === true,
    views: readArray(row.views).map((view, index) => {
      const entry = readObject(view, `relation.views[${index}]`)
      return {
        type: readRelationType(entry.type, `relation.views[${index}].type`),
        fromWorkItemId: readText(entry.fromWorkItemId, `relation.views[${index}].fromWorkItemId`),
        toWorkItemId: readText(entry.toWorkItemId, `relation.views[${index}].toWorkItemId`),
      } satisfies RelationView
    }),
    createdAt: readText(row.createdAt, 'relation.createdAt'),
  }
}

function readRelationType(value: unknown, field: string): WorkItemRelationType {
  const type = readText(value, field)
  if (!(workItemRelationTypes as readonly string[]).includes(type)) {
    throw new TypeError(`${field} is invalid: ${type}`)
  }
  return type as WorkItemRelationType
}

function readStatus(value: unknown, field: string): WorkItemStatus {
  const status = readText(value, field)
  if (!(workItemStatuses as readonly string[]).includes(status)) {
    throw new TypeError(`${field} is invalid: ${status}`)
  }
  return status as WorkItemStatus
}

function readObject(value: unknown, field: string): Record<string, unknown> {
  if (!value || typeof value !== 'object' || Array.isArray(value)) {
    throw new TypeError(`${field} is not an object`)
  }
  return value as Record<string, unknown>
}

function readArray(value: unknown): unknown[] {
  return Array.isArray(value) ? value : []
}

function readText(value: unknown, field: string): string {
  if (typeof value !== 'string') throw new TypeError(`${field} is not a string`)
  return value
}

function readNumber(value: unknown, field: string): number {
  if (typeof value !== 'number' || !Number.isFinite(value)) throw new TypeError(`${field} is not a number`)
  return value
}
