<script setup lang="ts">
import { X } from '@lucide/vue'
import { computed, nextTick, onBeforeUnmount, onMounted, ref, useId, useTemplateRef } from 'vue'
import { isTopmostModal } from '../../app/dialog'
import type { CreateWorkItemInput, WorkItemPriority, WorkItemType } from '../../domains/workitem/types'
import { workItemPriorities, workItemTypes } from '../../domains/workitem/types'
import BaseButton from '../base/BaseButton.vue'

const props = defineProps<{
  projectKey: string
  initialKey: string
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
  key: props.initialKey,
  type: 'TASK' as WorkItemType,
  title: '',
  description: '',
  priority: 'MEDIUM' as WorkItemPriority,
  labels: '',
  dueAt: '',
})
let returnTarget: HTMLElement | null = null

const normalizedKey = computed(() => form.value.key.trim())
const normalizedTitle = computed(() => form.value.title.trim())
const validKey = computed(() => /^[A-Z][A-Z0-9]{1,9}-[1-9][0-9]*$/.test(normalizedKey.value)
  && normalizedKey.value.startsWith(`${props.projectKey}-`))
const validTitle = computed(() => normalizedTitle.value.length > 0 && normalizedTitle.value.length <= 240)
const valid = computed(() => validKey.value && validTitle.value)

onMounted(() => {
  returnTarget = document.activeElement instanceof HTMLElement ? document.activeElement : null
  void nextTick(() => titleInput.value?.focus())
})

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
    key: normalizedKey.value,
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
  const controls = [...dialog.value.querySelectorAll<HTMLElement>('button:not(:disabled), input:not(:disabled), select:not(:disabled), textarea:not(:disabled)')]
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
            <span>创建者将成为初始 Owner，服务端原子提交工作项与责任事实。</span>
          </div>
          <button type="button" aria-label="关闭新建工作项" :disabled="submitting" @click="requestClose">
            <X :size="18" />
          </button>
        </header>
        <div class="form-grid">
          <label class="field-key">
            <span>工作项 Key</span>
            <input v-model="form.key" class="mono" autocomplete="off" :disabled="submitting" :aria-invalid="submitted && !validKey">
          </label>
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
          <label>
            <span>类型</span>
            <select v-model="form.type" :disabled="submitting">
              <option v-for="itemType in workItemTypes" :key="itemType" :value="itemType">{{ itemType }}</option>
            </select>
          </label>
          <label>
            <span>优先级</span>
            <select v-model="form.priority" :disabled="submitting">
              <option v-for="priority in workItemPriorities" :key="priority" :value="priority">{{ priority }}</option>
            </select>
          </label>
          <label>
            <span>到期时间</span>
            <input v-model="form.dueAt" type="datetime-local" :disabled="submitting">
          </label>
          <label>
            <span>标签</span>
            <input v-model="form.labels" placeholder="frontend, collaboration" :disabled="submitting">
          </label>
          <label class="field-wide">
            <span>描述</span>
            <textarea v-model="form.description" rows="4" placeholder="补充背景、范围和验收结果" :disabled="submitting" />
          </label>
        </div>
        <p v-if="submitted && !valid" class="form-error" role="alert">请填写有效标题，并使用当前项目的 Key 格式（例如 {{ projectKey }}-1）。</p>
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
.work-item-create-backdrop {
  position: fixed;
  inset: 0;
  z-index: 120;
  display: grid;
  place-items: center;
  padding: 18px;
  background: rgb(21 35 29 / 34%);
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
  gap: 16px;
  padding: 20px 22px;
  border-bottom: 1px solid var(--cs-border);
}

.work-item-create-dialog h2 { margin-bottom: 3px; font-size: 18px; }
.work-item-create-dialog header span { color: var(--cs-text-muted); font-size: 10px; }

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
  gap: 14px;
  padding: 20px 22px 8px;
}

.form-grid label {
  display: grid;
  gap: 5px;
  color: var(--cs-text-secondary);
  font-size: 9px;
  font-weight: 750;
}

.form-grid input,
.form-grid select,
.form-grid textarea {
  width: 100%;
  min-height: 34px;
  padding: 0 9px;
  border: 1px solid var(--cs-border-strong);
  border-radius: var(--cs-radius-sm);
  background: var(--cs-surface-subtle);
  color: var(--cs-text);
  font: 10px var(--cs-font-sans);
}

.form-grid textarea {
  padding-block: 9px;
  resize: vertical;
}

.field-title,
.field-wide { grid-column: 1 / -1; }
.form-grid input[aria-invalid='true'] { border-color: var(--cs-danger); }
.form-error { margin: 8px 22px 0; color: var(--cs-danger); font-size: 10px; }

.work-item-create-dialog > footer {
  display: flex;
  justify-content: flex-end;
  gap: 7px;
  padding: 17px 22px 20px;
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
  .work-item-create-dialog > footer { padding-inline: 16px; }
  .form-grid { grid-template-columns: 1fr; }
  .field-title,
  .field-wide { grid-column: 1; }
  .form-error { margin-inline: 16px; }
}
</style>
