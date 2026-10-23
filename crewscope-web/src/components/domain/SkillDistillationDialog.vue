<script setup lang="ts">
import { Sparkles } from '@lucide/vue'
import { computed, ref, useId, watch } from 'vue'
import { useClipboard } from '../../composables/useClipboard'
import { useDirtyForm } from '../../composables/useDirtyForm'
import { useIdempotentCommand } from '../../composables/useIdempotentCommand'
import { validateSkillKey } from '../../domains/skill/document'
import { skillStatusLabels } from '../../domains/skill/labels'
import type { DistillSkillInput, SkillDistillationReceipt } from '../../domains/skill/types'
import BaseButton from '../base/BaseButton.vue'
import BaseDialog from '../base/BaseDialog.vue'
import StatusBadge from '../base/StatusBadge.vue'
import BaseReceiptPanel from '../shared/BaseReceiptPanel.vue'
import ExecutionSourcePicker from './ExecutionSourcePicker.vue'

const props = defineProps<{
  teamId: string
  submitting: boolean
  errorMessage: string | null
  receipt: SkillDistillationReceipt | null
  /** M13b-F03（R14）：执行现场带入的执行（Task 详情「蒸馏为知识」的 Skill 同构路径）。 */
  presetExecution?: { taskExecutionId: string, objective?: string | null } | null
}>()

const emit = defineEmits<{
  close: []
  distill: [input: DistillSkillInput, idempotencyKey: string]
  openSkill: [skillId: string]
}>()

const taskExecutionId = ref('')
const skillKey = ref('')
const submitted = ref(false)
const formId = useId()
// M13b-F03（A11）：选择器选中后的预览事实；手粘与带入路径同样写入 taskExecutionId。
const selectedObjective = ref<string | null>(props.presetExecution?.objective ?? null)
const selectedExecutionId = ref<string | null>(props.presetExecution?.taskExecutionId ?? null)
const skillKeyTouched = ref(false)
const clipboard = useClipboard()
if (props.presetExecution) taskExecutionId.value = props.presetExecution.taskExecutionId
const dirtyForm = useDirtyForm(computed(() => ({
  taskExecutionId: taskExecutionId.value, skillKey: skillKey.value,
})))
const { submissionError, keyFor, clearAttempt } = useIdempotentCommand(
  () => [taskExecutionId.value, skillKey.value],
  () => {
    submitted.value = false
    dirtyForm.markDirty()
  },
)

const uuidPattern = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i
const executionValid = computed(() => uuidPattern.test(taskExecutionId.value.trim()))
const keyError = computed(() => validateSkillKey(skillKey.value))
const keyValid = computed(() => skillKey.value !== '' && keyError.value === null)
const valid = computed(() => executionValid.value && keyValid.value)

/** A11：执行一旦确定就生成可编辑的默认 skillKey；用户改过后不再覆盖（手粘高级区同样适用）。 */
watch(() => taskExecutionId.value, () => {
  if (skillKeyTouched.value) return
  skillKey.value = executionValid.value ? `skill-${taskExecutionId.value.trim().slice(0, 8)}` : ''
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
  if (taskExecutionId.value) await clipboard.copy(taskExecutionId.value.trim(), 'skill-distill-execution-id')
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
  const input: DistillSkillInput = {
    taskExecutionId: taskExecutionId.value.trim(),
    skillKey: skillKey.value,
  }
  const attemptKey = keyFor([props.teamId, input.taskExecutionId, input.skillKey])
  if (attemptKey === null) return
  emit('distill', input, attemptKey)
}

function distillAnother(): void {
  clearAttempt()
  viewingReceipt.value = false
  taskExecutionId.value = ''
  selectedExecutionId.value = null
  selectedObjective.value = null
  skillKey.value = ''
  skillKeyTouched.value = false
  submitted.value = false
  dirtyForm.markClean()
}
</script>

<template>
  <BaseDialog
    open
    title="从执行蒸馏 Skill"
    eyebrow="Distillation"
    subtitle="将一次任务执行的过程与结论沉淀为团队 Skill 草稿。"
    width="min(640px, 100%)"
    :close-disabled="submitting"
    close-label="关闭蒸馏 Skill"
    @close="requestClose"
  >
    <template #icon><Sparkles :size="20" /></template>
    <div class="skill-dialog__content">
      <form v-if="!receiptMode" :id="formId" @submit.prevent="submit">
        <!-- M13b-F03（A11）：两步选择器——先选最近执行（或从 Task 详情带入），系统生成
             可编辑的草稿 skillKey；手粘 UUID 收进「高级」，不再是唯一入口。 -->
        <div class="skill-dialog__field">
          <span>选择一次执行</span>
          <ExecutionSourcePicker
            v-model="selectedExecutionId"
            :team-id="teamId"
            :disabled="submitting"
            @select="onPickExecution"
          />
          <div v-if="executionValid" class="skill-dialog__preview">
            <strong v-if="selectedObjective">{{ selectedObjective }}</strong>
            <small>执行 {{ taskExecutionId }} <button type="button" class="skill-dialog__copy" @click="copyExecutionId">{{ clipboard.copied.value === 'skill-distill-execution-id' ? '已复制' : '复制' }}</button></small>
          </div>
          <details class="skill-dialog__advanced">
            <summary>高级：粘贴执行 ID</summary>
            <input
              v-model="taskExecutionId"
              class="skill-dialog__mono-input"
              type="text"
              autocomplete="off"
              spellcheck="false"
              aria-label="任务执行 ID"
              :disabled="submitting"
              :aria-invalid="submitted && !executionValid || undefined"
              aria-describedby="skill-distill-execution-hint"
              @input="onManualExecutionInput"
            >
            <small id="skill-distill-execution-hint" class="skill-dialog__hint">粘贴任务执行 ID（UUID）；Task 详情的执行区可一键复制。仅任务执行创建者本人可提炼该执行。</small>
            <small v-if="submitted && !executionValid" class="skill-dialog__error">任务执行 ID 需为有效的 UUID。</small>
          </details>
          <small class="skill-dialog__hint">选择一次执行，系统会生成 Skill 草稿。</small>
        </div>

        <label class="skill-dialog__field">
          <span>目标 skillKey</span>
          <input
            v-model="skillKey"
            class="skill-dialog__mono-input"
            type="text"
            autocomplete="off"
            spellcheck="false"
            :disabled="submitting"
            :aria-invalid="submitted && !keyValid || undefined"
            aria-describedby="skill-distill-key-hint"
            required
            @input="skillKeyTouched = true"
          >
          <small id="skill-distill-key-hint" class="skill-dialog__hint">已按所选执行自动生成，可修改；蒸馏结果保存为该 skillKey 的草稿，服务端组装 frontmatter（name 与该键一致）。</small>
          <small v-if="submitted && !keyValid" class="skill-dialog__error">{{ keyError }}</small>
        </label>

        <p v-if="submissionError || errorMessage" class="skill-dialog__command-error" role="alert">{{ submissionError || errorMessage }}</p>
      </form>

      <BaseReceiptPanel
        v-else
        label="蒸馏回执"
        :replayed="receipt!.replayed"
        replayed-note="此蒸馏命令此前已确认过，本次为幂等重放，没有重复创建内容。"
        open-label="打开 Skill"
        :open-available="receipt!.skillId != null"
        @open="emit('openSkill', receipt!.skillId!)"
        @another="distillAnother"
      >
        <template #facts>
          <div><dt>skillKey</dt><dd class="base-receipt__mono">{{ receipt!.skillKey }}</dd></div>
          <div v-if="receipt!.origin"><dt>蒸馏来源</dt><dd class="base-receipt__mono">执行 {{ receipt!.origin.taskExecutionId }} · 第 {{ receipt!.origin.attempt }} 次尝试</dd></div>
          <div v-if="receipt!.status"><dt>状态</dt><dd><StatusBadge tone="neutral">{{ skillStatusLabels[receipt!.status] }}</StatusBadge></dd></div>
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
.skill-dialog__content { display: grid; gap: var(--cs-space-16); }
.skill-dialog__content form { display: grid; gap: var(--cs-space-16); }
.skill-dialog__field { display: grid; gap: var(--cs-space-4); }
.skill-dialog__field > span { color: var(--cs-text-secondary); font-size: var(--cs-text-sm); font-weight: var(--cs-weight-semibold); }
.skill-dialog__field input { width: 100%; min-height: 40px; padding: 0 var(--cs-space-12); border: 1px solid var(--cs-border-strong); border-radius: var(--cs-radius-sm); background: var(--cs-surface); color: var(--cs-text); font-size: var(--cs-text-base); }
.skill-dialog__mono-input { font-family: var(--cs-font-mono, ui-monospace, monospace); }
.skill-dialog__hint { margin: 0; color: var(--cs-text-muted); font-size: var(--cs-text-xs); }
.skill-dialog__error { color: var(--cs-danger); font-size: var(--cs-text-xs); }
.skill-dialog__command-error { margin: 0; color: var(--cs-danger); font-size: var(--cs-text-sm); }
.skill-dialog__preview { display: grid; gap: var(--cs-space-4); padding: var(--cs-space-8) var(--cs-space-12); border: 1px solid var(--cs-border); border-radius: var(--cs-radius-sm); background: var(--cs-surface-subtle); }
.skill-dialog__preview strong { color: var(--cs-text-secondary); font-size: var(--cs-text-sm); font-weight: var(--cs-weight-semibold); }
.skill-dialog__preview small { color: var(--cs-text-muted); font: var(--cs-text-xs) var(--cs-font-mono, ui-monospace, monospace); overflow-wrap: anywhere; }
.skill-dialog__copy { color: var(--cs-text-brand); font-size: var(--cs-text-xs); text-decoration: underline; cursor: pointer; }
.skill-dialog__advanced { display: grid; gap: var(--cs-space-4); }
.skill-dialog__advanced summary { color: var(--cs-text-muted); font-size: var(--cs-text-xs); cursor: pointer; }
.skill-dialog__advanced input { width: 100%; min-height: 40px; padding: 0 var(--cs-space-12); border: 1px solid var(--cs-border-strong); border-radius: var(--cs-radius-sm); background: var(--cs-surface); color: var(--cs-text); font-size: var(--cs-text-base); }

</style>
