<script setup lang="ts">
import { computed, useTemplateRef } from 'vue'
import { parseSkillDocument, validateSkillDocument } from '../../domains/skill/document'
import { SKILL_CONTENT_MAX, type SkillSummary, type UpdateSkillDraftInput } from '../../domains/skill/types'
import BaseTextarea from '../base/BaseTextarea.vue'
import BaseDraftEditor from '../shared/BaseDraftEditor.vue'
import SkillDiffView from './SkillDiffView.vue'

/** Skill twin of the draft editor: the shared lifecycle plus the frontmatter document form. */
export interface SkillDraftScope {
  accountId: string
  principalId: string
  organizationId: string
  teamId: string
}

/** Form snapshot: the whole document (frontmatter + body) as one textarea value. */
interface SkillDraftForm {
  content: string
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

const emptyDescription = computed(() => props.skill.status === 'DISABLED'
  ? '此 Skill 已禁用；修改草稿并再次发布即可产出新修订、恢复生效。'
  : '已发布的内容全部生效，可由 Skill 管理员继续修订。')

function fromServer(draft: SkillSummary['draft']): SkillDraftForm {
  return { content: draft?.content ?? '' }
}

function validate(form: SkillDraftForm): string | null {
  if (form.content.trim() === '') return '正文不能为空'
  if (form.content.length > SKILL_CONTENT_MAX) return `正文超出 ${SKILL_CONTENT_MAX} 字上限`
  return validateSkillDocument(form.content, props.skill.skillKey)
}

// Expose contract passthrough (defineExpose does not tunnel through wrappers).
const editor = useTemplateRef<{ applyServerDraft: () => void, confirmDiscard: () => Promise<boolean>, isDirty: boolean }>('editor')
defineExpose({
  applyServerDraft: () => editor.value?.applyServerDraft(),
  confirmDiscard: () => editor.value?.confirmDiscard() ?? Promise.resolve(true),
  isDirty: computed(() => editor.value?.isDirty ?? false),
})
</script>

<template>
  <BaseDraftEditor
    ref="editor"
    :server-draft="skill.draft"
    :entry-id="skill.id"
    :scope="scope"
    :can-manage="canManage"
    :saving="saving"
    manage-permission-reason="需要 Skill 管理权限"
    :empty-description="emptyDescription"
    :new-draft="() => ({ content: `---\nname: ${skill.skillKey}\ndescription: \n---\n\n` })"
    :from-server="fromServer"
    :is-synced="(draft, form) => draft.content === form.content"
    :validate="validate"
    @save="form => emit('save', { content: form.content })"
  >
    <template #fields="{ snapshot, readonlyReason }">
      <div class="draft-editor__field">
        <label class="draft-editor__label" :for="`${skill.id}-draft-content`">
          草稿全文（frontmatter + 正文）
          <span class="draft-editor__count" :class="{ over: snapshot.content.length > SKILL_CONTENT_MAX }">{{ snapshot.content.length }}/{{ SKILL_CONTENT_MAX }}</span>
        </label>
        <BaseTextarea
          :id="`${skill.id}-draft-content`"
          v-model="snapshot.content"
          :rows="14"
          :disabled="!canManage || saving"
          :invalid="validateSkillDocument(snapshot.content, skill.skillKey) !== null || snapshot.content.length > SKILL_CONTENT_MAX || undefined"
          :aria-describedby="[readonlyReason, validateSkillDocument(snapshot.content, skill.skillKey) ? `${skill.id}-doc-error` : ''].filter(Boolean).join(' ') || undefined"
          placeholder="--- 开头的 frontmatter（name 与 skillKey 一致），随后是注入给任务的正文。"
        />
        <small class="draft-editor__hint">frontmatter 恰好含 name（必须等于 skillKey）与 description（≤200 字）两字段；正文即运行时注入的指令来源。</small>
        <small v-if="validateSkillDocument(snapshot.content, skill.skillKey)" :id="`${skill.id}-doc-error`" class="draft-editor__error">{{ validateSkillDocument(snapshot.content, skill.skillKey) }}</small>
      </div>
    </template>
    <template #panels="{ snapshot }">
      <div class="draft-editor__preview" aria-label="frontmatter 派生预览">
        <p class="draft-editor__preview-title">frontmatter 派生值</p>
        <dl>
          <div><dt>name</dt><dd class="draft-editor__mono">{{ parseSkillDocument(snapshot.content).name ?? '—' }}</dd></div>
          <div><dt>description</dt><dd>{{ parseSkillDocument(snapshot.content).description ?? '—' }}</dd></div>
        </dl>
      </div>

      <SkillDiffView
        v-if="validateSkillDocument(snapshot.content, skill.skillKey) === null && snapshot.content !== baselineContent"
        :before="baselineContent"
        :after="snapshot.content"
        :before-label="skill.draft === null ? '生效版本' : '已保存草稿'"
        after-label="未保存草稿"
      />
    </template>
  </BaseDraftEditor>
</template>

<style scoped>
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
</style>
