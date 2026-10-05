<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useDirtyForm } from '../../composables/useDirtyForm'
import { knowledgeCategoryLabels } from '../../domains/knowledge/labels'
import {
  knowledgeCategories,
  KNOWLEDGE_CONTENT_MAX,
  KNOWLEDGE_TITLE_MAX,
  type KnowledgeCategory,
  type KnowledgeEntrySummary,
  type UpdateKnowledgeDraftInput,
} from '../../domains/knowledge/types'
import BaseButton from '../base/BaseButton.vue'
import BaseSelect, { type BaseSelectOption } from '../base/BaseSelect.vue'
import BaseTextarea from '../base/BaseTextarea.vue'
import StatePanel from '../feedback/StatePanel.vue'

export interface KnowledgeDraftScope {
  accountId: string
  principalId: string
  organizationId: string
  teamId: string
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

// The head carries the draft; a null draft with no editing session is the read-only rest state
// (everything published). Starting a draft opens an empty form — the head DTO has no title.
const drafting = ref(props.entry.draft !== null)
const title = ref(props.entry.draft?.title ?? '')
const content = ref(props.entry.draft?.content ?? '')
const category = ref<KnowledgeCategory>(props.entry.category)
const restored = ref(false)

const categoryOptions: BaseSelectOption[] = knowledgeCategories.map(value => ({ label: knowledgeCategoryLabels[value], value }))

const formSnapshot = computed(() => ({ title: title.value, content: content.value, category: category.value }))
const draftScope = computed(() => ({ ...props.scope, objectId: props.entry.id }))
const dirtyForm = useDirtyForm(formSnapshot, {
  draftScope,
  title: '放弃未保存的草稿修改？',
  description: '当前草稿有未保存内容，离开后这些修改会丢失。',
  confirmLabel: '放弃修改',
  cancelLabel: '继续编辑',
})
const draftAvailable = ref(Boolean(dirtyForm.restoreDraft()))
watch(formSnapshot, value => dirtyForm.sync(value))

watch(() => props.entry.id, () => {
  drafting.value = props.entry.draft !== null
  title.value = props.entry.draft?.title ?? ''
  content.value = props.entry.draft?.content ?? ''
  category.value = props.entry.category
  restored.value = false
  dirtyForm.markClean()
  draftAvailable.value = Boolean(dirtyForm.restoreDraft())
})

// A refreshed head whose draft equals the form confirms the save round-trip; a different server
// draft (409 reload) must NOT overwrite the editor — D4 layer three.
watch(() => props.entry.draft, draft => {
  if (draft?.title === title.value && draft?.content === content.value) {
    dirtyForm.markClean()
    dirtyForm.clearDraft()
    draftAvailable.value = false
  }
})

const titleOver = computed(() => title.value.length > KNOWLEDGE_TITLE_MAX)
const contentOver = computed(() => content.value.length > KNOWLEDGE_CONTENT_MAX)
const titleBlank = computed(() => title.value.trim() === '')
const saveDisabledReason = computed<string | null>(() => {
  if (!props.canManage) return '需要知识库管理权限'
  if (props.saving) return null
  if (titleBlank.value) return '标题不能为空'
  if (titleOver.value) return `标题超出 ${KNOWLEDGE_TITLE_MAX} 字上限`
  if (contentOver.value) return `正文超出 ${KNOWLEDGE_CONTENT_MAX} 字上限`
  return null
})
const saveDisabled = computed(() => saveDisabledReason.value !== null || props.saving)
/** The read-only lock for members without knowledge:manage is a permission guard, so its spoken
 *  reason must be programmatically tied to the locked controls. */
const readonlyReason = computed(() => !props.canManage ? `${props.entry.id}-draft-readonly` : undefined)

function submit(): void {
  if (saveDisabled.value) return
  emit('save', { title: title.value, content: content.value, category: category.value })
}

function restoreDraft(): void {
  const snapshot = dirtyForm.restoreDraft() as typeof formSnapshot.value | null
  if (!snapshot) return
  title.value = snapshot.title
  content.value = snapshot.content
  category.value = snapshot.category
  restored.value = true
  dirtyForm.markDirty()
}

function startDraft(): void {
  drafting.value = true
  dirtyForm.markClean()
}

/** Overwrites the form from the current head draft — callers gate this behind confirmDiscard (D4). */
function applyServerDraft(): void {
  title.value = props.entry.draft?.title ?? ''
  content.value = props.entry.draft?.content ?? ''
  category.value = props.entry.category
  restored.value = false
  drafting.value = props.entry.draft !== null
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
      :description="entry.status === 'PUBLISHED' ? '已发布的内容全部生效，可由知识库管理员继续修订。' : '可由知识库管理员编辑标题、正文与分类。'"
    >
      <template #action>
        <BaseButton v-if="canManage" variant="secondary" size="small" @click="startDraft">开始新草稿</BaseButton>
      </template>
    </StatePanel>

    <form v-else class="draft-editor__form" @submit.prevent="submit">
      <div class="draft-editor__field">
        <label class="draft-editor__label" :for="`${entry.id}-draft-title`">标题 <span class="draft-editor__count" :class="{ over: titleOver }">{{ title.length }}/{{ KNOWLEDGE_TITLE_MAX }}</span></label>
        <input
          :id="`${entry.id}-draft-title`"
          v-model="title"
          class="draft-editor__input"
          type="text"
          :maxlength="KNOWLEDGE_TITLE_MAX + 100"
          :disabled="!canManage || saving"
          :aria-invalid="titleBlank || titleOver || undefined"
          :aria-describedby="readonlyReason"
          required
        >
      </div>
      <div class="draft-editor__field">
        <label class="draft-editor__label" :for="`${entry.id}-draft-category`">分类</label>
        <BaseSelect :id="`${entry.id}-draft-category`" v-model="category" :options="categoryOptions" :disabled="!canManage || saving" :aria-describedby="readonlyReason" />
      </div>
      <div class="draft-editor__field">
        <label class="draft-editor__label" :for="`${entry.id}-draft-content`">正文 <span class="draft-editor__count" :class="{ over: contentOver }">{{ content.length }}/{{ KNOWLEDGE_CONTENT_MAX }}</span></label>
        <BaseTextarea :id="`${entry.id}-draft-content`" v-model="content" :rows="12" :disabled="!canManage || saving" :invalid="contentOver || undefined" :aria-describedby="readonlyReason" placeholder="纯文本内容；渲染增强在后续切片提供。" />
      </div>
      <footer class="draft-editor__actions">
        <BaseButton type="submit" variant="primary" size="medium" :loading="saving" :disabled="saveDisabled">{{ saveDisabledReason ?? '保存草稿' }}</BaseButton>
        <span v-if="saveDisabledReason && !saving && canManage" class="draft-editor__reason">{{ saveDisabledReason }}</span>
        <span v-else-if="!canManage" :id="`${entry.id}-draft-readonly`" class="draft-editor__reason">{{ saveDisabledReason }}</span>
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
.draft-editor__count.over { color: var(--cs-danger, #b3261e); font-weight: var(--cs-weight-semibold); }
.draft-editor__input { padding: var(--cs-space-8); border: 1px solid var(--cs-border); border-radius: var(--cs-radius-md); background: var(--cs-surface); color: var(--cs-text); font: inherit; }
.draft-editor__input:focus-visible { outline: none; box-shadow: var(--cs-focus-ring); }
.draft-editor__actions { display: flex; align-items: center; gap: var(--cs-space-8); }
.draft-editor__reason { color: var(--cs-text-muted); font-size: var(--cs-text-xs); }
</style>
