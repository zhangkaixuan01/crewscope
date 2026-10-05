<script setup lang="ts">
import { createFormCommandKeys } from '../../api/formCommandKeys'
import { ListPlus, X } from '@lucide/vue'
import { computed, ref, useTemplateRef, watch } from 'vue'
import { isTopmostModal } from '../../app/dialog'
import { useDirtyForm } from '../../composables/useDirtyForm'
import { validateSkillDocument, validateSkillKey } from '../../domains/skill/document'
import { SKILL_CONTENT_MAX, type CreateSkillInput } from '../../domains/skill/types'
import BaseButton from '../base/BaseButton.vue'
import BaseTextarea from '../base/BaseTextarea.vue'

const props = defineProps<{
  teamId: string
  submitting: boolean
  errorMessage: string | null
}>()

const emit = defineEmits<{
  close: []
  create: [input: CreateSkillInput, idempotencyKey: string]
}>()

const dialog = useTemplateRef<HTMLElement>('dialog')
const skillKey = ref('')
const content = ref('')
const submitted = ref(false)
const submissionError = ref<string | null>(null)
let attemptKey = ''
const intentKeys = createFormCommandKeys()
const dirtyForm = useDirtyForm(computed(() => ({
  skillKey: skillKey.value, content: content.value,
})))

const keyError = computed(() => validateSkillKey(skillKey.value))
const documentError = computed(() => skillKey.value === '' ? null : validateSkillDocument(content.value, skillKey.value))
const keyValid = computed(() => skillKey.value !== '' && keyError.value === null)
const contentValid = computed(() => skillKey.value !== '' && documentError.value === null)
const valid = computed(() => keyValid.value && contentValid.value)

/** Seeds the document template while the form is untouched; the first manual edit takes over. */
let templateLive = true
watch(skillKey, key => {
  if (templateLive) content.value = templateFor(key)
})
watch(content, value => {
  if (templateLive && value !== templateFor(skillKey.value)) templateLive = false
})

watch([skillKey, content], () => {
  // Editing any command input creates a new logical request; an unchanged retry reuses its key.
  attemptKey = ''
  submitted.value = false
  dirtyForm.markDirty()
})

function templateFor(key: string): string {
  return `---\nname: ${key}\ndescription: 一句话说明这个 Skill 的用途。\n---\n\n在这里写给任务的指令正文。`
}

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
    attemptKey = intentKeys.forInput([props.teamId, skillKey.value, content.value])
  } catch (error) {
    submissionError.value = error instanceof Error ? error.message : '无法生成安全操作标识，本次操作尚未发送。'
    return
  }
  emit('create', { skillKey: skillKey.value, content: content.value }, attemptKey)
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
      aria-labelledby="skill-create-title"
      tabindex="-1"
      @submit.prevent="submit"
      @keydown="handleKeydown"
    >
      <header>
        <span class="dialog-icon"><ListPlus :size="20" /></span>
        <div>
          <p class="eyebrow">Team skill</p>
          <h2 id="skill-create-title">创建 Skill</h2>
          <span>skillKey 是团队的长期引用，创建后不可修改。</span>
        </div>
        <button type="button" aria-label="关闭创建 Skill" :disabled="submitting" @click="requestClose"><X :size="18" /></button>
      </header>

      <div class="skill-dialog__content">
        <label class="skill-dialog__field">
          <span>skillKey</span>
          <input
            v-model="skillKey"
            class="skill-dialog__mono-input"
            type="text"
            autocomplete="off"
            spellcheck="false"
            :disabled="submitting"
            :aria-invalid="submitted && !keyValid || undefined"
            aria-describedby="skill-create-key-hint"
            required
          >
          <small id="skill-create-key-hint" class="skill-dialog__hint">小写字母、数字与连字符（1-63 位，以小写字母或数字开头），例如 deploy-runbook-v2。</small>
          <small v-if="submitted && !keyValid" class="skill-dialog__error">{{ keyError }}</small>
        </label>

        <label class="skill-dialog__field">
          <span>文档全文 <span class="skill-dialog__count">{{ content.length }}/{{ SKILL_CONTENT_MAX }}</span></span>
          <BaseTextarea v-model="content" :rows="12" :disabled="submitting" :invalid="submitted && !contentValid || undefined" />
          <small class="skill-dialog__hint">frontmatter 恰好含 name（必须等于 skillKey）与 description（≤200 字）两字段；正文即运行时注入的指令来源。</small>
          <small v-if="submitted && !contentValid" class="skill-dialog__error">{{ documentError }}</small>
        </label>

        <p v-if="submissionError || errorMessage" class="skill-dialog__command-error" role="alert">{{ submissionError || errorMessage }}</p>
      </div>

      <footer>
        <BaseButton type="button" variant="ghost" :disabled="submitting" @click="requestClose">取消</BaseButton>
        <BaseButton type="submit" :loading="submitting" :disabled="!valid">创建 Skill</BaseButton>
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
.skill-dialog__count { color: var(--cs-text-muted); font-weight: var(--cs-weight-regular); }
.skill-dialog__field input { width: 100%; min-height: 40px; padding: 0 var(--cs-space-12); border: 1px solid var(--cs-border-strong); border-radius: var(--cs-radius-sm); background: var(--cs-surface); color: var(--cs-text); font-size: var(--cs-text-base); }
.skill-dialog__mono-input { font-family: var(--cs-font-mono, ui-monospace, monospace); }
.skill-dialog__hint { color: var(--cs-text-muted); font-size: var(--cs-text-xs); }
.skill-dialog__error { color: var(--cs-danger); font-size: var(--cs-text-xs); }
.skill-dialog__command-error { margin: 0; color: var(--cs-danger); font-size: var(--cs-text-sm); }
.skill-dialog > footer { display: flex; justify-content: flex-end; gap: var(--cs-space-8); padding: var(--cs-space-16) var(--cs-space-20) var(--cs-space-20); }
@media (max-width: 767px) {
  .skill-dialog-backdrop { align-items: end; padding: 0; }
  .skill-dialog { width: 100%; max-height: calc(100dvh - 12px); border-radius: 18px 18px 0 0; }
  .skill-dialog > footer { display: grid; }
  .skill-dialog > footer > * { width: 100%; }
}
</style>
