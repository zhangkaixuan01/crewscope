import { principalIdentity, readF05, removeF05, writeF05, type F05Identity, type F05Scope } from '../../app/f05Storage'
import type { CodingScope } from './types'

export interface RepoBindingDraft { repositoryKey: string, defaultBranch: string }

function f05Scope(scope: CodingScope, identity: F05Identity): F05Scope {
  return { accountId: identity.accountId, principalId: identity.principalId, organizationId: scope.organizationId, teamId: scope.teamId, projectId: scope.projectId, objectId: 'create:repository-binding' }
}

function valid(value: unknown): value is RepoBindingDraft {
  return Boolean(value && typeof value === 'object'
    && typeof (value as RepoBindingDraft).repositoryKey === 'string'
    && (value as RepoBindingDraft).repositoryKey.length <= 200
    && typeof (value as RepoBindingDraft).defaultBranch === 'string'
    && (value as RepoBindingDraft).defaultBranch.length <= 200)
}

export function readRepoBindingDraft(scope: CodingScope, principal?: { id: string, accountId: string } | null): RepoBindingDraft | null {
  const identity = principalIdentity(principal)
  if (!identity) return null
  const value = readF05<RepoBindingDraft>('draft', f05Scope(scope, identity))
  return value && valid(value.value) ? value.value : null
}

export function writeRepoBindingDraft(scope: CodingScope, draft: RepoBindingDraft, principal?: { id: string, accountId: string } | null): void {
  const identity = principalIdentity(principal)
  if (!identity) return
  writeF05('draft', f05Scope(scope, identity), draft)
}

/** A bound repository (or an emptied form) discards the draft; a cancelled dialog keeps it. */
export function clearRepoBindingDraft(scope: CodingScope, principal?: { id: string, accountId: string } | null): void {
  const identity = principalIdentity(principal)
  if (identity) removeF05('draft', f05Scope(scope, identity))
}
