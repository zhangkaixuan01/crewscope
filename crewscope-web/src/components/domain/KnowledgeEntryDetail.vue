<script setup lang="ts">
import { FileText, X } from '@lucide/vue'
import { computed, nextTick, useTemplateRef, watch } from 'vue'
import { knowledgeCategoryLabels, knowledgeEntryStatusLabels, knowledgeIndexStatusHints, knowledgeIndexStatusLabels } from '../../domains/knowledge/labels'
import type { KnowledgeEntrySummary, KnowledgeVersion, UpdateKnowledgeDraftInput } from '../../domains/knowledge/types'
import type { Etagged } from '../../domains/settings/types'
import type { KnowledgeCommandState, KnowledgeResource, KnowledgeVersionPageResource } from '../../domains/knowledge/store'
import type { KnowledgeDraftScope } from './KnowledgeDraftEditor.vue'
import KnowledgeDraftEditor from './KnowledgeDraftEditor.vue'
import KnowledgeVersionHistory from './KnowledgeVersionHistory.vue'
import BaseButton from '../base/BaseButton.vue'
import BaseTabs from '../base/BaseTabs.vue'
import RelativeTime from '../base/RelativeTime.vue'
import StatusBadge from '../base/StatusBadge.vue'
import StatePanel from '../feedback/StatePanel.vue'

const props = defineProps<{
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
}>()

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
}>()

const entry = computed(() => props.entryResource?.value?.value ?? null)
const tombstone = computed(() => entry.value?.status === 'DELETED')
const detailPhase = computed(() => props.entryResource?.phase ?? 'idle')
const editor = useTemplateRef<{ applyServerDraft: () => void, confirmDiscard: () => Promise<boolean> }>('editor')
const detailHeading = useTemplateRef<HTMLElement>('detailHeading')

const tabs = [
  { label: '草稿', value: 'draft' },
  { label: '版本', value: 'versions' },
]

const saving = computed(() => props.command?.phase === 'pending' && props.command.operation === 'save-draft')
const publishing = computed(() => props.command?.phase === 'pending' && props.command.operation === 'publish')
const retiring = computed(() => props.command?.phase === 'pending' && props.command.operation === 'retire')
const deleting = computed(() => props.command?.phase === 'pending' && props.command.operation === 'delete')
const conflict = computed(() => props.command?.phase === 'conflict')
const commandError = computed(() => props.command?.phase === 'error' ? props.command.errorMessage : null)
const receipt = computed(() => props.command?.phase === 'success' ? props.command.receipt : null)
const commandBusy = computed(() => props.command?.phase === 'pending')
const disabledReason = computed(() => {
  if (!props.online) return '离线期间管理命令保持关闭'
  return null
})

watch(
  () => [entry.value?.id, detailPhase.value] as const,
  async ([entryId, phase]) => {
    if (!entryId || (phase !== 'ready' && phase !== 'error')) return
    await nextTick()
    detailHeading.value?.focus()
  },
)

/** D4 layer three: the reload never overwrites the editor without an explicit discard. */
async function loadServerDraft(): Promise<void> {
  if (editor.value && !await editor.value.confirmDiscard()) return
  editor.value?.applyServerDraft()
}
</script>

<template>
  <aside class="knowledge-detail panel" aria-label="知识条目详情">
    <header class="knowledge-detail__head">
      <div>
        <p class="knowledge-detail__eyebrow">Knowledge entry</p>
        <h2 ref="detailHeading" tabindex="-1">条目详情</h2>
      </div>
      <button type="button" aria-label="关闭条目详情" @click="emit('close')"><X :size="17" /></button>
    </header>

    <StatePanel v-if="(detailPhase === 'idle' || detailPhase === 'loading') && !entry" state="loading" title="正在读取条目详情" />
    <StatePanel v-else-if="detailPhase === 'error' && !entry" state="error" :description="entryResource?.errorMessage ?? undefined" @retry="emit('retryEntry')" />

    <template v-else-if="entry">
      <!-- A forced reload keeps the previous content mounted (D4 layer three); only a detail-free
           first load shows the full loading panel above. -->
      <StatePanel v-if="detailPhase === 'loading'" compact state="loading" title="正在回读服务端版本" />
      <StatePanel
        v-if="conflict"
        compact
        state="conflict"
        title="条目已被其他成员更新"
        @retry="emit('retryEntry')"
      >
        <template #description>
          {{ command?.errorMessage }}<template v-if="command?.currentVersion != null">（服务端当前版本 v{{ command.currentVersion }}）</template>本地草稿仍保留在编辑器中。
        </template>
        <template #action>
          <BaseButton variant="secondary" size="small" @click="loadServerDraft">载入服务端内容</BaseButton>
        </template>
      </StatePanel>
      <StatePanel v-else-if="commandError" compact state="error" title="知识库命令失败" :description="commandError ?? undefined" />
      <p v-else-if="receipt" class="knowledge-detail__receipt" role="status">命令已确认：{{ receipt.commandId.slice(0, 8) }}，事件版本 v{{ receipt.committedVersion }}。</p>

      <section class="knowledge-detail__facts">
        <div class="knowledge-detail__identity">
          <FileText :size="16" aria-hidden="true" />
          <span class="knowledge-detail__key">{{ entry.entryKey }}</span>
        </div>
        <div class="knowledge-detail__badges">
          <StatusBadge tone="neutral">{{ knowledgeCategoryLabels[entry.category] }}</StatusBadge>
          <StatusBadge :tone="entry.status === 'PUBLISHED' ? 'info' : entry.status === 'RETIRED' ? 'warning' : entry.status === 'DELETED' ? 'danger' : 'neutral'">{{ knowledgeEntryStatusLabels[entry.status] }}</StatusBadge>
          <StatusBadge v-if="entry.status !== 'DELETED'" :tone="entry.indexStatus === 'INDEXED' ? 'success' : entry.indexStatus === 'FAILED' ? 'warning' : 'neutral'">{{ knowledgeIndexStatusLabels[entry.indexStatus] }}</StatusBadge>
        </div>
        <p v-if="entry.status !== 'DELETED' && knowledgeIndexStatusHints[entry.indexStatus]" class="knowledge-detail__hint">{{ knowledgeIndexStatusHints[entry.indexStatus] }}</p>
        <dl class="knowledge-detail__meta">
          <div><dt>最新修订</dt><dd>r{{ entry.latestRevision }}</dd></div>
          <div><dt>生效修订</dt><dd>{{ entry.effectiveRevision == null ? '无' : `r${entry.effectiveRevision}` }}</dd></div>
          <div><dt>最近更新</dt><dd><RelativeTime :value="entry.updatedAt" /></dd></div>
        </dl>
        <details v-if="canManage" class="knowledge-detail__audit">
          <summary>审计信息</summary>
          <dl>
            <div><dt>条目 ID</dt><dd class="knowledge-detail__mono">{{ entry.id }}</dd></div>
            <div><dt>创建者</dt><dd class="knowledge-detail__mono">{{ entry.createdBy }}</dd></div>
            <div><dt>最近更新者</dt><dd class="knowledge-detail__mono">{{ entry.updatedBy }}</dd></div>
            <div v-if="entry.origin"><dt>蒸馏来源</dt><dd class="knowledge-detail__mono">执行 {{ entry.origin.taskExecutionId }} · 第 {{ entry.origin.attempt }} 次尝试</dd></div>
            <div><dt>创建时间</dt><dd>{{ entry.createdAt }}</dd></div>
          </dl>
        </details>
      </section>

      <template v-if="tombstone">
        <StatePanel
          state="cancelled"
          title="此条目已删除"
          description="删除墓碑保留在列表中以维持历史引用的完整性；版本内容仍可只读查阅。"
        />
        <KnowledgeVersionHistory
          :history="history"
          :version-resource="versionResource"
          :effective="effective"
          :effective-revision="effectiveRevision"
          :selected-revision="selectedRevision"
          :online="online"
          @select="revision => emit('selectRevision', revision)"
          @load-more="emit('loadMoreVersions')"
          @retry="emit('retryVersions')"
        />
      </template>

      <template v-else>
        <nav v-if="canManage" class="knowledge-detail__actions" :aria-label="`条目 ${entry.entryKey} 管理操作`">
          <BaseButton
            variant="primary"
            size="small"
            :loading="publishing"
            :disabled="disabledReason !== null || commandBusy"
            :title="disabledReason ?? undefined"
            @click="emit('publish')"
          >发布当前草稿</BaseButton>
          <BaseButton
            v-if="entry.status === 'PUBLISHED'"
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

        <BaseTabs :model-value="tab" :tabs="tabs" @update:model-value="emit('changeTab', $event as 'draft' | 'versions')" />

        <KnowledgeDraftEditor
          v-show="tab === 'draft'"
          ref="editor"
          :entry="entry"
          :scope="scope"
          :can-manage="canManage"
          :saving="saving"
          @save="input => emit('save', input)"
        />
        <KnowledgeVersionHistory
          v-show="tab === 'versions'"
          :history="history"
          :version-resource="versionResource"
          :effective="effective"
          :effective-revision="effectiveRevision"
          :selected-revision="selectedRevision"
          :online="online"
          @select="revision => emit('selectRevision', revision)"
          @load-more="emit('loadMoreVersions')"
          @retry="emit('retryVersions')"
        />
      </template>
    </template>
  </aside>
</template>

<style scoped>
.knowledge-detail { display: flex; min-width: 0; flex-direction: column; gap: var(--cs-space-12); }
.knowledge-detail__head { display: flex; align-items: flex-start; justify-content: space-between; gap: var(--cs-space-8); }
.knowledge-detail__head > div { display: flex; flex-direction: column; gap: var(--cs-space-2); }
.knowledge-detail__eyebrow { margin: 0; color: var(--cs-text-muted); font-size: var(--cs-text-xs); text-transform: uppercase; letter-spacing: 0.04em; }
.knowledge-detail__head h2 { margin: 0; font-size: var(--cs-text-lg); }
.knowledge-detail__head button { display: flex; padding: var(--cs-space-4); border: none; border-radius: var(--cs-radius-sm, 4px); background: none; color: var(--cs-text-muted); cursor: pointer; }
.knowledge-detail__head button:focus-visible { outline: none; box-shadow: var(--cs-focus-ring); }
.knowledge-detail__facts { display: flex; flex-direction: column; gap: var(--cs-space-8); }
.knowledge-detail__identity { display: flex; align-items: center; gap: var(--cs-space-8); }
.knowledge-detail__key { overflow-wrap: anywhere; font-family: var(--cs-font-mono, ui-monospace, monospace); font-size: var(--cs-text-base); font-weight: var(--cs-weight-semibold); }
.knowledge-detail__badges { display: flex; flex-wrap: wrap; gap: var(--cs-space-4); }
.knowledge-detail__hint { margin: 0; color: var(--cs-text-muted); font-size: var(--cs-text-xs); }
.knowledge-detail__meta { display: flex; margin: 0; flex-wrap: wrap; gap: var(--cs-space-16); }
.knowledge-detail__meta div, .knowledge-detail__audit div { display: flex; flex-direction: column; gap: var(--cs-space-2); }
.knowledge-detail__meta dt, .knowledge-detail__audit dt { color: var(--cs-text-muted); font-size: var(--cs-text-xs); }
.knowledge-detail__meta dd, .knowledge-detail__audit dd { margin: 0; font-size: var(--cs-text-sm); overflow-wrap: anywhere; }
.knowledge-detail__audit { border: 1px solid var(--cs-border); border-radius: var(--cs-radius-md); padding: var(--cs-space-8); }
.knowledge-detail__audit summary { color: var(--cs-text-muted); font-size: var(--cs-text-xs); cursor: pointer; }
.knowledge-detail__audit dl { display: flex; margin: var(--cs-space-8) 0 0; flex-direction: column; gap: var(--cs-space-8); }
.knowledge-detail__mono { font-family: var(--cs-font-mono, ui-monospace, monospace); font-size: var(--cs-text-xs); }
.knowledge-detail__actions { display: flex; flex-wrap: wrap; align-items: center; gap: var(--cs-space-8); }
.knowledge-detail__reason { color: var(--cs-text-muted); font-size: var(--cs-text-xs); }
.knowledge-detail__receipt { margin: 0; padding: var(--cs-space-8); border: 1px solid var(--cs-border-accent); border-radius: var(--cs-radius-md); background: var(--cs-surface-accent); color: var(--cs-text); font-size: var(--cs-text-xs); }
</style>
