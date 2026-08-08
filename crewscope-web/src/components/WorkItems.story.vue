<script setup lang="ts">
import '../design/tokens.css'
import '../design/base.css'
import WorkItemCard from './domain/WorkItemCard.vue'
import WorkItemDetailDrawer from './domain/WorkItemDetailDrawer.vue'
import type { WorkItemDetails, WorkItemSummary } from '../domains/workitem/types'
import { demoResponsibilities } from '../domains/demo/fixtures'

const item: WorkItemSummary = {
  id: '00000000-0000-0000-0000-000000000601',
  organizationId: '00000000-0000-0000-0000-000000000001',
  teamId: '00000000-0000-0000-0000-000000000201',
  workspaceId: '00000000-0000-0000-0000-000000000501',
  projectId: '00000000-0000-0000-0000-000000000401',
  key: 'CRW-18',
  type: 'FEATURE',
  title: '建立团队 WorkItem 看板与 URL 状态恢复',
  description: '让团队成员共享同一个项目范围、筛选条件与可复制的工作视图。',
  status: 'IN_PROGRESS',
  priority: 'HIGH',
  labels: ['frontend', 'team-work'],
  dueAt: '2026-08-18T10:00:00Z',
  source: 'CREWSCOPE',
  sourceReference: null,
  version: 1,
  createdAt: '2026-08-08T01:00:00Z',
  createdByPrincipalId: '00000000-0000-0000-0000-000000000101',
  updatedAt: '2026-08-08T02:00:00Z',
  updatedByPrincipalId: '00000000-0000-0000-0000-000000000101',
}

const details: WorkItemDetails = {
  workItem: item,
  comments: [{ id: 'comment-1', workItemId: item.id, authorPrincipalId: item.createdByPrincipalId!, content: '已确认范围，准备进入 Review。', source: 'CREWSCOPE', externalId: null, createdAt: '2026-08-08T03:00:00Z' }],
  resourceLinks: [{ id: 'resource-1', workItemId: item.id, resourceType: 'REPOSITORY', resourceReference: 'crewscope-java', label: '主仓库', createdAt: '2026-08-08T03:10:00Z', createdByPrincipalId: item.createdByPrincipalId }],
}

const resolved = async (): Promise<void> => {}
const timeline = [{ eventId: 'event-1', domainEventId: 'event-1', source: 'DOMAIN_EVENT', eventType: 'RESPONSIBILITY_ASSIGNED', schemaVersion: '1', aggregateType: 'WorkItem', aggregateId: item.id, aggregateVersion: 1, actorType: 'USER', actorPrincipalId: item.createdByPrincipalId, actorDisplayName: '张凯旋', correlationId: 'correlation-1', causationId: null, occurredAt: '2026-08-08T03:20:00Z', outcome: 'SUCCEEDED', payload: {} }]
</script>

<template>
  <Story title="Domain/WorkItem card">
    <Variant title="List"><div class="story-list"><WorkItemCard :item="item" layout="list" /></div></Variant>
    <Variant title="Board"><div class="story-board"><WorkItemCard :item="item" layout="board" /></div></Variant>
    <Variant title="Detail drawer"><WorkItemDetailDrawer phase="ready" :details="details" :error-message="null" :command-pending="null" :command-error-message="null" :version-conflict="null" can-participate can-manage-responsibility responsibility-phase="ready" :responsibilities="demoResponsibilities" :responsibility-candidates="[{ principalId: item.createdByPrincipalId!, displayName: '张凯旋' }]" :responsibility-error-message="null" :responsibility-command-pending="null" :responsibility-command-error-message="null" timeline-phase="ready" :timeline="timeline" :timeline-next-cursor="null" :timeline-loading-more="false" :timeline-error-message="null" :on-retry="() => {}" :on-transition="resolved" :on-add-comment="resolved" :on-link-resource="resolved" :on-replace-owner="resolved" :on-assign-executor="resolved" :on-assign-gate-reviewer="resolved" :on-assign-advisory-reviewer="resolved" :on-release-responsibility="resolved" :on-load-timeline-more="resolved" /></Variant>
  </Story>
</template>

<style scoped>
.story-list, .story-board { min-height: 320px; padding: 28px; background: var(--cs-canvas); font-family: var(--cs-font-sans); }.story-board > * { width: 290px; }
</style>
