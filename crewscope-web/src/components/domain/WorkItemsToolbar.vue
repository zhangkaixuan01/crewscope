<script setup lang="ts">
import BaseSelect from '../base/BaseSelect.vue'
import { Columns3, List, Network, Plus } from '@lucide/vue'
import { computed, useId } from 'vue'
import {
  unsortableWorkItemField,
  workItemSortDirectionLabels,
  workItemSortOptions,
  type WorkItemSortKey,
} from '../../domains/workitem/list'
import BaseButton from '../base/BaseButton.vue'
import ScanToolbar from '../base/ScanToolbar.vue'

const props = defineProps<{
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

const sortHintId = `work-sort-scope-${useId()}`

/**
 * What the sort and filter controls act on, said out loud.
 *
 * Both travel with the list query (M9b-A06), so the set they narrow and order is the whole
 * project's, not the loaded page's. The counts stay because pagination is still real: 已加载 N 项、
 * 还有更多 is the difference between a finished result and one page of it. M13b-F09 单行预算下这
 * 句作用域声明从视觉行降级为排序控件的 aria 描述，屏幕阅读器照读。
 */
const sortScopeHint = computed(() => props.hasMore
  ? `排序与筛选作用于整个项目，已加载 ${props.loadedCount} 项，还有更多`
  : `排序与筛选作用于整个项目，共 ${props.loadedCount} 项`)
</script>

<template>
  <ScanToolbar class="work-toolbar" aria-label="工作项筛选与排序">
    <template #count>{{ resultCount }} 项当前结果</template>
    <template #filters>
      <label>状态<BaseSelect :model-value="status" @update:model-value="emit('updateQuery', 'status', String($event))"><option value="all">全部状态</option><option v-for="item in statuses" :key="item" :value="item">{{ statusLabels[item] }}</option></BaseSelect></label>
      <label>类型<BaseSelect :model-value="type" @update:model-value="emit('updateQuery', 'type', String($event))"><option value="all">全部类型</option><option v-for="item in types" :key="item" :value="item">{{ typeLabels[item] }}</option></BaseSelect></label>
      <label>优先级<BaseSelect :model-value="priority" @update:model-value="emit('updateQuery', 'priority', String($event))"><option value="all">全部优先级</option><option v-for="item in priorities" :key="item" :value="item">{{ priorityLabels[item] }}</option></BaseSelect></label>
    </template>
    <template #side>
      <div class="view-switcher" aria-label="工作项视图">
        <button type="button" :class="{ active: view === 'list' }" aria-label="列表视图" @click="emit('updateQuery', 'view', 'list')"><List :size="15" />List</button>
        <button type="button" :class="{ active: view === 'board' }" aria-label="看板视图" @click="emit('updateQuery', 'view', 'board')"><Columns3 :size="15" />Board</button>
        <!-- M11-F02: the third view entry. The graph shell renders its own switcher, so this button
             never carries the active state — it only promises the view exists and navigates to it.
             M13b-F05（R34）: the entry explains what the graph is for — it is not a third layout of
             the same list, it is the blocking-relation view of the whole project. -->
        <button type="button" aria-label="依赖图视图：按阻塞关系查看整个项目的工作项" @click="emit('updateQuery', 'view', 'graph')"><Network :size="15" />Graph</button>
      </div>
      <!-- 每个排序键自带服务端合同的固定方向（写进选项文本）；负责人以禁用选项出现，排序边界与
           原因由 aria-describedby 读出——M9b-A06 平铺按钮契约的 M13b-F09 菜单化形态。 -->
      <div class="sort-control" role="group" aria-label="排序">
        <label>排序<BaseSelect :model-value="sortKey" :aria-describedby="sortHintId" @update:model-value="emit('sort', $event as WorkItemSortKey)"><option v-for="option in workItemSortOptions" :key="option.key" :value="option.key">{{ option.label }}（{{ workItemSortDirectionLabels[option.key] }}）</option><option value="assignee" disabled>{{ unsortableWorkItemField.label }}（不可排序）</option></BaseSelect></label>
        <span :id="sortHintId" class="sr-only">{{ sortScopeHint }}。{{ unsortableWorkItemField.label }}：{{ unsortableWorkItemField.hint }}</span>
      </div>
      <slot name="entry" />
      <BaseButton v-if="canCreate" class="create-work-item" size="small" @click="emit('create')"><Plus :size="14" />新建工作项</BaseButton>
    </template>
  </ScanToolbar>
</template>

<style scoped>
/* M13b-F09（S2）：标题区退出工具栏（context header 已命名团队与项目），三段内容进 ScanToolbar
   的单行预算；手机上不再折叠筛选，槽内内容自然换行（R16 的折叠+badge 由 wrap 替代）。 */
.view-switcher { display: flex; gap: var(--cs-space-4); padding: var(--cs-space-4); border: 1px solid var(--cs-border); border-radius: 9px; background: var(--cs-surface-subtle); }.view-switcher button { display: flex; min-height: var(--cs-density-control-height); align-items: center; gap: var(--cs-space-4); padding: 0 var(--cs-space-8); border-radius: 6px; background: transparent; color: var(--cs-text-muted); font-size: var(--cs-text-sm); cursor: pointer; }.view-switcher button.active { background: var(--cs-surface); box-shadow: var(--cs-shadow-hairline); color: var(--cs-text-brand); font-weight: var(--cs-weight-semibold); }
.sort-control { display: flex; align-items: center; gap: var(--cs-space-8); color: var(--cs-text-muted); font-size: var(--cs-text-xs); font-weight: var(--cs-weight-semibold); white-space: nowrap; }
.create-work-item { flex: 0 0 auto; }
@media (max-width: 1050px) { .work-toolbar :deep(.scan-toolbar__filters) { flex-basis: 100%; order: 3; flex-wrap: wrap; } }
/* M12b-F01：悬停反馈补齐——此前这些可点元素对指针移动完全沉默。 */
.view-switcher button:not(.active):hover:not(:disabled) { background: var(--cs-surface-accent); }
.view-switcher button:not(.active):active:not(:disabled) { transform: scale(.97); }
.view-switcher button { transition: background-color var(--cs-motion-fast) var(--cs-ease-out), border-color var(--cs-motion-fast) var(--cs-ease-out), transform var(--cs-motion-fast) var(--cs-ease-out); }
</style>
