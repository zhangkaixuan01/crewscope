import { browserStorage, safeGet, safeRemove, safeSet } from '../../app/browserStorage'

/**
 * F02 acceptance survival (R29): an accepted invitation commits server-side, but the browser
 * session may not survive the refresh back into the app. These accessors keep the committed
 * coordinates — object ids only, never the one-time proof — in the session-scoped browser
 * storage for a short window so a reload or re-login can still land on the joined Team
 * instead of degrading to the first Team of the session.
 */
const ACCEPTANCE_KEY = 'crewscope:invitation-acceptance:v1'
const ACCEPTANCE_TTL_MS = 10 * 60 * 1000

export interface AcceptanceRecovery {
  organizationId: string | null
  teamId: string | null
  memberId: string | null
  /** The accepting principal; a record from another identity is never restored. */
  principalId: string
  acceptedAt: number
}

export function persistAcceptance(entry: AcceptanceRecovery): void {
  safeSet(browserStorage(), ACCEPTANCE_KEY, JSON.stringify(entry))
}

/**
 * Returns the stored acceptance only when it belongs to the given principal and is still
 * within its TTL. Anything else — missing, corrupt, foreign or stale — reads as null and is
 * removed so the slot never lingers.
 */
export function readAcceptance(principalId: string | null): AcceptanceRecovery | null {
  const raw = safeGet(browserStorage(), ACCEPTANCE_KEY)
  if (!raw) return null
  const entry = parse(raw)
  if (!entry || !principalId || entry.principalId !== principalId
    || Date.now() - entry.acceptedAt > ACCEPTANCE_TTL_MS) {
    safeRemove(browserStorage(), ACCEPTANCE_KEY)
    return null
  }
  return entry
}

export function clearAcceptance(): void {
  safeRemove(browserStorage(), ACCEPTANCE_KEY)
}

function parse(raw: string): AcceptanceRecovery | null {
  try {
    const value = JSON.parse(raw) as Partial<AcceptanceRecovery>
    if (typeof value.principalId !== 'string' || !value.principalId) return null
    if (typeof value.acceptedAt !== 'number' || !Number.isFinite(value.acceptedAt)) return null
    return {
      organizationId: typeof value.organizationId === 'string' ? value.organizationId : null,
      teamId: typeof value.teamId === 'string' ? value.teamId : null,
      memberId: typeof value.memberId === 'string' ? value.memberId : null,
      principalId: value.principalId,
      acceptedAt: value.acceptedAt,
    }
  } catch {
    return null
  }
}
