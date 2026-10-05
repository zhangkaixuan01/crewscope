<script setup lang="ts">
import { createFormCommandKeys } from '../../api/formCommandKeys'
import { BookPlus, X } from '@lucide/vue'
import { computed, ref, useTemplateRef, watch } from 'vue'
import { isTopmostModal } from '../../app/dialog'
import { useDirtyForm } from '../../composables/useDirtyForm'
import { knowledgeCategoryLabels } from '../../domains/knowledge/labels'
import {
  knowledgeCategories,
  KNOWLEDGE_CONTENT_MAX,
  KNOWLEDGE_TITLE_MAX,
  type CreateKnowledgeEntryInput,
  type KnowledgeCategory,
} from '../../domains/knowledge/types'
import BaseButton from '../base/BaseButton.vue'
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

const dialog = useTemplateRef<HTMLElement>('dialog')
const entryKey = ref('')
const category = ref<KnowledgeCategory>('CONVENTION')
const title = ref('')
const content = ref('')
const submitted = ref(false)
const submissionError = ref<string | null>(null)
let attemptKey = ''
const intentKeys = createFormCommandKeys()
const dirtyForm = useDirtyForm(computed(() => ({
  entryKey: entryKey.value, category: category.value, title: title.value, content: content.value,
})))

const categoryOptions: BaseSelectOption[] = knowledgeCategories.map(value => ({ label: knowledgeCategoryLabels[value], value }))

const entryKeyValid = computed(() => /^[a-z0-9][a-z0-9-]{1,62}[a-z0-9]$/.test(entryKey.value))
const titleValid = computed(() => title.value.trim() !== '' && title.value.length <= KNOWLEDGE_TITLE_MAX)
const contentValid = computed(() => content.value.length <= KNOWLEDGE_CONTENT_MAX)
const valid = computed(() => entryKeyValid.value && titleValid.value && contentValid.value)

watch([entryKey, category, title, content], () => {
  // Editing any command input creates a new logical request; an unchanged retry reuses its key.
  attemptKey = ''
  submitted.value = false
  dirtyForm.markDirty()
})

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
  submissionError.value = null
  try {
    attemptKey = intentKeys.forInput([props.teamId, entryKey.value, category.value, title.value, content.value])
  } catch (error) {
    submissionError.value = error instanceof Error ? error.message : '无法生成安全操作标识，本次操作尚未发送。'
    return
  }
  emit('create', {
    entryKey: entryKey.value,
    category: category.value,
    title: title.value,
    content: content.value,
  }, attemptKey)
}

function clearAttempt(): void {
  attemptKey = ''
  intentKeys.clear()
}

function handleKeydown(event: KeyboardEvent): void {
  if (!isTopmostModal(dialog.value)) return
  event.stopPropagation()
  if (event.key === 'Escape') {
    event.preventDefault()
    requestClose()
    return
  }
  if (event.key !== 'Tab' || !dialog.value) return
  const controls = [...dialog.value.querySelectorAll<HTMLElement>(
    'button:not(:disabled), input:not(:disabled), select:not(:disabled), textarea:not(:disabled)',
  )]
  const first = controls[0]
  const last = controls.at(-1)
  if (!first || !last) return
  if (event.shiftKey && document.activeElement === first) {
    event.preventDefault()
    last.focus()
  } else if (!event.shiftKey && document.activeElement === last) {
    event.preventDefault()
    first.focus()
  }
}
</script>

<template>
  <div class="knowledge-dialog-backdrop" @click.self="requestClose">
    <form
      ref="dialog"
      class="knowledge-dialog panel"
      role="dialog"
      aria-modal="true"
      aria-labelledby="knowledge-create-title"
      tabindex="-1"
      @submit.prevent="submit"
      @keydown="handleKeydown"
    >
      <header>
        <span class="dialog-icon"><BookPlus :size="20" /></span>
        <div>
          <p class="eyebrow">Knowledge entry</p>
          <h2 id="knowledge-create-title">创建知识条目</h2>
          <span>条目 Key 是团队的长期引用，创建后不可修改。</span>
        </div>
        <button type="button" aria-label="关闭创建知识条目" :disabled="submitting" @click="requestClose"><X :size="18" /></button>
      </header>

      <div class="knowledge-dialog__content">
        <label class="knowledge-dialog__field">
          <span>条目 Key</span>
          <input
            v-model="entryKey"
            class="knowledge-dialog__mono-input"
            type="text"
            autocomplete="off"
            spellcheck="false"
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
      </div>

      <footer>
        <BaseButton type="button" variant="ghost" :disabled="submitting" @click="requestClose">取消</BaseButton>
        <BaseButton type="submit" :loading="submitting" :disabled="!valid">创建条目</BaseButton>
      </footer>
    </form>
  </div>
</template>

<style scoped>
.knowledge-dialog-backdrop { position: fixed; inset: 0; z-index: var(--cs-z-dialog); display: grid; place-items: center; padding: var(--cs-space-20); background: var(--cs-scrim); backdrop-filter: blur(3px); }
.knowledge-dialog { display: flex; width: min(640px, 100%); max-height: calc(100dvh - 40px); flex-direction: column; overflow-y: auto; box-shadow: var(--cs-shadow-float); }
.knowledge-dialog > header { display: grid; grid-template-columns: 42px minmax(0, 1fr) 32px; align-items: start; gap: var(--cs-space-12); padding: var(--cs-space-20); border-bottom: 1px solid var(--cs-border); }
.dialog-icon { display: grid; width: 42px; height: 42px; place-items: center; border-radius: 12px; background: var(--cs-surface-accent-strong); color: var(--cs-text-brand); }
.knowledge-dialog h2 { margin: 0 0 var(--cs-space-4); font-size: var(--cs-text-lg); }
.knowledge-dialog header div > span { color: var(--cs-text-muted); font-size: var(--cs-text-sm); }
.knowledge-dialog header button { display: grid; width: 32px; height: 32px; place-items: center; border-radius: 8px; background: var(--cs-surface-subtle); color: var(--cs-text); cursor: pointer; }
.knowledge-dialog__content { display: grid; gap: var(--cs-space-16); padding: var(--cs-space-20) var(--cs-space-20) var(--cs-space-4); }
.knowledge-dialog__field { display: grid; gap: var(--cs-space-4); }
.knowledge-dialog__field > span { color: var(--cs-text-secondary); font-size: var(--cs-text-sm); font-weight: var(--cs-weight-semibold); }
.knowledge-dialog__count { color: var(--cs-text-muted); font-weight: var(--cs-weight-regular); }
.knowledge-dialog__field input, .knowledge-dialog__mono-input { width: 100%; min-height: 40px; padding: 0 var(--cs-space-12); border: 1px solid var(--cs-border-strong); border-radius: var(--cs-radius-sm); background: var(--cs-surface); color: var(--cs-text); font-size: var(--cs-text-base); }
.knowledge-dialog__mono-input { font-family: var(--cs-font-mono, ui-monospace, monospace); }
.knowledge-dialog__hint { color: var(--cs-text-muted); font-size: var(--cs-text-xs); }
.knowledge-dialog__error { color: var(--cs-danger); font-size: var(--cs-text-xs); }
.knowledge-dialog__command-error { margin: 0; color: var(--cs-danger); font-size: var(--cs-text-sm); }
.knowledge-dialog > footer { display: flex; justify-content: flex-end; gap: var(--cs-space-8); padding: var(--cs-space-16) var(--cs-space-20) var(--cs-space-20); }
@media (max-width: 767px) {
  .knowledge-dialog-backdrop { align-items: end; padding: 0; }
  .knowledge-dialog { width: 100%; max-height: calc(100dvh - 12px); border-radius: 18px 18px 0 0; }
  .knowledge-dialog > footer { display: grid; }
  .knowledge-dialog > footer > * { width: 100%; }
}
</style>
