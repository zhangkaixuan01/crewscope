import { beforeEach, describe, expect, it } from 'vitest'
import { clearSsoReturn, consumeSsoReturn, persistSsoReturn, type SsoReturnRecord } from './ssoReturn'

const PRINCIPAL = '00000000-0000-4000-8000-000000000101'
const OTHER_PRINCIPAL = '00000000-0000-4000-8000-000000000999'

function record(overrides: Partial<SsoReturnRecord> = {}): SsoReturnRecord {
  return { path: '/work?team=team-1', principalId: null, savedAt: Date.now(), ...overrides }
}

describe('ssoReturn', () => {
  beforeEach(() => {
    sessionStorage.clear()
  })

  it('restores an anonymously parked destination for the principal that completes the hop', () => {
    // The normal SSO case: the entry is clicked on the anonymous login page, so the record has
    // no principal to bind; whoever comes back signed-in consumes it.
    const parked = record()
    persistSsoReturn(parked)
    expect(consumeSsoReturn(PRINCIPAL)).toEqual(parked)
  })

  it('consumes on read — a restored Today handoff cannot re-trigger itself', () => {
    persistSsoReturn(record())
    expect(consumeSsoReturn(PRINCIPAL)).not.toBeNull()
    expect(consumeSsoReturn(PRINCIPAL)).toBeNull()
  })

  it('restores a record bound to the same principal and refuses one from another identity', () => {
    persistSsoReturn(record({ principalId: PRINCIPAL }))
    expect(consumeSsoReturn(PRINCIPAL)?.path).toBe('/work?team=team-1')

    persistSsoReturn(record({ principalId: PRINCIPAL }))
    expect(consumeSsoReturn(OTHER_PRINCIPAL)).toBeNull()
    // The foreign record is dropped, not left for the next visitor of this tab.
    expect(consumeSsoReturn(PRINCIPAL)).toBeNull()
  })

  it('refuses an anonymous caller — the restore happens only after the session lands', () => {
    persistSsoReturn(record())
    expect(consumeSsoReturn(null)).toBeNull()
    // The consume above already removed it; a later signed-in read finds nothing.
    expect(consumeSsoReturn(PRINCIPAL)).toBeNull()
  })

  it('expires a record past its ten-minute window', () => {
    persistSsoReturn(record({ savedAt: Date.now() - 10 * 60 * 1000 - 1 }))
    expect(consumeSsoReturn(PRINCIPAL)).toBeNull()
  })

  it('treats corrupt or legacy records as absent', () => {
    sessionStorage.setItem('crewscope:sso-return:v1', 'not-json')
    expect(consumeSsoReturn(PRINCIPAL)).toBeNull()
    sessionStorage.setItem('crewscope:sso-return:v1', JSON.stringify({ path: '/today', savedAt: 1 }))
    expect(consumeSsoReturn(PRINCIPAL)).toBeNull()
  })

  it('clears on demand so a password login supersedes the parked hop', () => {
    persistSsoReturn(record())
    clearSsoReturn()
    expect(consumeSsoReturn(PRINCIPAL)).toBeNull()
  })
})
