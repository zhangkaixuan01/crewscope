export type RegistrationInvitationContext =
  | { kind: 'none' }
  | { kind: 'valid', token: string }
  | { kind: 'invalid' }

const INVITATION_TOKEN = /^[A-Za-z0-9_-]{43}$/

/** Parses the one supported Fragment key without accepting duplicate or additional parameters. */
export function registrationInvitationFromHash(hash: string): RegistrationInvitationContext {
  if (!hash) return { kind: 'none' }
  const fragment = hash.startsWith('#') ? hash.slice(1) : hash
  const parameters = new URLSearchParams(fragment)
  const tokens = parameters.getAll('token')
  if (tokens.length !== 1 || [...parameters.keys()].some(key => key !== 'token')) return { kind: 'invalid' }
  const token = tokens[0] ?? ''
  return INVITATION_TOKEN.test(token) ? { kind: 'valid', token } : { kind: 'invalid' }
}
