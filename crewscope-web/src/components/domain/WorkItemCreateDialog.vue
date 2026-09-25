<script setup lang="ts">
import { X } from '@lucide/vue'
import { computed, inject, nextTick, onBeforeUnmount, onMounted, ref, useId, useTemplateRef, watch } from 'vue'
import { AUTH_PRINCIPAL } from '../../app/auth'
import { isTopmostModal } from '../../app/dialog'
import type { CreateWorkItemInput, WorkItemPriority, WorkItemType } from '../../domains/workitem/types'
import { workItemPriorities, workItemTypes } from '../../domains/workitem/types'
import { workItemPriorityLabels, workItemTypeLabels } from '../../domains/workitem/labels'
import type { PrincipalScope } from '../../domains/principal/types'
import { clearWorkItemCreateDraft, readWorkItemCreateDraft, writeWorkItemCreateDraft } from '../../domains/workitem/createDraft'
import BaseButton from '../base/BaseButton.vue'

const props = defineProps<{
  projectKey: string
  scope: PrincipalScope | null
  initialKey?: string
  submitting: boolean
  errorMessage: string | null
}>()

const emit = defineEmits<{
  close: []
  submit: [input: CreateWorkItemInput]
}>()

const dialog = useTemplateRef<HTMLElement>('dialog')
const titleInput = useTemplateRef<HTMLInputElement>('titleInput')
const titleId = `${useId()}-title`
const submitted = ref(false)
const form = ref({
  type: 'TASK' as WorkItemType,
  title: '',
  description: '',
  priority: 'MEDIUM' as WorkItemPriority,
  labels: '',
  dueAt: '',
})
let returnTarget: HTMLElement | null = null
const principal = inject(AUTH_PRINCIPAL)

const normalizedTitle = computed(() => form.value.title.trim())
const validTitle = computed(() => normalizedTitle.value.length > 0 && normalizedTitle.value.length <= 240)
const valid = computed(() => validTitle.value && (!form.value.dueAt || Number.isFinite(Date.parse(form.value.dueAt))))
const timezone = Intl.DateTimeFormat().resolvedOptions().timeZone
const moreSummary = computed(() => [
  workItemTypeLabels[form.value.type], workItemPriorityLabels[form.value.priority],
  form.value.labels ? '已填标签' : '', form.value.dueAt ? '已设到期时间' : '',
].filter(Boolean).join(' · '))
const hasInput = computed(() => Boolean(form.value.title.trim() || form.value.description.trim() || form.value.labels.trim() || form.value.dueAt))

onMounted(() => {
  returnTarget = document.activeElement instanceof HTMLElement ? document.activeElement : null
  restoreDraft()
  void nextTick(() => titleInput.value?.focus())
})

// Closing or escaping keeps the in-progress input as a draft; only a successful create clears it.
watch(() => [form.value.type, form.value.title, form.value.description, form.value.priority, form.value.labels, form.value.dueAt] as const, () => {
  if (!props.scope) return
  if (hasInput.value) writeWorkItemCreateDraft(props.scope, props.projectKey, form.value, principal)
  else clearWorkItemCreateDraft(props.scope, props.projectKey, principal)
})

function restoreDraft(): void {
  if (!props.scope) return
  if (hasInput.value) return
  const draft = readWorkItemCreateDraft(props.scope, props.projectKey, principal)
  if (!draft) return
  form.value = { type: draft.type, title: draft.title, description: draft.description, priority: draft.priority, labels: draft.labels, dueAt: draft.dueAt }
}

onBeforeUnmount(() => {
  if (returnTarget?.isConnected) returnTarget.focus()
})

function requestClose(): void {
  if (!props.submitting) emit('close')
}

function submit(): void {
  submitted.value = true
  if (!valid.value) return
  emit('submit', {
    type: form.value.type,
    title: normalizedTitle.value,
    description: form.value.description.trim() || null,
    priority: form.value.priority,
    labels: [...new Set(form.value.labels.split(',').map(value => value.trim()).filter(Boolean))],
    dueAt: form.value.dueAt ? new Date(form.value.dueAt).toISOString() : null,
  })
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
  const controls = [...dialog.value.querySelectorAll<HTMLElement>('button:not(:disabled), input:not(:disabled), select:not(:disabled), textarea:not(:disabled), summary')]
    .filter(control => !control.closest('details:not([open])') || control.tagName === 'SUMMARY')
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
  <Teleport to="body">
    <div class="work-item-create-backdrop" @click.self="requestClose">
      <form
        ref="dialog"
        class="work-item-create-dialog panel"
        role="dialog"
        aria-modal="true"
        :aria-labelledby="titleId"
        tabindex="-1"
        @submit.prevent="submit"
        @keydown="handleKeydown"
      >
        <header>
          <div>
            <p class="eyebrow">{{ projectKey }} · 工作项</p>
            <h2 :id="titleId">新建工作项</h2>
            <span>编号自动生成；你将成为负责人。创建不会启动执行。</span>
          </div>
          <button type="button" aria-label="关闭新建工作项" :disabled="submitting" @click="requestClose">
            <X :size="18" />
          </button>
        </header>
        <div class="form-grid">
          <label class="field-title">
            <span>标题</span>
            <input
              ref="titleInput"
              v-model="form.title"
              maxlength="240"
              autocomplete="off"
              placeholder="描述团队需要完成的结果"
              :disabled="submitting"
              :aria-invalid="submitted && !validTitle"
            >
          </label>
          <label class="field-wide">
            <span>描述（可选）</span>
            <textarea v-model="form.description" rows="4" placeholder="补充背景、范围和验收结果" :disabled="submitting" />
          </label>
          <details class="field-wide">
            <summary>更多选项 · {{ moreSummary }}</summary>
          <label>
            <span>类型</span>
            <select v-model="form.type" :disabled="submitting">
              <option v-for="itemType in workItemTypes" :key="itemType" :value="itemType">{{ workItemTypeLabels[itemType] }}</option>
            </select>
          </label>
          <label>
            <span>优先级</span>
            <select v-model="form.priority" :disabled="submitting">
              <option v-for="priority in workItemPriorities" :key="priority" :value="priority">{{ workItemPriorityLabels[priority] }}</option>
            </select>
          </label>
          <label>
            <span>到期时间</span>
            <input v-model="form.dueAt" type="datetime-local" :disabled="submitting">
            <small>时区：{{ timezone }}</small>
          </label>
          <label>
            <span>标签</span>
            <input v-model="form.labels" placeholder="frontend, collaboration" :disabled="submitting">
          </label>
          </details>
        </div>
        <p v-if="submitted && !valid" class="form-error" role="alert">请填写 1–240 字的标题，并检查到期时间。</p>
        <p v-if="errorMessage" class="form-error" role="alert">{{ errorMessage }}</p>
        <footer>
          <BaseButton type="button" variant="ghost" :disabled="submitting" @click="requestClose">取消</BaseButton>
          <BaseButton type="submit" :loading="submitting">创建工作项</BaseButton>
        </footer>
      </form>
    </div>
  </Teleport>
</template>

<style scoped>
details > label { display: grid; gap: var(--cs-space-8); margin-top: var(--cs-space-12); }
summary { cursor: pointer; font-size: var(--cs-text-sm); }
.work-item-create-backdrop {
  position: fixed;
  inset: 0;
  z-index: var(--cs-z-dialog);
  display: grid;
  place-items: center;
  padding: var(--cs-space-20);
  background: var(--cs-scrim);
  backdrop-filter: blur(3px);
}

.work-item-create-dialog {
  width: min(720px, 100%);
  max-height: calc(100dvh - 36px);
  overflow-y: auto;
  box-shadow: var(--cs-shadow-float);
}

.work-item-create-dialog > header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--cs-space-16);
  padding: var(--cs-space-20) var(--cs-space-24);
  border-bottom: 1px solid var(--cs-border);
}

.work-item-create-dialog h2 { margin-bottom: var(--cs-space-4); font-size: var(--cs-text-lg); }
.work-item-create-dialog header span { color: var(--cs-text-muted); font-size: var(--cs-text-sm); }

.work-item-create-dialog header button {
  display: grid;
  width: 31px;
  height: 31px;
  flex: 0 0 auto;
  place-items: center;
  border-radius: 8px;
  background: var(--cs-surface-subtle);
  cursor: pointer;
}

.form-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: var(--cs-space-16);
  padding: var(--cs-space-20) var(--cs-space-24) var(--cs-space-8);
}

.form-grid label {
  display: grid;
  gap: var(--cs-space-4);
  color: var(--cs-text-secondary);
  font-size: var(--cs-text-xs);
  font-weight: var(--cs-weight-semibold);
}

.form-grid input,
.form-grid select,
.form-grid textarea {
  width: 100%;
  min-height: 34px;
  padding: 0 var(--cs-space-8);
  border: 1px solid var(--cs-border-strong);
  border-radius: var(--cs-radius-sm);
  background: var(--cs-surface-subtle);
  color: var(--cs-text);
  font: var(--cs-text-base) var(--cs-font-sans);
}

.form-grid textarea {
  padding-block: var(--cs-space-8);
  resize: vertical;
}

.field-title,
.field-wide { grid-column: 1 / -1; }
.form-grid input[aria-invalid='true'] { border-color: var(--cs-danger); }
.form-error { margin: var(--cs-space-8) var(--cs-space-24) 0; color: var(--cs-danger); font-size: var(--cs-text-sm); }

.work-item-create-dialog > footer {
  display: flex;
  justify-content: flex-end;
  gap: var(--cs-space-8);
  padding: var(--cs-space-16) var(--cs-space-24) var(--cs-space-20);
}

@media (max-width: 767px) {
  .work-item-create-backdrop { align-items: end; padding: 0; }
  .work-item-create-dialog {
    width: 100%;
    max-height: 92vh;
    border-radius: 18px 18px 0 0;
  }
  .work-item-create-dialog > header,
  .form-grid,
  .work-item-create-dialog > footer { padding-inline: var(--cs-space-16); }
  .form-grid { grid-template-columns: 1fr; }
  .field-title,
  .field-wide { grid-column: 1; }
  .form-error { margin-inline: var(--cs-space-16); }
}
</style>
