<script setup lang="ts">
import { teamMemberUnresolvedDescription } from '../../domains/shared/stateCopy'
import { computed } from 'vue'
import {
  knowledgeIndexJobSourceLabels,
  knowledgeIndexJobStatusLabels,
} from '../../domains/knowledge/labels'
import {
  knowledgeIndexJobSources,
  knowledgeIndexJobStatuses,
  type KnowledgeIndexJob,
  type KnowledgeIndexJobSource,
  type KnowledgeIndexJobStatus,
} from '../../domains/knowledge/types'
import type { KnowledgeIndexPhase } from '../../domains/knowledge/index-store'
import BaseButton from '../base/BaseButton.vue'
import BaseSelect, { type BaseSelectOption } from '../base/BaseSelect.vue'
import BaseListShell from '../shared/BaseListShell.vue'
import KnowledgeIndexJobItem from './KnowledgeIndexJobItem.vue'

/** Index-jobs twin of the list shell: source/status/entry filters and the job rows. */
const props = defineProps<{
  phase: KnowledgeIndexPhase
  items: KnowledgeIndexJob[]
  nextAfter: string | null
  loadingMore: boolean
  errorMessage: string | null
  errorStatus: number | null
  online: boolean
  sourceFilter: KnowledgeIndexJobSource | 'ALL'
  statusFilter: KnowledgeIndexJobStatus | 'ALL'
  /** M13b-A04/R16: the entry deep-link filter — null means unfiltered. */
  entryFilterId: string | null
  /** Readable label for the entry filter chip (title or entryKey; falls back to the short id). */
  entryFilterLabel: string | null
  /** entryId → readable title for KNOWLEDGE_ENTRY rows (R16). */
  entryTitles: Record<string, string>
  /** Commands and the technical block are manager-only (plan D5); the read surface stays complete. */
  canManage: boolean
  /** The store cancel slot: only the row it names shows its pending spinner. */
  cancellingJobId: string | null
  /** The store refresh slot: only the entry it names shows its pending spinner. */
  refreshingEntryId: string | null
}>()

const emit = defineEmits<{
  changeSource: [value: KnowledgeIndexJobSource | 'ALL']
  changeStatus: [value: KnowledgeIndexJobStatus | 'ALL']
  clearEntryFilter: []
  loadMore: []
  cancel: [jobId: string]
  retryEntry: [entryId: string]
  retry: []
}>()

const cancelling = computed(() => props.cancellingJobId !== null)

const sourceOptions: BaseSelectOption[] = [
  { label: '全部来源', value: 'ALL' },
  ...knowledgeIndexJobSources.map(source => ({ label: knowledgeIndexJobSourceLabels[source], value: source })),
]
const statusOptions: BaseSelectOption[] = [
  { label: '全部状态', value: 'ALL' },
  ...knowledgeIndexJobStatuses.map(status => ({ label: knowledgeIndexJobStatusLabels[status], value: status })),
]

function clearFilters(): void {
  emit('changeSource', 'ALL')
  emit('changeStatus', 'ALL')
  if (props.entryFilterId) emit('clearEntryFilter')
}
</script>

<template>
  <BaseListShell
    :phase="phase"
    :items="items"
    :next-after="nextAfter"
    :loading-more="loadingMore"
    :error-message="errorMessage"
    :error-status="errorStatus"
    :online="online"
    label="索引作业列表"
    loading-title="正在读取索引作业"
    loading-description="正在读取当前 Team 的索引作业。"
    forbidden-title="无权读取索引作业"
    :forbidden-description="teamMemberUnresolvedDescription"
    offline-empty-title="离线时没有可用索引作业"
    offline-empty-description="联网后会回读当前 Team 的索引作业。"
    empty-title="暂无索引作业"
    empty-description="知识条目发布与仓库索引构建的作业会出现在这里。"
    compact-offline-title="正在展示最近读取的索引作业"
    compact-offline-description="离线期间控制命令保持关闭。"
    load-more-label="读取更多作业"
    @retry="emit('retry')"
    @load-more="emit('loadMore')"
  >
    <template #filters>
      <header class="job-list__filters">
        <BaseSelect
          :model-value="sourceFilter"
          :options="sourceOptions"
          aria-label="按来源筛选"
          @update:model-value="emit('changeSource', $event as KnowledgeIndexJobSource | 'ALL')"
        />
        <BaseSelect
          :model-value="statusFilter"
          :options="statusOptions"
          aria-label="按状态筛选"
          @update:model-value="emit('changeStatus', $event as KnowledgeIndexJobStatus | 'ALL')"
        />
        <!-- R16：条目深链过滤的事实条——常显过滤中状态并可一键清除。 -->
        <p v-if="entryFilterId" class="job-list__entry-filter">
          只看条目：{{ entryFilterLabel ?? entryFilterId.slice(0, 8) }}
          <button type="button" aria-label="清除条目过滤" @click="emit('clearEntryFilter')">×</button>
        </p>
      </header>
    </template>

    <template #empty-action>
      <BaseButton v-if="sourceFilter !== 'ALL' || statusFilter !== 'ALL' || entryFilterId" variant="ghost" size="small" @click="clearFilters">清除筛选</BaseButton>
    </template>

    <template #items>
      <KnowledgeIndexJobItem
        v-for="job in items"
        :key="job.id"
        :job="job"
        :can-manage="canManage"
        :cancelling="cancelling && cancellingJobId === job.id"
        :entry-title="job.entryId ? entryTitles[job.entryId] ?? null : null"
        :refreshing="refreshingEntryId !== null && refreshingEntryId === job.entryId"
        @cancel="emit('cancel', $event)"
        @retry-entry="emit('retryEntry', $event)"
      />
    </template>
  </BaseListShell>
</template>

<style scoped>
.job-list__filters { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: var(--cs-space-8); }
.job-list__entry-filter { grid-column: 1 / -1; display: flex; align-items: center; gap: var(--cs-space-4); margin: 0; color: var(--cs-text-muted); font-size: var(--cs-text-xs); }
.job-list__entry-filter button { display: grid; width: 22px; height: 22px; place-items: center; border: 1px solid var(--cs-border); border-radius: var(--cs-radius-sm); background: transparent; color: inherit; cursor: pointer; }
.job-list__entry-filter button:focus-visible { outline: none; box-shadow: var(--cs-focus-ring); }
@media (max-width: 767px) { .job-list__filters { grid-template-columns: 1fr; } }
</style>
