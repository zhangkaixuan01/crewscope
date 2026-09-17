import type {
  AgentBindingSource,
  AgentExecutionScope,
  AgentOwnershipType,
  AgentRuntimeRole,
  AgentStatus,
} from './types'

/**
 * User-facing text for the Agent profile enums.
 *
 * Ownership and runtime role were previously restated inline in three components with three
 * different wordings for the same constant, so they live here once: an Agent's ownership decides
 * who may reconfigure it and its runtime role decides what it may be delegated, and both are read
 * by members as identity, not as backend detail.
 */
export const agentOwnershipTypeLabels: Record<AgentOwnershipType, string> = {
  USER: '个人',
  TEAM: '团队',
  ORGANIZATION: '组织',
}

export const agentRuntimeRoleLabels: Record<AgentRuntimeRole, string> = {
  PERSONAL_ASSISTANT: '个人助理',
  TEAM_COORDINATOR: '团队协调者',
  SPECIALIST: 'Specialist',
}

/** `AgentProfileStatus` / `AgentTemplateStatus`, which share their three constants. */
export const agentStatusLabels: Record<AgentStatus, string> = {
  ACTIVE: '启用',
  DISABLED: '已禁用',
  ARCHIVED: '已归档',
}

/** Mirrors `AgentExecutionScope`: how far an execution may reach, not who owns the profile. */
export const agentExecutionScopeLabels: Record<AgentExecutionScope, string> = {
  PERSONAL: '个人范围',
  TEAM: '团队范围',
}

/** Which layer supplied the configuration a Preflight resolved, from most to least specific. */
export const agentBindingSourceLabels: Record<AgentBindingSource, string> = {
  DIRECT: 'Agent 直接配置',
  TEAM_DEFAULT: '继承 Team 默认',
  ORGANIZATION_DEFAULT: '继承 Organization 默认',
}

/** `{displayName} · 个人 Specialist` — the one-line identity used in every Agent picker. */
export function agentIdentityLabel(agent: {
  displayName: string
  ownershipType: string
  runtimeRole: string
}): string {
  const ownership = agentOwnershipTypeLabels[agent.ownershipType as AgentOwnershipType] ?? agent.ownershipType
  const role = agentRuntimeRoleLabels[agent.runtimeRole as AgentRuntimeRole] ?? agent.runtimeRole
  return `${agent.displayName} · ${ownership}${role}`
}
