import type { LocationQuery, LocationQueryRaw } from 'vue-router'
import { parseExecutionSelection } from '../task/executionSelection'
import type { CodingScope } from './types'

export interface CodingRouteSelection {
  teamId: string | null
  projectId: string | null
  workItemId: string | null
  taskId: string | null
  executionId: string | null
  workspaceId: string | null
  /** `taskExecution=` and `attempt=` name different executions: the coordinates conflict, nothing is chosen. */
  executionConflict: boolean
}

/** Reads the Task/execution/Workspace deep-link without manufacturing missing identities. */
export function codingRouteSelection(query: LocationQuery): CodingRouteSelection {
  const execution = parseExecutionSelection(query)
  return {
    teamId: scalar(query.team),
    projectId: scalar(query.project),
    workItemId: scalar(query.workItem),
    taskId: scalar(query.task),
    // On a conflict neither parameter is chosen; the workspace surfaces the conflict instead.
    executionId: execution.conflict ? null : execution.unified ?? execution.alias,
    workspaceId: scalar(query.workspace),
    executionConflict: execution.conflict,
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
    // Contract §4.1: `taskExecution=` is the unified write-side parameter; `attempt=` is always
    // cleared so a restored legacy link can never resurface as a conflict.
    taskExecution: value.executionId ?? undefined,
    attempt: undefined,
    workspace: value.workspaceId ?? undefined,
  }
}

/**
 * Closes the Coding focus while preserving the parent Task and shared Work filters. The execution
 * coordinates leave with it — both spellings — so a stale selection can never leak into the next Task.
 */
export function withoutCodingRoute(query: LocationQuery): LocationQueryRaw {
  return { ...query, taskExecution: undefined, attempt: undefined, workspace: undefined }
}

function scalar(value: LocationQuery[string]): string | null {
  return typeof value === 'string' && value.trim() ? value : null
}
