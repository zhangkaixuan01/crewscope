<script setup lang="ts">
import { computed, useTemplateRef } from 'vue'
import { knowledgeCategoryLabels } from '../../domains/knowledge/labels'
import {
  knowledgeCategories,
  KNOWLEDGE_CONTENT_MAX,
  KNOWLEDGE_TITLE_MAX,
  type KnowledgeCategory,
  type KnowledgeEntrySummary,
  type UpdateKnowledgeDraftInput,
} from '../../domains/knowledge/types'
import BaseSelect, { type BaseSelectOption } from '../base/BaseSelect.vue'
import BaseTextarea from '../base/BaseTextarea.vue'
import BaseDraftEditor from '../shared/BaseDraftEditor.vue'

/** Knowledge twin of the draft editor: the shared lifecycle plus the three-field form. */
export interface KnowledgeDraftScope {
  accountId: string
  principalId: string
  organizationId: string
  teamId: string
}

/** Form snapshot: the server draft folded with the head category (selects are head state). */
interface EntryDraftForm {
  title: string
  content: string
  category: KnowledgeCategory
}

const props = defineProps<{
  entry: KnowledgeEntrySummary
  scope: KnowledgeDraftScope
  canManage: boolean
  saving: boolean
}>()

const emit = defineEmits<{
  save: [input: UpdateKnowledgeDraftInput]
}>()

const categoryOptions: BaseSelectOption[] = knowledgeCategories.map(value => ({ label: knowledgeCategoryLabels[value], value }))

const emptyDescription = computed(() => props.entry.status === 'PUBLISHED' ? '已发布的内容全部生效，可由知识库管理员继续修订。' : '可由知识库管理员编辑标题、正文与分类。')

function fromServer(draft: KnowledgeEntrySummary['draft']): EntryDraftForm {
  return { title: draft?.title ?? '', content: draft?.content ?? '', category: props.entry.category }
}

function validate(form: EntryDraftForm): string | null {
  if (form.title.trim() === '') return '标题不能为空'
  if (form.title.length > KNOWLEDGE_TITLE_MAX) return `标题超出 ${KNOWLEDGE_TITLE_MAX} 字上限`
  if (form.content.length > KNOWLEDGE_CONTENT_MAX) return `正文超出 ${KNOWLEDGE_CONTENT_MAX} 字上限`
  return null
}

function onSave(form: EntryDraftForm): void {
  emit('save', { title: form.title, content: form.content, category: form.category })
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
    :server-draft="entry.draft"
    :entry-id="entry.id"
    :scope="scope"
    :can-manage="canManage"
    :saving="saving"
    manage-permission-reason="需要知识库管理权限"
    :empty-description="emptyDescription"
    :new-draft="() => ({ title: '', content: '', category: entry.category })"
    :from-server="fromServer"
    :is-synced="(draft, form) => draft.title === form.title && draft.content === form.content"
    :validate="validate"
    @save="onSave"
  >
    <template #fields="{ snapshot, readonlyReason }">
      <div class="draft-editor__field">
        <label class="draft-editor__label" :for="`${entry.id}-draft-title`">标题 <span class="draft-editor__count" :class="{ over: snapshot.title.length > KNOWLEDGE_TITLE_MAX }">{{ snapshot.title.length }}/{{ KNOWLEDGE_TITLE_MAX }}</span></label>
        <input
          :id="`${entry.id}-draft-title`"
          v-model="snapshot.title"
          class="draft-editor__input"
          type="text"
          :maxlength="KNOWLEDGE_TITLE_MAX + 100"
          :disabled="!canManage || saving"
          :aria-invalid="snapshot.title.trim() === '' || snapshot.title.length > KNOWLEDGE_TITLE_MAX || undefined"
          :aria-describedby="readonlyReason"
          required
        >
      </div>
      <div class="draft-editor__field">
        <label class="draft-editor__label" :for="`${entry.id}-draft-category`">分类</label>
        <BaseSelect :id="`${entry.id}-draft-category`" v-model="snapshot.category" :options="categoryOptions" :disabled="!canManage || saving" :aria-describedby="readonlyReason" />
      </div>
      <div class="draft-editor__field">
        <label class="draft-editor__label" :for="`${entry.id}-draft-content`">正文 <span class="draft-editor__count" :class="{ over: snapshot.content.length > KNOWLEDGE_CONTENT_MAX }">{{ snapshot.content.length }}/{{ KNOWLEDGE_CONTENT_MAX }}</span></label>
        <BaseTextarea :id="`${entry.id}-draft-content`" v-model="snapshot.content" :rows="12" :disabled="!canManage || saving" :invalid="snapshot.content.length > KNOWLEDGE_CONTENT_MAX || undefined" :aria-describedby="readonlyReason" placeholder="纯文本内容。" />
      </div>
    </template>
  </BaseDraftEditor>
</template>

<style scoped>
.draft-editor__field { display: flex; flex-direction: column; gap: var(--cs-space-4); }
.draft-editor__label { display: flex; justify-content: space-between; color: var(--cs-text-muted); font-size: var(--cs-text-xs); }
.draft-editor__count.over { color: var(--cs-danger); font-weight: var(--cs-weight-semibold); }
.draft-editor__input { padding: var(--cs-space-8); border: 1px solid var(--cs-border); border-radius: var(--cs-radius-md); background: var(--cs-surface); color: var(--cs-text); font: inherit; }
.draft-editor__input:focus-visible { outline: none; box-shadow: var(--cs-focus-ring); }
</style>
