<script setup lang="ts">
import { BookPlus } from '@lucide/vue'
import { computed, ref, useId, watch } from 'vue'
import { useDirtyForm } from '../../composables/useDirtyForm'
import { useIdempotentCommand } from '../../composables/useIdempotentCommand'
import { knowledgeCategoryLabels } from '../../domains/knowledge/labels'
import {
  knowledgeCategories,
  KNOWLEDGE_CONTENT_MAX,
  KNOWLEDGE_TITLE_MAX,
  type CreateKnowledgeEntryInput,
  type KnowledgeCategory,
} from '../../domains/knowledge/types'
import BaseButton from '../base/BaseButton.vue'
import BaseDialog from '../base/BaseDialog.vue'
import BaseSelect, { type BaseSelectOption } from '../base/BaseSelect.vue'
import BaseTextarea from '../base/BaseTextarea.vue'

const props = defineProps<{
  teamId: string
  submitting: boolean
  errorMessage: string | null
}>()

const emit = defineEmits<{
  close: []
  create: [input: CreateKnowledgeEntryInput, idempotencyKey: string]
}>()

const entryKey = ref('')
const category = ref<KnowledgeCategory>('CONVENTION')
const title = ref('')
const content = ref('')
const submitted = ref(false)
const formId = useId()
const dirtyForm = useDirtyForm(computed(() => ({
  entryKey: entryKey.value, category: category.value, title: title.value, content: content.value,
})))
const { submissionError, keyFor, clearAttempt } = useIdempotentCommand(
  () => [entryKey.value, category.value, title.value, content.value],
  () => {
    submitted.value = false
    dirtyForm.markDirty()
  },
)

const categoryOptions: BaseSelectOption[] = knowledgeCategories.map(value => ({ label: knowledgeCategoryLabels[value], value }))

const entryKeyValid = computed(() => /^[a-z0-9][a-z0-9-]{1,62}[a-z0-9]$/.test(entryKey.value))
const titleValid = computed(() => title.value.trim() !== '' && title.value.length <= KNOWLEDGE_TITLE_MAX)
const contentValid = computed(() => content.value.length <= KNOWLEDGE_CONTENT_MAX)
const valid = computed(() => entryKeyValid.value && titleValid.value && contentValid.value)

async function requestClose(): Promise<void> {
  if (props.submitting) return
  await dirtyForm.closeWithGuard(() => {
    clearAttempt()
    emit('close')
  })
}

function submit(): void {
  submitted.value = true
  if (!valid.value || props.submitting) return
  dirtyForm.markClean()
  const attemptKey = keyFor([props.teamId, entryKey.value, category.value, title.value, content.value])
  if (attemptKey === null) return
  emit('create', {
    entryKey: entryKey.value,
    category: category.value,
    title: title.value,
    content: content.value,
  }, attemptKey)
}
</script>

<template>
  <BaseDialog
    open
    title="创建知识条目"
    eyebrow="Knowledge entry"
    subtitle="条目 Key 是团队的长期引用，创建后不可修改。"
    width="min(640px, 100%)"
    :close-disabled="submitting"
    close-label="关闭创建知识条目"
    @close="requestClose"
  >
    <template #icon><BookPlus :size="20" /></template>
    <form :id="formId" class="knowledge-dialog__content" @submit.prevent="submit">
      <label class="knowledge-dialog__field">
        <span>条目 Key</span>
        <input
          v-model="entryKey"
          class="knowledge-dialog__mono-input"
          type="text"
          autocomplete="off"
          spellcheck="false"
          autofocus
          :disabled="submitting"
          :aria-invalid="submitted && !entryKeyValid || undefined"
          aria-describedby="knowledge-create-key-hint"
          required
        >
        <small id="knowledge-create-key-hint" class="knowledge-dialog__hint">小写字母、数字与连字符（2-64 位），例如 deploy-runbook。</small>
        <small v-if="submitted && !entryKeyValid" class="knowledge-dialog__error">条目 Key 需为小写字母、数字与连字符（2-64 位）。</small>
      </label>

      <label class="knowledge-dialog__field">
        <span>分类</span>
        <BaseSelect v-model="category" :options="categoryOptions" :disabled="submitting" aria-label="条目分类" />
      </label>

      <label class="knowledge-dialog__field">
        <span>标题 <span class="knowledge-dialog__count">{{ title.length }}/{{ KNOWLEDGE_TITLE_MAX }}</span></span>
        <input
          v-model="title"
          type="text"
          :maxlength="KNOWLEDGE_TITLE_MAX + 100"
          :disabled="submitting"
          :aria-invalid="submitted && !titleValid || undefined"
          required
        >
        <small v-if="submitted && title.trim() === ''" class="knowledge-dialog__error">标题不能为空。</small>
        <small v-else-if="submitted && title.length > KNOWLEDGE_TITLE_MAX" class="knowledge-dialog__error">标题超出 {{ KNOWLEDGE_TITLE_MAX }} 字上限。</small>
      </label>

      <label class="knowledge-dialog__field">
        <span>正文 <span class="knowledge-dialog__count">{{ content.length }}/{{ KNOWLEDGE_CONTENT_MAX }}</span></span>
        <BaseTextarea v-model="content" :rows="8" :disabled="submitting" :invalid="submitted && !contentValid || undefined" placeholder="纯文本内容；渲染增强在后续切片提供。" />
        <small v-if="submitted && !contentValid" class="knowledge-dialog__error">正文超出 {{ KNOWLEDGE_CONTENT_MAX }} 字上限。</small>
      </label>

      <p v-if="submissionError || errorMessage" class="knowledge-dialog__command-error" role="alert">{{ submissionError || errorMessage }}</p>
    </form>
    <template #footer>
      <BaseButton type="button" variant="ghost" :disabled="submitting" @click="requestClose">取消</BaseButton>
      <BaseButton type="submit" :form="formId" :loading="submitting" :disabled="!valid">创建条目</BaseButton>
    </template>
  </BaseDialog>
</template>

<style scoped>
.knowledge-dialog__content { display: grid; gap: var(--cs-space-16); }
.knowledge-dialog__field { display: grid; gap: var(--cs-space-4); }
.knowledge-dialog__field > span { color: var(--cs-text-secondary); font-size: var(--cs-text-sm); font-weight: var(--cs-weight-semibold); }
.knowledge-dialog__count { color: var(--cs-text-muted); font-weight: var(--cs-weight-regular); }
.knowledge-dialog__field input, .knowledge-dialog__mono-input { width: 100%; min-height: 40px; padding: 0 var(--cs-space-12); border: 1px solid var(--cs-border-strong); border-radius: var(--cs-radius-sm); background: var(--cs-surface); color: var(--cs-text); font-size: var(--cs-text-base); }
.knowledge-dialog__mono-input { font-family: var(--cs-font-mono, ui-monospace, monospace); }
.knowledge-dialog__hint { color: var(--cs-text-muted); font-size: var(--cs-text-xs); }
.knowledge-dialog__error { color: var(--cs-danger); font-size: var(--cs-text-xs); }
.knowledge-dialog__command-error { margin: 0; color: var(--cs-danger); font-size: var(--cs-text-sm); }
</style>
