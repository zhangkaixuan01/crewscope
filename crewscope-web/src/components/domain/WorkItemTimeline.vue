<script setup lang="ts">
import { Activity, Bot, CircleCheck, Link2, MessageSquare, UserRound } from '@lucide/vue'
import type { WorkItemPhase } from '../../domains/workitem/store'
import type { WorkItemTimelineEvent } from '../../domains/workitem/types'
import BaseButton from '../base/BaseButton.vue'

defineProps<{
  phase: WorkItemPhase
  events: WorkItemTimelineEvent[]
  nextCursor: string | null
  loadingMore: boolean
  errorMessage: string | null
  onLoadMore: () => Promise<void>
}>()

const eventLabels: Record<string, string> = {
  WORK_ITEM_CREATED: '创建工作项',
  WORK_ITEM_TRANSITIONED: '更新工作项状态',
  WORK_ITEM_STATUS_CHANGED: '更新工作项状态',
  COMMENT_ADDED: '添加评论',
  RESOURCE_LINKED: '关联资源',
  RESPONSIBILITY_ASSIGNED: '分配责任',
  RESPONSIBILITY_RELEASED: '释放责任',
  GATE_REVIEWER_ASSIGNED: '分配 Gate Reviewer',
}

function displayDate(value: string): string {
  return new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value))
}

function eventLabel(event: WorkItemTimelineEvent): string {
  return eventLabels[event.eventType] ?? event.eventType.replaceAll('_', ' ').toLowerCase()
}

function actorName(event: WorkItemTimelineEvent): string {
  return event.actorDisplayName ?? event.actorPrincipalId?.slice(0, 8) ?? 'System'
}
</script>

<template>
  <div class="timeline">
    <p v-if="phase === 'loading' && events.length === 0" class="timeline-state" aria-live="polite">正在加载活动时间线…</p>
    <p v-else-if="phase === 'error' && events.length === 0" class="timeline-state error" role="alert">{{ errorMessage }}</p>
    <p v-else-if="phase === 'empty'" class="timeline-state">还没有可展示的业务活动。</p>
    <ol v-else aria-label="工作项时间线">
      <li v-for="event in events" :key="event.eventId">
        <i :class="{ agent: event.actorType !== 'USER' && event.actorType !== 'SYSTEM' }">
          <MessageSquare v-if="event.eventType.includes('COMMENT')" :size="13" />
          <Link2 v-else-if="event.eventType.includes('RESOURCE')" :size="13" />
          <Bot v-else-if="event.actorType !== 'USER' && event.actorType !== 'SYSTEM'" :size="13" />
          <UserRound v-else-if="event.actorType === 'USER'" :size="13" />
          <Activity v-else :size="13" />
        </i>
        <div><header><strong>{{ eventLabel(event) }}</strong><time>{{ displayDate(event.occurredAt) }}</time></header><p><span>{{ actorName(event) }}</span><span v-if="event.aggregateVersion !== null" class="mono">v{{ event.aggregateVersion }}</span><CircleCheck v-if="event.outcome === 'SUCCEEDED'" :size="11" aria-label="成功" /></p></div>
      </li>
    </ol>
    <p v-if="errorMessage && events.length" class="timeline-more-error" role="alert">{{ errorMessage }}</p>
    <BaseButton v-if="nextCursor" size="small" variant="ghost" :loading="loadingMore" @click="onLoadMore">加载更早活动</BaseButton>
  </div>
</template>

<style scoped>
.timeline { display: grid; }.timeline-state { margin: 0; padding: 10px; border-radius: 8px; background: var(--cs-surface-subtle); color: var(--cs-text-muted); font-size: 9px; }.timeline-state.error, .timeline-more-error { color: var(--cs-danger); }.timeline ol { display: grid; gap: 0; padding: 0; margin: 0; list-style: none; }.timeline li { position: relative; display: grid; grid-template-columns: 28px minmax(0, 1fr); gap: 9px; min-height: 54px; }.timeline li:not(:last-child)::before { position: absolute; top: 27px; bottom: 0; left: 13px; width: 1px; background: var(--cs-border); content: ''; }.timeline li > i { z-index: 1; display: grid; width: 28px; height: 28px; place-items: center; border: 1px solid var(--cs-border); border-radius: 50%; background: var(--cs-brand-50); color: var(--cs-brand-700); font-style: normal; }.timeline li > i.agent { background: var(--cs-agent-soft); color: var(--cs-agent); }.timeline header { display: flex; align-items: baseline; justify-content: space-between; gap: 8px; }.timeline header strong { font-size: 10px; }.timeline time { color: var(--cs-text-muted); font-size: 8px; white-space: nowrap; }.timeline li p { display: flex; align-items: center; gap: 6px; margin: 3px 0 0; color: var(--cs-text-muted); font-size: 8px; }.timeline li p svg { color: var(--cs-success); }.timeline-more-error { margin: 4px 0 8px; font-size: 9px; }.timeline > button { justify-self: center; margin-top: 3px; }
</style>
