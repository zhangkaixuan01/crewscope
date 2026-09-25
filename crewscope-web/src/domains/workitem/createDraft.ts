import { principalIdentity, readF05, removeF05, writeF05, type F05Identity, type F05Scope } from '../../app/f05Storage'
import { workItemPriorities, workItemTypes, type WorkItemPriority, type WorkItemType } from './types'
import type { PrincipalScope } from '../principal/types'

export interface WorkItemCreateDraft {
  type: WorkItemType
  title: string
  description: string
  priority: WorkItemPriority
  labels: string
  dueAt: string
}

function f05Scope(scope: PrincipalScope, projectKey: string, identity: F05Identity): F05Scope {
  return { accountId: identity.accountId, principalId: identity.principalId, organizationId: scope.organizationId, teamId: scope.teamId, projectId: null, objectId: `create:${projectKey}` }
}

function valid(value: unknown): value is WorkItemCreateDraft {
  if (!value || typeof value !== 'object') return false
  const draft = value as Partial<WorkItemCreateDraft>
  return workItemTypes.includes(draft.type as WorkItemType)
    && workItemPriorities.includes(draft.priority as WorkItemPriority)
    && typeof draft.title === 'string' && draft.title.length <= 240
    && typeof draft.description === 'string' && draft.description.length <= 20_000
    && typeof draft.labels === 'string' && draft.labels.length <= 2_000
    && typeof draft.dueAt === 'string'
}

export function readWorkItemCreateDraft(scope: PrincipalScope, projectKey: string, principal?: { id: string, accountId: string } | null): WorkItemCreateDraft | null {
  const identity = principalIdentity(principal)
  if (!identity) return null
  const value = readF05<WorkItemCreateDraft>('draft', f05Scope(scope, projectKey, identity))
  return value && valid(value.value) ? value.value : null
}

export function writeWorkItemCreateDraft(scope: PrincipalScope, projectKey: string, draft: WorkItemCreateDraft, principal?: { id: string, accountId: string } | null): void {
  const identity = principalIdentity(principal)
  if (!identity) return
  writeF05('draft', f05Scope(scope, projectKey, identity), draft)
}

/** Creation has no server revision to compare, so a successful submit simply discards the draft. */
export function clearWorkItemCreateDraft(scope: PrincipalScope, projectKey: string, principal?: { id: string, accountId: string } | null): void {
  const identity = principalIdentity(principal)
  if (identity) removeF05('draft', f05Scope(scope, projectKey, identity))
}
