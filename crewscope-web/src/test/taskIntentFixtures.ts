import type { TaskIntent, TaskIntentConfirmationPreview } from '../domains/conversation/types'
import { conversationIds } from './conversationFixtures'
import { fixtureIds } from './scopeFixtures'

export const taskIntentIds = {
  release: '74000000-0000-4000-8000-000000000001',
  ownerMember: '74000000-0000-4000-8000-000000000002',
  project: '74000000-0000-4000-8000-000000000003',
}

export function fixtureTaskIntent(overrides: Partial<TaskIntent> = {}): TaskIntent {
  return {
    id: taskIntentIds.release,
    conversationId: conversationIds.provider,
    proposedByPrincipalId: fixtureIds.secondPrincipal,
    schemaVersion: 1,
    proposalRevision: 1,
    status: 'READY',
    version: 2,
    proposal: {
      workProjectId: taskIntentIds.project,
      objective: '完成 GitHub Provider 接入并验证团队协作流程',
      acceptanceCriteria: ['能够读取仓库元数据', '关键操作进入审计记录'],
      owner: { role: 'OWNER', principalId: fixtureIds.principal, principalType: 'HUMAN', teamMemberId: taskIntentIds.ownerMember },
      executor: null,
      gateReviewer: null,
    },
    decision: null,
    createdAt: '2026-08-11T00:00:00Z',
    updatedAt: '2026-08-11T00:00:00Z',
    ...overrides,
  }
}

export function fixtureConfirmationPreview(intent = fixtureTaskIntent()): TaskIntentConfirmationPreview {
  return {
    confirmable: true,
    taskIntentId: intent.id,
    proposalRevision: intent.proposalRevision,
    version: intent.version,
    confirmingPrincipalId: intent.proposal.owner.principalId,
    proposal: structuredClone(intent.proposal),
  }
}
