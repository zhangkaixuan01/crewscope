<script setup lang="ts">
import { computed } from 'vue'
import { RouterLink } from 'vue-router'
import { permissions } from '../../app/auth'
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
import StatePanel from '../feedback/StatePanel.vue'
import KnowledgeIndexJobItem from './KnowledgeIndexJobItem.vue'

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

const initialLoading = computed(() => (props.phase === 'idle' || props.phase === 'loading') && props.items.length === 0)
const hasItems = computed(() => props.items.length > 0)
const forbidden = computed(() => props.phase === 'error' && props.errorStatus === 403)
const hardError = computed(() => props.phase === 'error' && !forbidden.value)
const offline = computed(() => !props.online)
const hasFilter = computed(() => props.sourceFilter !== 'ALL' || props.statusFilter !== 'ALL')
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
  <section class="job-list panel" aria-label="索引作业列表">
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

    <StatePanel v-if="initialLoading" state="loading" title="正在读取索引作业" description="正在读取当前 Team 的索引作业。" />
    <StatePanel v-else-if="forbidden" state="forbidden" title="无权读取索引作业" description="服务端没有为当前身份解析出这个 Team 的活动成员。">
      <template #action>
        <RouterLink :to="{ name: 'access-denied', query: { requiredPermission: permissions.scopeRead } }">
          <BaseButton variant="secondary" size="small">查看权限说明</BaseButton>
        </RouterLink>
      </template>
    </StatePanel>
    <StatePanel v-else-if="offline && !hasItems" state="offline" title="离线时没有可用索引作业" description="联网后会回读当前 Team 的索引作业。" />
    <StatePanel v-else-if="hardError && !hasItems" state="error" :description="errorMessage ?? undefined" @retry="emit('retry')" />
    <StatePanel v-else-if="!hasItems" state="empty" title="暂无索引作业" description="知识条目发布与仓库索引构建的作业会出现在这里。">
      <template #action>
        <BaseButton v-if="hasFilter" variant="ghost" size="small" @click="clearFilters">清除筛选</BaseButton>
      </template>
    </StatePanel>

    <template v-else>
      <StatePanel v-if="offline" compact state="offline" title="正在展示最近读取的索引作业" description="离线期间控制命令保持关闭。" />
      <StatePanel v-else-if="hardError" compact state="error" :description="errorMessage ?? undefined" @retry="emit('retry')" />

      <ol class="job-list__items">
        <KnowledgeIndexJobItem
          v-for="job in items"
          :key="job.id"
          :job="job"
          :can-manage="canManage"
          :cancelling="cancelling && cancellingJobId === job.id"
          @cancel="emit('cancel', $event)"
        />
      </ol>

      <footer v-if="nextAfter !== null" class="job-list__more">
        <BaseButton
          variant="secondary"
          size="small"
          :loading="loadingMore"
          :disabled="offline"
          :aria-describedby="offline ? 'job-load-more-reason' : undefined"
          @click="emit('loadMore')"
        >读取更多作业</BaseButton>
        <p id="job-load-more-reason" class="job-list__reason">离线时续页读取保持关闭。</p>
      </footer>
    </template>
  </section>
</template>

<style scoped>
.job-list { display: flex; min-width: 0; flex-direction: column; gap: var(--cs-space-12); }
.job-list__filters { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: var(--cs-space-8); }
.job-list__items { display: flex; margin: 0; padding: 0; list-style: none; flex-direction: column; gap: var(--cs-space-8); }
.job-list__more { display: grid; justify-items: center; gap: var(--cs-space-4); }
.job-list__reason { margin: 0; color: var(--cs-text-muted); font-size: var(--cs-text-xs); }
@media (max-width: 767px) { .job-list__filters { grid-template-columns: 1fr; } }
</style>
