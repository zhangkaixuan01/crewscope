import type { TaskScope } from './types'

export interface TaskDelegationDraft {
  objective: string
  acceptanceCriteria: string
  executorAgentProfileId: string
  agentConfigurationRevision: number | null
}

/** Scope-partitioned session draft; credentials and resolved model payloads are never persisted. */
export function taskDelegationDraftKey(
  scope: TaskScope,
  projectId: string,
  workItemId: string,
): string {
  return `crewscope:task-delegation:v1:${scope.organizationId}:${scope.teamId}:${projectId}:${workItemId}`
}

export function readTaskDelegationDraft(
  scope: TaskScope,
  projectId: string,
  workItemId: string,
): TaskDelegationDraft | null {
  try {
    const raw = sessionStorage.getItem(taskDelegationDraftKey(scope, projectId, workItemId))
    if (!raw) return null
    const value = JSON.parse(raw) as Partial<TaskDelegationDraft>
    if (typeof value.objective !== 'string'
      || value.objective.length > 2_000
      || typeof value.acceptanceCriteria !== 'string'
      || value.acceptanceCriteria.length > 8_000
      || typeof value.executorAgentProfileId !== 'string'
      || (value.agentConfigurationRevision !== null
        && (!Number.isInteger(value.agentConfigurationRevision) || Number(value.agentConfigurationRevision) < 1))) {
      sessionStorage.removeItem(taskDelegationDraftKey(scope, projectId, workItemId))
      return null
    }
    return {
      objective: value.objective,
      acceptanceCriteria: value.acceptanceCriteria,
      executorAgentProfileId: value.executorAgentProfileId,
      agentConfigurationRevision: value.agentConfigurationRevision ?? null,
    }
  } catch {
    return null
  }
}

export function writeTaskDelegationDraft(
  scope: TaskScope,
  projectId: string,
  workItemId: string,
  draft: TaskDelegationDraft,
): void {
  try {
    sessionStorage.setItem(taskDelegationDraftKey(scope, projectId, workItemId), JSON.stringify(draft))
  } catch {
    // Browser storage availability never weakens the server-side preflight and Task boundary.
  }
}

export function clearTaskDelegationDraft(
  scope: TaskScope,
  projectId: string,
  workItemId: string,
): void {
  try {
    sessionStorage.removeItem(taskDelegationDraftKey(scope, projectId, workItemId))
  } catch {
    // Draft cleanup is best effort; the key is already isolated to this WorkItem and session.
  }
}
