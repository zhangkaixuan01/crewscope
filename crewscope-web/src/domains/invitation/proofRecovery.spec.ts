import { beforeEach, describe, expect, it } from 'vitest'
import { clearProofRecord, persistProofRecord, readProofRecord, type InvitationProofRecord } from './proofRecovery'

const PRINCIPAL = '00000000-0000-4000-8000-000000000101'
const OTHER_PRINCIPAL = '00000000-0000-4000-8000-000000000999'

function record(overrides: Partial<InvitationProofRecord> = {}): InvitationProofRecord {
  return { proof: 'one-time-proof', principalId: null, savedAt: Date.now(), ...overrides }
}

describe('proofRecovery', () => {
  beforeEach(() => {
    sessionStorage.clear()
  })

  it('restores an anonymously parked proof — invitation links are opened signed-out', () => {
    const parked = record()
    persistProofRecord(parked)
    expect(readProofRecord(PRINCIPAL)).toEqual(parked)
    expect(readProofRecord(null)).toEqual(parked)
  })

  it('is not a consume — the member may refresh several times before accepting', () => {
    persistProofRecord(record())
    expect(readProofRecord(PRINCIPAL)).not.toBeNull()
    expect(readProofRecord(PRINCIPAL)).not.toBeNull()
  })

  it('restores a record bound to the same principal and refuses one from another identity', () => {
    persistProofRecord(record({ principalId: PRINCIPAL }))
    expect(readProofRecord(PRINCIPAL)?.proof).toBe('one-time-proof')

    persistProofRecord(record({ principalId: PRINCIPAL }))
    expect(readProofRecord(OTHER_PRINCIPAL)).toBeNull()
    // The foreign record is dropped, not left for whoever signs in next.
    expect(readProofRecord(PRINCIPAL)).toBeNull()
  })

  it('still restores a bound record to an anonymous tab — account switching keeps the link', () => {
    // The in-memory proof already survived switchAccount (the member re-logs in with the
    // matching account); the session-scoped shadow must not be stricter than that.
    persistProofRecord(record({ principalId: PRINCIPAL }))
    expect(readProofRecord(null)?.proof).toBe('one-time-proof')
  })

  it('expires a record past its ten-minute window', () => {
    persistProofRecord(record({ savedAt: Date.now() - 10 * 60 * 1000 - 1 }))
    expect(readProofRecord(PRINCIPAL)).toBeNull()
  })

  it('treats corrupt or legacy records as absent', () => {
    sessionStorage.setItem('crewscope:invitation-proof:v1', 'not-json')
    expect(readProofRecord(PRINCIPAL)).toBeNull()
    sessionStorage.setItem('crewscope:invitation-proof:v1', JSON.stringify({ savedAt: Date.now() }))
    expect(readProofRecord(PRINCIPAL)).toBeNull()
  })

  it('clears on demand so a spent or abandoned proof never lingers', () => {
    persistProofRecord(record())
    clearProofRecord()
    expect(readProofRecord(PRINCIPAL)).toBeNull()
  })
})
