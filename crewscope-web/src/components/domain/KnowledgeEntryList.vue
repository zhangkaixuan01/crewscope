<script setup lang="ts">
import { BookOpen, ChevronRight } from '@lucide/vue'
import { computed } from 'vue'
import { RouterLink } from 'vue-router'
import { permissions } from '../../app/auth'
import {
  knowledgeCategoryLabels,
  knowledgeEntryStatusLabels,
  knowledgeIndexStatusLabels,
} from '../../domains/knowledge/labels'
import {
  knowledgeCategories,
  knowledgeEntryStatuses,
  type KnowledgeCategory,
  KnowledgeEntryStatus,
  type KnowledgeEntrySummary,
} from '../../domains/knowledge/types'
import type { KnowledgeResourcePhase } from '../../domains/knowledge/store'
import BaseButton from '../base/BaseButton.vue'
import BaseSelect, { type BaseSelectOption } from '../base/BaseSelect.vue'
import RelativeTime from '../base/RelativeTime.vue'
import StatusBadge from '../base/StatusBadge.vue'
import StatePanel from '../feedback/StatePanel.vue'

const props = defineProps<{
  phase: KnowledgeResourcePhase
  items: KnowledgeEntrySummary[]
  nextAfter: string | null
  loadingMore: boolean
  errorMessage: string | null
  errorStatus: number | null
  online: boolean
  statusFilter: KnowledgeEntryStatus | 'ALL'
  categoryFilter: KnowledgeCategory | 'ALL'
  canManage: boolean
  selectedEntryId: string | null
}>()

const emit = defineEmits<{
  select: [entryId: string]
  changeStatus: [value: KnowledgeEntryStatus | 'ALL']
  changeCategory: [value: KnowledgeCategory | 'ALL']
  loadMore: []
  create: []
  distill: []
  retry: []
}>()

const initialLoading = computed(() => (props.phase === 'idle' || props.phase === 'loading') && props.items.length === 0)
const hasItems = computed(() => props.items.length > 0)
const forbidden = computed(() => props.phase === 'error' && props.errorStatus === 403)
const hardError = computed(() => props.phase === 'error' && !forbidden.value)
const offline = computed(() => !props.online)
const hasFilter = computed(() => props.statusFilter !== 'ALL' || props.categoryFilter !== 'ALL')

const statusOptions: BaseSelectOption[] = [
  { label: '全部状态', value: 'ALL' },
  ...knowledgeEntryStatuses.map(status => ({ label: knowledgeEntryStatusLabels[status], value: status })),
]
const categoryOptions: BaseSelectOption[] = [
  { label: '全部分类', value: 'ALL' },
  ...knowledgeCategories.map(category => ({ label: knowledgeCategoryLabels[category], value: category })),
]

function statusTone(status: KnowledgeEntryStatus): 'neutral' | 'info' | 'warning' | 'danger' {
  if (status === 'PUBLISHED') return 'info'
  if (status === 'RETIRED') return 'warning'
  if (status === 'DELETED') return 'danger'
  return 'neutral'
}

function indexTone(indexStatus: string): 'neutral' | 'success' | 'warning' {
  if (indexStatus === 'INDEXED') return 'success'
  if (indexStatus === 'FAILED') return 'warning'
  return 'neutral'
}

function clearFilters(): void {
  emit('changeStatus', 'ALL')
  emit('changeCategory', 'ALL')
}
</script>

<template>
  <section class="knowledge-list panel" aria-label="知识条目列表">
    <header class="knowledge-list__filters">
      <BaseSelect
        :model-value="statusFilter"
        :options="statusOptions"
        aria-label="按状态筛选"
        @update:model-value="emit('changeStatus', $event as KnowledgeEntryStatus | 'ALL')"
      />
      <BaseSelect
        :model-value="categoryFilter"
        :options="categoryOptions"
        aria-label="按分类筛选"
        @update:model-value="emit('changeCategory', $event as KnowledgeCategory | 'ALL')"
      />
    </header>

    <StatePanel v-if="initialLoading" state="loading" title="正在读取知识条目" description="正在读取当前 Team 的知识资产。" />
    <StatePanel v-else-if="forbidden" state="forbidden" title="无权读取知识库" description="服务端没有为当前身份解析出这个 Team 的活动成员。">
      <template #action>
        <RouterLink :to="{ name: 'access-denied', query: { requiredPermission: permissions.scopeRead } }">
          <BaseButton variant="secondary" size="small">查看权限说明</BaseButton>
        </RouterLink>
      </template>
    </StatePanel>
    <StatePanel v-else-if="offline && !hasItems" state="offline" title="离线时没有可用知识条目" description="联网后会回读当前 Team 的知识资产。" />
    <StatePanel v-else-if="hardError && !hasItems" state="error" :description="errorMessage ?? undefined" @retry="emit('retry')" />
    <StatePanel v-else-if="!hasItems" state="empty" title="暂无知识条目" description="团队约定、操作手册与决策记录会沉淀在这里。">
      <template #action>
        <BaseButton v-if="canManage" variant="secondary" size="small" @click="emit('create')">创建条目</BaseButton>
        <BaseButton v-if="canManage" variant="secondary" size="small" @click="emit('distill')">从执行蒸馏</BaseButton>
        <BaseButton v-if="hasFilter" variant="ghost" size="small" @click="clearFilters">清除筛选</BaseButton>
      </template>
    </StatePanel>

    <template v-else>
      <StatePanel v-if="offline" compact state="offline" title="正在展示最近读取的知识条目" description="离线期间管理命令保持关闭。" />
      <StatePanel v-else-if="hardError" compact state="error" :description="errorMessage ?? undefined" @retry="emit('retry')" />

      <ol class="knowledge-list__items">
        <li v-for="entry in items" :key="entry.id" :class="{ selected: selectedEntryId === entry.id }">
          <button type="button" class="knowledge-list__row" :aria-current="selectedEntryId === entry.id ? 'true' : undefined" @click="emit('select', entry.id)">
            <BookOpen :size="16" aria-hidden="true" />
            <span class="knowledge-list__key">{{ entry.entryKey }}</span>
            <span class="knowledge-list__badges">
              <StatusBadge tone="neutral">{{ knowledgeCategoryLabels[entry.category] }}</StatusBadge>
              <StatusBadge :tone="statusTone(entry.status)">{{ knowledgeEntryStatusLabels[entry.status] }}</StatusBadge>
              <StatusBadge v-if="entry.status !== 'DELETED'" :tone="indexTone(entry.indexStatus)">{{ knowledgeIndexStatusLabels[entry.indexStatus] }}</StatusBadge>
            </span>
            <span class="knowledge-list__meta">
              <span v-if="entry.effectiveRevision != null">生效 r{{ entry.effectiveRevision }}</span>
              <span>最新 r{{ entry.latestRevision }}</span>
              <RelativeTime :value="entry.updatedAt" />
            </span>
            <ChevronRight :size="14" aria-hidden="true" />
          </button>
        </li>
      </ol>

      <footer v-if="nextAfter !== null" class="knowledge-list__more">
        <BaseButton
          variant="secondary"
          size="small"
          :loading="loadingMore"
          :disabled="offline"
          :aria-describedby="offline ? 'knowledge-load-more-reason' : undefined"
          @click="emit('loadMore')"
        >读取更多条目</BaseButton>
        <p id="knowledge-load-more-reason" class="knowledge-list__reason">离线时续页读取保持关闭。</p>
      </footer>
    </template>
  </section>
</template>

<style scoped>
.knowledge-list { display: flex; min-width: 0; flex-direction: column; gap: var(--cs-space-12); }
.knowledge-list__filters { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: var(--cs-space-8); }
.knowledge-list__items { display: flex; margin: 0; padding: 0; list-style: none; flex-direction: column; gap: var(--cs-space-8); }
.knowledge-list__items li.selected > .knowledge-list__row { border-color: var(--cs-border-accent-strong); background: var(--cs-surface-accent); }
.knowledge-list__row { display: grid; width: 100%; grid-template-columns: auto minmax(0, 1fr) auto; align-items: center; gap: var(--cs-space-8) var(--cs-space-12); padding: var(--cs-space-12); border: 1px solid var(--cs-border); border-radius: var(--cs-radius-md); background: var(--cs-surface); color: var(--cs-text); text-align: left; cursor: pointer; }
.knowledge-list__row:hover { border-color: var(--cs-border-strong); }
.knowledge-list__row:focus-visible { outline: none; box-shadow: var(--cs-focus-ring); }
.knowledge-list__key { min-width: 0; overflow-wrap: anywhere; font-family: var(--cs-font-mono, ui-monospace, monospace); font-size: var(--cs-text-sm); font-weight: var(--cs-weight-semibold); }
.knowledge-list__badges { display: flex; flex-wrap: wrap; gap: var(--cs-space-4); }
.knowledge-list__meta { display: flex; grid-column: 2; align-items: center; gap: var(--cs-space-8); color: var(--cs-text-muted); font-size: var(--cs-text-xs); }
.knowledge-list__more { display: grid; justify-items: center; gap: var(--cs-space-4); }
.knowledge-list__reason { margin: 0; color: var(--cs-text-muted); font-size: var(--cs-text-xs); }
@media (max-width: 767px) { .knowledge-list__filters { grid-template-columns: 1fr; } }
</style>
