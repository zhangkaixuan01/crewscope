import type { LocationQuery, LocationQueryRaw } from 'vue-router'
import type { TaskScope } from './types'

export interface TaskRouteSelection {
  teamId: string | null
  projectId: string | null
  workItemId: string | null
  taskId: string | null
}

/** Reads the server-authored `/work?...&task=` deep-link contract without guessing identities. */
export function taskRouteSelection(query: LocationQuery): TaskRouteSelection {
  return {
    teamId: scalar(query.team),
    projectId: scalar(query.project),
    workItemId: scalar(query.workItem),
    taskId: scalar(query.task),
  }
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
