import type { CommandReceipt } from '../scope/types'

/**
 * Hand-written mirrors of the M10-A02 knowledge API DTOs (docs/api/M10-知识库API契约.md §7).
 * `src/api/generated/openapi.ts` is a route registry without schema types, so the domain owns
 * its vocabulary the way every other domain does — enums as const arrays, DTO fields as the
 * contract's closed whitelist.
 */
export const knowledgeCategories = ['CONVENTION', 'RUNBOOK', 'DECISION', 'GUIDE', 'OTHER'] as const
export type KnowledgeCategory = typeof knowledgeCategories[number]

/** `KnowledgeEntryStatus` — the §3 lifecycle, DELETED being the irreversible tombstone. */
export const knowledgeEntryStatuses = ['DRAFT', 'PUBLISHED', 'RETIRED', 'DELETED'] as const
export type KnowledgeEntryStatus = typeof knowledgeEntryStatuses[number]

/** `KnowledgeIndexStatus` — projection owned by the index side (contract §5), never the API. */
export const knowledgeIndexStatuses = ['PENDING', 'INDEXED', 'FAILED'] as const
export type KnowledgeIndexStatus = typeof knowledgeIndexStatuses[number]

/** The mutable draft on the entry head; cleared by publish, re-created by updateDraft. */
export interface KnowledgeDraft {
  title: string
  content: string
}

/** Immutable distillation attribution (§11.2); null on manually authored entries. */
export interface KnowledgeOrigin {
  taskExecutionId: string
  attempt: number
}

/** Entry head DTO (§7 closed whitelist); ETag source is the head `version`. */
export interface KnowledgeEntrySummary {
  id: string
  entryKey: string
  category: KnowledgeCategory
  status: KnowledgeEntryStatus
  indexStatus: KnowledgeIndexStatus
  effectiveRevision: number | null
  latestRevision: number
  /** Present while a draft exists; null right after a publish. */
  draft: KnowledgeDraft | null
  version: number
  createdAt: string
  updatedAt: string
  createdBy: string
  updatedBy: string
  origin: KnowledgeOrigin | null
}

/** Immutable version-row DTO; ETag source is `contentHash`, never consumed by a command. */
export interface KnowledgeVersion {
  entryId: string
  revision: number
  previousRevision: number | null
  title: string
  content: string
  contentHash: string
  indexStatus: KnowledgeIndexStatus
  createdAt: string
  createdBy: string
}

/** Entry-key ascending keyset page (contract §7). */
export interface KnowledgeEntryPage {
  items: KnowledgeEntrySummary[]
  nextAfter: string | null
}

/** Revision-ascending keyset page for the version history; the cursor is a revision number. */
export interface KnowledgeVersionPage {
  items: KnowledgeVersion[]
  nextAfter: number | null
}

export interface KnowledgeEntryFilter {
  status?: KnowledgeEntryStatus
  category?: KnowledgeCategory
}

export interface CreateKnowledgeEntryInput {
  entryKey: string
  category: KnowledgeCategory
  title: string
  content: string
}

export interface UpdateKnowledgeDraftInput {
  title: string
  content: string
  category?: KnowledgeCategory
}

export interface DistillKnowledgeInput {
  taskExecutionId: string
  entryKey: string
  category?: KnowledgeCategory
}

export type KnowledgeCommandReceipt = CommandReceipt

/**
 * Distillation receipt (§11.1). On an idempotent replay the stored envelope carries no result
 * body, so `entryId` / `origin` / `indexStatus` are absent and only `entryKey` (echoed from the
 * request) locates the entry — the receipt panel branches on `replayed`.
 */
export interface KnowledgeDistillationReceipt {
  commandId: string
  domainEventId: string
  committedVersion: number
  correlationId: string
  entryId: string | null
  entryKey: string
  origin: KnowledgeOrigin | null
  indexStatus: KnowledgeIndexStatus | null
  replayed: boolean
}

/** Request-level @Size limits (contract §9), mirrored for client-side validation. */
export const KNOWLEDGE_TITLE_MAX = 200
export const KNOWLEDGE_CONTENT_MAX = 65536

/**
 * M10-I01c job vocabulary (docs/api/M10-仓库索引API契约.md §1): one durable job table carries
 * both sources, statuses walk QUEUED → CHUNKING → EMBEDDING → ACTIVATING → READY with FAILED
 * reachable from any live state and CANCELLED only from QUEUED (the last three are terminal).
 */
export const knowledgeIndexJobSources = ['KNOWLEDGE_ENTRY', 'REPOSITORY'] as const
export type KnowledgeIndexJobSource = typeof knowledgeIndexJobSources[number]

export const knowledgeIndexJobStatuses = ['QUEUED', 'CHUNKING', 'EMBEDDING', 'ACTIVATING', 'READY', 'FAILED', 'CANCELLED'] as const
export type KnowledgeIndexJobStatus = typeof knowledgeIndexJobStatuses[number]

/** The frozen repository index coordinate, decomposed by the API — never a canonical string. */
export interface KnowledgeRepositoryIndexKey {
  bindingId: string
  commit: string
  chunkPolicyHash: string
  modelKey: string
  modelRevision: number
}

/**
 * Closed job snapshot (contract §3): `entryId` XOR `indexKey` by source, `claimToken` deliberately
 * absent (worker-internal fence). `claimedBy`/`leaseExpiresAt` are the first recovery question —
 * the UI renders them only for knowledge managers, mirroring the entry audit block.
 */
export interface KnowledgeIndexJob {
  id: string
  source: KnowledgeIndexJobSource
  status: KnowledgeIndexJobStatus
  entryId: string | null
  projectId: string | null
  indexKey: KnowledgeRepositoryIndexKey | null
  attempt: number
  chunksDone: number
  chunksTotal: number
  /** Open vocabulary: nine closed constants plus sanitized model-connection health codes. */
  failureCode: string | null
  generationBuildSequence: number
  claimedBy: string | null
  leaseExpiresAt: string | null
  createdBy: string
  createdAt: string
  updatedAt: string
}

/** Job-id ascending keyset page over (createdAt, id); the cursor is the last job id. */
export interface KnowledgeIndexJobPage {
  items: KnowledgeIndexJob[]
  nextAfter: string | null
}

export interface KnowledgeIndexJobFilter {
  source?: KnowledgeIndexJobSource
  status?: KnowledgeIndexJobStatus
}

/** `POST /rebuilds` 202 — a closed refresh gate answers zero, a skip rather than an error. */
export interface RebuildAccepted {
  enqueued: number
}

/** `POST /repository-builds` 202 — `enqueued: 0` with `job: null` means the gate is closed. */
export interface RepositoryBuildAccepted {
  enqueued: number
  job: KnowledgeIndexJob | null
}

export interface RepositoryBuildInput {
  projectId: string
  bindingId: string
  commit: string
}

/** Contract §2: the commit must be a 40- or 64-character hex string. */
export const COMMIT_PATTERN = /^[0-9a-fA-F]{40}$|^[0-9a-fA-F]{64}$/
