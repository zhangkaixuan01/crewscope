import type { InjectionKey } from 'vue'

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

export function can(principal: AuthenticatedPrincipal, permission: string): boolean {
  return principal.permissions.has(permission)
}
