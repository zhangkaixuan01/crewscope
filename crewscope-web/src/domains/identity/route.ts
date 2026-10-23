import type { LocationQuery, Router } from 'vue-router'
import { permissions } from '../../app/auth'

const restorableRouteNames = new Set([
  'conversation',
  'today',
  'work',
  'activity',
  'inbox',
  'team-observer',
  'operations',
  'audit',
  'team-members',
  'repository-settings',
  'agent-settings',
  'model-settings',
  'lark-settings',
  // M13b-F01（R33）：治理页深链同样值得会话过期后恢复——组织设置/配额是纯组织角色
  // 唯一可达的工作面，恢复名单缺席会把这些深链甩回默认落点。
  'organization-settings',
  'quota-settings',
  'onboarding',
  'account',
  'invite',
])

/**
 * M13b-F01（R01/R49）：默认落点按会话权限裁决，不再是常量——纯组织角色（无任何 team
 * 粒度权限）登录后直达组织设置，而不是被 `/conversation` 的守卫拦进 access-denied。
 * 顺序即产品裁定：对话是成员的核心工作面；组织审计是组织角色的唯一工作面；Today 是
 * 无对话能力成员的第一屏；账号页无权限门禁，是任何会话都可达的兜底。
 */
export function defaultLoginDestination(principal: { permissions: ReadonlySet<string> } | null | undefined): string {
  const grants = principal?.permissions
  if (grants?.has(permissions.conversationUse)) return '/conversation'
  if (grants?.has(permissions.orgAuditRead)) return '/settings/organization'
  if (grants?.has(permissions.scopeRead)) return '/today'
  return '/account'
}

/** Restores registered protected routes plus the proof-free invitation handoff route. */
export function safeLoginDestination(
  query: LocationQuery,
  router: Router,
  principal: { permissions: ReadonlySet<string> } | null | undefined,
): string {
  const fallback = defaultLoginDestination(principal)
  const candidate = query.returnTo
  if (typeof candidate !== 'string' || !isSafeCandidate(candidate)) return fallback
  const resolved = router.resolve(candidate)
  return typeof resolved.name === 'string' && restorableRouteNames.has(resolved.name)
    ? resolved.fullPath
    : fallback
}

function isSafeCandidate(candidate: string): boolean {
  return candidate.length > 0
    && candidate.length <= 2_048
    && candidate.startsWith('/')
    && !candidate.startsWith('//')
    && !candidate.includes('\\')
    && !candidate.includes('#')
    && !/[\u0000-\u001f\u007f]/.test(candidate)
}
