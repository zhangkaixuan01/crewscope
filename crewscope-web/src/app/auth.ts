import type { App, InjectionKey } from 'vue'

export interface AuthenticatedPrincipal {
  id: string
  displayName: string
  role: string
  organizationId: string
  organization: string
  permissions: ReadonlySet<string>
}

export const AUTH_PRINCIPAL: InjectionKey<AuthenticatedPrincipal> = Symbol('crewscope-auth-principal')

export const permissions = {
  conversationUse: 'conversation:use',
  scopeRead: 'scope:read',
  teamMembersRead: 'team:members:read',
  teamMembersManage: 'team:members:manage',
  workProjectsRead: 'work-projects:read',
  workProjectsManage: 'work-projects:manage',
  workRead: 'work:read',
  workCreate: 'work:create',
  workParticipate: 'work:participate',
  responsibilityManage: 'responsibility:manage',
  repositoriesManage: 'repositories:manage',
  agentManage: 'agent:manage',
  providerManage: 'provider:manage',
  auditRead: 'audit:read',
  governanceExport: 'governance:export',
  operationsManage: 'operations:manage',
} as const

/**
 * Development identity used by the Bootstrap security profile.
 *
 * The server remains the authorization boundary. These permissions only drive navigation and
 * optimistic UI guards until the OIDC session endpoint replaces this development principal.
 */
export const bootstrapPrincipal: AuthenticatedPrincipal = {
  id: import.meta.env.VITE_CREWSCOPE_PRINCIPAL_ID ?? '00000000-0000-0000-0000-000000000101',
  displayName: '张凯旋',
  role: 'Team Owner',
  organizationId: import.meta.env.VITE_CREWSCOPE_ORGANIZATION_ID ?? '00000000-0000-0000-0000-000000000001',
  organization: 'Acme Technology',
  permissions: new Set(Object.values(permissions)),
}

/** Installs an explicit Bootstrap identity boundary that will be replaced by OIDC session data. */
export function installAuthPlaceholder(app: App, principal = bootstrapPrincipal): void {
  app.provide(AUTH_PRINCIPAL, principal)
}

export function can(principal: AuthenticatedPrincipal, permission: string): boolean {
  return principal.permissions.has(permission)
}
