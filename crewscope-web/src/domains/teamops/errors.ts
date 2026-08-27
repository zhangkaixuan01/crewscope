import { CrewScopeApiError } from '../../api/client'

export type TeamOpsErrorKind =
  | 'offline'
  | 'forbidden'
  | 'cursor-expired'
  | 'conflict'
  | 'unavailable'
  | 'invalid-response'
  | 'unknown'

export interface TeamOpsErrorState {
  kind: TeamOpsErrorKind
  message: string
  status: number | null
  retryable: boolean
  currentVersion: number | null
}

/** Converts transport and contract failures into the stable UI error vocabulary. */
export function teamOpsError(error: unknown, fallback: string): TeamOpsErrorState {
  if (error instanceof CrewScopeApiError) {
    return {
      kind: kindOf(error),
      message: error.envelope.message || fallback,
      status: error.status,
      retryable: error.envelope.retryable,
      currentVersion: error.envelope.currentVersion,
    }
  }
  return {
    kind: error instanceof TypeError || error instanceof SyntaxError ? 'invalid-response' : 'unknown',
    message: fallback,
    status: null,
    retryable: false,
    currentVersion: null,
  }
}

export function isAbort(error: unknown): boolean {
  return error instanceof DOMException && error.name === 'AbortError'
}

export function statusOf(error: unknown): number | null {
  return error instanceof CrewScopeApiError ? error.status : null
}

export function currentVersionOf(error: unknown): number | null {
  return error instanceof CrewScopeApiError ? error.envelope.currentVersion : null
}

function kindOf(error: CrewScopeApiError): TeamOpsErrorKind {
  if (error.status === 0 || error.envelope.code === 'network_unavailable') return 'offline'
  if (error.status === 403) return 'forbidden'
  if (error.status === 410 || error.envelope.code === 'cursor_expired') return 'cursor-expired'
  if (error.status === 409 || error.envelope.code === 'optimistic_lock_conflict') return 'conflict'
  if (error.status >= 500) return 'unavailable'
  return 'unknown'
}
