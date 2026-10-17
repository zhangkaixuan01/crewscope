<script setup lang="ts">
import { createFormCommandKeys } from '../../api/formCommandKeys'
import { Sparkles } from '@lucide/vue'
import { computed, ref, useId, watch } from 'vue'
import { useDirtyForm } from '../../composables/useDirtyForm'
import { validateSkillKey } from '../../domains/skill/document'
import { skillStatusLabels } from '../../domains/skill/labels'
import type { DistillSkillInput, SkillDistillationReceipt } from '../../domains/skill/types'
import BaseButton from '../base/BaseButton.vue'
import BaseDialog from '../base/BaseDialog.vue'
import StatusBadge from '../base/StatusBadge.vue'

const props = defineProps<{
  teamId: string
  submitting: boolean
  errorMessage: string | null
  receipt: SkillDistillationReceipt | null
}>()

const emit = defineEmits<{
  close: []
  distill: [input: DistillSkillInput, idempotencyKey: string]
  openSkill: [skillId: string]
}>()

const taskExecutionId = ref('')
const skillKey = ref('')
const submitted = ref(false)
const submissionError = ref<string | null>(null)
const formId = useId()
let attemptKey = ''
const intentKeys = createFormCommandKeys()
const dirtyForm = useDirtyForm(computed(() => ({
  taskExecutionId: taskExecutionId.value, skillKey: skillKey.value,
})))

const uuidPattern = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i
const executionValid = computed(() => uuidPattern.test(taskExecutionId.value.trim()))
const keyError = computed(() => validateSkillKey(skillKey.value))
const keyValid = computed(() => skillKey.value !== '' && keyError.value === null)
const valid = computed(() => executionValid.value && keyValid.value)

/** D3: the dialog stays open and turns into the receipt surface; the form comes back on demand. */
const viewingReceipt = ref(props.receipt !== null)
const receiptMode = computed(() => props.receipt !== null && viewingReceipt.value)

watch([taskExecutionId, skillKey], () => {
  // Editing any command input creates a new logical request; an unchanged retry reuses its key.
  attemptKey = ''
  submitted.value = false
  dirtyForm.markDirty()
})

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
  const input: DistillSkillInput = {
    taskExecutionId: taskExecutionId.value.trim(),
    skillKey: skillKey.value,
  }
  try {
    attemptKey = intentKeys.forInput([props.teamId, input.taskExecutionId, input.skillKey])
  } catch (error) {
    submissionError.value = error instanceof Error ? error.message : '无法生成安全操作标识，本次操作尚未发送。'
    return
  }
  emit('distill', input, attemptKey)
}

function distillAnother(): void {
  clearAttempt()
  viewingReceipt.value = false
  taskExecutionId.value = ''
  skillKey.value = ''
  submitted.value = false
  dirtyForm.markClean()
}

function clearAttempt(): void {
  attemptKey = ''
  intentKeys.clear()
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
        <label class="skill-dialog__field">
          <span>任务执行 ID</span>
          <input
            v-model="taskExecutionId"
            class="skill-dialog__mono-input"
            type="text"
            autocomplete="off"
            spellcheck="false"
            autofocus
            :disabled="submitting"
            :aria-invalid="submitted && !executionValid || undefined"
            aria-describedby="skill-distill-execution-hint"
            required
          >
          <small id="skill-distill-execution-hint" class="skill-dialog__hint">在执行详情或产物面板可复制执行 ID（UUID）；仅任务执行创建者本人可提炼该执行。</small>
          <small v-if="submitted && !executionValid" class="skill-dialog__error">任务执行 ID 需为有效的 UUID。</small>
        </label>

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
          >
          <small id="skill-distill-key-hint" class="skill-dialog__hint">蒸馏结果会保存为该 skillKey 的草稿；服务端组装 frontmatter（name 与该键一致）。</small>
          <small v-if="submitted && !keyValid" class="skill-dialog__error">{{ keyError }}</small>
        </label>

        <p v-if="submissionError || errorMessage" class="skill-dialog__command-error" role="alert">{{ submissionError || errorMessage }}</p>
      </form>

      <section v-else class="skill-dialog__receipt" aria-label="蒸馏回执">
        <p v-if="receipt!.replayed" class="skill-dialog__replayed" role="status">此蒸馏命令此前已确认过，本次为幂等重放，没有重复创建内容。</p>
        <dl>
          <div><dt>skillKey</dt><dd class="skill-dialog__mono">{{ receipt!.skillKey }}</dd></div>
          <div v-if="receipt!.origin"><dt>蒸馏来源</dt><dd class="skill-dialog__mono">执行 {{ receipt!.origin.taskExecutionId }} · 第 {{ receipt!.origin.attempt }} 次尝试</dd></div>
          <div v-if="receipt!.status"><dt>状态</dt><dd><StatusBadge tone="neutral">{{ skillStatusLabels[receipt!.status] }}</StatusBadge></dd></div>
        </dl>
        <div class="skill-dialog__receipt-actions">
          <BaseButton v-if="receipt!.skillId" type="button" variant="primary" size="small" @click="emit('openSkill', receipt!.skillId!)">打开 Skill</BaseButton>
          <BaseButton type="button" variant="secondary" size="small" @click="distillAnother">再蒸馏一条</BaseButton>
        </div>
      </section>
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
.skill-dialog__receipt { display: flex; flex-direction: column; gap: var(--cs-space-12); padding: var(--cs-space-12); border: 1px solid var(--cs-border-accent); border-radius: var(--cs-radius-md); background: var(--cs-surface-accent); }
.skill-dialog__replayed { margin: 0; color: var(--cs-text); font-size: var(--cs-text-sm); }
.skill-dialog__receipt dl { display: flex; margin: 0; flex-direction: column; gap: var(--cs-space-8); }
.skill-dialog__receipt dl div { display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: var(--cs-space-8); }
.skill-dialog__receipt dt { color: var(--cs-text-muted); font-size: var(--cs-text-xs); }
.skill-dialog__receipt dd { margin: 0; font-size: var(--cs-text-sm); overflow-wrap: anywhere; }
.skill-dialog__mono { font-family: var(--cs-font-mono, ui-monospace, monospace); font-size: var(--cs-text-xs); }
.skill-dialog__receipt-actions { display: flex; flex-wrap: wrap; gap: var(--cs-space-8); }
</style>
