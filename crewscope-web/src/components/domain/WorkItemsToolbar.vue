<script setup lang="ts">
import { ArrowDown, ArrowUp, Columns3, List, Plus } from '@lucide/vue'
import { computed } from 'vue'
import type { SortDirection } from '../../composables/useListSort'
import { unsortableWorkItemField, workItemSortOptions, type WorkItemSortKey } from '../../domains/workitem/list'
import BaseButton from '../base/BaseButton.vue'
import BaseTooltip from '../base/BaseTooltip.vue'

const props = defineProps<{
  teamName: string | undefined
  projectKey: string
  resultCount: number
  view: 'list' | 'board'
  status: string
  type: string
  priority: string
  statuses: readonly string[]
  types: readonly string[]
  priorities: readonly string[]
  statusLabels: Record<string, string>
  typeLabels: Record<string, string>
  priorityLabels: Record<string, string>
  canCreate: boolean
  sortKey: WorkItemSortKey
  sortDirection: SortDirection
  /** How many rows the sort control orders, and whether the collection holds more than that. */
  loadedCount: number
  hasMore: boolean
}>()

const emit = defineEmits<{
  updateQuery: [name: 'view' | 'status' | 'type' | 'priority', value: string]
  sort: [key: WorkItemSortKey]
  create: []
}>()

// URL persistence and filter validation stay in WorkPage; the toolbar remains a
// presentational boundary so it can be reused by list and board shells.

/**
 * What the sort control is ordering, said out loud.
 *
 * The collection is cursor-paginated and the server lists it by updated time; ordering by anything
 * else can only be done to the rows already in hand. Saying which set the control acts on is not a
 * disclaimer — it is the difference between "the most urgent work in this project" and "the most
 * urgent work on this page", and a member planning their day needs to know which one they are
 * looking at.
 */
const sortScopeHint = computed(() => props.hasMore
  ? `排序作用于已加载的 ${props.loadedCount} 项，还有更多未加载`
  : `排序作用于当前 ${props.loadedCount} 项`)

function sortLabel(key: WorkItemSortKey, label: string): string {
  if (key !== props.sortKey) return `按${label}排序`
  return `按${label}排序（当前${props.sortDirection === 'asc' ? '升序' : '降序'}），再次点击切换方向`
}
</script>

<template>
  <section class="work-toolbar panel">
    <div class="work-toolbar__scope">
      <div class="work-toolbar__headline">
        <p class="eyebrow">{{ teamName }} · {{ projectKey }}</p>
        <h2>团队工作项</h2>
        <span>{{ resultCount }} 项当前结果</span>
      </div>
      <BaseButton v-if="canCreate" class="create-work-item" size="small" @click="emit('create')"><Plus :size="14" />新建工作项</BaseButton>
    </div>
    <div class="filters" aria-label="工作项筛选">
      <label><span>状态</span><select :value="status" @change="emit('updateQuery', 'status', ($event.target as HTMLSelectElement).value)"><option value="all">全部状态</option><option v-for="item in statuses" :key="item" :value="item">{{ statusLabels[item] }}</option></select></label>
      <label><span>类型</span><select :value="type" @change="emit('updateQuery', 'type', ($event.target as HTMLSelectElement).value)"><option value="all">全部类型</option><option v-for="item in types" :key="item" :value="item">{{ typeLabels[item] }}</option></select></label>
      <label><span>优先级</span><select :value="priority" @change="emit('updateQuery', 'priority', ($event.target as HTMLSelectElement).value)"><option value="all">全部优先级</option><option v-for="item in priorities" :key="item" :value="item">{{ priorityLabels[item] }}</option></select></label>
    </div>
    <div class="view-switcher" aria-label="工作项视图">
      <button type="button" :class="{ active: view === 'list' }" aria-label="列表视图" @click="emit('updateQuery', 'view', 'list')"><List :size="15" />List</button>
      <button type="button" :class="{ active: view === 'board' }" aria-label="看板视图" @click="emit('updateQuery', 'view', 'board')"><Columns3 :size="15" />Board</button>
    </div>
    <div class="sort-control" role="group" aria-label="排序">
      <span class="sort-control__label">排序</span>
      <BaseButton
        v-for="option in workItemSortOptions"
        :key="option.key"
        size="small"
        variant="ghost"
        :class="{ 'sort-control__active': option.key === sortKey }"
        :aria-pressed="option.key === sortKey"
        :aria-label="sortLabel(option.key, option.label)"
        @click="emit('sort', option.key)"
      >
        {{ option.label }}<ArrowUp v-if="option.key === sortKey && sortDirection === 'asc'" :size="13" /><ArrowDown v-if="option.key === sortKey && sortDirection === 'desc'" :size="13" />
      </BaseButton>
      <!-- The one field this list cannot be ordered by, and the reason it cannot: named, so its
           absence reads as a boundary rather than as a missing feature. -->
      <BaseTooltip :text="unsortableWorkItemField.hint">
        <BaseButton size="small" variant="ghost" disabled>{{ unsortableWorkItemField.label }}</BaseButton>
      </BaseTooltip>
      <span class="sort-control__scope">{{ sortScopeHint }}</span>
    </div>
  </section>
</template>

<style scoped>
.work-toolbar { display: grid; grid-template-columns: minmax(190px, .65fr) minmax(430px, 1.5fr) auto; align-items: end; gap: var(--cs-space-20); padding: var(--cs-space-16) var(--cs-space-20); }.work-toolbar__scope { display: flex; align-items: flex-end; justify-content: space-between; gap: var(--cs-space-12); }.work-toolbar__headline { min-width: 0; }.work-toolbar__scope h2 { margin: 0; font-size: var(--cs-text-lg); }.work-toolbar__scope > span { color: var(--cs-text-muted); font-size: var(--cs-text-xs); }.create-work-item { flex: 0 0 auto; }.filters { display: grid; grid-template-columns: repeat(3, minmax(110px, 1fr)); gap: var(--cs-space-8); }.filters label { display: grid; gap: var(--cs-space-4); color: var(--cs-text-secondary); font-size: var(--cs-text-xs); font-weight: var(--cs-weight-semibold); }.filters select { width: 100%; min-height: var(--cs-density-control-height); padding: 0 var(--cs-space-8); border: 1px solid var(--cs-border-strong); border-radius: var(--cs-radius-sm); background: var(--cs-surface-subtle); color: var(--cs-text); font: var(--cs-text-base) var(--cs-font-sans); }.view-switcher { display: flex; gap: var(--cs-space-4); padding: var(--cs-space-4); border: 1px solid var(--cs-border); border-radius: 9px; background: var(--cs-surface-subtle); }.view-switcher button { display: flex; min-height: var(--cs-density-control-height); align-items: center; gap: var(--cs-space-4); padding: 0 var(--cs-space-8); border-radius: 6px; background: transparent; color: var(--cs-text-muted); font-size: var(--cs-text-sm); cursor: pointer; }.view-switcher button.active { background: var(--cs-surface); box-shadow: var(--cs-shadow-hairline); color: var(--cs-text-brand); font-weight: var(--cs-weight-semibold); }
/*
 * The sort control spans the panel rather than sitting in a column: it is the one control whose
 * meaning depends on what the other two rows selected, and the hint under it is part of the control.
 */
.sort-control { display: flex; grid-column: 1 / -1; flex-wrap: wrap; align-items: center; gap: var(--cs-space-4); padding-top: var(--cs-space-8); border-top: 1px solid var(--cs-border); }
.sort-control__label { margin-right: var(--cs-space-4); color: var(--cs-text-secondary); font-size: var(--cs-text-xs); font-weight: var(--cs-weight-semibold); }
.sort-control__active { background: var(--cs-surface); box-shadow: var(--cs-shadow-hairline); color: var(--cs-text-brand); font-weight: var(--cs-weight-semibold); }
.sort-control__scope { margin-left: auto; color: var(--cs-text-muted); font-size: var(--cs-text-xs); }
@media (max-width: 1050px) { .work-toolbar { grid-template-columns: 1fr auto; }.filters { grid-column: 1 / -1; grid-row: 2; }.view-switcher { grid-column: 2; grid-row: 1; } }
@media (max-width: 767px) { .work-toolbar { grid-template-columns: 1fr; align-items: stretch; gap: var(--cs-space-12); padding: var(--cs-space-16); }.filters { grid-column: 1; grid-template-columns: 1fr 1fr; }.filters label:first-child { grid-column: 1 / -1; }.view-switcher { grid-column: 1; grid-row: auto; }.view-switcher button { flex: 1; justify-content: center; }.sort-control__scope { margin-left: 0; } }
</style>
