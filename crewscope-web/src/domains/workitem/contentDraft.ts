import { principalIdentity, readF05, removeF05, removeF05DraftIfRevision, writeF05, type F05Identity, type F05Scope } from '../../app/f05Storage'
import type { WorkItemPriority, WorkItemType } from './types'

export interface WorkItemContentDraft {
  type: WorkItemType
  title: string
  description: string
  priority: WorkItemPriority
  labels: string
  dueAt: string
  draftRevision: number
}

interface ContentScope { organizationId: string, teamId: string, projectId: string }

function f05Scope(scope: ContentScope, workItemId: string, identity: F05Identity): F05Scope {
  return { accountId: identity.accountId, principalId: identity.principalId, organizationId: scope.organizationId, teamId: scope.teamId, projectId: scope.projectId, objectId: `content:${workItemId}` }
}

function valid(value: unknown): value is WorkItemContentDraft {
  return Boolean(value && typeof value === 'object'
    && typeof (value as WorkItemContentDraft).title === 'string'
    && (value as WorkItemContentDraft).title.length <= 240
    && typeof (value as WorkItemContentDraft).description === 'string'
    && typeof (value as WorkItemContentDraft).labels === 'string'
    && typeof (value as WorkItemContentDraft).dueAt === 'string'
    && Number.isInteger((value as WorkItemContentDraft).draftRevision))
}

export function readWorkItemContentDraft(scope: ContentScope, workItemId: string, principal?: { id: string, accountId: string } | null): WorkItemContentDraft | null {
  const identity = principalIdentity(principal)
  if (!identity) return null
  const value = readF05<WorkItemContentDraft>('draft', f05Scope(scope, workItemId, identity))
  return value && valid(value.value) ? value.value : null
}

/** The draft stores the item version it was edited against, so a submit only clears its own snapshot. */
export function writeWorkItemContentDraft(scope: ContentScope, workItemId: string, itemVersion: number, draft: Omit<WorkItemContentDraft, 'draftRevision'>, principal?: { id: string, accountId: string } | null): void {
  const identity = principalIdentity(principal)
  if (!identity) return
  writeF05('draft', f05Scope(scope, workItemId, identity), { ...draft, draftRevision: itemVersion })
}

export function clearWorkItemContentDraftIfRevision(scope: ContentScope, workItemId: string, revision: number, principal?: { id: string, accountId: string } | null): boolean {
  const identity = principalIdentity(principal)
  if (!identity) return false
  return removeF05DraftIfRevision(f05Scope(scope, workItemId, identity), revision)
}

/** An explicitly discarded edit clears the draft regardless of which version wrote it. */
export function clearWorkItemContentDraft(scope: ContentScope, workItemId: string, principal?: { id: string, accountId: string } | null): void {
  const identity = principalIdentity(principal)
  if (identity) removeF05('draft', f05Scope(scope, workItemId, identity))
}
