<script setup lang="ts">
import { ArrowDown, ArrowUp, Columns3, List, Plus, SlidersHorizontal } from '@lucide/vue'
import { computed, ref } from 'vue'
import {
  unsortableWorkItemField,
  workItemSortDirectionLabels,
  workItemSortDirections,
  workItemSortOptions,
  type WorkItemSortKey,
} from '../../domains/workitem/list'
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
  /** How many rows of the filtered collection are loaded, and whether the collection continues. */
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
 * What the sort and filter controls act on, said out loud.
 *
 * Both now travel with the list query (M9b-A06), so the set they narrow and order is the whole
 * project's, not the loaded page's. The counts stay because pagination is still real: 已加载 N 项、
 * 还有更多 is the difference between a finished result and one page of it.
 */
const sortScopeHint = computed(() => props.hasMore
  ? `排序与筛选作用于整个项目，已加载 ${props.loadedCount} 项，还有更多`
  : `排序与筛选作用于整个项目，共 ${props.loadedCount} 项`)

/** Every ordering runs in the direction its server contract fixed; the label says which one. */
function sortLabel(key: WorkItemSortKey, label: string): string {
  const direction = workItemSortDirectionLabels[key]
  return key === props.sortKey
    ? `按${label}排序（当前${direction}）`
    : `按${label}排序（${direction}）`
}

/** How many filters are narrowing the list right now — the badge a collapsed phone filter row shows. */
const appliedFilterCount = computed(() =>
  [props.status, props.type, props.priority].filter(value => value !== 'all').length)
/** Whether the phone-collapsed filter area is unfolded. Desktop never collapses it. */
const filtersOpen = ref(false)
const filterToggleLabel = computed(() => {
  const applied = appliedFilterCount.value ? `已应用 ${appliedFilterCount.value} 项` : '未应用筛选'
  return `${filtersOpen.value ? '收起' : '展开'}筛选（${applied}）`
})
</script>

<template>
  <section class="work-toolbar panel">
    <div class="work-toolbar__scope">
      <div class="work-toolbar__headline">
        <p class="eyebrow">{{ teamName }} · {{ projectKey }}</p>
        <h2>团队工作项</h2>
        <span>{{ resultCount }} 项当前结果</span>
      </div>
      <div class="work-toolbar__entry">
        <slot name="entry" />
        <BaseButton v-if="canCreate" class="create-work-item" size="small" @click="emit('create')"><Plus :size="14" />新建工作项</BaseButton>
      </div>
    </div>
    <!-- On a phone the three selects collapse behind one toggle so the primary actions keep their
         row; the badge count keeps the applied filters readable while the area is folded (R16). -->
    <button type="button" class="filters-toggle" :aria-expanded="filtersOpen" @click="filtersOpen = !filtersOpen">
      <SlidersHorizontal :size="14" aria-hidden="true" />{{ filterToggleLabel }}
    </button>
    <div class="filters" :class="{ 'filters--open': filtersOpen }" aria-label="工作项筛选">
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
        {{ option.label }}<ArrowUp v-if="option.key === sortKey && workItemSortDirections[option.key] === 'asc'" :size="13" aria-hidden="true" /><ArrowDown v-if="option.key === sortKey && workItemSortDirections[option.key] === 'desc'" :size="13" aria-hidden="true" />
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
.work-toolbar { display: grid; grid-template-columns: minmax(190px, .65fr) minmax(430px, 1.5fr) auto; align-items: end; gap: var(--cs-space-20); padding: var(--cs-space-16) var(--cs-space-20); }.work-toolbar__scope { display: flex; align-items: flex-end; justify-content: space-between; gap: var(--cs-space-12); }.work-toolbar__headline { min-width: 0; }.work-toolbar__scope h2 { margin: 0; font-size: var(--cs-text-lg); }.work-toolbar__scope > span { color: var(--cs-text-muted); font-size: var(--cs-text-xs); }.work-toolbar__entry { display: flex; align-items: center; gap: var(--cs-space-8); }.create-work-item { flex: 0 0 auto; }.filters { display: grid; grid-template-columns: repeat(3, minmax(110px, 1fr)); gap: var(--cs-space-8); }.filters label { display: grid; gap: var(--cs-space-4); color: var(--cs-text-secondary); font-size: var(--cs-text-xs); font-weight: var(--cs-weight-semibold); }.filters select { width: 100%; min-height: var(--cs-density-control-height); padding: 0 var(--cs-space-8); border: 1px solid var(--cs-border-strong); border-radius: var(--cs-radius-sm); background: var(--cs-surface-subtle); color: var(--cs-text); font: var(--cs-text-base) var(--cs-font-sans); }.view-switcher { display: flex; gap: var(--cs-space-4); padding: var(--cs-space-4); border: 1px solid var(--cs-border); border-radius: 9px; background: var(--cs-surface-subtle); }.view-switcher button { display: flex; min-height: var(--cs-density-control-height); align-items: center; gap: var(--cs-space-4); padding: 0 var(--cs-space-8); border-radius: 6px; background: transparent; color: var(--cs-text-muted); font-size: var(--cs-text-sm); cursor: pointer; }.view-switcher button.active { background: var(--cs-surface); box-shadow: var(--cs-shadow-hairline); color: var(--cs-text-brand); font-weight: var(--cs-weight-semibold); }
/*
 * The sort control spans the panel rather than sitting in a column: it is the one control whose
 * meaning depends on what the other two rows selected, and the hint under it is part of the control.
 */
.sort-control { display: flex; grid-column: 1 / -1; flex-wrap: wrap; align-items: center; gap: var(--cs-space-4); padding-top: var(--cs-space-8); border-top: 1px solid var(--cs-border); }
.sort-control__label { margin-right: var(--cs-space-4); color: var(--cs-text-secondary); font-size: var(--cs-text-xs); font-weight: var(--cs-weight-semibold); }
.sort-control__active { background: var(--cs-surface); box-shadow: var(--cs-shadow-hairline); color: var(--cs-text-brand); font-weight: var(--cs-weight-semibold); }
.sort-control__scope { margin-left: auto; color: var(--cs-text-muted); font-size: var(--cs-text-xs); }
@media (max-width: 1050px) { .work-toolbar { grid-template-columns: 1fr auto; }.filters { grid-column: 1 / -1; grid-row: 2; }.view-switcher { grid-column: 2; grid-row: 1; } }
/* Desktop never folds the filter row; the toggle exists only for the phone layout below. */
.filters-toggle { display: none; }
@media (max-width: 767px) {
  .work-toolbar { grid-template-columns: 1fr; align-items: stretch; gap: var(--cs-space-12); padding: var(--cs-space-16); }
  /* 标题区与主操作（固定入口 + 新建）的 min-content 合计会超出手机行宽：不换行时整页被
     撑出 ~17px 横向溢出（按钮右缘越过视口）。放不下就让主操作整行换到标题下方，而不是
     挤出屏幕（R16：主操作必须保留可见）。 */
  .work-toolbar__scope { flex-wrap: wrap; }
  /* The folded state keeps the applied-filter count on the toggle itself (R16): the member can
     always tell the list is narrowed, and one press unfolds the selects that say by what. */
  .filters { display: none; }
  .filters--open { display: grid; grid-column: 1; grid-template-columns: 1fr 1fr; }
  .filters--open label:first-child { grid-column: 1 / -1; }
  .filters-toggle { display: inline-flex; align-items: center; gap: var(--cs-space-8); min-height: var(--cs-density-control-height); padding: 0 var(--cs-space-12); border: 1px solid var(--cs-border-strong); border-radius: var(--cs-radius-sm); background: var(--cs-surface-subtle); color: var(--cs-text-secondary); font-size: var(--cs-text-sm); font-weight: var(--cs-weight-semibold); cursor: pointer; }
  .view-switcher { grid-column: 1; grid-row: auto; }
  .view-switcher button { flex: 1; justify-content: center; }
  .sort-control__scope { margin-left: 0; }
}
</style>
