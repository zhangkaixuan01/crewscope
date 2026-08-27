<script setup lang="ts">
import '../design/tokens.css'
import '../design/base.css'
import ActivityStream from './domain/ActivityStream.vue'
import type { ActivityItem } from '../domains/teamops/types'

const noop = (): void => {}
const items: ActivityItem[] = [
  activity('event-review', 'REVIEW_APPROVED', 'REVIEW', 'APPROVED', 'REVIEW_REQUEST'),
  activity('event-task', 'TASK_COMPLETED', 'EXECUTION', 'COMPLETED', 'WORK_ITEM'),
  activity('event-action', 'ACTION_DISPATCHED', 'DELIVERY', 'SUCCEEDED', 'PLANNED_ACTION'),
]
const base = { phase: 'ready' as const, items, nextCursor: 'older-cursor', loadingMore: false, error: null, online: true, realtimePhase: 'live' as const }

function activity(eventId: string, eventType: string, category: string, outcome: string, referenceType: string): ActivityItem {
  return {
    eventId, domainEventId: `${eventId}-domain`, teamSequence: 1, eventType, category, visibility: 'TEAM',
    subject: { type: referenceType, id: '00000000-0000-0000-0000-000000000501' },
    actor: { type: 'MEMBER', principalId: '00000000-0000-0000-0000-000000000101' },
    references: [{ type: referenceType, id: '00000000-0000-0000-0000-000000000501' }],
    occurredAt: '2026-08-27T08:00:00Z', payload: { schemaName: 'activity-summary', schemaVersion: 1, values: { outcome } },
  }
}
</script>

<template>
  <Story title="M6/Activity Stream" :layout="{ type: 'grid', width: 760 }">
    <Variant title="Live"><ActivityStream v-bind="base" @retry="noop" @load-more="noop" /></Variant>
    <Variant title="Reconnecting"><ActivityStream v-bind="base" realtime-phase="reconnecting" @retry="noop" @load-more="noop" /></Variant>
    <Variant title="WorkItem embedded"><div class="drawer-fixture"><ActivityStream v-bind="base" realtime-phase="idle" compact heading="WorkItem Activity" /></div></Variant>
    <Variant title="Loading"><ActivityStream v-bind="base" phase="loading" :items="[]" realtime-phase="connecting" /></Variant>
    <Variant title="Empty"><ActivityStream v-bind="base" phase="empty" :items="[]" realtime-phase="live" /></Variant>
    <Variant title="Forbidden"><ActivityStream v-bind="base" phase="error" :items="[]" :error="{ kind: 'forbidden', message: 'forbidden', status: 403, retryable: false, currentVersion: null }" realtime-phase="forbidden" /></Variant>
    <Variant title="Offline"><ActivityStream v-bind="base" :online="false" realtime-phase="offline" /></Variant>
    <Variant title="Cursor expired"><ActivityStream v-bind="base" phase="error" :items="[]" :error="{ kind: 'cursor-expired', message: 'expired', status: 410, retryable: true, currentVersion: null }" realtime-phase="cursor-expired" /></Variant>
    <Variant title="Cached hard error"><ActivityStream v-bind="base" phase="error" :error="{ kind: 'unavailable', message: 'Activity 服务暂不可用', status: 503, retryable: true, currentVersion: null }" realtime-phase="error" /></Variant>
  </Story>
</template>

<style scoped>.drawer-fixture { width: 520px; padding: 18px; background: var(--cs-surface); }</style>
