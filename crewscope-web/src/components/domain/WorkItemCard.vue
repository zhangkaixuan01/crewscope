<script setup lang="ts">
import { CalendarClock, CircleAlert, Tag } from '@lucide/vue'
import { computed } from 'vue'
import type { SemanticTone } from '../base/types'
import type { WorkItemAvailableTransition, WorkItemStatus, WorkItemSummary } from '../../domains/workitem/types'
import { workItemPriorityLabels, workItemStatusLabels, workItemTypeLabels } from '../../domains/workitem/labels'
import { formatAbsoluteTime, formatRelativeTime } from '../../composables/useRelativeTime'
import BaseTooltip from '../base/BaseTooltip.vue'
import StatusBadge from '../base/StatusBadge.vue'
import StatusTransitionMenu from '../action/StatusTransitionMenu.vue'

/**
 * One WorkItem as a card, in either the list or the board layout.
 *
 * The card is deliberately *not* one big button any more. A button cannot contain the action entry —
 * nested interactive elements are invalid HTML, and a browser that renders them anyway gives the
 * keyboard one stop for two controls — so the card became a container with an open button and an
 * action trigger inside it: the status badge, which is the same control the drawer and the home page
 * use. Clicking anywhere else still opens the item, because the container forwards the click.
 */
const props = withDefaults(defineProps<{
  item: WorkItemSummary
  layout?: 'list' | 'board'
  /** The action awaiting a second click, when the page asked an irreversible one to confirm. */
  confirmingTarget?: WorkItemStatus | null
  /** A command is in flight for this item. */
  busy?: boolean
  /** Offers the row's batch checkbox. The board has no selection, so it is never set there. */
  selectable?: boolean
  selected?: boolean
}>(), {
  layout: 'list',
  confirmingTarget: null,
  busy: false,
  selectable: false,
  selected: false,
})

const emit = defineEmits<{
  select: [item: WorkItemSummary]
  action: [action: WorkItemAvailableTransition]
  'toggle-select': [item: WorkItemSummary]
  'drag-start': [item: WorkItemSummary]
  'drag-end': []
}>()

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
const labelBudget = computed(() => (props.layout === 'list' ? 3 : 2))
const overdue = computed(() => Boolean(props.item.dueAt && new Date(props.item.dueAt).getTime() < Date.now()))

/**
 * Forwards a click on the card body to the open button.
 *
 * The action trigger, the remedy link inside its menu and the open button itself are all interactive
 * and handle their own clicks; anything else on the card belongs to the open affordance, so a member
 * who clicks a label chip gets the item rather than nothing.
 */
function openFromCard(event: MouseEvent): void {
  // The checkbox is interactive too: without it here, ticking a row would also open the drawer and
  // the member would lose the list they were working through.
  if (event.target instanceof Element && event.target.closest('button, a, input')) return
  emit('select', props.item)
}
</script>

<template>
  <article
    class="work-item-card"
    :class="`work-item-card--${layout}`"
    :draggable="layout === 'board'"
    @click="openFromCard"
    @dragstart="layout === 'board' && emit('drag-start', item)"
    @dragend="layout === 'board' && emit('drag-end')"
  >
    <div class="work-item-card__identity">
      <input
        v-if="selectable && layout === 'list'"
        type="checkbox"
        class="work-item-card__select"
        :checked="selected"
        :aria-label="`选择 ${item.key} ${item.title}`"
        @change="emit('toggle-select', item)"
      >
      <span class="mono">{{ item.key }}</span>
      <StatusTransitionMenu
        :tone="statusTone"
        dot
        compact
        :actions="item.availableActions"
        :confirming-target="confirmingTarget"
        :busy="busy"
        @select="emit('action', $event)"
      >{{ workItemStatusLabels[item.status] }}</StatusTransitionMenu>
    </div>
    <button
      type="button"
      class="work-item-card__open"
      :data-work-item-id="item.id"
      :aria-label="`打开 ${item.key} ${item.title}`"
      @click="emit('select', item)"
    >
      <h3>{{ item.title }}</h3>
      <p v-if="item.description">{{ item.description }}</p>
    </button>
    <div class="work-item-card__metadata">
      <StatusBadge :tone="priorityTone"><CircleAlert :size="11" />{{ workItemPriorityLabels[item.priority] }}</StatusBadge>
      <span>{{ workItemTypeLabels[item.type] }}</span>
      <!-- Every time on the card is relative with the exact instant one keystroke away: a list of
           "2026/09/12 14:03:27" makes the member do the subtraction the page already knows how to
           do, and an absolute date with no time cannot be compared to now at all. -->
      <BaseTooltip v-if="item.dueAt" :text="`截止 ${formatAbsoluteTime(item.dueAt)}`" :class="{ overdue }">
        <span><CalendarClock :size="12" />截止 {{ formatRelativeTime(item.dueAt) }}</span>
      </BaseTooltip>
      <BaseTooltip :text="`更新于 ${formatAbsoluteTime(item.updatedAt)}`">
        <time :datetime="item.updatedAt">更新于 {{ formatRelativeTime(item.updatedAt) }}</time>
      </BaseTooltip>
      <span v-for="label in item.labels.slice(0, labelBudget)" :key="label"><Tag :size="11" />{{ label }}</span>
      <span v-if="item.labels.length > labelBudget">+{{ item.labels.length - labelBudget }}</span>
    </div>
  </article>
</template>

<style scoped>
.work-item-card { min-width: 0; border: 1px solid var(--cs-border); border-radius: var(--cs-radius-md); background: var(--cs-surface); transition: border-color var(--cs-motion-fast) var(--cs-ease-out), box-shadow var(--cs-motion-fast) var(--cs-ease-out), transform var(--cs-motion-fast) var(--cs-ease-out); }
.work-item-card--board { display: grid; gap: var(--cs-space-8); padding: var(--cs-space-16); cursor: grab; }.work-item-card--board:active { cursor: grabbing; }
.work-item-card:hover { border-color: var(--cs-border-accent-strong); box-shadow: var(--cs-shadow-raised); transform: translateY(-1px); }
.work-item-card__open { display: block; width: 100%; padding: 0; background: transparent; color: inherit; text-align: left; cursor: pointer; }
.work-item-card__identity { display: flex; align-items: center; justify-content: space-between; gap: var(--cs-space-12); }
.work-item-card__identity > span:first-child { color: var(--cs-text-brand); font-size: var(--cs-text-sm); font-weight: var(--cs-weight-semibold); }
.work-item-card h3 { margin: var(--cs-space-8) 0 var(--cs-space-8); overflow: hidden; color: var(--cs-text); font-size: var(--cs-text-base); line-height: var(--cs-leading-tight); text-overflow: ellipsis; }
.work-item-card p { display: -webkit-box; overflow: hidden; margin: 0 0 var(--cs-space-12); -webkit-box-orient: vertical; -webkit-line-clamp: 2; color: var(--cs-text-muted); font-size: var(--cs-text-sm); line-height: var(--cs-leading-normal); }
.work-item-card__metadata { display: flex; flex-wrap: wrap; align-items: center; gap: var(--cs-space-8); color: var(--cs-text-muted); }
.work-item-card__metadata > span { display: inline-flex; min-height: 22px; align-items: center; gap: var(--cs-space-4); padding: var(--cs-space-2) var(--cs-space-8); border-radius: 6px; background: var(--cs-surface-subtle); font-size: var(--cs-text-xs); white-space: nowrap; }
.work-item-card__metadata > span.overdue { background: var(--cs-danger-soft); color: var(--cs-danger); }
/* The selection checkbox belongs to the row, not to the identity cell's content: it keeps the key
   and the status badge on the same 12px rhythm whether or not selection is on offer. */
.work-item-card__select { flex: 0 0 auto; width: 16px; height: 16px; margin: 0 var(--cs-space-4) 0 0; accent-color: var(--cs-focus); cursor: pointer; }
/*
 * List layout is the same three columns it always was, with the identity cell now owning the action
 * trigger rather than the whole row owning every pixel. Keeping the tracks here means the columns of
 * every row still line up, whether or not a row has an executable action.
 */
.work-item-card--list { display: grid; grid-template-columns: minmax(175px, .55fr) minmax(240px, 1.3fr) minmax(300px, 1fr); align-items: center; gap: var(--cs-space-16); padding: var(--cs-space-12) var(--cs-space-16); }
.work-item-card--list .work-item-card__identity { justify-content: flex-start; }
.work-item-card--list h3 { display: -webkit-box; margin: 0; -webkit-box-orient: vertical; -webkit-line-clamp: 1; }
.work-item-card--list p { display: none; }
.work-item-card--list .work-item-card__metadata { justify-content: flex-end; }
@media (max-width: 900px) { .work-item-card--list { grid-template-columns: 1fr; gap: var(--cs-space-8); }.work-item-card--list .work-item-card__metadata { justify-content: flex-start; } }
@media (max-width: 767px) { .work-item-card--board { padding: var(--cs-space-12); }.work-item-card--list { gap: var(--cs-space-8); } }
</style>
