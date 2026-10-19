import { CrewScopeApiError } from './client'

/**
 * Shared error semantics for every store and page (M12b-F05): one definition instead of
 * three canonical copies (teamops/errors, task/storeUtils, coding/storeUtils) plus a
 * local re-declaration in most domain stores. Domain-specialised predicates (conflict,
 * retryable, currentVersionOf, kindOf) stay in their domains — only the verbatim
 * triplicate trio converges here.
 */
export function isAbort(error: unknown): boolean {
  return error instanceof DOMException && error.name === 'AbortError'
}

export function statusOf(error: unknown): number | null {
  return error instanceof CrewScopeApiError ? error.status : null
}

export function presentError(error: unknown, fallback: string): string {
  return error instanceof CrewScopeApiError ? error.envelope.message : fallback
}
