import { principalIdentity, readF05, removeF05, writeF05, type F05Identity, type F05Scope } from '../../app/f05Storage'
import type { TaskScope } from './types'

export interface TaskDelegationDraft {
  objective: string
  acceptanceCriteria: string
  executorAgentProfileId: string
  agentConfigurationRevision: number | null
}

/** Legacy pre-F05 session key, kept only so the one-time purge lists the exact prefix. */
export function taskDelegationDraftKey(
  scope: TaskScope,
  projectId: string,
  workItemId: string,
): string {
  return `crewscope:task-delegation:v1:${scope.organizationId}:${scope.teamId}:${projectId}:${workItemId}`
}

function f05Scope(scope: TaskScope, projectId: string, workItemId: string, identity: F05Identity): F05Scope { return { accountId: identity.accountId, principalId: identity.principalId, organizationId: scope.organizationId, teamId: scope.teamId, projectId, objectId: workItemId } }

function valid(value: unknown): value is TaskDelegationDraft {
  return Boolean(value && typeof value === 'object'
    && typeof (value as TaskDelegationDraft).objective === 'string'
    && (value as TaskDelegationDraft).objective.length <= 2_000
    && typeof (value as TaskDelegationDraft).acceptanceCriteria === 'string'
    && (value as TaskDelegationDraft).acceptanceCriteria.length <= 8_000
    && typeof (value as TaskDelegationDraft).executorAgentProfileId === 'string'
    && ((value as TaskDelegationDraft).agentConfigurationRevision === null
      || (Number.isInteger((value as TaskDelegationDraft).agentConfigurationRevision) && Number((value as TaskDelegationDraft).agentConfigurationRevision) >= 1)))
}

export function readTaskDelegationDraft(
  scope: TaskScope,
  projectId: string,
  workItemId: string,
  principal?: { id: string, accountId: string } | null,
): TaskDelegationDraft | null {
  const identity = principalIdentity(principal)
  if (!identity) return null
  const value = readF05<TaskDelegationDraft>('draft', f05Scope(scope, projectId, workItemId, identity))
  return value && valid(value.value) ? value.value : null
}

export function writeTaskDelegationDraft(
  scope: TaskScope,
  projectId: string,
  workItemId: string,
  draft: TaskDelegationDraft,
  principal?: { id: string, accountId: string } | null,
): void {
  const identity = principalIdentity(principal)
  if (!identity) return
  writeF05('draft', f05Scope(scope, projectId, workItemId, identity), draft)
}

export function clearTaskDelegationDraft(
  scope: TaskScope,
  projectId: string,
  workItemId: string,
  principal?: { id: string, accountId: string } | null,
): void {
  const identity = principalIdentity(principal)
  if (identity) removeF05('draft', f05Scope(scope, projectId, workItemId, identity))
}
