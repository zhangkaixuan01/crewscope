import { principalIdentity, readF05, removeF05DraftIfRevision, writeF05, type F05Identity, type F05Scope } from '../../app/f05Storage'
import type { PrincipalScope } from '../principal/types'
import type { ReviewCommentSide } from './types'

export interface ReviewLineCommentDraft { content: string, draftRevision: number }

export interface ReviewLineCommentCoordinate {
  executionId: string
  filePath: string
  side: ReviewCommentSide
  lineNumber: number
  diffGeneration: number
}

/**
 * A line comment is anchored to one diff generation: the generation rides in the key's
 * revision segment, so a re-projected diff (generation bump) simply never sees the old
 * draft instead of restoring it against shifted lines.
 */
function f05Scope(scope: PrincipalScope, coordinate: ReviewLineCommentCoordinate, identity: F05Identity): F05Scope {
  return {
    accountId: identity.accountId, principalId: identity.principalId, organizationId: scope.organizationId, teamId: scope.teamId, projectId: null,
    objectId: `${coordinate.executionId}:${coordinate.filePath}:${coordinate.side}:${coordinate.lineNumber}`,
    objectVersion: coordinate.diffGeneration,
  }
}

function valid(value: unknown): value is ReviewLineCommentDraft {
  return Boolean(value && typeof value === 'object'
    && typeof (value as ReviewLineCommentDraft).content === 'string'
    && (value as ReviewLineCommentDraft).content.length <= 8_000
    && Number.isInteger((value as ReviewLineCommentDraft).draftRevision))
}

export function readReviewLineCommentDraft(scope: PrincipalScope, coordinate: ReviewLineCommentCoordinate, principal?: { id: string, accountId: string } | null): ReviewLineCommentDraft | null {
  const identity = principalIdentity(principal)
  if (!identity) return null
  const value = readF05<ReviewLineCommentDraft>('draft', f05Scope(scope, coordinate, identity))
  return value && valid(value.value) ? value.value : null
}

export function writeReviewLineCommentDraft(scope: PrincipalScope, coordinate: ReviewLineCommentCoordinate, content: string, principal?: { id: string, accountId: string } | null): void {
  const identity = principalIdentity(principal)
  if (!identity) return
  writeF05('draft', f05Scope(scope, coordinate, identity), { content, draftRevision: coordinate.diffGeneration })
}

export function clearReviewLineCommentDraftIfRevision(scope: PrincipalScope, coordinate: ReviewLineCommentCoordinate, principal?: { id: string, accountId: string } | null): boolean {
  const identity = principalIdentity(principal)
  if (!identity) return false
  return removeF05DraftIfRevision(f05Scope(scope, coordinate, identity), coordinate.diffGeneration)
}
