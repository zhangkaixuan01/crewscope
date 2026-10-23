import { browserStorage, safeGet, safeRemove, safeSet } from '../../app/browserStorage'

/**
 * M13b-F07（R09）: the SSO channel is a full-page navigation — `/oauth2/authorization/{id}` and
 * back — so a query returnTo cannot survive it the way it survives the password login. These
 * accessors keep the intended destination in session-scoped storage instead: versioned, short
 * TTL, bound to the accepting principal, and only ever holding whitelisted in-app paths (the
 * candidate is validated by the caller through `isRestorablePath` — the open-redirect red line
 * stays in identity/route.ts, never in a URL the IdP or its logs could see).
 *
 * The read is a consume: a returned entry is removed with it, so a restored Today handoff can
 * never re-trigger itself in the redirect chain, and an abandoned entry ages out with the TTL.
 */
const SSO_RETURN_KEY = 'crewscope:sso-return:v1'
const SSO_RETURN_TTL_MS = 10 * 60 * 1000

export interface SsoReturnRecord {
  /** Whitelisted in-app path only; validated before persisting. */
  path: string
  /** The principal that started the SSO hop, when already signed in; null on the login page. */
  principalId: string | null
  savedAt: number
}

export function persistSsoReturn(entry: SsoReturnRecord): void {
  safeSet(browserStorage(), SSO_RETURN_KEY, JSON.stringify(entry))
}

/**
 * Returns and removes the stored destination when the caller is signed in, the record is within
 * its TTL, and — when the hop started from an already-signed-in session — it belongs to the same
 * principal. The record is written on the anonymous login page (the SSO entry lives there), so
 * `principalId: null` is the normal save and restores for whoever completes the hop; a non-null
 * record from another identity is never restored and is dropped so the slot never lingers.
 */
export function consumeSsoReturn(principalId: string | null): SsoReturnRecord | null {
  const raw = safeGet(browserStorage(), SSO_RETURN_KEY)
  if (!raw) return null
  safeRemove(browserStorage(), SSO_RETURN_KEY)
  const entry = parse(raw)
  if (!entry || !principalId
    || (entry.principalId !== null && entry.principalId !== principalId)
    || Date.now() - entry.savedAt > SSO_RETURN_TTL_MS) {
    return null
  }
  return entry
}

/** Clears an abandoned entry — e.g. a password login superseded the SSO hop. */
export function clearSsoReturn(): void {
  safeRemove(browserStorage(), SSO_RETURN_KEY)
}

function parse(raw: string): SsoReturnRecord | null {
  try {
    const value = JSON.parse(raw) as Partial<SsoReturnRecord>
    if (typeof value.path !== 'string' || !value.path.startsWith('/')) return null
    if (typeof value.savedAt !== 'number' || !Number.isFinite(value.savedAt)) return null
    return {
      path: value.path,
      principalId: typeof value.principalId === 'string' && value.principalId ? value.principalId : null,
      savedAt: value.savedAt,
    }
  } catch {
    return null
  }
}
