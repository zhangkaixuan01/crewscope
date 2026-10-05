<script setup lang="ts">
import { AlertTriangle, X } from '@lucide/vue'
import { computed, ref, useTemplateRef, watch } from 'vue'
import { isTopmostModal } from '../../app/dialog'
import { SKILL_DISABLE_REASON_MAX } from '../../domains/skill/types'
import BaseButton from '../base/BaseButton.vue'
import BaseTextarea from '../base/BaseTextarea.vue'

/**
 * One alertdialog, three confirmations (F02 D3, governance §10.6 "explain the impact and
 * confirm"): publish, disable and rollback each carry contract-specific consequences that a
 * bare "确定？" would hide.
 */
export type SkillActionIntent =
  | { kind: 'publish', skillKey: string, nextRevision: number }
  | { kind: 'disable', skillKey: string }
  | { kind: 'rollback', skillKey: string, toRevision: number, effectiveRevision: number }

const props = defineProps<{
  intent: SkillActionIntent
  submitting: boolean
  errorMessage: string | null
}>()

const emit = defineEmits<{
  cancel: []
  confirm: [reason: string | null]
}>()

const dialog = useTemplateRef<HTMLElement>('dialog')
const reason = ref('')

watch(() => props.intent, () => {
  reason.value = ''
})

const reasonOver = computed(() => reason.value.length > SKILL_DISABLE_REASON_MAX)

const heading = computed(() => {
  if (props.intent.kind === 'publish') return '发布当前草稿？'
  if (props.intent.kind === 'disable') return '禁用这个 Skill？'
  return `回滚到 r${props.intent.toRevision}？`
})
const confirmLabel = computed(() => {
  if (props.intent.kind === 'publish') return '确认发布'
  if (props.intent.kind === 'disable') return '确认禁用'
  return '确认回滚'
})
const confirmDisabled = computed(() => props.submitting || reasonOver.value)

function submit(): void {
  if (confirmDisabled.value) return
  emit('confirm', props.intent.kind === 'disable' && reason.value.trim() !== '' ? reason.value.trim() : null)
}

function requestClose(): void {
  if (props.submitting) return
  emit('cancel')
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
  <div class="skill-confirm-backdrop" @click.self="requestClose">
    <form
      ref="dialog"
      class="skill-confirm panel"
      role="alertdialog"
      aria-modal="true"
      aria-labelledby="skill-confirm-title"
      aria-describedby="skill-confirm-impact"
      tabindex="-1"
      @submit.prevent="submit"
      @keydown="handleKeydown"
    >
      <header>
        <span class="dialog-icon"><AlertTriangle :size="20" /></span>
        <div>
          <p class="eyebrow">Team skill</p>
          <h2 id="skill-confirm-title">{{ heading }}</h2>
          <span class="skill-confirm__key">{{ intent.skillKey }}</span>
        </div>
        <button type="button" aria-label="取消操作" :disabled="submitting" @click="requestClose"><X :size="18" /></button>
      </header>

      <div class="skill-confirm__content">
        <p v-if="intent.kind === 'publish'" id="skill-confirm-impact" class="skill-confirm__impact">
          发布会产出新的不可变修订 r{{ intent.nextRevision }} 并更新生效指针。服务端会对全部内容（frontmatter 与正文）执行无条件披露扫描——Skill 会注入未来任务的指令，审核标准比知识条目更严。发布后仍可回滚。
        </p>
        <template v-else-if="intent.kind === 'disable'">
          <p id="skill-confirm-impact" class="skill-confirm__impact">
            禁用即下线：后续任务不再装载这个 Skill，但目录与历史版本全部保留（没有删除操作）。修改草稿并再次发布即可产出新修订、恢复生效。
          </p>
          <label class="skill-confirm__field">
            <span>禁用原因（可选）<span class="skill-confirm__count" :class="{ over: reasonOver }">{{ reason.length }}/{{ SKILL_DISABLE_REASON_MAX }}</span></span>
            <BaseTextarea v-model="reason" :rows="3" :disabled="submitting" :invalid="reasonOver || undefined" placeholder="会展示给 Team 全体成员。" />
          </label>
        </template>
        <p v-else id="skill-confirm-impact" class="skill-confirm__impact">
          回滚不是删除：会产出一个新的不可变修订，其内容与哈希与 r{{ intent.toRevision }} 完全相同（相邻修订同哈希属正常形态），当前生效的 r{{ intent.effectiveRevision }} 之后的中间修订全部保留在历史中。
        </p>

        <p v-if="errorMessage" class="skill-confirm__error" role="alert">{{ errorMessage }}</p>
      </div>

      <footer>
        <BaseButton type="button" variant="ghost" :disabled="submitting" @click="requestClose">取消</BaseButton>
        <BaseButton type="submit" :variant="intent.kind === 'disable' ? 'danger' : 'primary'" :loading="submitting" :disabled="confirmDisabled">{{ confirmLabel }}</BaseButton>
      </footer>
    </form>
  </div>
</template>

<style scoped>
.skill-confirm-backdrop { position: fixed; inset: 0; z-index: var(--cs-z-dialog); display: grid; place-items: center; padding: var(--cs-space-20); background: var(--cs-scrim); backdrop-filter: blur(3px); }
.skill-confirm { display: flex; width: min(560px, 100%); max-height: calc(100dvh - 40px); flex-direction: column; overflow-y: auto; box-shadow: var(--cs-shadow-float); }
.skill-confirm > header { display: grid; grid-template-columns: 42px minmax(0, 1fr) 32px; align-items: start; gap: var(--cs-space-12); padding: var(--cs-space-20); border-bottom: 1px solid var(--cs-border); }
.dialog-icon { display: grid; width: 42px; height: 42px; place-items: center; border-radius: 12px; background: var(--cs-surface-accent-strong); color: var(--cs-text-brand); }
.skill-confirm h2 { margin: 0 0 var(--cs-space-4); font-size: var(--cs-text-lg); }
.skill-confirm__key { color: var(--cs-text-muted); font-family: var(--cs-font-mono, ui-monospace, monospace); font-size: var(--cs-text-sm); }
.skill-confirm header button { display: grid; width: 32px; height: 32px; place-items: center; border-radius: 8px; background: var(--cs-surface-subtle); color: var(--cs-text); cursor: pointer; }
.skill-confirm__content { display: grid; gap: var(--cs-space-16); padding: var(--cs-space-20) var(--cs-space-20) var(--cs-space-4); }
.skill-confirm__impact { margin: 0; color: var(--cs-text); font-size: var(--cs-text-sm); line-height: var(--cs-leading-normal); }
.skill-confirm__field { display: grid; gap: var(--cs-space-4); }
.skill-confirm__field > span { color: var(--cs-text-secondary); font-size: var(--cs-text-sm); font-weight: var(--cs-weight-semibold); }
.skill-confirm__count { color: var(--cs-text-muted); font-weight: var(--cs-weight-regular); }
.skill-confirm__count.over { color: var(--cs-danger); font-weight: var(--cs-weight-semibold); }
.skill-confirm__error { margin: 0; color: var(--cs-danger); font-size: var(--cs-text-sm); }
.skill-confirm > footer { display: flex; justify-content: flex-end; gap: var(--cs-space-8); padding: var(--cs-space-16) var(--cs-space-20) var(--cs-space-20); }
@media (max-width: 767px) {
  .skill-confirm-backdrop { align-items: end; padding: 0; }
  .skill-confirm { width: 100%; max-height: calc(100dvh - 12px); border-radius: 18px 18px 0 0; }
  .skill-confirm > footer { display: grid; }
  .skill-confirm > footer > * { width: 100%; }
}
</style>
