import type { CommandReceipt } from '../scope/types'

/**
 * Hand-written mirrors of the M10-A03 team skill API DTOs (docs/api/M10-Skill目录与审核API契约.md).
 * `src/api/generated/openapi.ts` is a route registry without schema types, so the domain owns
 * its vocabulary the way every other domain does — enums as const arrays, DTO fields as the
 * contract's closed whitelist.
 */
export const skillStatuses = ['DRAFT', 'PUBLISHED', 'DISABLED'] as const
export type SkillStatus = typeof skillStatuses[number]

/**
 * The mutable draft on the skill head; cleared by publish, re-created by updateDraft. A DISABLED
 * head keeps its unconsumed draft (contract §3) — the revival path is updateDraft + publish.
 */
export interface SkillDraft {
  name: string
  description: string
  content: string
}

/** Immutable distillation attribution (§10); null on manually authored skills. */
export interface SkillOrigin {
  taskExecutionId: string
  attempt: number
}

/**
 * Skill head DTO (closed whitelist); ETag source is the head `version`. `disableReason` is only
 * present on a DISABLED head; `effectiveRevision` survives disable as the last effective fact.
 */
export interface SkillSummary {
  id: string
  skillKey: string
  status: SkillStatus
  effectiveRevision: number | null
  latestRevision: number
  draft: SkillDraft | null
  disableReason: string | null
  version: number
  createdAt: string
  updatedAt: string
  createdBy: string
  updatedBy: string
  origin: SkillOrigin | null
}

/**
 * Immutable version-row DTO; ETag source is `contentHash`, never consumed by a command. A
 * rollback appends a new row whose contentHash equals the target revision's — adjacent rows
 * with identical hashes are the legal rollback shape, never an anomaly.
 */
export interface SkillVersion {
  skillId: string
  revision: number
  previousRevision: number | null
  content: string
  contentHash: string
  createdAt: string
  createdBy: string
}

/** Skill-key ascending keyset page (contract §5); the cursor is the last skillKey. */
export interface SkillPage {
  items: SkillSummary[]
  nextAfter: string | null
}

/** Revision-ascending keyset page for the version history; the cursor is a revision number. */
export interface SkillVersionPage {
  items: SkillVersion[]
  nextAfter: number | null
}

export interface SkillFilter {
  status?: SkillStatus
}

export interface CreateSkillInput {
  skillKey: string
  content: string
}

export interface UpdateSkillDraftInput {
  content: string
}

export interface DistillSkillInput {
  taskExecutionId: string
  skillKey: string
}

export type SkillCommandReceipt = CommandReceipt

/**
 * Distillation receipt (§11). On an idempotent replay the stored envelope carries no result
 * body, so `skillId` / `origin` are absent and only `skillKey` (echoed from the request)
 * locates the skill — the receipt panel branches on `replayed`.
 */
export interface SkillDistillationReceipt {
  commandId: string
  domainEventId: string
  committedVersion: number
  correlationId: string
  skillId: string | null
  skillKey: string
  status: SkillStatus | null
  origin: SkillOrigin | null
  replayed: boolean
}

/** Request-level limits (contract §4), mirrored for client-side validation. */
export const SKILL_DESCRIPTION_MAX = 200
export const SKILL_CONTENT_MAX = 65536
export const SKILL_DISABLE_REASON_MAX = 200

/**
 * Contract §4, verbatim: 1-63 chars, may end with a hyphen and may be a single character —
 * deliberately narrower than nothing but *looser* than the knowledge entryKey client regex;
 * do not copy that one here.
 */
export const SKILL_KEY_PATTERN = /^[a-z0-9][a-z0-9-]{0,62}$/

/** Reserved for the built-in read-only Coding skill (contract §4); the server rejects it too. */
export const SKILL_RESERVED_KEYS = ['java-spring-v1']
