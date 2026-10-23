<script setup lang="ts" generic="E extends EntryLike">
import { X } from '@lucide/vue'
import { computed, nextTick, useTemplateRef, watch } from 'vue'
import BaseButton from '../base/BaseButton.vue'
import BaseTabs from '../base/BaseTabs.vue'
import RelativeTime from '../base/RelativeTime.vue'
import StatePanel from '../feedback/StatePanel.vue'
import type { Etagged, EntryLike, Resource } from './types'

/**
 * Shared detail-aside shell for the skill/knowledge twins (M12b-F04). Owns the heading,
 * the first-load / reload state gates, the conflict + receipt strips, the facts meta and
 * the audit fold, and the draft/versions tab wiring. Domain divergence enters through
 * props (copy) and slots (identity line, badges, actions, panels).
 */
export interface DetailCommandState {
  phase: 'idle' | 'pending' | 'success' | 'error' | 'conflict'
  operation: string | null
  receipt: { commandId: string; committedVersion: number } | null
  errorMessage: string | null
  /** Server head version echoed by a 409 envelope — the conflict panel's only trustworthy source. */
  currentVersion: number | null
}

const props = withDefaults(
  defineProps<{
    entryResource: Resource<Etagged<E>> | null | undefined
    command: DetailCommandState | null
    tab: 'draft' | 'versions'
    online: boolean
    /** Heading copy: eyebrow line, h2, aside aria-label and the close button's label. */
    eyebrow: string
    title: string
    detailLabel: string
    closeLabel: string
    loadingTitle: string
    /** Conflict / command-failure strip copy. */
    conflictTitle: string
    commandFailedTitle: string
    /** Audit fold label for the object id row (条目 ID / Skill ID). */
    auditIdLabel: string
    /** BaseTabs id; panels derive `${tabsId}-panel-draft|versions`. */
    tabsId: string
    /** Tombstone heads (knowledge DELETED) swap actions+tabs for the read-only frame. */
    tombstone?: boolean
    canManage?: boolean
  }>(),
  { tombstone: false, canManage: false },
)

const emit = defineEmits<{
  close: []
  retryEntry: []
  loadServerDraft: []
  changeTab: [tab: 'draft' | 'versions']
}>()

const entry = computed(() => props.entryResource?.value?.value ?? null)
const detailPhase = computed(() => props.entryResource?.phase ?? 'idle')
const detailHeading = useTemplateRef<HTMLElement>('detailHeading')

const tabs = [
  { label: '草稿', value: 'draft' },
  { label: '版本', value: 'versions' },
]

const saving = computed(() => props.command?.phase === 'pending' && props.command.operation === 'save-draft')
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
</script>

<template>
  <aside class="detail-panel panel" :aria-label="detailLabel">
    <header class="detail-panel__head">
      <div>
        <p class="detail-panel__eyebrow">{{ eyebrow }}</p>
        <h2 ref="detailHeading" tabindex="-1">{{ title }}</h2>
      </div>
      <button type="button" :aria-label="closeLabel" @click="emit('close')"><X :size="17" /></button>
    </header>

    <StatePanel v-if="(detailPhase === 'idle' || detailPhase === 'loading') && !entry" state="loading" :title="loadingTitle" />
    <StatePanel v-else-if="detailPhase === 'error' && !entry" state="error" :description="entryResource?.errorMessage ?? undefined" @retry="emit('retryEntry')" />

    <template v-else-if="entry">
      <!-- A forced reload keeps the previous content mounted (D4 layer three); only a
           detail-free first load shows the full loading panel above. -->
      <StatePanel v-if="detailPhase === 'loading'" compact state="loading" title="正在回读服务端版本" />
      <StatePanel
        v-if="conflict"
        compact
        state="conflict"
        :title="conflictTitle"
        @retry="emit('retryEntry')"
      >
        <template #description>
          {{ command?.errorMessage }}<template v-if="command?.currentVersion != null">（服务端当前版本 v{{ command.currentVersion }}）</template>本地草稿仍保留在编辑器中。
        </template>
        <template #action>
          <BaseButton variant="secondary" size="small" @click="emit('loadServerDraft')">载入服务端内容</BaseButton>
        </template>
      </StatePanel>
      <!-- M13b-F11（C2）：命令失败的恢复在面板之外——本地草稿仍保留，按错误修正后重新提交即可。 -->
      <StatePanel v-else-if="commandError" compact state="error" :retryable="false" :title="commandFailedTitle" :description="commandError ?? undefined" />
      <p v-else-if="receipt" class="detail-panel__receipt" role="status">命令已确认：{{ receipt.commandId.slice(0, 8) }}，事件版本 v{{ receipt.committedVersion }}。</p>

      <section class="detail-panel__facts">
        <div class="detail-panel__identity">
          <slot name="identity" :entry="entry" />
        </div>
        <div class="detail-panel__badges">
          <slot name="badges" :entry="entry" />
        </div>
        <slot name="facts-extra" :entry="entry" />
        <dl class="detail-panel__meta">
          <div><dt>最新修订</dt><dd>r{{ entry.latestRevision }}</dd></div>
          <div><dt>生效修订</dt><dd>{{ entry.effectiveRevision == null ? '无' : `r${entry.effectiveRevision}` }}</dd></div>
          <div><dt>最近更新</dt><dd><RelativeTime :value="entry.updatedAt" /></dd></div>
        </dl>
        <details v-if="canManage" class="detail-panel__audit">
          <summary>审计信息</summary>
          <dl>
            <div><dt>{{ auditIdLabel }}</dt><dd class="detail-panel__mono">{{ entry.id }}</dd></div>
            <div><dt>创建者</dt><dd class="detail-panel__mono">{{ entry.createdBy }}</dd></div>
            <div><dt>最近更新者</dt><dd class="detail-panel__mono">{{ entry.updatedBy }}</dd></div>
            <div v-if="entry.origin"><dt>蒸馏来源</dt><dd class="detail-panel__mono">执行 {{ entry.origin.taskExecutionId }} · 第 {{ entry.origin.attempt }} 次尝试</dd></div>
            <div><dt>创建时间</dt><dd>{{ entry.createdAt }}</dd></div>
          </dl>
        </details>
      </section>

      <template v-if="tombstone">
        <slot name="tombstone" :entry="entry" />
        <slot name="versions" :entry="entry" />
      </template>

      <template v-else>
        <slot name="actions" :entry="entry" :disabled-reason="disabledReason" :command-busy="commandBusy" />

        <BaseTabs :id="tabsId" :model-value="tab" :tabs="tabs" @update:model-value="emit('changeTab', $event as 'draft' | 'versions')" />

        <div v-show="tab === 'draft'" role="tabpanel" :aria-labelledby="`${tabsId}-tab-draft`" :id="`${tabsId}-panel-draft`">
          <slot name="draft" :entry="entry" :saving="saving" />
        </div>
        <div v-show="tab === 'versions'" role="tabpanel" :aria-labelledby="`${tabsId}-tab-versions`" :id="`${tabsId}-panel-versions`">
          <slot name="versions" :entry="entry" />
        </div>
      </template>
    </template>
  </aside>
</template>

<style scoped>
.detail-panel { display: flex; min-width: 0; flex-direction: column; gap: var(--cs-space-12); }
.detail-panel__head { display: flex; align-items: flex-start; justify-content: space-between; gap: var(--cs-space-8); }
.detail-panel__head > div { display: flex; flex-direction: column; gap: var(--cs-space-2); }
.detail-panel__eyebrow { margin: 0; color: var(--cs-text-muted); font-size: var(--cs-text-xs); text-transform: uppercase; letter-spacing: 0.04em; }
.detail-panel__head h2 { margin: 0; font-size: var(--cs-text-lg); }
.detail-panel__head button { display: flex; padding: var(--cs-space-4); border: none; border-radius: var(--cs-radius-sm, 4px); background: none; color: var(--cs-text-muted); cursor: pointer; }
.detail-panel__head button:focus-visible { outline: none; box-shadow: var(--cs-focus-ring); }
.detail-panel__facts { display: flex; flex-direction: column; gap: var(--cs-space-8); }
.detail-panel__identity { display: flex; align-items: center; gap: var(--cs-space-8); }
/* The identity line is domain slot content (icon + key); the key typography stays uniform. */
.detail-panel__identity :slotted(.detail-panel__key) { overflow-wrap: anywhere; font-family: var(--cs-font-mono, ui-monospace, monospace); font-size: var(--cs-text-base); font-weight: var(--cs-weight-semibold); }
.detail-panel__badges { display: flex; flex-wrap: wrap; gap: var(--cs-space-4); }
.detail-panel__meta { display: flex; margin: 0; flex-wrap: wrap; gap: var(--cs-space-16); }
.detail-panel__meta div, .detail-panel__audit div { display: flex; flex-direction: column; gap: var(--cs-space-2); }
.detail-panel__meta dt, .detail-panel__audit dt { color: var(--cs-text-muted); font-size: var(--cs-text-xs); }
.detail-panel__meta dd, .detail-panel__audit dd { margin: 0; font-size: var(--cs-text-sm); overflow-wrap: anywhere; }
.detail-panel__audit { border: 1px solid var(--cs-border); border-radius: var(--cs-radius-md); padding: var(--cs-space-8); }
.detail-panel__audit summary { color: var(--cs-text-muted); font-size: var(--cs-text-xs); cursor: pointer; }
.detail-panel__audit dl { display: flex; margin: var(--cs-space-8) 0 0; flex-direction: column; gap: var(--cs-space-8); }
.detail-panel__mono { font-family: var(--cs-font-mono, ui-monospace, monospace); font-size: var(--cs-text-xs); }
.detail-panel__receipt { margin: 0; padding: var(--cs-space-8); border: 1px solid var(--cs-border-accent); border-radius: var(--cs-radius-md); background: var(--cs-surface-accent); color: var(--cs-text); font-size: var(--cs-text-xs); }

/* M12b-F01：悬停反馈补齐——此前这些可点元素对指针移动完全沉默。 */
.detail-panel__head button:hover:not(:disabled) { background: var(--cs-surface-accent); }
.detail-panel__head button:active:not(:disabled) { transform: scale(.97); }
.detail-panel__head button { transition: background-color var(--cs-motion-fast) var(--cs-ease-out), border-color var(--cs-motion-fast) var(--cs-ease-out), transform var(--cs-motion-fast) var(--cs-ease-out); }
.detail-panel__audit summary:hover:not(:disabled) { background: var(--cs-surface-accent); }
.detail-panel__audit summary:active:not(:disabled) { transform: scale(.97); }
.detail-panel__audit summary { transition: background-color var(--cs-motion-fast) var(--cs-ease-out), border-color var(--cs-motion-fast) var(--cs-ease-out), transform var(--cs-motion-fast) var(--cs-ease-out); }
</style>
