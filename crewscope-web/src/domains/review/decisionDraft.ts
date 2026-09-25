import { principalIdentity, readF05, removeF05, removeF05DraftIfRevision, writeF05, type F05Identity, type F05Scope } from '../../app/f05Storage'
import type { PrincipalScope } from '../principal/types'
import type { ReviewDecisionType } from './types'

export interface ReviewDecisionDraft { decisionType: ReviewDecisionType, rationale: string, draftRevision: number }

function f05Scope(scope: PrincipalScope, reviewRequestId: string, identity: F05Identity): F05Scope {
  return { accountId: identity.accountId, principalId: identity.principalId, organizationId: scope.organizationId, teamId: scope.teamId, projectId: null, objectId: `decision:${reviewRequestId}` }
}

function valid(value: unknown): value is ReviewDecisionDraft {
  return Boolean(value && typeof value === 'object'
    && typeof (value as ReviewDecisionDraft).decisionType === 'string'
    && typeof (value as ReviewDecisionDraft).rationale === 'string'
    && (value as ReviewDecisionDraft).rationale.length <= 4_000
    && Number.isInteger((value as ReviewDecisionDraft).draftRevision))
}

export function readReviewDecisionDraft(scope: PrincipalScope, reviewRequestId: string, principal?: { id: string, accountId: string } | null): ReviewDecisionDraft | null {
  const identity = principalIdentity(principal)
  if (!identity) return null
  const value = readF05<ReviewDecisionDraft>('draft', f05Scope(scope, reviewRequestId, identity))
  return value && valid(value.value) ? value.value : null
}

export function writeReviewDecisionDraft(scope: PrincipalScope, reviewRequestId: string, reviewRevision: number, draft: Omit<ReviewDecisionDraft, 'draftRevision'>, principal?: { id: string, accountId: string } | null): void {
  const identity = principalIdentity(principal)
  if (!identity) return
  writeF05('draft', f05Scope(scope, reviewRequestId, identity), { ...draft, draftRevision: reviewRevision })
}

export function clearReviewDecisionDraftIfRevision(scope: PrincipalScope, reviewRequestId: string, revision: number, principal?: { id: string, accountId: string } | null): boolean {
  const identity = principalIdentity(principal)
  if (!identity) return false
  return removeF05DraftIfRevision(f05Scope(scope, reviewRequestId, identity), revision)
}

/** The rationale was emptied on purpose, so the draft goes regardless of which revision wrote it. */
export function clearReviewDecisionDraft(scope: PrincipalScope, reviewRequestId: string, principal?: { id: string, accountId: string } | null): void {
  const identity = principalIdentity(principal)
  if (identity) removeF05('draft', f05Scope(scope, reviewRequestId, identity))
}
