import { principalIdentity, readF05, removeF05, writeF05, type F05Identity, type F05Scope } from '../../app/f05Storage'
import type { CodingScope } from './types'
export interface CodingTargetDraft { enabled: boolean, repositoryBindingId: string, baselineRef: string, allowedPaths: string, buildProfileCoordinate: string }

/** Legacy pre-F05 session key, kept only so the one-time purge lists the exact prefix. */
export function codingTargetDraftKey(scope: CodingScope, workItemId: string): string {
  return `crewscope:coding-target:v1:${scope.organizationId}:${scope.teamId}:${scope.projectId}:${workItemId}`
}

function f05Scope(scope: CodingScope, workItemId: string, identity: F05Identity): F05Scope { return { accountId: identity.accountId, principalId: identity.principalId, organizationId: scope.organizationId, teamId: scope.teamId, projectId: scope.projectId, objectId: `coding-target:${workItemId}` } }
function valid(value: unknown): value is CodingTargetDraft { if (!value || typeof value !== 'object') return false; const draft = value as Partial<CodingTargetDraft>; return typeof draft.enabled === 'boolean' && typeof draft.repositoryBindingId === 'string' && typeof draft.baselineRef === 'string' && typeof draft.allowedPaths === 'string' && typeof draft.buildProfileCoordinate === 'string' }
export function readCodingTargetDraft(scope: CodingScope, workItemId: string, principal?: { id: string, accountId: string } | null): CodingTargetDraft | null {
  const identity = principalIdentity(principal)
  if (!identity) return null
  const value = readF05<CodingTargetDraft>('draft', f05Scope(scope, workItemId, identity))
  return value && valid(value.value) ? value.value : null
}
export function writeCodingTargetDraft(scope: CodingScope, workItemId: string, draft: CodingTargetDraft, principal?: { id: string, accountId: string } | null): void {
  const identity = principalIdentity(principal)
  if (!identity) return
  writeF05('draft', f05Scope(scope, workItemId, identity), draft)
}
export function clearCodingTargetDraft(scope: CodingScope, workItemId: string, principal?: { id: string, accountId: string } | null): void {
  const identity = principalIdentity(principal)
  if (identity) removeF05('draft', f05Scope(scope, workItemId, identity))
}
