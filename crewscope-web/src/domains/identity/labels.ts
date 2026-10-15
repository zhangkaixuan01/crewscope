import type { LoginIdentityStatus } from './types'

/** Owner-facing copy for the login-identity status enum (M12-I01 §1); subjects never render. */
export const loginIdentityStatusLabels: Record<LoginIdentityStatus, string> = {
  ACTIVE: '可用',
  DISABLED: '已停用',
  REVOKED: '已撤销',
}

/**
 * Provider labels for the account's login identities. `local` is the password credential;
 * `oidc/{registrationId}` is one federated IdP registration. Unknown providers keep their
 * raw identifier so a future backend vocabulary stays visible instead of blank.
 */
export function loginIdentityProviderLabel(provider: string): string {
  if (provider === 'local') return '账号密码'
  if (provider.startsWith('oidc/')) return '企业单点登录'
  return provider
}
