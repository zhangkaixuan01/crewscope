import { principalIdentity, readF05, removeF05, removeF05DraftIfRevision, writeF05, type F05Identity, type F05Scope } from '../../app/f05Storage'
import type { PrincipalScope } from '../principal/types'

export interface WorkItemCommentDraft { content: string, draftRevision: number }

function f05Scope(scope: PrincipalScope & { projectId: string }, workItemId: string, identity: F05Identity): F05Scope {
  return { accountId: identity.accountId, principalId: identity.principalId, organizationId: scope.organizationId, teamId: scope.teamId, projectId: scope.projectId, objectId: workItemId }
}

function valid(value: unknown): value is WorkItemCommentDraft {
  return Boolean(value && typeof value === 'object'
    && typeof (value as WorkItemCommentDraft).content === 'string'
    && (value as WorkItemCommentDraft).content.length <= 8_000
    && Number.isInteger((value as WorkItemCommentDraft).draftRevision))
}

/**
 * A comment outlives item edits, so the draft is keyed by object only and stores the item
 * version it was written against as `draftRevision`; a successful submit removes the draft
 * only when that revision still matches, so a re-written newer draft survives.
 */
export function readWorkItemCommentDraft(scope: PrincipalScope & { projectId: string }, workItemId: string, principal?: { id: string, accountId: string } | null): WorkItemCommentDraft | null {
  const identity = principalIdentity(principal)
  if (!identity) return null
  const value = readF05<WorkItemCommentDraft>('draft', f05Scope(scope, workItemId, identity))
  return value && valid(value.value) ? value.value : null
}

export function writeWorkItemCommentDraft(scope: PrincipalScope & { projectId: string }, workItemId: string, itemVersion: number, content: string, principal?: { id: string, accountId: string } | null): void {
  const identity = principalIdentity(principal)
  if (!identity) return
  writeF05('draft', f05Scope(scope, workItemId, identity), { content, draftRevision: itemVersion })
}

export function clearWorkItemCommentDraftIfRevision(scope: PrincipalScope & { projectId: string }, workItemId: string, revision: number, principal?: { id: string, accountId: string } | null): boolean {
  const identity = principalIdentity(principal)
  if (!identity) return false
  return removeF05DraftIfRevision(f05Scope(scope, workItemId, identity), revision)
}

/** The composer was emptied on purpose, so the draft goes regardless of which version wrote it. */
export function clearWorkItemCommentDraft(scope: PrincipalScope & { projectId: string }, workItemId: string, principal?: { id: string, accountId: string } | null): void {
  const identity = principalIdentity(principal)
  if (identity) removeF05('draft', f05Scope(scope, workItemId, identity))
}
