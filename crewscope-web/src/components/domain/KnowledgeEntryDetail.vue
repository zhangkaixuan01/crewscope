<script setup lang="ts">
import { FileText } from '@lucide/vue'
import { computed, useTemplateRef } from 'vue'
import { knowledgeCategoryLabels, knowledgeEntryStatusLabels, knowledgeIndexStatusHint, knowledgeIndexStatusLabels } from '../../domains/knowledge/labels'
import type { KnowledgeEntrySummary, KnowledgeVersion, UpdateKnowledgeDraftInput } from '../../domains/knowledge/types'
import type { Etagged } from '../../domains/settings/types'
import type { KnowledgeCommandState, KnowledgeResource, KnowledgeVersionPageResource } from '../../domains/knowledge/store'
import type { KnowledgeDraftScope } from './KnowledgeDraftEditor.vue'
import KnowledgeDraftEditor from './KnowledgeDraftEditor.vue'
import KnowledgeVersionHistory from './KnowledgeVersionHistory.vue'
import BaseButton from '../base/BaseButton.vue'
import StatusBadge from '../base/StatusBadge.vue'
import StatePanel from '../feedback/StatePanel.vue'
import BaseDetailPage from '../shared/BaseDetailPage.vue'

/** Knowledge twin of the detail aside: the shared shell plus index-status vocabulary and the
 * retire/delete action set. */
const props = withDefaults(defineProps<{
  entryResource: KnowledgeResource<Etagged<KnowledgeEntrySummary>> | null | undefined
  command: KnowledgeCommandState | null
  history: KnowledgeVersionPageResource
  versionResource: KnowledgeResource<Etagged<KnowledgeVersion>> | null | undefined
  effective: KnowledgeResource<Etagged<KnowledgeVersion>> | null | undefined
  effectiveRevision: number | null
  selectedRevision: number | null
  tab: 'draft' | 'versions'
  scope: KnowledgeDraftScope
  canManage: boolean
  online: boolean
  /** R04: confirmed `false` swaps the PENDING hint for the disabled wording; null = unknown.
   * The explicit default matters: an absent Boolean-typed prop would otherwise cast to false. */
  indexEnabled?: boolean | null
}>(), { indexEnabled: null })

const emit = defineEmits<{
  close: []
  save: [input: UpdateKnowledgeDraftInput]
  publish: []
  retire: []
  delete: []
  changeTab: [tab: 'draft' | 'versions']
  selectRevision: [revision: number]
  loadMoreVersions: []
  retryEntry: []
  retryVersions: []
  rollback: [revision: number]
}>()

const entry = computed(() => props.entryResource?.value?.value ?? null)
const tombstone = computed(() => entry.value?.status === 'DELETED')
const editor = useTemplateRef<{ applyServerDraft: () => void, confirmDiscard: () => Promise<boolean> }>('editor')

/** M13b-F04（R28）：回滚钮的 busy 态挂在共享命令槽的 rollback 操作上。 */
const rollingBack = computed(() => props.command?.phase === 'pending' && props.command.operation === 'rollback')

const saving = computed(() => props.command?.phase === 'pending' && props.command.operation === 'save-draft')
const publishing = computed(() => props.command?.phase === 'pending' && props.command.operation === 'publish')
const retiring = computed(() => props.command?.phase === 'pending' && props.command.operation === 'retire')
const deleting = computed(() => props.command?.phase === 'pending' && props.command.operation === 'delete')
const commandBusy = computed(() => props.command?.phase === 'pending')

function statusTone(status: KnowledgeEntrySummary['status']): 'neutral' | 'info' | 'warning' | 'danger' {
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

/** D4 layer three: the reload never overwrites the editor without an explicit discard. */
async function loadServerDraft(): Promise<void> {
  if (editor.value && !await editor.value.confirmDiscard()) return
  editor.value?.applyServerDraft()
}
</script>

<template>
  <BaseDetailPage
    :entry-resource="entryResource"
    :command="command"
    :tab="tab"
    :online="online"
    :tombstone="tombstone"
    :can-manage="canManage"
    eyebrow="Knowledge entry"
    title="条目详情"
    detail-label="知识条目详情"
    close-label="关闭条目详情"
    loading-title="正在读取条目详情"
    conflict-title="条目已被其他成员更新"
    command-failed-title="知识库命令失败"
    audit-id-label="条目 ID"
    tabs-id="knowledge-entry-tabs"
    @close="emit('close')"
    @retry-entry="emit('retryEntry')"
    @load-server-draft="loadServerDraft"
    @change-tab="tab => emit('changeTab', tab)"
  >
    <template #identity="{ entry: current }">
      <FileText :size="16" aria-hidden="true" />
      <span class="detail-panel__key">{{ current.entryKey }}</span>
    </template>

    <template #badges="{ entry: current }">
      <StatusBadge tone="neutral">{{ knowledgeCategoryLabels[current.category] }}</StatusBadge>
      <StatusBadge :tone="statusTone(current.status)">{{ knowledgeEntryStatusLabels[current.status] }}</StatusBadge>
      <StatusBadge v-if="current.status !== 'DELETED'" :tone="indexTone(current.indexStatus)">{{ knowledgeIndexStatusLabels[current.indexStatus] }}</StatusBadge>
    </template>

    <template #facts-extra="{ entry: current }">
      <!-- M13b-F04（R19）: the distillation attribution is product meaning, not an admin-only
           audit fact — surfaced unfolded for every reader; the full id stays in the audit fold. -->
      <p v-if="current.origin" class="knowledge-detail__hint">蒸馏自执行 {{ current.origin.taskExecutionId.slice(0, 8) }} · 第 {{ current.origin.attempt }} 次尝试</p>
      <p v-if="current.status !== 'DELETED' && knowledgeIndexStatusHint(current.indexStatus, indexEnabled)" class="knowledge-detail__hint">
        {{ knowledgeIndexStatusHint(current.indexStatus, indexEnabled) }}
        <!-- R16/R17：失败重试落点在索引作业面——带条目过滤直达该条目的作业。 -->
        <RouterLink v-if="current.indexStatus === 'FAILED'" class="knowledge-detail__jobs-link" :to="`/knowledge/index?entryId=${current.id}`">查看该条目的索引作业</RouterLink>
      </p>
    </template>

    <template #tombstone>
      <StatePanel
        state="cancelled"
        title="此条目已删除"
        description="删除墓碑保留在列表中以维持历史引用的完整性；版本内容仍可只读查阅。"
      />
    </template>

    <template #actions="{ disabledReason }">
      <nav v-if="canManage" class="knowledge-detail__actions" :aria-label="`条目 ${entry?.entryKey} 管理操作`">
        <BaseButton
          variant="primary"
          size="small"
          :loading="publishing"
          :disabled="disabledReason !== null || commandBusy"
          :title="disabledReason ?? undefined"
          @click="emit('publish')"
        >发布当前草稿</BaseButton>
        <BaseButton
          v-if="entry?.status === 'PUBLISHED'"
          variant="secondary"
          size="small"
          :loading="retiring"
          :disabled="disabledReason !== null || commandBusy"
          :title="disabledReason ?? undefined"
          @click="emit('retire')"
        >废弃条目</BaseButton>
        <BaseButton
          variant="danger"
          size="small"
          :loading="deleting"
          :disabled="disabledReason !== null || commandBusy"
          :title="disabledReason ?? undefined"
          @click="emit('delete')"
        >删除条目</BaseButton>
        <span v-if="disabledReason" class="knowledge-detail__reason">{{ disabledReason }}</span>
      </nav>
    </template>

    <template #draft="{ entry: current }">
      <KnowledgeDraftEditor
        ref="editor"
        :entry="current"
        :scope="scope"
        :can-manage="canManage"
        :saving="saving"
        @save="input => emit('save', input)"
      />
    </template>

    <template #versions>
      <KnowledgeVersionHistory
        :history="history"
        :version-resource="versionResource"
        :effective="effective"
        :effective-revision="effectiveRevision"
        :selected-revision="selectedRevision"
        :online="online"
        :can-manage="canManage"
        :can-rollback="canManage"
        :rolling-back="rollingBack"
        :index-enabled="indexEnabled"
        @select="revision => emit('selectRevision', revision)"
        @load-more="emit('loadMoreVersions')"
        @retry="emit('retryVersions')"
        @rollback="revision => emit('rollback', revision)"
      />
    </template>
  </BaseDetailPage>
</template>

<style scoped>
.knowledge-detail__hint { margin: 0; color: var(--cs-text-muted); font-size: var(--cs-text-xs); }
.knowledge-detail__jobs-link { margin-left: var(--cs-space-4); color: var(--cs-text-brand); }
.knowledge-detail__actions { display: flex; flex-wrap: wrap; align-items: center; gap: var(--cs-space-8); }
.knowledge-detail__reason { color: var(--cs-text-muted); font-size: var(--cs-text-xs); }
</style>
