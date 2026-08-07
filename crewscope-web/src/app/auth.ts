import type { App, InjectionKey } from 'vue'

export interface AuthenticatedPrincipal {
  id: string
  displayName: string
  role: string
  organization: string
  team: string
  permissions: ReadonlySet<string>
}

export const AUTH_PRINCIPAL: InjectionKey<AuthenticatedPrincipal> = Symbol('crewscope-auth-principal')

const bootstrapPrincipal: AuthenticatedPrincipal = {
  id: 'principal-m0-demo',
  displayName: '张凯旋',
  role: 'Team Owner',
  organization: 'Acme Technology',
  team: 'Platform Engineering',
  permissions: new Set(['conversation:use', 'control:view', 'work:create']),
}

/** Installs an explicit M0 identity boundary that will be replaced by OIDC in a later milestone. */
export function installAuthPlaceholder(app: App, principal = bootstrapPrincipal): void {
  app.provide(AUTH_PRINCIPAL, principal)
}
