import type { DiffManifestSummary } from '../coding/types'
import type { WorkItemBlockedReason, WorkItemExecutionSummary } from './types'

/**
 * Outcome-first screen facts (contract §4.1 / R11): the WorkItem workspace leads with what was
 * delivered, or with the honest waiting fact — never a synthesized green completion.
 *
 * This module is judgement only. Every string it returns comes from the server read model
 * (A06's `resultSummary` template), and the presentation labels live in the components.
 */

/** The diagnostic pointer A06 mints for a delivered result; `null` when the text matches neither shape. */
export type ResultReference =
  | { kind: 'coding-attempt', executionId: string, finalHash: string }
  | { kind: 'runtime-artifact', artifactId: string }

/**
 * `coding-attempt:{executionId}@{finalHash}` or `runtime-artifact:{artifactId}`, exactly as the
 * server serialises them. An unrecognised shape returns `null` — the panel then shows the raw
 * text with a copy affordance instead of guessing a navigation target.
 */
export function parseResultReference(ref: string | null | undefined): ResultReference | null {
  if (!ref) return null
  const coding = /^coding-attempt:([^@:\s]+)@([^@\s]+)$/.exec(ref)
  if (coding) return { kind: 'coding-attempt', executionId: coding[1]!, finalHash: coding[2]! }
  const artifact = /^runtime-artifact:([^:\s]+)$/.exec(ref)
  if (artifact) return { kind: 'runtime-artifact', artifactId: artifact[1]! }
  return null
}

/** The §4.1 outcome states, in presentation order; running is WAITING with its honest fact. */
export type WorkItemOutcomeState = 'DELIVERED' | 'AWAITING_DECISION' | 'WAITING' | 'NOT_STARTED' | 'NO_SUMMARY'

export interface WorkItemOutcome {
  state: WorkItemOutcomeState
  /** A06's authoritative delivery line; non-null only for DELIVERED. */
  deliveredSummary: string | null
  /** Parsed from the A06 template's「测试未通过」clause — the test verdict stays red on its own. */
  testsFailed: boolean
  reference: ResultReference | null
  pendingReviewCount: number
  blockedReasons: WorkItemBlockedReason[]
  activeTaskCount: number
  taskCount: number
  executionStatus: string | null
}

/**
 * The judgement order is contract-fixed: a delivered result outranks a pending decision, a
 * pending decision outranks running work, and「no tasks」only counts when nothing ran at all.
 * A summary that is still mid-read returns `null` — the caller hides the block rather than
 * inventing a fact.
 */
export function resolveWorkItemOutcome(summary: WorkItemExecutionSummary | null): WorkItemOutcome | null {
  if (!summary) return null
  const facts = {
    deliveredSummary: null,
    testsFailed: false,
    reference: parseResultReference(summary.resultSourceReference),
    pendingReviewCount: summary.pendingReviewCount,
    blockedReasons: summary.blockedReasons,
    activeTaskCount: summary.activeTaskCount,
    taskCount: summary.taskCount,
    executionStatus: summary.executionStatus,
  }
  if (summary.resultSummary) {
    return { ...facts, state: 'DELIVERED', deliveredSummary: summary.resultSummary, testsFailed: summary.resultSummary.includes('测试未通过') }
  }
  if (summary.pendingReviewCount > 0) return { ...facts, state: 'AWAITING_DECISION' }
  if (summary.activeTaskCount > 0) return { ...facts, state: 'WAITING' }
  if (summary.taskCount === 0) return { ...facts, state: 'NOT_STARTED' }
  return { ...facts, state: 'NO_SUMMARY' }
}

/**
 * Which Task a `coding-attempt` reference can safely open. The current execution is certain;
 * a single-Task WorkItem is certain by structure (every execution belongs to that Task). Any
 * other execution needs evidence the caller does not have, so the answer is `null` and the
 * panel keeps the raw reference instead of guessing a navigation target.
 */
export function resolveReferenceTask(
  reference: ResultReference,
  summary: WorkItemExecutionSummary,
): string | null {
  if (reference.kind !== 'coding-attempt' || !summary.currentTaskId) return null
  return reference.executionId === summary.currentExecutionId || summary.taskCount === 1
    ? summary.currentTaskId
    : null
}

/**
 * `true`/`false` when both the pinned reference and the loaded manifest name a final hash,
 * `null` when the comparison is not possible — no reference, no manifest, or a non-coding
 * reference. Callers show the「证据版本不一致」notice only on an explicit `false`.
 */
export function referenceMatchesManifest(
  reference: ResultReference | null,
  manifest: DiffManifestSummary | null,
): boolean | null {
  if (!reference || reference.kind !== 'coding-attempt' || !manifest) return null
  return reference.finalHash === manifest.finalHash
}
