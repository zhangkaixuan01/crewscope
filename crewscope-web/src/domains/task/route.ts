import type { LocationQuery, LocationQueryRaw } from 'vue-router'
import type { TaskScope } from './types'

export interface TaskRouteSelection {
  teamId: string | null
  projectId: string | null
  workItemId: string | null
  taskId: string | null
  /** The WorkDesk names the Task Execution `taskExecution=`; the Coding studio names it `attempt=`. */
  executionId: string | null
}

/** Reads the server-authored `/work?...&task=` deep-link contract without guessing identities. */
export function taskRouteSelection(query: LocationQuery): TaskRouteSelection {
  return {
    teamId: scalar(query.team),
    projectId: scalar(query.project),
    workItemId: scalar(query.workItem),
    taskId: scalar(query.task),
    executionId: scalar(query.taskExecution),
  }
}

/**
 * Chooses which Task Execution the Task detail shows.
 *
 * A `taskExecution=` deep link is honoured only while it names an attempt the Task actually has —
 * a stale or foreign identity falls through to the member's own selection instead of blanking the
 * runtime facts. The fallback order is the one the detail already used: keep the current choice
 * while it exists, then the Task's current execution, then the newest attempt.
 */
export function resolveTaskExecution(
  selection: Pick<TaskRouteSelection, 'executionId'>,
  attempts: readonly { id: string }[],
  current: { selectedId: string | null, currentExecutionId: string | null },
): string | null {
  const known = (id: string | null): boolean => Boolean(id) && attempts.some(item => item.id === id)
  if (known(selection.executionId)) return selection.executionId
  if (known(current.selectedId)) return current.selectedId
  return current.currentExecutionId ?? attempts[0]?.id ?? null
}

/** A Task deep link is usable only after its Team and WorkProject scopes are restored. */
export function isRestorableTaskRoute(selection: TaskRouteSelection): boolean {
  return Boolean(selection.teamId && selection.projectId && selection.taskId)
}

export function taskRouteMatchesScope(selection: TaskRouteSelection, scope: TaskScope): boolean {
  return selection.teamId === scope.teamId
}

export function withTaskRoute(
  query: LocationQuery,
  value: { teamId: string, projectId: string, workItemId: string, taskId: string },
): LocationQueryRaw {
  return {
    ...query,
    team: value.teamId,
    project: value.projectId,
    workItem: value.workItemId,
    task: value.taskId,
  }
}

export function withoutTaskRoute(query: LocationQuery): LocationQueryRaw {
  return { ...query, task: undefined }
}

function scalar(value: LocationQuery[string]): string | null {
  return typeof value === 'string' && value.trim() ? value : null
}
