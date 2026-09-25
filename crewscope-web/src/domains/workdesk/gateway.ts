import { apiClient, type CrewScopeApiClient } from '../../api/client'
import { safeRoute } from '../shared/route'
import { readAvailableTransition } from '../workitem/availability'
import type { WorkItemAvailableTransition } from '../workitem/types'
import { workDeskResponsibilityRoles, type WorkDeskFilter, type WorkDeskItem, type WorkDeskRowSummary, type WorkDeskScope, type WorkDeskSection, type WorkDeskSummary, type WorkDeskWaitingOn } from './types'

export interface WorkDeskGateway {
  /**
   * The first screen: every section's first page in one request. `limit` is sent only when passed, so
   * callers that let the server default stay byte-identical to what they sent before the field existed.
   */
  get(scope: WorkDeskScope, filter?: WorkDeskFilter, limit?: number, signal?: AbortSignal): Promise<WorkDeskSummary>
  /** Continues exactly one section from the cursor its current page ended with. */
  getSection(
    scope: WorkDeskScope,
    sectionKey: string,
    filter?: WorkDeskFilter,
    after?: string,
    limit?: number,
    signal?: AbortSignal,
  ): Promise<WorkDeskSection>
}

/** Strict browser adapter for the derived WorkDesk response; unsafe routes never reach the router. */
export class HttpWorkDeskGateway implements WorkDeskGateway {
  constructor(private readonly client: CrewScopeApiClient = apiClient) {}

  async get(scope: WorkDeskScope, filter: WorkDeskFilter = {}, limit?: number, signal?: AbortSignal): Promise<WorkDeskSummary> {
    const search = filterParams(filter)
    if (limit !== undefined) search.set('limit', String(limit))
    const query = search.toString()
    const value = record(await this.client.get(`/organizations/${segment(scope.organizationId)}/teams/${segment(scope.teamId)}/work-desk${query ? `?${query}` : ''}`, { signal }))
    if (string(value.organizationId) !== scope.organizationId || string(value.teamId) !== scope.teamId) throw new TypeError('WorkDesk scope does not match the active Team')
    const sections = array(value.sections).map(mapSection)
    if (sections.length !== sectionKeys.length || sections.some((section, index) => section.key !== sectionKeys[index])) {
      throw new TypeError('Invalid WorkDesk section order')
    }
    return {
      organizationId: scope.organizationId,
      teamId: scope.teamId,
      projectId: nullableString(value.projectId),
      generatedAt: string(value.generatedAt),
      sections,
    }
  }

  async getSection(
    scope: WorkDeskScope,
    sectionKey: string,
    filter: WorkDeskFilter = {},
    after?: string,
    limit?: number,
    signal?: AbortSignal,
  ): Promise<WorkDeskSection> {
    const search = filterParams(filter)
    if (after) search.set('after', after)
    if (limit !== undefined) search.set('limit', String(limit))
    const query = search.toString()
    const value = record(await this.client.get(`/organizations/${segment(scope.organizationId)}/teams/${segment(scope.teamId)}/work-desk/sections/${segment(sectionKey)}${query ? `?${query}` : ''}`, { signal }))
    const section = mapSection(value)
    if (section.key !== sectionKey) throw new TypeError('WorkDesk section key does not match the request')
    return section
  }
}

function filterParams(filter: WorkDeskFilter): URLSearchParams {
  const search = new URLSearchParams()
  if (filter.projectId) search.set('projectId', filter.projectId)
  if (filter.responsibilityRole) search.set('responsibilityRole', filter.responsibilityRole)
  if (filter.onlyNeedsAction) search.set('onlyNeedsAction', 'true')
  return search
}

const sectionKeys = ['HUMAN_GATE', 'REVIEW', 'BLOCKED', 'WORK_ITEM', 'TASK_EXECUTION', 'INBOX'] as const

function mapSection(input: unknown): WorkDeskSection {
  const value = record(input)
  const key = string(value.key)
  if (!sectionKeys.includes(key as typeof sectionKeys[number])) throw new TypeError('Invalid WorkDesk section')
  const priority = integer(value.priority)
  const total = integer(value.total)
  const items = array(value.items).map(mapItem)
  if (priority < 1 || total < items.length || items.length > 500) throw new TypeError('Invalid WorkDesk section counters')
  return { key, title: string(value.title), priority, total, truncated: boolean(value.truncated), items, nextCursor: nullableString(value.nextCursor) }
}

function mapItem(input: unknown): WorkDeskItem {
  const value = record(input)
  const role = nullableString(value.responsibilityRole)
  if (role !== null && !workDeskResponsibilityRoles.includes(role as typeof workDeskResponsibilityRoles[number])) throw new TypeError('Invalid WorkDesk responsibility role')
  const progress = value.progress == null ? null : integer(value.progress)
  if (progress !== null && progress > 100) throw new TypeError('Invalid WorkDesk progress')
  const route = string(value.route)
  if (!safeRoute(route)) throw new TypeError('Unsafe WorkDesk route')
  return {
    objectType: string(value.objectType), objectId: string(value.objectId), projectId: nullableString(value.projectId),
    title: nullableString(value.title), status: string(value.status), updatedAt: string(value.updatedAt),
    responsibilityRole: role as WorkDeskItem['responsibilityRole'], needsAction: boolean(value.needsAction),
    urgency: string(value.urgency), progress, availableActions: readActions(value.availableActions), route,
    workItemId: nullableString(value.workItemId), workItemTitle: nullableString(value.workItemTitle),
    rowSummary: readRowSummary(value.rowSummary), waitingOn: readWaitingOn(value.waitingOn),
  }
}

/**
 * Reads the §4.1 summary block of one row.
 *
 * Absent is read as "no facts published" so the row renders; present is parsed strictly — the counts
 * are what a chip prints, and a malformed block must fail loudly rather than render a wrong number.
 */
function readRowSummary(input: unknown): WorkDeskRowSummary | null {
  if (input == null) return null
  const value = record(input)
  return {
    taskCount: integer(value.taskCount),
    activeTaskCount: integer(value.activeTaskCount),
    pendingReviewCount: integer(value.pendingReviewCount),
    selectionRequired: boolean(value.selectionRequired),
    currentExecutionStatus: nullableString(value.currentExecutionStatus),
    waitingReason: nullableString(value.waitingReason),
    observedAt: string(value.observedAt),
    workItemVersion: integer(value.workItemVersion),
  }
}

function readWaitingOn(input: unknown): WorkDeskWaitingOn | null {
  if (input == null) return null
  const value = record(input)
  return { principalId: string(value.principalId), displayName: nullableString(value.displayName), role: nullableString(value.role) }
}

/**
 * Reads the executable actions of one row.
 *
 * A disabled entry here means the server sent something the WorkDesk contract does not allow — this
 * surface offers only what can be executed, so an entry whose reason a list row cannot render must
 * fail loudly instead of becoming a dead button. The per-object endpoint remains the place where
 * disabled actions and their reasons arrive.
 */
function readActions(input: unknown): WorkItemAvailableTransition[] {
  return array(input).map(entry => {
    const action = readAvailableTransition(entry)
    if (!action.enabled) throw new TypeError('WorkDesk action must be executable')
    return action
  })
}

function segment(value: string): string { return encodeURIComponent(value) }
function record(value: unknown): Record<string, unknown> { if (!value || typeof value !== 'object' || Array.isArray(value)) throw new TypeError('Invalid WorkDesk response'); return value as Record<string, unknown> }
function array(value: unknown): unknown[] { if (!Array.isArray(value)) throw new TypeError('Invalid WorkDesk collection'); return value }
function string(value: unknown): string { if (typeof value !== 'string' || value.length === 0) throw new TypeError('Invalid WorkDesk text'); return value }
function nullableString(value: unknown): string | null { return value == null ? null : string(value) }
function boolean(value: unknown): boolean { if (typeof value !== 'boolean') throw new TypeError('Invalid WorkDesk boolean'); return value }
function integer(value: unknown): number { if (!Number.isInteger(value) || Number(value) < 0) throw new TypeError('Invalid WorkDesk number'); return Number(value) }

export { safeRoute }
