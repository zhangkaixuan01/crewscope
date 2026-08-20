import type { LocationQuery, LocationQueryRaw } from 'vue-router'
import type { CodingScope } from './types'

export interface CodingRouteSelection {
  teamId: string | null
  projectId: string | null
  workItemId: string | null
  taskId: string | null
  executionId: string | null
  workspaceId: string | null
}

/** Reads the Task/attempt/Workspace deep-link without manufacturing missing identities. */
export function codingRouteSelection(query: LocationQuery): CodingRouteSelection {
  return {
    teamId: scalar(query.team),
    projectId: scalar(query.project),
    workItemId: scalar(query.workItem),
    taskId: scalar(query.task),
    executionId: scalar(query.attempt),
    workspaceId: scalar(query.workspace),
  }
}

/** Nested coordinates are accepted only when every parent coordinate is present. */
export function isRestorableCodingRoute(selection: CodingRouteSelection): boolean {
  if (!selection.teamId || !selection.projectId || !selection.taskId) return false
  if (selection.workspaceId && !selection.executionId) return false
  return true
}

export function codingRouteMatchesScope(selection: CodingRouteSelection, scope: CodingScope): boolean {
  return selection.teamId === scope.teamId && selection.projectId === scope.projectId
}

export function withCodingRoute(
  query: LocationQuery,
  value: {
    teamId: string
    projectId: string
    workItemId?: string | null
    taskId: string
    executionId?: string | null
    workspaceId?: string | null
  },
): LocationQueryRaw {
  return {
    ...query,
    team: value.teamId,
    project: value.projectId,
    workItem: value.workItemId ?? undefined,
    task: value.taskId,
    attempt: value.executionId ?? undefined,
    workspace: value.workspaceId ?? undefined,
  }
}

/** Closes the Coding focus while preserving the parent Task and shared Work filters. */
export function withoutCodingRoute(query: LocationQuery): LocationQueryRaw {
  return { ...query, attempt: undefined, workspace: undefined }
}

function scalar(value: LocationQuery[string]): string | null {
  return typeof value === 'string' && value.trim() ? value : null
}
