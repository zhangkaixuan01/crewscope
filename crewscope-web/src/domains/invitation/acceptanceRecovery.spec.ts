import { beforeEach, describe, expect, it } from 'vitest'
import { clearAcceptance, persistAcceptance, readAcceptance, type AcceptanceRecovery } from './acceptanceRecovery'

const PRINCIPAL = '00000000-0000-4000-8000-000000000101'
const OTHER_PRINCIPAL = '00000000-0000-4000-8000-000000000999'

function entry(overrides: Partial<AcceptanceRecovery> = {}): AcceptanceRecovery {
  return {
    organizationId: '00000000-0000-4000-8000-000000000001',
    teamId: '00000000-0000-4000-8000-000000000201',
    memberId: '00000000-0000-4000-8000-000000000301',
    principalId: PRINCIPAL,
    acceptedAt: Date.now(),
    ...overrides,
  }
}

describe('acceptanceRecovery', () => {
  beforeEach(() => {
    sessionStorage.clear()
  })

  it('round-trips a committed acceptance for the same principal', () => {
    // The committed entry is captured once: calling entry() again would fetch a new acceptedAt
    // and the assertion would race the clock by a millisecond.
    const committed = entry()
    persistAcceptance(committed)
    expect(readAcceptance(PRINCIPAL)).toEqual(committed)
  })

  it('refuses an acceptance that belongs to another principal', () => {
    persistAcceptance(entry())
    expect(readAcceptance(OTHER_PRINCIPAL)).toBeNull()
    // The foreign record is removed, so it cannot leak into a later visit.
    expect(readAcceptance(PRINCIPAL)).toBeNull()
  })

  it('refuses an anonymous read and ignores missing records', () => {
    expect(readAcceptance(null)).toBeNull()
    expect(readAcceptance(PRINCIPAL)).toBeNull()
  })

  it('expires a record past its ten-minute window', () => {
    persistAcceptance(entry({ acceptedAt: Date.now() - 10 * 60 * 1000 - 1 }))
    expect(readAcceptance(PRINCIPAL)).toBeNull()
  })

  it('keeps a record that is close to but still inside the window', () => {
    const acceptedAt = Date.now() - 10 * 60 * 1000 + 1_000
    persistAcceptance(entry({ acceptedAt }))
    expect(readAcceptance(PRINCIPAL)).toEqual(entry({ acceptedAt }))
  })

  it('treats corrupt or legacy records as absent', () => {
    sessionStorage.setItem('crewscope:invitation-acceptance:v1', 'not-json')
    expect(readAcceptance(PRINCIPAL)).toBeNull()
    sessionStorage.setItem('crewscope:invitation-acceptance:v1', JSON.stringify({ teamId: 't' }))
    expect(readAcceptance(PRINCIPAL)).toBeNull()
  })

  it('clears on demand so a completed entry never lingers', () => {
    persistAcceptance(entry())
    clearAcceptance()
    expect(readAcceptance(PRINCIPAL)).toBeNull()
  })
})
