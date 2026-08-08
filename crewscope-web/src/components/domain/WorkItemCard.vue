<script setup lang="ts">
import { CalendarClock, CircleAlert, Tag } from '@lucide/vue'
import { computed } from 'vue'
import type { SemanticTone } from '../base/types'
import type { WorkItemSummary } from '../../domains/workitem/types'
import StatusBadge from '../base/StatusBadge.vue'

const props = withDefaults(defineProps<{
  item: WorkItemSummary
  layout?: 'list' | 'board'
}>(), {
  layout: 'list',
})

defineEmits<{ select: [item: WorkItemSummary] }>()

const statusTones: Partial<Record<WorkItemSummary['status'], SemanticTone>> = {
  BLOCKED: 'danger',
  DONE: 'success',
  IN_PROGRESS: 'info',
  IN_REVIEW: 'warning',
  CANCELLED: 'neutral',
  ARCHIVED: 'neutral',
}

const priorityTones: Record<WorkItemSummary['priority'], SemanticTone> = {
  URGENT: 'danger',
  HIGH: 'warning',
  MEDIUM: 'info',
  LOW: 'neutral',
}

const statusTone = computed<SemanticTone>(() => statusTones[props.item.status] ?? 'neutral')
const priorityTone = computed<SemanticTone>(() => priorityTones[props.item.priority])

function displayDueAt(value: string): string {
  return new Intl.DateTimeFormat('zh-CN', { month: 'numeric', day: 'numeric' }).format(new Date(value))
}
</script>

<template>
  <article class="work-item-card" :class="`work-item-card--${layout}`">
    <button type="button" :data-work-item-id="item.id" :aria-label="`打开 ${item.key} ${item.title}`" @click="$emit('select', item)">
      <div class="work-item-card__identity">
        <span class="mono">{{ item.key }}</span>
        <StatusBadge :tone="statusTone" dot>{{ item.status }}</StatusBadge>
      </div>
      <h3>{{ item.title }}</h3>
      <p v-if="item.description">{{ item.description }}</p>
      <div class="work-item-card__metadata">
        <StatusBadge :tone="priorityTone"><CircleAlert :size="11" />{{ item.priority }}</StatusBadge>
        <span>{{ item.type }}</span>
        <span v-if="item.dueAt" :class="{ overdue: new Date(item.dueAt).getTime() < Date.now() }"><CalendarClock :size="12" />{{ displayDueAt(item.dueAt) }}</span>
        <span v-for="label in item.labels.slice(0, layout === 'list' ? 3 : 2)" :key="label"><Tag :size="11" />{{ label }}</span>
        <span v-if="item.labels.length > (layout === 'list' ? 3 : 2)">+{{ item.labels.length - (layout === 'list' ? 3 : 2) }}</span>
      </div>
    </button>
  </article>
</template>

<style scoped>
.work-item-card { min-width: 0; border: 1px solid var(--cs-border); border-radius: var(--cs-radius-md); background: var(--cs-surface); transition: border-color var(--cs-transition-fast), box-shadow var(--cs-transition-fast), transform var(--cs-transition-fast); }
.work-item-card:hover { border-color: var(--cs-brand-300); box-shadow: 0 8px 22px rgb(21 35 29 / 7%); transform: translateY(-1px); }
.work-item-card button { width: 100%; padding: 14px; background: transparent; text-align: left; cursor: pointer; }
.work-item-card__identity { display: flex; align-items: center; justify-content: space-between; gap: 10px; }
.work-item-card__identity > span:first-child { color: var(--cs-brand-700); font-size: 10px; font-weight: 800; }
.work-item-card h3 { margin: 9px 0 6px; overflow: hidden; color: var(--cs-text); font-size: 13px; line-height: 1.35; text-overflow: ellipsis; }
.work-item-card p { display: -webkit-box; overflow: hidden; margin: 0 0 11px; -webkit-box-orient: vertical; -webkit-line-clamp: 2; color: var(--cs-text-muted); font-size: 10px; line-height: 1.5; }
.work-item-card__metadata { display: flex; flex-wrap: wrap; align-items: center; gap: 6px; color: var(--cs-text-muted); }
.work-item-card__metadata > span { display: inline-flex; min-height: 22px; align-items: center; gap: 4px; padding: 2px 6px; border-radius: 6px; background: var(--cs-surface-subtle); font-size: 9px; white-space: nowrap; }
.work-item-card__metadata > span.overdue { background: var(--cs-danger-soft); color: var(--cs-danger); }
.work-item-card--list button { display: grid; grid-template-columns: minmax(175px, .55fr) minmax(240px, 1.3fr) minmax(300px, 1fr); align-items: center; gap: 15px; padding: 12px 15px; }
.work-item-card--list .work-item-card__identity { justify-content: flex-start; }
.work-item-card--list h3 { margin: 0; }
.work-item-card--list p { display: none; }
.work-item-card--list .work-item-card__metadata { justify-content: flex-end; }
@media (max-width: 900px) { .work-item-card--list button { grid-template-columns: 1fr; gap: 8px; }.work-item-card--list .work-item-card__metadata { justify-content: flex-start; } }
@media (max-width: 767px) { .work-item-card button { padding: 12px; }.work-item-card--list button { gap: 7px; }.work-item-card h3 { font-size: 12px; } }
</style>
