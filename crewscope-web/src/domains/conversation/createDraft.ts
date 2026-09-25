import { principalIdentity, readF05, removeF05, writeF05, type F05Identity, type F05Scope } from '../../app/f05Storage'
import type { PrincipalScope } from '../principal/types'
import type { ConversationVisibility } from './types'

export interface ConversationCreateDraft { title: string, visibility: ConversationVisibility }

function f05Scope(scope: PrincipalScope, identity: F05Identity): F05Scope {
  return { accountId: identity.accountId, principalId: identity.principalId, organizationId: scope.organizationId, teamId: scope.teamId, projectId: null, objectId: 'create' }
}

function valid(value: unknown): value is ConversationCreateDraft {
  if (!value || typeof value !== 'object') return false
  const draft = value as Partial<ConversationCreateDraft>
  return typeof draft.title === 'string' && draft.title.length <= 200
    && (draft.visibility === 'PRIVATE' || draft.visibility === 'TEAM')
}

export function readConversationCreateDraft(scope: PrincipalScope, principal?: { id: string, accountId: string } | null): ConversationCreateDraft | null {
  const identity = principalIdentity(principal)
  if (!identity) return null
  const value = readF05<ConversationCreateDraft>('draft', f05Scope(scope, identity))
  return value && valid(value.value) ? value.value : null
}

export function writeConversationCreateDraft(scope: PrincipalScope, draft: ConversationCreateDraft, principal?: { id: string, accountId: string } | null): void {
  const identity = principalIdentity(principal)
  if (!identity) return
  writeF05('draft', f05Scope(scope, identity), draft)
}

/** Creation has no server revision to compare, so a successful submit simply discards the draft. */
export function clearConversationCreateDraft(scope: PrincipalScope, principal?: { id: string, accountId: string } | null): void {
  const identity = principalIdentity(principal)
  if (identity) removeF05('draft', f05Scope(scope, identity))
}
