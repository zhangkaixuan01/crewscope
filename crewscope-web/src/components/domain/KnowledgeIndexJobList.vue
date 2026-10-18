<script setup lang="ts">
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

/** Index-jobs twin of the list shell: source/status filters and the cancellable job rows. */
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
  /** Commands and the technical block are manager-only (plan D5); the read surface stays complete. */
  canManage: boolean
  /** The store cancel slot: only the row it names shows its pending spinner. */
  cancellingJobId: string | null
}>()

const emit = defineEmits<{
  changeSource: [value: KnowledgeIndexJobSource | 'ALL']
  changeStatus: [value: KnowledgeIndexJobStatus | 'ALL']
  loadMore: []
  cancel: [jobId: string]
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
    forbidden-description="服务端没有为当前身份解析出这个 Team 的活动成员。"
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
      </header>
    </template>

    <template #empty-action>
      <BaseButton v-if="sourceFilter !== 'ALL' || statusFilter !== 'ALL'" variant="ghost" size="small" @click="clearFilters">清除筛选</BaseButton>
    </template>

    <template #items>
      <KnowledgeIndexJobItem
        v-for="job in items"
        :key="job.id"
        :job="job"
        :can-manage="canManage"
        :cancelling="cancelling && cancellingJobId === job.id"
        @cancel="emit('cancel', $event)"
      />
    </template>
  </BaseListShell>
</template>

<style scoped>
.job-list__filters { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: var(--cs-space-8); }
@media (max-width: 767px) { .job-list__filters { grid-template-columns: 1fr; } }
</style>
