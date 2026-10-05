<script setup lang="ts">
import { createFormCommandKeys } from '../../api/formCommandKeys'
import { Sparkles, X } from '@lucide/vue'
import { computed, ref, useTemplateRef, watch } from 'vue'
import { isTopmostModal } from '../../app/dialog'
import { useDirtyForm } from '../../composables/useDirtyForm'
import { validateSkillKey } from '../../domains/skill/document'
import { skillStatusLabels } from '../../domains/skill/labels'
import type { DistillSkillInput, SkillDistillationReceipt } from '../../domains/skill/types'
import BaseButton from '../base/BaseButton.vue'
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

const dialog = useTemplateRef<HTMLElement>('dialog')
const taskExecutionId = ref('')
const skillKey = ref('')
const submitted = ref(false)
const submissionError = ref<string | null>(null)
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
  <div class="skill-dialog-backdrop" @click.self="requestClose">
    <form
      ref="dialog"
      class="skill-dialog panel"
      role="dialog"
      aria-modal="true"
      aria-labelledby="skill-distill-title"
      tabindex="-1"
      @submit.prevent="submit"
      @keydown="handleKeydown"
    >
      <header>
        <span class="dialog-icon"><Sparkles :size="20" /></span>
        <div>
          <p class="eyebrow">Distillation</p>
          <h2 id="skill-distill-title">从执行蒸馏 Skill</h2>
          <span>将一次任务执行的过程与结论沉淀为团队 Skill 草稿。</span>
        </div>
        <button type="button" aria-label="关闭蒸馏 Skill" :disabled="submitting" @click="requestClose"><X :size="18" /></button>
      </header>

      <div class="skill-dialog__content">
        <template v-if="!receiptMode">
          <label class="skill-dialog__field">
            <span>任务执行 ID</span>
            <input
              v-model="taskExecutionId"
              class="skill-dialog__mono-input"
              type="text"
              autocomplete="off"
              spellcheck="false"
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
        </template>

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

      <footer v-if="!receiptMode">
        <BaseButton type="button" variant="ghost" :disabled="submitting" @click="requestClose">取消</BaseButton>
        <BaseButton type="submit" :loading="submitting" :disabled="!valid">开始蒸馏</BaseButton>
      </footer>
      <footer v-else>
        <BaseButton type="button" variant="ghost" @click="requestClose">关闭</BaseButton>
      </footer>
    </form>
  </div>
</template>

<style scoped>
.skill-dialog-backdrop { position: fixed; inset: 0; z-index: var(--cs-z-dialog); display: grid; place-items: center; padding: var(--cs-space-20); background: var(--cs-scrim); backdrop-filter: blur(3px); }
.skill-dialog { display: flex; width: min(640px, 100%); max-height: calc(100dvh - 40px); flex-direction: column; overflow-y: auto; box-shadow: var(--cs-shadow-float); }
.skill-dialog > header { display: grid; grid-template-columns: 42px minmax(0, 1fr) 32px; align-items: start; gap: var(--cs-space-12); padding: var(--cs-space-20); border-bottom: 1px solid var(--cs-border); }
.dialog-icon { display: grid; width: 42px; height: 42px; place-items: center; border-radius: 12px; background: var(--cs-surface-accent-strong); color: var(--cs-text-brand); }
.skill-dialog h2 { margin: 0 0 var(--cs-space-4); font-size: var(--cs-text-lg); }
.skill-dialog header div > span { color: var(--cs-text-muted); font-size: var(--cs-text-sm); }
.skill-dialog header button { display: grid; width: 32px; height: 32px; place-items: center; border-radius: 8px; background: var(--cs-surface-subtle); color: var(--cs-text); cursor: pointer; }
.skill-dialog__content { display: grid; gap: var(--cs-space-16); padding: var(--cs-space-20) var(--cs-space-20) var(--cs-space-4); }
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
.skill-dialog > footer { display: flex; justify-content: flex-end; gap: var(--cs-space-8); padding: var(--cs-space-16) var(--cs-space-20) var(--cs-space-20); }
@media (max-width: 767px) {
  .skill-dialog-backdrop { align-items: end; padding: 0; }
  .skill-dialog { width: 100%; max-height: calc(100dvh - 12px); border-radius: 18px 18px 0 0; }
  .skill-dialog > footer { display: grid; }
  .skill-dialog > footer > * { width: 100%; }
}
</style>
