<script setup lang="ts">
import { ListChecks } from '@lucide/vue'
import { computed, useTemplateRef } from 'vue'
import { skillStatusLabels } from '../../domains/skill/labels'
import type { SkillSummary, SkillVersion, UpdateSkillDraftInput } from '../../domains/skill/types'
import type { Etagged } from '../../domains/settings/types'
import type { SkillCommandState, SkillResource, SkillVersionPageResource } from '../../domains/skill/store'
import type { SkillDraftScope } from './SkillDraftEditor.vue'
import SkillDraftEditor from './SkillDraftEditor.vue'
import SkillVersionHistory from './SkillVersionHistory.vue'
import BaseButton from '../base/BaseButton.vue'
import StatusBadge from '../base/StatusBadge.vue'
import BaseDetailPage from '../shared/BaseDetailPage.vue'

/** Skill twin of the detail aside: the shared shell plus the disable note, per-action
 * disabled reasons and the rollback wiring. */
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
const editor = useTemplateRef<{ applyServerDraft: () => void, confirmDiscard: () => Promise<boolean> }>('editor')

const saving = computed(() => props.command?.phase === 'pending' && props.command.operation === 'save-draft')
const publishing = computed(() => props.command?.phase === 'pending' && props.command.operation === 'publish')
const disabling = computed(() => props.command?.phase === 'pending' && props.command.operation === 'disable')
const rollingBack = computed(() => props.command?.phase === 'pending' && props.command.operation === 'rollback')
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

/** The reload never overwrites the editor without an explicit discard. */
async function loadServerDraft(): Promise<void> {
  if (editor.value && !await editor.value.confirmDiscard()) return
  editor.value?.applyServerDraft()
}
</script>

<template>
  <BaseDetailPage
    :entry-resource="skillResource"
    :command="command"
    :tab="tab"
    :online="online"
    :can-manage="canManage"
    eyebrow="Team skill"
    title="Skill 详情"
    detail-label="Skill 详情"
    close-label="关闭 Skill 详情"
    loading-title="正在读取 Skill 详情"
    conflict-title="Skill 已被其他成员更新"
    command-failed-title="Skill 命令失败"
    audit-id-label="Skill ID"
    tabs-id="skill-detail-tabs"
    @close="emit('close')"
    @retry-entry="emit('retrySkill')"
    @load-server-draft="loadServerDraft"
    @change-tab="tab => emit('changeTab', tab)"
  >
    <template #identity="{ entry: current }">
      <ListChecks :size="16" aria-hidden="true" />
      <span class="detail-panel__key">{{ current.skillKey }}</span>
    </template>

    <template #badges="{ entry: current }">
      <StatusBadge :tone="current.status === 'PUBLISHED' ? 'info' : current.status === 'DISABLED' ? 'danger' : 'neutral'">{{ skillStatusLabels[current.status] }}</StatusBadge>
    </template>

    <template #facts-extra="{ entry: current }">
      <!-- Acceptance: the disable reason stays visible on the read side for every member. -->
      <div v-if="current.status === 'DISABLED'" class="skill-detail__disabled" role="note">
        <p class="skill-detail__disabled-title">此 Skill 已禁用{{ current.disableReason ? `：${current.disableReason}` : '。' }}</p>
        <p class="skill-detail__disabled-note">禁用即下线：历史版本全部保留{{ current.effectiveRevision != null ? `，最后生效修订为 r${current.effectiveRevision}` : '' }}，不会从目录删除任何证据。</p>
      </div>
    </template>

    <template #actions>
      <nav v-if="canManage" class="skill-detail__actions" :aria-label="`Skill ${skill?.skillKey} 管理操作`">
        <BaseButton
          variant="primary"
          size="small"
          :loading="publishing"
          :disabled="publishDisabledReason !== null || commandBusy"
          :title="publishDisabledReason ?? undefined"
          @click="emit('publish')"
        >发布当前草稿</BaseButton>
        <BaseButton
          v-if="skill?.status === 'PUBLISHED'"
          variant="danger"
          size="small"
          :loading="disabling"
          :disabled="disableDisabledReason !== null || commandBusy"
          :title="disableDisabledReason ?? undefined"
          @click="emit('disable')"
        >禁用 Skill</BaseButton>
        <span v-if="disabledReason" class="skill-detail__reason">{{ disabledReason }}</span>
      </nav>
    </template>

    <template #draft="{ entry: current }">
      <SkillDraftEditor
        ref="editor"
        :skill="current"
        :scope="scope"
        :can-manage="canManage"
        :saving="saving"
        :baseline-content="baselineContent"
        @save="input => emit('save', input)"
      />
    </template>

    <template #versions>
      <SkillVersionHistory
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
  </BaseDetailPage>
</template>

<style scoped>
.skill-detail__disabled { display: flex; flex-direction: column; gap: var(--cs-space-4); padding: var(--cs-space-12); border: 1px solid var(--cs-danger); border-radius: var(--cs-radius-md); }
.skill-detail__disabled-title { margin: 0; color: var(--cs-danger); font-size: var(--cs-text-sm); font-weight: var(--cs-weight-semibold); }
.skill-detail__disabled-note { margin: 0; color: var(--cs-text-muted); font-size: var(--cs-text-xs); }
.skill-detail__actions { display: flex; flex-wrap: wrap; align-items: center; gap: var(--cs-space-8); }
.skill-detail__reason { color: var(--cs-text-muted); font-size: var(--cs-text-xs); }
</style>
