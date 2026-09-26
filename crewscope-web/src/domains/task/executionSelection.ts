import type { LocationQuery } from 'vue-router'

/** What the URL says about the selected Task Execution — `taskExecution=` is the one write-side parameter. */
export interface ExecutionSelectionParse {
  /** The unified parameter; the only key the workspace itself writes. */
  unified: string | null
  /** The legacy Coding alias `attempt=`; restored for old deep links, then normalized away. */
  alias: string | null
  /** `taskExecution=` and `attempt=` both present naming different executions: a coordinate conflict. */
  conflict: boolean
  /** Only the legacy alias is present — an old deep link that is restored, then rewritten unified. */
  aliasOnly: boolean
}

export function parseExecutionSelection(query: LocationQuery): ExecutionSelectionParse {
  const unified = scalar(query.taskExecution)
  const alias = scalar(query.attempt)
  return {
    unified,
    alias,
    conflict: Boolean(unified && alias && unified !== alias),
    aliasOnly: Boolean(alias && !unified),
  }
}

export type ExecutionSelectionResolution =
  | { state: 'CONFLICT', unified: string, alias: string }
  | { state: 'SELECTED', executionId: string }
  | { state: 'HISTORY_VIEW', executionId: string }
  | { state: 'NO_EXECUTION' }

export interface ExecutionSelectionContext {
  /** The member's own in-memory choice, honoured only while it still names an attempt the Task has. */
  selectedId: string | null
  /** The server's current execution for this Task. */
  currentExecutionId: string | null
}

/**
 * Resolves which Task Execution the workspace shows (contract §4.1).
 *
 * A coordinate conflict outranks everything: no execution is chosen and no write action runs until
 * the member picks one. An explicit URL choice wins next — as SELECTED while the Task still has that
 * attempt, as HISTORY_VIEW once it does not, with no silent fallback that would swap the evidence
 * the member is reading. Without an explicit choice only the member's live selection or the server's
 * current execution is used — never a guess by list order.
 */
export function resolveExecutionSelection(
  parse: ExecutionSelectionParse,
  attempts: readonly { id: string }[],
  context: ExecutionSelectionContext,
): ExecutionSelectionResolution {
  if (parse.conflict && parse.unified && parse.alias) {
    return { state: 'CONFLICT', unified: parse.unified, alias: parse.alias }
  }
  const explicit = parse.unified ?? parse.alias
  if (explicit) {
    return attempts.some(attempt => attempt.id === explicit)
      ? { state: 'SELECTED', executionId: explicit }
      : { state: 'HISTORY_VIEW', executionId: explicit }
  }
  const remembered = context.selectedId
  if (remembered && attempts.some(attempt => attempt.id === remembered)) {
    return { state: 'SELECTED', executionId: remembered }
  }
  if (context.currentExecutionId) return { state: 'SELECTED', executionId: context.currentExecutionId }
  return { state: 'NO_EXECUTION' }
}

function scalar(value: LocationQuery[string]): string | null {
  return typeof value === 'string' && value.trim() ? value : null
}
