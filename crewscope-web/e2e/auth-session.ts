import { permissions } from '../src/app/auth'

export function authenticatedSession(organizationId: string, principalId: string, teamId?: string | null) {
  const granted = Object.values(permissions)
  return {
    authenticated: true,
    registrationMode: 'OPEN',
    csrf: { headerName: 'X-XSRF-TOKEN', parameterName: '_csrf', token: 'csrf-e2e-session' },
    account: {
      accountId: '00000000-0000-4000-8000-000000000111', username: 'e2e-member', displayName: '张凯旋',
      platformRole: 'USER', securityVersion: 1, version: 1,
    },
    principal: { principalId, organizationId },
    teams: teamId
      ? [{ teamId, name: 'Platform Engineering', memberId: '00000000-0000-4000-8000-000000000301', permissions: granted }]
      : [],
    permissions: granted,
  }
}
