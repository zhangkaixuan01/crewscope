import { browserStorage, safeGet, safeRemove, safeSet } from '../../app/browserStorage'

/**
 * M13b-F07（R10）: the invitation proof used to live only in process memory, so a refresh — or
 * the SSO hop out to the identity provider and back — degraded the link into the "ask an admin
 * for a new one" dead end even though the one-time proof was still valid. These accessors keep
 * it in session-scoped storage following the acceptance-recovery pattern (F02 R29): versioned,
 * short TTL, bound to the principal when one is known. The proof never returns to the URL; the
 * page keeps stripping the hash, so sharing the visible link still shares nothing.
 *
 * Unlike the SSO return hop this read is not a consume: the member may refresh several times
 * before accepting. The slot is cleared explicitly when the proof is spent or abandoned.
 */
const PROOF_KEY = 'crewscope:invitation-proof:v1'
const PROOF_TTL_MS = 10 * 60 * 1000

export interface InvitationProofRecord {
  /** The one-time invitation proof; object coordinates never ride along (R29 split). */
  proof: string
  /** The signed-in principal when the link was opened; null when the visitor was anonymous. */
  principalId: string | null
  savedAt: number
}

export function persistProofRecord(entry: InvitationProofRecord): void {
  safeSet(browserStorage(), PROOF_KEY, JSON.stringify(entry))
}

/**
 * Returns the stored proof while it is within its TTL. An anonymous save (the common case —
 * invitation links are opened signed-out) restores for whoever signs in and completes the
 * accept; a record bound to another identity is dropped, not restored, so the slot never
 * lingers. The caller re-binds the record to the now-known principal after a restore.
 */
export function readProofRecord(principalId: string | null): InvitationProofRecord | null {
  const raw = safeGet(browserStorage(), PROOF_KEY)
  if (!raw) return null
  const entry = parse(raw)
  if (!entry
    || (entry.principalId !== null && principalId !== null && entry.principalId !== principalId)
    || Date.now() - entry.savedAt > PROOF_TTL_MS) {
    safeRemove(browserStorage(), PROOF_KEY)
    return null
  }
  return entry
}

/** Clears the slot — the proof was spent by an accept, or abandoned by leaving the flow. */
export function clearProofRecord(): void {
  safeRemove(browserStorage(), PROOF_KEY)
}

function parse(raw: string): InvitationProofRecord | null {
  try {
    const value = JSON.parse(raw) as Partial<InvitationProofRecord>
    if (typeof value.proof !== 'string' || !value.proof) return null
    if (typeof value.savedAt !== 'number' || !Number.isFinite(value.savedAt)) return null
    return {
      proof: value.proof,
      principalId: typeof value.principalId === 'string' && value.principalId ? value.principalId : null,
      savedAt: value.savedAt,
    }
  } catch {
    return null
  }
}
