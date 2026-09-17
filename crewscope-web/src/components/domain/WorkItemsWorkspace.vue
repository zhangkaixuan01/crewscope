<script setup lang="ts">
import { Filter, Plus } from '@lucide/vue'
import type { WorkItemPhase } from '../../domains/workitem/store'
import type { WorkItemAvailableTransition, WorkItemStatus, WorkItemSummary } from '../../domains/workitem/types'
import BaseButton from '../base/BaseButton.vue'
import StatusBadge from '../base/StatusBadge.vue'
import StatePanel from '../feedback/StatePanel.vue'
import WorkItemCard from './WorkItemCard.vue'

defineProps<{
  phase: WorkItemPhase
  errorMessage: string | null
  filteredItems: WorkItemSummary[]
  view: 'list' | 'board'
  boardStatuses: WorkItemStatus[]
  statusLabels: Record<string, string>
  nextCursor: string | null
  loadingMore: boolean
  canCreate: boolean
  draggedWorkItem: WorkItemSummary | null
  dragOverStatus: WorkItemStatus | null
  boardAnnouncement: string
  /** The one item currently waiting for a second click. The confirmation is per page, not per card. */
  pendingItemId: string | null
  confirmingTarget: WorkItemStatus | null
  /** The one item with a command in flight; every other card stays executable. */
  busyItemId: string | null
  allowDrop: (status: WorkItemStatus) => boolean
  itemsFor: (status: WorkItemStatus) => WorkItemSummary[]
  /** Offers the batch checkbox on list rows. The board has no selection, so it stays off there. */
  selectable: boolean
  isSelected: (id: string) => boolean
  onCreate: () => void
  onRetry: () => void
  onClearFilters: () => void
  onLoadMore: () => void
  onSelect: (item: WorkItemSummary) => void
  onToggleSelect: (item: WorkItemSummary) => void
  onAction: (item: WorkItemSummary, action: WorkItemAvailableTransition) => void
  onDragStart: (item: WorkItemSummary) => void
  onDragEnd: () => void
  onDragOver: (status: WorkItemStatus) => void
  onDragLeave: () => void
  onDrop: (status: WorkItemStatus) => void
  onBoardKeydown: (event: KeyboardEvent) => void
}>()
</script>

<template>
  <section class="work-content" :class="`work-content--${view}`">
    <StatePanel v-if="phase === 'loading'" state="loading" />
    <StatePanel v-else-if="phase === 'error'" state="error" :description="errorMessage ?? undefined" @retry="onRetry" />
    <StatePanel v-else-if="phase === 'empty'" state="empty" title="当前范围还没有工作项" description="创建第一个 WorkItem，让团队目标进入可追踪的执行流。"><template #action><BaseButton v-if="canCreate" @click="onCreate"><Plus :size="15" />新建工作项</BaseButton></template></StatePanel>
    <StatePanel v-else-if="filteredItems.length === 0" state="empty" title="没有符合筛选条件的工作项" description="调整类型或优先级筛选即可恢复结果。"><template #action><BaseButton variant="secondary" @click="onClearFilters"><Filter :size="15" />清除本地筛选</BaseButton></template></StatePanel>
    <div v-else-if="view === 'list'" class="work-list" aria-label="工作项列表">
      <WorkItemCard v-for="item in filteredItems" :key="item.id" :item="item" layout="list" :confirming-target="pendingItemId === item.id ? confirmingTarget : null" :busy="busyItemId === item.id" :selectable="selectable" :selected="selectable && isSelected(item.id)" @select="onSelect" @action="onAction(item, $event)" @toggle-select="onToggleSelect" />
    </div>
    <div v-else class="work-board" aria-label="工作项看板" @keydown="onBoardKeydown">
      <section v-for="status in boardStatuses" :key="status" class="board-column" :class="{ 'drop-target': dragOverStatus === status, 'drop-rejected': draggedWorkItem && !allowDrop(status) }" :aria-label="statusLabels[status]" @dragover.prevent="onDragOver(status)" @dragleave="onDragLeave" @drop.prevent="onDrop(status)">
        <header><span>{{ statusLabels[status] }}</span><StatusBadge>{{ itemsFor(status).length }}</StatusBadge></header>
        <div class="board-column__items">
          <WorkItemCard v-for="item in itemsFor(status)" :key="item.id" :item="item" layout="board" :confirming-target="pendingItemId === item.id ? confirmingTarget : null" :busy="busyItemId === item.id" @select="onSelect" @action="onAction(item, $event)" @drag-start="onDragStart" @drag-end="onDragEnd" />
          <p v-if="itemsFor(status).length === 0">暂无工作项</p>
        </div>
      </section>
    </div>
    <p class="sr-only" role="status" aria-live="polite">{{ boardAnnouncement }}</p>
    <div v-if="nextCursor" class="load-more">
      <BaseButton variant="secondary" :loading="loadingMore" @click="onLoadMore">加载更多工作项</BaseButton>
      <p v-if="errorMessage" role="alert">{{ errorMessage }}</p>
    </div>
  </section>
</template>

<style scoped>
.work-content { min-width: 0; }.work-content :deep(.state-panel) { border: 1px solid var(--cs-border); border-radius: var(--cs-radius-lg); background: var(--cs-surface); }.work-list { display: grid; gap: var(--cs-space-8); }.work-board { display: grid; grid-auto-columns: minmax(255px, 1fr); grid-auto-flow: column; gap: var(--cs-space-12); overflow-x: auto; padding-bottom: var(--cs-space-8); scroll-snap-type: x proximity; }.board-column { min-height: 390px; overflow: hidden; border: 1px solid var(--cs-border); border-radius: var(--cs-radius-md); background: var(--cs-surface-subtle); scroll-snap-align: start; }.board-column > header { display: flex; min-height: 47px; align-items: center; justify-content: space-between; padding: 0 var(--cs-space-12); border-bottom: 1px solid var(--cs-border); color: var(--cs-text-secondary); font-size: var(--cs-text-sm); font-weight: var(--cs-weight-semibold); }.board-column__items { display: grid; align-content: start; gap: var(--cs-space-8); padding: var(--cs-space-8); }.board-column__items > p { padding: var(--cs-space-24) var(--cs-space-8); color: var(--cs-text-muted); font-size: var(--cs-text-xs); text-align: center; }.load-more { display: grid; justify-items: center; gap: var(--cs-space-8); padding: var(--cs-space-16); }.load-more p { margin: 0; color: var(--cs-danger); font-size: var(--cs-text-sm); }.board-column.drop-target { border-color: var(--cs-border-accent-strong); background: var(--cs-surface-accent); box-shadow: inset 0 0 0 2px var(--cs-ring-brand); }.board-column.drop-rejected { opacity: .62; }
@media (max-width: 767px) { .work-board { grid-auto-columns: minmax(272px, 84vw); } }
</style>
