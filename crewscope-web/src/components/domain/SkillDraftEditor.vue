<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useDirtyForm } from '../../composables/useDirtyForm'
import { parseSkillDocument, validateSkillDocument } from '../../domains/skill/document'
import { SKILL_CONTENT_MAX, type SkillSummary, type UpdateSkillDraftInput } from '../../domains/skill/types'
import BaseButton from '../base/BaseButton.vue'
import BaseTextarea from '../base/BaseTextarea.vue'
import StatePanel from '../feedback/StatePanel.vue'
import SkillDiffView from './SkillDiffView.vue'

export interface SkillDraftScope {
  accountId: string
  principalId: string
  organizationId: string
  teamId: string
}

const props = defineProps<{
  skill: SkillSummary
  scope: SkillDraftScope
  canManage: boolean
  saving: boolean
  /** Last committed content: the server draft, or the effective revision when no draft exists. */
  baselineContent: string
}>()

const emit = defineEmits<{
  save: [input: UpdateSkillDraftInput]
}>()

// The head carries the draft; a null draft with no editing session is the read-only rest state
// (everything published, or the DISABLED head awaiting revival via a new draft + publish).
const drafting = ref(props.skill.draft !== null)
const content = ref(props.skill.draft?.content ?? '')
const restored = ref(false)

const formSnapshot = computed(() => ({ content: content.value }))
const draftScope = computed(() => ({ ...props.scope, objectId: props.skill.id }))
const dirtyForm = useDirtyForm(formSnapshot, {
  draftScope,
  title: '放弃未保存的草稿修改？',
  description: '当前草稿有未保存内容，离开后这些修改会丢失。',
  confirmLabel: '放弃修改',
  cancelLabel: '继续编辑',
})
const draftAvailable = ref(Boolean(dirtyForm.restoreDraft()))
watch(formSnapshot, value => dirtyForm.sync(value))

watch(() => props.skill.id, () => {
  drafting.value = props.skill.draft !== null
  content.value = props.skill.draft?.content ?? ''
  restored.value = false
  dirtyForm.markClean()
  draftAvailable.value = Boolean(dirtyForm.restoreDraft())
})

// A refreshed head whose draft equals the form confirms the save round-trip; a different server
// draft (409 reload) must NOT overwrite the editor — the conflict panel gates applyServerDraft.
watch(() => props.skill.draft, draft => {
  if (draft?.content === content.value) {
    dirtyForm.markClean()
    dirtyForm.clearDraft()
    draftAvailable.value = false
  }
})

const parsed = computed(() => parseSkillDocument(content.value))
const documentError = computed(() => validateSkillDocument(content.value, props.skill.skillKey))
const contentOver = computed(() => content.value.length > SKILL_CONTENT_MAX)
const saveDisabledReason = computed<string | null>(() => {
  if (!props.canManage) return '需要 Skill 管理权限'
  if (props.saving) return null
  if (content.value.trim() === '') return '正文不能为空'
  if (contentOver.value) return `正文超出 ${SKILL_CONTENT_MAX} 字上限`
  return documentError.value
})
const saveDisabled = computed(() => saveDisabledReason.value !== null || props.saving)
/** The read-only lock for members without skill:manage is a permission guard, so its spoken
 *  reason must be programmatically tied to the locked controls. */
const readonlyReason = computed(() => !props.canManage ? `${props.skill.id}-draft-readonly` : undefined)

const diffVisible = computed(() => drafting.value && !documentError.value && content.value !== props.baselineContent)
const baselineLabel = computed(() => props.skill.draft === null ? '生效版本' : '已保存草稿')

function submit(): void {
  if (saveDisabled.value) return
  emit('save', { content: content.value })
}

function restoreDraft(): void {
  const snapshot = dirtyForm.restoreDraft() as typeof formSnapshot.value | null
  if (!snapshot) return
  content.value = snapshot.content
  restored.value = true
  dirtyForm.markDirty()
}

function startDraft(): void {
  drafting.value = true
  content.value = `---\nname: ${props.skill.skillKey}\ndescription: \n---\n\n`
  dirtyForm.markClean()
}

/** Overwrites the form from the current head draft — callers gate this behind confirmDiscard. */
function applyServerDraft(): void {
  content.value = props.skill.draft?.content ?? ''
  restored.value = false
  drafting.value = props.skill.draft !== null
  dirtyForm.markClean()
}

defineExpose({ applyServerDraft, confirmDiscard: dirtyForm.confirmDiscard, isDirty: dirtyForm.isDirty })
</script>

<template>
  <div class="draft-editor">
    <section v-if="draftAvailable" class="draft-editor__recovery" role="status">
      <div>
        <strong>发现未保存的本地草稿</strong>
        <span>上次离开时已安全保留当前浏览器中的草稿修改。</span>
      </div>
      <div>
        <BaseButton type="button" variant="secondary" size="small" @click="restoreDraft">恢复草稿</BaseButton>
        <BaseButton type="button" variant="ghost" size="small" @click="dirtyForm.clearDraft(); draftAvailable = false">丢弃</BaseButton>
      </div>
    </section>

    <p v-if="restored" class="draft-editor__restored" role="status">已恢复本地草稿，内容尚未保存到服务端。</p>

    <StatePanel
      v-if="!drafting"
      state="empty"
      title="当前没有未保存的草稿"
      :description="skill.status === 'DISABLED'
        ? '此 Skill 已禁用；修改草稿并再次发布即可产出新修订、恢复生效。'
        : '已发布的内容全部生效，可由 Skill 管理员继续修订。'"
    >
      <template #action>
        <BaseButton v-if="canManage" variant="secondary" size="small" @click="startDraft">开始新草稿</BaseButton>
      </template>
    </StatePanel>

    <form v-else class="draft-editor__form" @submit.prevent="submit">
      <div class="draft-editor__field">
        <label class="draft-editor__label" :for="`${skill.id}-draft-content`">
          草稿全文（frontmatter + 正文）
          <span class="draft-editor__count" :class="{ over: contentOver }">{{ content.length }}/{{ SKILL_CONTENT_MAX }}</span>
        </label>
        <BaseTextarea
          :id="`${skill.id}-draft-content`"
          v-model="content"
          :rows="14"
          :disabled="!canManage || saving"
          :invalid="documentError !== null || contentOver || undefined"
          :aria-describedby="readonlyReason"
          placeholder="--- 开头的 frontmatter（name 与 skillKey 一致），随后是注入给任务的正文。"
        />
        <small class="draft-editor__hint">frontmatter 恰好含 name（必须等于 skillKey）与 description（≤200 字）两字段；正文即运行时注入的指令来源。</small>
        <small v-if="documentError" class="draft-editor__error">{{ documentError }}</small>
      </div>

      <div class="draft-editor__preview" aria-label="frontmatter 派生预览">
        <p class="draft-editor__preview-title">frontmatter 派生值</p>
        <dl>
          <div><dt>name</dt><dd class="draft-editor__mono">{{ parsed.name ?? '—' }}</dd></div>
          <div><dt>description</dt><dd>{{ parsed.description ?? '—' }}</dd></div>
        </dl>
      </div>

      <SkillDiffView
        v-if="diffVisible"
        :before="baselineContent"
        :after="content"
        :before-label="baselineLabel"
        after-label="未保存草稿"
      />

      <footer class="draft-editor__actions">
        <BaseButton type="submit" variant="primary" size="medium" :loading="saving" :disabled="saveDisabled">{{ saveDisabledReason ?? '保存草稿' }}</BaseButton>
        <span v-if="saveDisabledReason && !saving && canManage" class="draft-editor__reason">{{ saveDisabledReason }}</span>
        <span v-else-if="!canManage" :id="`${skill.id}-draft-readonly`" class="draft-editor__reason">{{ saveDisabledReason }}</span>
      </footer>
    </form>
  </div>
</template>

<style scoped>
.draft-editor { display: flex; min-width: 0; flex-direction: column; gap: var(--cs-space-12); }
.draft-editor__restored { margin: 0; color: var(--cs-text-muted); font-size: var(--cs-text-xs); }
.draft-editor__recovery { display: flex; align-items: center; justify-content: space-between; gap: var(--cs-space-12); padding: var(--cs-space-12); border: 1px solid var(--cs-border-accent); border-radius: var(--cs-radius-md); background: var(--cs-surface-accent); }
.draft-editor__recovery > div { display: flex; flex-direction: column; gap: var(--cs-space-2); }
.draft-editor__recovery > div:last-child { flex-direction: row; align-items: center; }
.draft-editor__form { display: flex; flex-direction: column; gap: var(--cs-space-12); }
.draft-editor__field { display: flex; flex-direction: column; gap: var(--cs-space-4); }
.draft-editor__label { display: flex; justify-content: space-between; color: var(--cs-text-muted); font-size: var(--cs-text-xs); }
.draft-editor__count.over { color: var(--cs-danger); font-weight: var(--cs-weight-semibold); }
.draft-editor__hint { color: var(--cs-text-muted); font-size: var(--cs-text-xs); }
.draft-editor__error { color: var(--cs-danger); font-size: var(--cs-text-xs); }
.draft-editor__preview { display: flex; flex-direction: column; gap: var(--cs-space-4); padding: var(--cs-space-8); border: 1px solid var(--cs-border); border-radius: var(--cs-radius-md); }
.draft-editor__preview-title { margin: 0; color: var(--cs-text-muted); font-size: var(--cs-text-xs); }
.draft-editor__preview dl { display: flex; margin: 0; flex-direction: column; gap: var(--cs-space-4); }
.draft-editor__preview dl div { display: flex; flex-wrap: wrap; gap: var(--cs-space-8); }
.draft-editor__preview dt { color: var(--cs-text-muted); font-size: var(--cs-text-xs); }
.draft-editor__preview dd { margin: 0; min-width: 0; font-size: var(--cs-text-sm); overflow-wrap: anywhere; }
.draft-editor__mono { font-family: var(--cs-font-mono, ui-monospace, monospace); }
.draft-editor__actions { display: flex; align-items: center; gap: var(--cs-space-8); }
.draft-editor__reason { color: var(--cs-text-muted); font-size: var(--cs-text-xs); }
</style>
