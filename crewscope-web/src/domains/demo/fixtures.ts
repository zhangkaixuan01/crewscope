import type { ResponsibilityAssignment } from '../workitem/types'

export const demoResponsibilities: ResponsibilityAssignment[] = [
  responsibility('demo-owner', 'OWNER', 'USER', '张凯旋'),
  responsibility('demo-executor', 'EXECUTOR', 'TEAM_AGENT', 'Coding Agent'),
  responsibility('demo-reviewer', 'REVIEWER', 'USER', '林晨'),
]

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
