import type { WorkItemAvailableTransition, WorkItemStatus } from './types'

/**
 * Batch actions over a WorkItem selection.
 *
 * The rules live here rather than in a page because the batch is offered from a toolbar and executed
 * by the Store, and those two must not disagree: {@link exclusionReason} is what the toolbar counts
 * before the member presses anything, and it is what the Store asks again per row while the batch
 * runs. One function, two moments — the row can have changed in between, and a batch that trusted
 * the count it showed five seconds ago is a batch that reports a number it cannot stand behind.
 *
 * What a row may do is never decided here. It is read from the availability verdict the server
 * published with the row (`availableActions`), which is the same verdict the drawer, the card menu
 * and the command endpoint consult.
 */

/** The least a row must be for a batch to be planned and reported. */
export interface WorkItemBulkRow {
  readonly id: string
  /** How the row names itself to the member. A projection without a key falls back to the id. */
  readonly key: string | null
  readonly availableActions: readonly WorkItemAvailableTransition[]
}

/**
 * What one row of a batch came to.
 *
 * `excluded` is the only outcome that never reached the server: the row's own published verdict
 * already said the action was unavailable, and the reason it gave is carried through verbatim. The
 * other three are the same verdicts a single row action reports, so a batch summary and a card click
 * explain the same refusal in the same words.
 */
export type WorkItemBulkOutcome = 'executed' | 'excluded' | 'refused' | 'failed'

export interface WorkItemBulkRowResult {
  readonly id: string
  readonly key: string
  readonly title: string
  readonly outcome: WorkItemBulkOutcome
  readonly message: string | null
}

/**
 * How each outcome reads to a member.
 *
 * 未确认 is the important one: a row whose command never came back is not a row that did nothing — it
 * is a row whose result nobody knows yet, and calling it 失败 would invite the member to retry a
 * command that may already have run.
 */
export const workItemBulkOutcomeLabels: Record<WorkItemBulkOutcome, string> = {
  executed: '已执行',
  excluded: '已跳过',
  refused: '被拒绝',
  failed: '未确认',
}

/**
 * How far the batch in flight has got.
 *
 * A batch runs one row at a time, so it stays visible for as long as it takes. Without a count the
 * member watches a spinner over rows that are quietly changing one by one, which is exactly the
 * half-finished state a batch is not allowed to leave behind.
 */
export interface WorkItemBulkProgress {
  readonly completed: number
  readonly total: number
}

/** The two responsibilities a batch may assign. Reviewers are per-decision and stay single-row. */
export type WorkItemBulkAssignmentRole = 'OWNER' | 'EXECUTOR'

export interface WorkItemBulkSummary {
  readonly total: number
  readonly executed: number
  readonly excluded: number
  readonly refused: number
  readonly failed: number
  /** True when at least one row did not execute. A surface may never call a partial batch done. */
  readonly partial: boolean
}

/**
 * Why this row cannot take the action, or `null` when nothing published says it cannot.
 *
 * Silence is not a refusal. A row that carries no entry for the target says nothing about it — an
 * empty list is what a response that predates the field looks like — so the batch tries it and lets
 * the command answer. Treating silence as "no" would let a degraded list response exclude rows the
 * member can actually move.
 */
export function exclusionReason(row: WorkItemBulkRow, targetStatus: WorkItemStatus): string | null {
  const offered = row.availableActions.find(candidate => candidate.targetStatus === targetStatus)
  if (!offered || offered.enabled) return null
  return offered.reasonMessage ?? '该工作项当前不能执行这个动作'
}

/** Counts a batch's results. Every row is in exactly one bucket, so the four add up to `total`. */
export function summarizeBulkResults(results: readonly WorkItemBulkRowResult[]): WorkItemBulkSummary {
  const count = (outcome: WorkItemBulkOutcome) => results.filter(result => result.outcome === outcome).length
  const executed = count('executed')
  return {
    total: results.length,
    executed,
    excluded: count('excluded'),
    refused: count('refused'),
    failed: count('failed'),
    partial: executed !== results.length,
  }
}

/**
 * One sentence for a surface that has no room for the per-row list.
 *
 * A partial batch says it is partial and says where the rest is. A sentence that read as success
 * would leave the member believing thirty rows moved when nine did.
 */
export function bulkSummaryMessage(summary: WorkItemBulkSummary, actionLabel: string): string {
  if (summary.total === 0) return `没有可执行「${actionLabel}」的工作项`
  if (!summary.partial) return `已对 ${summary.executed} 项执行「${actionLabel}」`
  return `${summary.total} 项中 ${summary.executed} 项已执行「${actionLabel}」，其余 ${summary.total - summary.executed} 项见逐项结果`
}
