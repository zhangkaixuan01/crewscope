<script setup lang="ts">
import { CircleSlash, GripVertical } from '@lucide/vue'
import { computed } from 'vue'
import type { SemanticTone } from '../base/types'
import type { WorkDeskItem } from '../../domains/workdesk/types'
import type { WorkItemAvailableTransition, WorkItemStatus } from '../../domains/workitem/types'
import { workDeskStatusLabel } from '../../domains/workdesk/labels'
import { formatAbsoluteTime, formatRelativeTime } from '../../composables/formatRelativeTime'
import BaseTooltip from '../base/BaseTooltip.vue'
import StatusBadge from '../base/StatusBadge.vue'
import StatusTransitionMenu from '../action/StatusTransitionMenu.vue'

/**
 * One row of the home board.
 *
 * It is not `WorkItemCard`. That card renders a `WorkItemSummary` — key, type, priority, labels, due
 * date — and a WorkDesk row carries none of those: it is a cross-aggregate feed row whose status is a
 * plain string because five different aggregates produce it. Mapping one onto the other would mean
 * inventing a key, a priority and a type, and the card would then state facts nobody sent.
 *
 * The status badge is the action entry wherever the projection offered an action, and a plain chip
 * where it did not. A menu that opens only to say "nothing to do" costs a click and returns nothing;
 * the work board renders it unconditionally because every WorkItem there is actionable by
 * construction, which is not true of a personal feed.
 */
const props = withDefaults(defineProps<{
  item: WorkDeskItem
  /** How the row names itself; the page resolves the fallback for an untitled row. */
  title: string
  /** The WorkProject this row belongs to, resolved by the page from the loaded collection. */
  projectLabel: string | null
  /** The responsibility role in the member's own language. */
  roleLabel: string
  /** Board cards state their role; grouped-by-role columns already do. */
  showRole?: boolean
  /** False while the board's columns mean something other than the row's status. */
  draggable?: boolean
  /** The action awaiting a second click, when this card asked an irreversible one to confirm. */
  confirmingTarget?: WorkItemStatus | null
  /** A command is in flight for this row. */
  busy?: boolean
}>(), { showRole: true, draggable: false, confirmingTarget: null, busy: false })

const emit = defineEmits<{
  open: []
  action: [action: WorkItemAvailableTransition]
  'drag-start': []
  'drag-end': []
}>()

const statusText = computed(() => workDeskStatusLabel(props.item))
const statusTone = computed<SemanticTone>(() => {
  if (props.item.status === 'DONE' || props.item.status === 'COMPLETED') return 'success'
  if (props.item.status === 'BLOCKED' || props.item.status === 'CANCELLED') return 'danger'
  if (props.item.status === 'IN_PROGRESS') return 'info'
  if (props.item.status === 'IN_REVIEW' || props.item.status === 'WAITING') return 'warning'
  return 'neutral'
})
/** Only a WorkItem has a status machine to move through; the other four row kinds are read-only. */
const transitions = computed<readonly WorkItemAvailableTransition[]>(() => (
  props.item.objectType === 'WORK_ITEM' ? props.item.availableActions : []
))
const isBlocked = computed(() => props.item.status === 'BLOCKED')
const updatedAt = computed(() => new Date(props.item.updatedAt))

/**
 * Forwards a click on the card body to the open button.
 *
 * The action entry and the open button each handle their own clicks; anything else on the card
 * belongs to the open affordance, so pressing a label or the progress bar opens the row instead of
 * doing nothing.
 */
function openFromCard(event: MouseEvent): void {
  if (event.target instanceof Element && event.target.closest('button, a')) return
  emit('open')
}
</script>

<template>
  <article
    class="desk-card"
    :class="{ 'desk-card--blocked': isBlocked }"
    :data-desk-item-id="item.objectId"
    :draggable="draggable"
    @click="openFromCard"
    @dragstart="draggable && emit('drag-start')"
    @dragend="draggable && emit('drag-end')"
  >
    <header class="desk-card__head">
      <GripVertical v-if="draggable" class="desk-card__grip" :size="13" aria-hidden="true" />
      <span v-if="showRole" class="desk-card__role">{{ roleLabel }}</span>
      <StatusTransitionMenu
        v-if="transitions.length"
        compact
        :tone="statusTone"
        dot
        :actions="transitions"
        :confirming-target="confirmingTarget"
        :busy="busy"
        @select="emit('action', $event)"
      >{{ statusText }}</StatusTransitionMenu>
      <StatusBadge v-else :tone="statusTone" dot>{{ statusText }}</StatusBadge>
    </header>

    <button type="button" class="desk-card__open" :aria-label="`打开 ${title}`" @click="emit('open')">
      <h3>{{ title }}</h3>
      <small v-if="projectLabel" class="desk-card__project">{{ projectLabel }}</small>
    </button>

    <div v-if="item.progress !== null" class="desk-card__progress" :aria-label="`进度 ${item.progress}%`" role="img">
      <i :style="{ width: `${item.progress}%` }" />
      <small>{{ item.progress }}%</small>
    </div>

    <footer class="desk-card__foot">
      <BaseTooltip :text="formatAbsoluteTime(updatedAt)">
        <span :class="{ 'desk-card__stale': item.needsAction }">最近更新 {{ formatRelativeTime(updatedAt) }}</span>
      </BaseTooltip>
      <span v-if="isBlocked" class="desk-card__blocked"><CircleSlash :size="12" aria-hidden="true" />已阻塞</span>
    </footer>
  </article>
</template>

<style scoped>
.desk-card { display: grid; align-content: start; gap: var(--cs-space-8); padding: var(--cs-space-12); border: 1px solid var(--cs-border); border-radius: var(--cs-radius-md); background: var(--cs-surface); box-shadow: var(--cs-shadow-hairline); cursor: pointer; transition: border-color var(--cs-motion-fast) var(--cs-ease-out), transform var(--cs-motion-fast) var(--cs-ease-out); }
.desk-card:hover { border-color: var(--cs-border-accent-strong); transform: translateY(-1px); }
/* A blocked row is a fact about the work, not a decoration: the whole card leans on the danger
   border so a column of them reads as a problem before any label is parsed. */
.desk-card--blocked { border-color: var(--cs-danger); }
.desk-card__head { display: flex; align-items: center; gap: var(--cs-space-8); }
.desk-card__grip { flex: 0 0 auto; color: var(--cs-text-muted); }
.desk-card__role { flex: 1; overflow: hidden; color: var(--cs-text-muted); font-size: var(--cs-text-xs); text-overflow: ellipsis; white-space: nowrap; }
.desk-card__open { display: block; width: 100%; padding: 0; border: 0; background: transparent; color: inherit; text-align: left; cursor: pointer; }
.desk-card__open h3 { margin: 0; font-size: var(--cs-text-sm); font-weight: var(--cs-weight-semibold); }
.desk-card__project { display: block; margin-top: var(--cs-space-2); color: var(--cs-text-muted); font-size: var(--cs-text-xs); }
.desk-card__progress { display: flex; align-items: center; gap: var(--cs-space-8); }
.desk-card__progress i { display: block; height: 4px; border-radius: 99px; background: var(--cs-brand-600); }
.desk-card__progress::before { flex: 1; height: 4px; border-radius: 99px; background: var(--cs-surface-accent-strong); content: ''; }
.desk-card__progress small { flex: 0 0 auto; color: var(--cs-text-muted); font-size: var(--cs-text-xs); }
.desk-card__foot { display: flex; align-items: center; justify-content: space-between; gap: var(--cs-space-8); color: var(--cs-text-muted); font-size: var(--cs-text-xs); }
.desk-card__stale { color: var(--cs-warning); }
.desk-card__blocked { display: inline-flex; align-items: center; gap: var(--cs-space-4); color: var(--cs-danger); font-weight: var(--cs-weight-semibold); }
</style>
