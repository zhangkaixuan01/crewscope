<script setup lang="ts">
import BaseSelect from '../base/BaseSelect.vue'
import { computed, inject, onMounted, ref, useId, watch } from 'vue'
import { AUTH_PRINCIPAL } from '../../app/auth'
import type { CreateWorkItemInput, WorkItemPriority, WorkItemType } from '../../domains/workitem/types'
import { workItemPriorities, workItemTypes } from '../../domains/workitem/types'
import { workItemPriorityLabels, workItemTypeLabels } from '../../domains/workitem/labels'
import type { PrincipalScope } from '../../domains/principal/types'
import { clearWorkItemCreateDraft, readWorkItemCreateDraft, writeWorkItemCreateDraft } from '../../domains/workitem/createDraft'
import BaseButton from '../base/BaseButton.vue'
import BaseDialog from '../base/BaseDialog.vue'

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

const formId = useId()
const submitted = ref(false)
const form = ref({
  type: 'TASK' as WorkItemType,
  title: '',
  description: '',
  priority: 'MEDIUM' as WorkItemPriority,
  labels: '',
  dueAt: '',
})
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
  restoreDraft()
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
</script>

<template>
  <BaseDialog
    open
    title="新建工作项"
    :eyebrow="`${projectKey} · 工作项`"
    subtitle="编号自动生成；你将成为负责人。创建不会启动执行。"
    width="min(720px, 100%)"
    :close-disabled="submitting"
    close-label="关闭新建工作项"
    @close="requestClose"
  >
    <form :id="formId" class="work-item-create-form" @submit.prevent="submit">
      <div class="form-grid">
        <label class="field-title">
          <span>标题</span>
          <input
            v-model="form.title"
            maxlength="240"
            autocomplete="off"
            placeholder="描述团队需要完成的结果"
            autofocus
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
          <BaseSelect v-model="form.type" :disabled="submitting">
            <option v-for="itemType in workItemTypes" :key="itemType" :value="itemType">{{ workItemTypeLabels[itemType] }}</option>
          </BaseSelect>
        </label>
        <label>
          <span>优先级</span>
          <BaseSelect v-model="form.priority" :disabled="submitting">
            <option v-for="priority in workItemPriorities" :key="priority" :value="priority">{{ workItemPriorityLabels[priority] }}</option>
          </BaseSelect>
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
    </form>

    <template #footer>
      <BaseButton type="button" variant="ghost" :disabled="submitting" @click="requestClose">取消</BaseButton>
      <BaseButton type="submit" :form="formId" :loading="submitting">创建工作项</BaseButton>
    </template>
  </BaseDialog>
</template>

<style scoped>
details > label { display: grid; gap: var(--cs-space-8); margin-top: var(--cs-space-12); }
summary { cursor: pointer; font-size: var(--cs-text-sm); }
.work-item-create-form { display: grid; gap: var(--cs-space-16); }

.form-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: var(--cs-space-16);
}

.form-grid label {
  display: grid;
  gap: var(--cs-space-4);
  color: var(--cs-text-secondary);
  font-size: var(--cs-text-xs);
  font-weight: var(--cs-weight-semibold);
}

.form-grid input,
.form-grid :deep(select),
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
.form-error { margin: 0; color: var(--cs-danger); font-size: var(--cs-text-sm); }

@media (max-width: 767px) {
  .form-grid { grid-template-columns: 1fr; }
  .field-title,
  .field-wide { grid-column: 1; }
}

/* M12b-F01：悬停反馈补齐——此前这些可点元素对指针移动完全沉默。 */
summary:hover:not(:disabled) { background: var(--cs-surface-accent); }
summary:active:not(:disabled) { transform: scale(.97); }
summary { transition: background-color var(--cs-motion-fast) var(--cs-ease-out), border-color var(--cs-motion-fast) var(--cs-ease-out), transform var(--cs-motion-fast) var(--cs-ease-out); }
</style>
