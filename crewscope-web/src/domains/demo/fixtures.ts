import type { ResponsibilityAssignment } from '../workitem/types'

export interface AgentSnapshot {
  name: string
  type: string
  status: 'running' | 'waiting' | 'offline'
  step: string
  runtime: string
}

export const demoResponsibilities: ResponsibilityAssignment[] = [
  responsibility('demo-owner', 'OWNER', 'USER', '张凯旋'),
  responsibility('demo-executor', 'EXECUTOR', 'TEAM_AGENT', 'Coding Agent'),
  responsibility('demo-reviewer', 'REVIEWER', 'USER', '林晨'),
]

export const demoAgent: AgentSnapshot = {
  name: 'Coding Agent',
  type: 'Specialist Agent',
  status: 'running',
  step: '运行领域测试并整理变更证据',
  runtime: 'AgentScope 2.0 · Docker Sandbox',
}

function responsibility(
  id: string,
  role: ResponsibilityAssignment['role'],
  actorType: string,
  actorDisplayName: string,
): ResponsibilityAssignment {
  return {
    id,
    workItemId: 'demo-work-item',
    role,
    actorPrincipalId: `${id}-principal`,
    actorType,
    actorMemberId: actorType === 'USER' ? `${id}-member` : null,
    actorDisplayName,
    actorAgentProfileId: null,
    status: 'ACTIVE',
    assignedByPrincipalId: 'demo-owner-principal',
    assignedAt: '2026-08-08T00:00:00Z',
    acceptedAt: '2026-08-08T00:00:00Z',
    version: 0,
  }
}
