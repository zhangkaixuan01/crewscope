import { principalIdentity, readF05, removeF05, writeF05, type F05Identity, type F05Scope } from '../../app/f05Storage'
import type { PrincipalScope } from '../principal/types'

export interface ConversationMessageDraft { content: string }

function f05Scope(scope: PrincipalScope, conversationId: string, identity: F05Identity): F05Scope {
  return { accountId: identity.accountId, principalId: identity.principalId, organizationId: scope.organizationId, teamId: scope.teamId, projectId: null, objectId: conversationId }
}

function valid(value: unknown): value is ConversationMessageDraft {
  return Boolean(value && typeof value === 'object' && typeof (value as ConversationMessageDraft).content === 'string'
    && (value as ConversationMessageDraft).content.length <= 50_000)
}

export function readConversationMessageDraft(scope: PrincipalScope, conversationId: string, principal?: { id: string, accountId: string } | null): ConversationMessageDraft | null {
  const identity = principalIdentity(principal)
  if (!identity) return null
  const value = readF05<ConversationMessageDraft>('draft', f05Scope(scope, conversationId, identity))
  return value && valid(value.value) ? value.value : null
}

export function writeConversationMessageDraft(scope: PrincipalScope, conversationId: string, content: string, principal?: { id: string, accountId: string } | null): void {
  const identity = principalIdentity(principal)
  if (!identity) return
  writeF05('draft', f05Scope(scope, conversationId, identity), { content })
}

/** A sent message (or an emptied composer) has no revision to honour, so the draft simply goes. */
export function clearConversationMessageDraft(scope: PrincipalScope, conversationId: string, principal?: { id: string, accountId: string } | null): void {
  const identity = principalIdentity(principal)
  if (identity) removeF05('draft', f05Scope(scope, conversationId, identity))
}
