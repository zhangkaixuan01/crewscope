import { registrationInvitationFromHash } from './invitation'

describe('registrationInvitationFromHash', () => {
  const token = 'A'.repeat(43)

  it('accepts only one fixed Base64URL token', () => {
    expect(registrationInvitationFromHash(`#token=${token}`)).toEqual({ kind: 'valid', token })
    expect(registrationInvitationFromHash('')).toEqual({ kind: 'none' })
  })

  it.each([
    '#token=short',
    `#token=${token}&token=${'B'.repeat(43)}`,
    `#token=${token}&returnTo=/work`,
    `#invitation=${token}`,
    `#token=${'='.repeat(43)}`,
  ])('fails closed without exposing fragment details: %s', hash => {
    expect(registrationInvitationFromHash(hash)).toEqual({ kind: 'invalid' })
  })
})
