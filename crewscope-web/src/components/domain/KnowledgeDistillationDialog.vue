<script setup lang="ts">
import { distillReplayedNote } from '../../domains/shared/stateCopy'
import { Sparkles } from '@lucide/vue'
import { computed, ref, useId, watch } from 'vue'
import { useClipboard } from '../../composables/useClipboard'
import { useDirtyForm } from '../../composables/useDirtyForm'
import { useIdempotentCommand } from '../../composables/useIdempotentCommand'
import { knowledgeCategoryLabels, knowledgeIndexStatusHint, knowledgeIndexStatusLabels } from '../../domains/knowledge/labels'
import {
  knowledgeCategories,
  type DistillKnowledgeInput,
  type KnowledgeCategory,
  type KnowledgeDistillationReceipt,
} from '../../domains/knowledge/types'
import BaseButton from '../base/BaseButton.vue'
import BaseDialog from '../base/BaseDialog.vue'
import BaseSelect, { type BaseSelectOption } from '../base/BaseSelect.vue'
import StatusBadge from '../base/StatusBadge.vue'
import BaseReceiptPanel from '../shared/BaseReceiptPanel.vue'
import ExecutionSourcePicker from './ExecutionSourcePicker.vue'

const props = withDefaults(defineProps<{
  teamId: string
  submitting: boolean
  errorMessage: string | null
  receipt: KnowledgeDistillationReceipt | null
  /** M13b-F03（R14）：执行现场带入的执行（Task 详情「蒸馏为知识」）——直接进入已选态。 */
  presetExecution?: { taskExecutionId: string, objective?: string | null } | null
  /** R04: confirmed `false` swaps the receipt's PENDING hint for the disabled wording.
   * The explicit default matters: an absent Boolean-typed prop would otherwise cast to false. */
  indexEnabled?: boolean | null
}>(), { indexEnabled: null })

const emit = defineEmits<{
  close: []
  distill: [input: DistillKnowledgeInput, idempotencyKey: string]
  openEntry: [entryId: string]
}>()

const taskExecutionId = ref('')
const entryKey = ref('')
const category = ref<KnowledgeCategory | ''>('')
const submitted = ref(false)
const formId = useId()
// M13b-F03（A11）：选择器选中后的预览事实；手粘与带入路径同样写入 taskExecutionId。
const selectedObjective = ref<string | null>(props.presetExecution?.objective ?? null)
const selectedExecutionId = ref<string | null>(props.presetExecution?.taskExecutionId ?? null)
const entryKeyTouched = ref(false)
const clipboard = useClipboard()
if (props.presetExecution) taskExecutionId.value = props.presetExecution.taskExecutionId
const dirtyForm = useDirtyForm(computed(() => ({
  taskExecutionId: taskExecutionId.value, entryKey: entryKey.value, category: category.value,
})))
const { submissionError, keyFor, clearAttempt } = useIdempotentCommand(
  () => [taskExecutionId.value, entryKey.value, category.value],
  () => {
    submitted.value = false
    dirtyForm.markDirty()
  },
)

const categoryOptions: BaseSelectOption[] = [
  { label: '不指定（默认其他）', value: '' },
  ...knowledgeCategories.map(value => ({ label: knowledgeCategoryLabels[value], value })),
]

const uuidPattern = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i
const executionValid = computed(() => uuidPattern.test(taskExecutionId.value.trim()))
const entryKeyValid = computed(() => /^[a-z0-9][a-z0-9-]{1,62}[a-z0-9]$/.test(entryKey.value))
const valid = computed(() => executionValid.value && entryKeyValid.value)

/** A11：执行一旦确定就生成可编辑的默认 Key；用户改过 Key 后不再覆盖（手粘高级区同样适用）。 */
watch(() => taskExecutionId.value, () => {
  if (entryKeyTouched.value) return
  entryKey.value = executionValid.value ? `exec-${taskExecutionId.value.trim().slice(0, 8)}` : ''
}, { immediate: true })

function onPickExecution(option: { taskExecutionId: string, objective: string }): void {
  selectedExecutionId.value = option.taskExecutionId
  selectedObjective.value = option.objective
  taskExecutionId.value = option.taskExecutionId
}

function onManualExecutionInput(): void {
  selectedExecutionId.value = null
  selectedObjective.value = null
}

async function copyExecutionId(): Promise<void> {
  if (taskExecutionId.value) await clipboard.copy(taskExecutionId.value.trim(), 'distill-execution-id')
}

/** D3: the dialog stays open and turns into the receipt surface; the form comes back on demand. */
const viewingReceipt = ref(props.receipt !== null)
const receiptMode = computed(() => props.receipt !== null && viewingReceipt.value)

// A fresh receipt rules out a pending submission error from the same dialog session.
watch(() => props.receipt, receipt => {
  viewingReceipt.value = receipt !== null
  if (receipt !== null) submissionError.value = null
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
  const input: DistillKnowledgeInput = {
    taskExecutionId: taskExecutionId.value.trim(),
    entryKey: entryKey.value,
    ...(category.value === '' ? {} : { category: category.value }),
  }
  const attemptKey = keyFor([props.teamId, input.taskExecutionId, input.entryKey, input.category ?? null])
  if (attemptKey === null) return
  emit('distill', input, attemptKey)
}

function distillAnother(): void {
  clearAttempt()
  viewingReceipt.value = false
  taskExecutionId.value = ''
  selectedExecutionId.value = null
  selectedObjective.value = null
  entryKey.value = ''
  entryKeyTouched.value = false
  category.value = ''
  submitted.value = false
  dirtyForm.markClean()
}
</script>

<template>
  <BaseDialog
    open
    title="从执行蒸馏知识"
    eyebrow="Distillation"
    subtitle="将一次任务执行的过程与结论沉淀为团队知识草稿。"
    width="min(640px, 100%)"
    :close-disabled="submitting"
    close-label="关闭蒸馏知识"
    @close="requestClose"
  >
    <template #icon><Sparkles :size="20" /></template>
    <div class="knowledge-dialog__content">
      <form v-if="!receiptMode" :id="formId" @submit.prevent="submit">
        <!-- M13b-F03（A11）：两步选择器——先选最近执行（或从 Task 详情带入），系统生成
             可编辑的草稿 Key；手粘 UUID 收进「高级」，不再是唯一入口。 -->
        <div class="knowledge-dialog__field">
          <span id="knowledge-distill-execution-label">选择一次执行</span>
          <ExecutionSourcePicker
            v-model="selectedExecutionId"
            :team-id="teamId"
            :disabled="submitting"
            @select="onPickExecution"
          />
          <div v-if="executionValid" class="knowledge-dialog__preview">
            <strong v-if="selectedObjective">{{ selectedObjective }}</strong>
            <small>执行 {{ taskExecutionId }} <button type="button" class="knowledge-dialog__copy" @click="copyExecutionId">{{ clipboard.copied.value === 'distill-execution-id' ? '已复制' : '复制' }}</button></small>
          </div>
          <details class="knowledge-dialog__advanced">
            <summary>高级：粘贴执行 ID</summary>
            <input
              v-model="taskExecutionId"
              class="knowledge-dialog__mono-input"
              type="text"
              autocomplete="off"
              spellcheck="false"
              aria-label="任务执行 ID"
              :disabled="submitting"
              :aria-invalid="submitted && !executionValid || undefined"
              aria-describedby="knowledge-distill-execution-hint"
              @input="onManualExecutionInput"
            >
            <small id="knowledge-distill-execution-hint" class="knowledge-dialog__hint">粘贴任务执行 ID（UUID）；Task 详情的执行区可一键复制。</small>
            <small v-if="submitted && !executionValid" class="knowledge-dialog__error">任务执行 ID 需为有效的 UUID。</small>
          </details>
        </div>

        <label class="knowledge-dialog__field">
          <span>目标条目 Key</span>
          <input
            v-model="entryKey"
            class="knowledge-dialog__mono-input"
            type="text"
            autocomplete="off"
            spellcheck="false"
            :disabled="submitting"
            :aria-invalid="submitted && !entryKeyValid || undefined"
            aria-describedby="knowledge-distill-key-hint"
            required
            @input="entryKeyTouched = true"
          >
          <small id="knowledge-distill-key-hint" class="knowledge-dialog__hint">已按所选执行自动生成，可修改；蒸馏结果保存为该 Key 的草稿，已有条目会并入其草稿。</small>
          <small v-if="submitted && !entryKeyValid" class="knowledge-dialog__error">条目 Key 需为小写字母、数字与连字符（2-64 位）。</small>
        </label>

        <label class="knowledge-dialog__field">
          <span>分类（可选）</span>
          <BaseSelect v-model="category" :options="categoryOptions" :disabled="submitting" aria-label="蒸馏结果分类" />
        </label>

        <p v-if="submissionError || errorMessage" class="knowledge-dialog__command-error" role="alert">{{ submissionError || errorMessage }}</p>
      </form>

      <BaseReceiptPanel
        v-else
        label="蒸馏回执"
        :replayed="receipt!.replayed"
        :replayed-note="distillReplayedNote"
        open-label="打开条目"
        :open-available="receipt!.entryId != null"
        :hint="receipt!.indexStatus ? (knowledgeIndexStatusHint(receipt!.indexStatus, indexEnabled) || undefined) : undefined"
        @open="emit('openEntry', receipt!.entryId!)"
        @another="distillAnother"
      >
        <template #facts>
          <div><dt>条目 Key</dt><dd class="base-receipt__mono">{{ receipt!.entryKey }}</dd></div>
          <div v-if="receipt!.origin"><dt>蒸馏来源</dt><dd class="base-receipt__mono">执行 {{ receipt!.origin.taskExecutionId }} · 第 {{ receipt!.origin.attempt }} 次尝试</dd></div>
          <div v-if="receipt!.indexStatus"><dt>索引状态</dt><dd><StatusBadge :tone="receipt!.indexStatus === 'INDEXED' ? 'success' : receipt!.indexStatus === 'FAILED' ? 'warning' : 'neutral'">{{ knowledgeIndexStatusLabels[receipt!.indexStatus] }}</StatusBadge></dd></div>
        </template>
      </BaseReceiptPanel>
    </div>
    <template #footer>
      <template v-if="!receiptMode">
        <BaseButton type="button" variant="ghost" :disabled="submitting" @click="requestClose">取消</BaseButton>
        <BaseButton type="submit" :form="formId" :loading="submitting" :disabled="!valid">开始蒸馏</BaseButton>
      </template>
      <BaseButton v-else type="button" variant="ghost" @click="requestClose">关闭</BaseButton>
    </template>
  </BaseDialog>
</template>

<style scoped>
.knowledge-dialog__content { display: grid; gap: var(--cs-space-16); }
.knowledge-dialog__content form { display: grid; gap: var(--cs-space-16); }
.knowledge-dialog__field { display: grid; gap: var(--cs-space-4); }
.knowledge-dialog__field > span { color: var(--cs-text-secondary); font-size: var(--cs-text-sm); font-weight: var(--cs-weight-semibold); }
.knowledge-dialog__field input { width: 100%; min-height: 40px; padding: 0 var(--cs-space-12); border: 1px solid var(--cs-border-strong); border-radius: var(--cs-radius-sm); background: var(--cs-surface); color: var(--cs-text); font-size: var(--cs-text-base); }
.knowledge-dialog__mono-input { font-family: var(--cs-font-mono, ui-monospace, monospace); }
.knowledge-dialog__hint { margin: 0; color: var(--cs-text-muted); font-size: var(--cs-text-xs); }
.knowledge-dialog__error { color: var(--cs-danger); font-size: var(--cs-text-xs); }
.knowledge-dialog__command-error { margin: 0; color: var(--cs-danger); font-size: var(--cs-text-sm); }
.knowledge-dialog__preview { display: grid; gap: var(--cs-space-4); padding: var(--cs-space-8) var(--cs-space-12); border: 1px solid var(--cs-border); border-radius: var(--cs-radius-sm); background: var(--cs-surface-subtle); }
.knowledge-dialog__preview strong { color: var(--cs-text-secondary); font-size: var(--cs-text-sm); font-weight: var(--cs-weight-semibold); }
.knowledge-dialog__preview small { color: var(--cs-text-muted); font: var(--cs-text-xs) var(--cs-font-mono, ui-monospace, monospace); overflow-wrap: anywhere; }
.knowledge-dialog__copy { color: var(--cs-text-brand); font-size: var(--cs-text-xs); text-decoration: underline; cursor: pointer; }
.knowledge-dialog__advanced { display: grid; gap: var(--cs-space-4); }
.knowledge-dialog__advanced summary { color: var(--cs-text-muted); font-size: var(--cs-text-xs); cursor: pointer; }
.knowledge-dialog__advanced input { width: 100%; min-height: 40px; padding: 0 var(--cs-space-12); border: 1px solid var(--cs-border-strong); border-radius: var(--cs-radius-sm); background: var(--cs-surface); color: var(--cs-text); font-size: var(--cs-text-base); }
</style>
