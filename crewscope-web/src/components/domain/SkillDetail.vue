<script setup lang="ts">
import { ListChecks, X } from '@lucide/vue'
import { computed, nextTick, useTemplateRef, watch } from 'vue'
import { skillStatusLabels } from '../../domains/skill/labels'
import type { SkillSummary, SkillVersion, UpdateSkillDraftInput } from '../../domains/skill/types'
import type { Etagged } from '../../domains/settings/types'
import type { SkillCommandState, SkillResource, SkillVersionPageResource } from '../../domains/skill/store'
import type { SkillDraftScope } from './SkillDraftEditor.vue'
import SkillDraftEditor from './SkillDraftEditor.vue'
import SkillVersionHistory from './SkillVersionHistory.vue'
import BaseButton from '../base/BaseButton.vue'
import BaseTabs from '../base/BaseTabs.vue'
import RelativeTime from '../base/RelativeTime.vue'
import StatusBadge from '../base/StatusBadge.vue'
import StatePanel from '../feedback/StatePanel.vue'

const props = defineProps<{
  skillResource: SkillResource<Etagged<SkillSummary>> | null | undefined
  command: SkillCommandState | null
  history: SkillVersionPageResource
  versionResource: SkillResource<Etagged<SkillVersion>> | null | undefined
  effective: SkillResource<Etagged<SkillVersion>> | null | undefined
  effectiveRevision: number | null
  selectedRevision: number | null
  tab: 'draft' | 'versions'
  scope: SkillDraftScope
  canManage: boolean
  online: boolean
  /** The editor diff baseline comes from the page, which owns the effective-version resource. */
  baselineContent: string
}>()

const emit = defineEmits<{
  close: []
  save: [input: UpdateSkillDraftInput]
  publish: []
  disable: []
  rollback: [revision: number]
  changeTab: [tab: 'draft' | 'versions']
  selectRevision: [revision: number]
  loadMoreVersions: []
  retrySkill: []
  retryVersions: []
}>()

const skill = computed(() => props.skillResource?.value?.value ?? null)
const detailPhase = computed(() => props.skillResource?.phase ?? 'idle')
const editor = useTemplateRef<{ applyServerDraft: () => void, confirmDiscard: () => Promise<boolean> }>('editor')
const detailHeading = useTemplateRef<HTMLElement>('detailHeading')

const tabs = [
  { label: '草稿', value: 'draft' },
  { label: '版本', value: 'versions' },
]

const saving = computed(() => props.command?.phase === 'pending' && props.command.operation === 'save-draft')
const publishing = computed(() => props.command?.phase === 'pending' && props.command.operation === 'publish')
const disabling = computed(() => props.command?.phase === 'pending' && props.command.operation === 'disable')
const rollingBack = computed(() => props.command?.phase === 'pending' && props.command.operation === 'rollback')
const conflict = computed(() => props.command?.phase === 'conflict')
const commandError = computed(() => props.command?.phase === 'error' ? props.command.errorMessage : null)
const receipt = computed(() => props.command?.phase === 'success' ? props.command.receipt : null)
const commandBusy = computed(() => props.command?.phase === 'pending')
const disabledReason = computed(() => {
  if (!props.online) return '离线期间管理命令保持关闭'
  return null
})
/** Publish requires a draft on the head; a draft-less PUBLISHED head has nothing to publish. */
const publishDisabledReason = computed<string | null>(() => {
  if (disabledReason.value !== null) return disabledReason.value
  if (skill.value?.draft === null) return '没有未保存草稿可发布'
  return null
})
const disableDisabledReason = computed<string | null>(() => {
  if (disabledReason.value !== null) return disabledReason.value
  if (skill.value?.status !== 'PUBLISHED') return '仅已发布状态可禁用'
  return null
})
/** Rollback needs a live effective pointer to diverge from (contract §3: target ≠ effective). */
const canRollback = computed(() => skill.value?.status === 'PUBLISHED' && props.effectiveRevision !== null)

watch(
  () => [skill.value?.id, detailPhase.value] as const,
  async ([skillId, phase]) => {
    if (!skillId || (phase !== 'ready' && phase !== 'error')) return
    await nextTick()
    detailHeading.value?.focus()
  },
)

/** The reload never overwrites the editor without an explicit discard. */
async function loadServerDraft(): Promise<void> {
  if (editor.value && !await editor.value.confirmDiscard()) return
  editor.value?.applyServerDraft()
}
</script>

<template>
  <aside class="skill-detail panel" aria-label="Skill 详情">
    <header class="skill-detail__head">
      <div>
        <p class="skill-detail__eyebrow">Team skill</p>
        <h2 ref="detailHeading" tabindex="-1">Skill 详情</h2>
      </div>
      <button type="button" aria-label="关闭 Skill 详情" @click="emit('close')"><X :size="17" /></button>
    </header>

    <StatePanel v-if="(detailPhase === 'idle' || detailPhase === 'loading') && !skill" state="loading" title="正在读取 Skill 详情" />
    <StatePanel v-else-if="detailPhase === 'error' && !skill" state="error" :description="skillResource?.errorMessage ?? undefined" @retry="emit('retrySkill')" />

    <template v-else-if="skill">
      <!-- A forced reload keeps the previous content mounted; only a detail-free first load
           shows the full loading panel above. -->
      <StatePanel v-if="detailPhase === 'loading'" compact state="loading" title="正在回读服务端版本" />
      <StatePanel
        v-if="conflict"
        compact
        state="conflict"
        title="Skill 已被其他成员更新"
        @retry="emit('retrySkill')"
      >
        <template #description>
          {{ command?.errorMessage }}<template v-if="command?.currentVersion != null">（服务端当前版本 v{{ command.currentVersion }}）</template>本地草稿仍保留在编辑器中。
        </template>
        <template #action>
          <BaseButton variant="secondary" size="small" @click="loadServerDraft">载入服务端内容</BaseButton>
        </template>
      </StatePanel>
      <StatePanel v-else-if="commandError" compact state="error" title="Skill 命令失败" :description="commandError ?? undefined" />
      <p v-else-if="receipt" class="skill-detail__receipt" role="status">命令已确认：{{ receipt.commandId.slice(0, 8) }}，事件版本 v{{ receipt.committedVersion }}。</p>

      <section class="skill-detail__facts">
        <div class="skill-detail__identity">
          <ListChecks :size="16" aria-hidden="true" />
          <span class="skill-detail__key">{{ skill.skillKey }}</span>
        </div>
        <div class="skill-detail__badges">
          <StatusBadge :tone="skill.status === 'PUBLISHED' ? 'info' : skill.status === 'DISABLED' ? 'danger' : 'neutral'">{{ skillStatusLabels[skill.status] }}</StatusBadge>
        </div>

        <!-- Acceptance: the disable reason stays visible on the read side for every member. -->
        <div v-if="skill.status === 'DISABLED'" class="skill-detail__disabled" role="note">
          <p class="skill-detail__disabled-title">此 Skill 已禁用{{ skill.disableReason ? `：${skill.disableReason}` : '。' }}</p>
          <p class="skill-detail__disabled-note">禁用即下线：历史版本全部保留{{ skill.effectiveRevision != null ? `，最后生效修订为 r${skill.effectiveRevision}` : '' }}，不会从目录删除任何证据。</p>
        </div>

        <dl class="skill-detail__meta">
          <div><dt>最新修订</dt><dd>r{{ skill.latestRevision }}</dd></div>
          <div><dt>生效修订</dt><dd>{{ skill.effectiveRevision == null ? '无' : `r${skill.effectiveRevision}` }}</dd></div>
          <div><dt>最近更新</dt><dd><RelativeTime :value="skill.updatedAt" /></dd></div>
        </dl>
        <details v-if="canManage" class="skill-detail__audit">
          <summary>审计信息</summary>
          <dl>
            <div><dt>Skill ID</dt><dd class="skill-detail__mono">{{ skill.id }}</dd></div>
            <div><dt>创建者</dt><dd class="skill-detail__mono">{{ skill.createdBy }}</dd></div>
            <div><dt>最近更新者</dt><dd class="skill-detail__mono">{{ skill.updatedBy }}</dd></div>
            <div v-if="skill.origin"><dt>蒸馏来源</dt><dd class="skill-detail__mono">执行 {{ skill.origin.taskExecutionId }} · 第 {{ skill.origin.attempt }} 次尝试</dd></div>
            <div><dt>创建时间</dt><dd>{{ skill.createdAt }}</dd></div>
          </dl>
        </details>
      </section>

      <nav v-if="canManage" class="skill-detail__actions" :aria-label="`Skill ${skill.skillKey} 管理操作`">
        <BaseButton
          variant="primary"
          size="small"
          :loading="publishing"
          :disabled="publishDisabledReason !== null || commandBusy"
          :title="publishDisabledReason ?? undefined"
          @click="emit('publish')"
        >发布当前草稿</BaseButton>
        <BaseButton
          v-if="skill.status === 'PUBLISHED'"
          variant="danger"
          size="small"
          :loading="disabling"
          :disabled="disableDisabledReason !== null || commandBusy"
          :title="disableDisabledReason ?? undefined"
          @click="emit('disable')"
        >禁用 Skill</BaseButton>
        <span v-if="disabledReason" class="skill-detail__reason">{{ disabledReason }}</span>
      </nav>

      <BaseTabs :model-value="tab" :tabs="tabs" @update:model-value="emit('changeTab', $event as 'draft' | 'versions')" />

      <SkillDraftEditor
        v-show="tab === 'draft'"
        ref="editor"
        :skill="skill"
        :scope="scope"
        :can-manage="canManage"
        :saving="saving"
        :baseline-content="baselineContent"
        @save="input => emit('save', input)"
      />
      <SkillVersionHistory
        v-show="tab === 'versions'"
        :history="history"
        :version-resource="versionResource"
        :effective="effective"
        :effective-revision="effectiveRevision"
        :selected-revision="selectedRevision"
        :online="online"
        :can-manage="canManage"
        :can-rollback="canRollback"
        :rolling-back="rollingBack"
        @select="revision => emit('selectRevision', revision)"
        @load-more="emit('loadMoreVersions')"
        @retry="emit('retryVersions')"
        @rollback="revision => emit('rollback', revision)"
      />
    </template>
  </aside>
</template>

<style scoped>
.skill-detail { display: flex; min-width: 0; flex-direction: column; gap: var(--cs-space-12); }
.skill-detail__head { display: flex; align-items: flex-start; justify-content: space-between; gap: var(--cs-space-8); }
.skill-detail__head > div { display: flex; flex-direction: column; gap: var(--cs-space-2); }
.skill-detail__eyebrow { margin: 0; color: var(--cs-text-muted); font-size: var(--cs-text-xs); text-transform: uppercase; letter-spacing: 0.04em; }
.skill-detail__head h2 { margin: 0; font-size: var(--cs-text-lg); }
.skill-detail__head button { display: flex; padding: var(--cs-space-4); border: none; border-radius: var(--cs-radius-sm, 4px); background: none; color: var(--cs-text-muted); cursor: pointer; }
.skill-detail__head button:focus-visible { outline: none; box-shadow: var(--cs-focus-ring); }
.skill-detail__facts { display: flex; flex-direction: column; gap: var(--cs-space-8); }
.skill-detail__identity { display: flex; align-items: center; gap: var(--cs-space-8); }
.skill-detail__key { overflow-wrap: anywhere; font-family: var(--cs-font-mono, ui-monospace, monospace); font-size: var(--cs-text-base); font-weight: var(--cs-weight-semibold); }
.skill-detail__badges { display: flex; flex-wrap: wrap; gap: var(--cs-space-4); }
.skill-detail__disabled { display: flex; flex-direction: column; gap: var(--cs-space-4); padding: var(--cs-space-12); border: 1px solid var(--cs-danger); border-radius: var(--cs-radius-md); }
.skill-detail__disabled-title { margin: 0; color: var(--cs-danger); font-size: var(--cs-text-sm); font-weight: var(--cs-weight-semibold); }
.skill-detail__disabled-note { margin: 0; color: var(--cs-text-muted); font-size: var(--cs-text-xs); }
.skill-detail__meta { display: flex; margin: 0; flex-wrap: wrap; gap: var(--cs-space-16); }
.skill-detail__meta div, .skill-detail__audit div { display: flex; flex-direction: column; gap: var(--cs-space-2); }
.skill-detail__meta dt, .skill-detail__audit dt { color: var(--cs-text-muted); font-size: var(--cs-text-xs); }
.skill-detail__meta dd, .skill-detail__audit dd { margin: 0; font-size: var(--cs-text-sm); overflow-wrap: anywhere; }
.skill-detail__audit { border: 1px solid var(--cs-border); border-radius: var(--cs-radius-md); padding: var(--cs-space-8); }
.skill-detail__audit summary { color: var(--cs-text-muted); font-size: var(--cs-text-xs); cursor: pointer; }
.skill-detail__audit dl { display: flex; margin: var(--cs-space-8) 0 0; flex-direction: column; gap: var(--cs-space-8); }
.skill-detail__mono { font-family: var(--cs-font-mono, ui-monospace, monospace); font-size: var(--cs-text-xs); }
.skill-detail__actions { display: flex; flex-wrap: wrap; align-items: center; gap: var(--cs-space-8); }
.skill-detail__reason { color: var(--cs-text-muted); font-size: var(--cs-text-xs); }
.skill-detail__receipt { margin: 0; padding: var(--cs-space-8); border: 1px solid var(--cs-border-accent); border-radius: var(--cs-radius-md); background: var(--cs-surface-accent); color: var(--cs-text); font-size: var(--cs-text-xs); }
</style>
