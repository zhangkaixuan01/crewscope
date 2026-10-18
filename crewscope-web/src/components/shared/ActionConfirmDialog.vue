<script setup lang="ts">
import { AlertTriangle } from '@lucide/vue'
import { computed, ref, useId } from 'vue'
import BaseButton from '../base/BaseButton.vue'
import BaseDialog from '../base/BaseDialog.vue'
import BaseTextarea from '../base/BaseTextarea.vue'

/**
 * One alertdialog, three confirmation modes (M12b-F04, governance §10.6 "explain the impact
 * and confirm"): plain (impact text only), reason (optional free-text justification with a
 * length cap) and confirm (an explicit checkbox gate). Consumers own the wording — the
 * contract-specific consequences a bare "确定？" would hide stay at the call sites.
 */
const props = withDefaults(defineProps<{
  heading: string
  /** Impact statement rendered as the dialog's aria-describedby target. */
  impact: string
  confirmLabel: string
  submitting: boolean
  errorMessage: string | null
  /** plain: no extra gate; reason: optional capped justification; confirm: checkbox gate. */
  mode?: 'plain' | 'reason' | 'confirm'
  variant?: 'primary' | 'danger'
  subtitle?: string
  eyebrow?: string
  width?: string
  cancelLabel?: string
  closeLabel?: string
  /** reason mode. */
  reasonLabel?: string
  reasonMaxlength?: number
  reasonPlaceholder?: string
  /** confirm mode. */
  confirmCheckboxLabel?: string
}>(), {
  mode: 'plain',
  variant: 'primary',
  width: 'min(560px, 100%)',
  cancelLabel: '取消',
  closeLabel: '取消操作',
  reasonLabel: '原因（可选）',
  reasonPlaceholder: '',
  confirmCheckboxLabel: '',
})

const emit = defineEmits<{
  cancel: []
  confirm: [reason: string | null]
}>()

const reason = ref('')
const checked = ref(false)
const impactId = useId()

const reasonOver = computed(() => reason.value.length > (props.reasonMaxlength ?? Number.MAX_SAFE_INTEGER))
const confirmDisabled = computed(() => {
  if (props.submitting || reasonOver.value) return true
  return props.mode === 'confirm' && !checked.value
})

function submit(): void {
  if (confirmDisabled.value) return
  emit('confirm', props.mode === 'reason' && reason.value.trim() !== '' ? reason.value.trim() : null)
}

function requestClose(): void {
  if (props.submitting) return
  emit('cancel')
}
</script>

<template>
  <BaseDialog
    open
    role="alertdialog"
    :title="heading"
    :eyebrow="eyebrow"
    :subtitle="subtitle"
    :width="width"
    :described-by="impactId"
    :close-disabled="submitting"
    :close-label="closeLabel"
    @close="requestClose"
  >
    <template #icon><AlertTriangle :size="20" /></template>
    <form class="action-confirm__content" @submit.prevent="submit">
      <p :id="impactId" class="action-confirm__impact">{{ impact }}</p>
      <label v-if="mode === 'reason'" class="action-confirm__field">
        <span>{{ reasonLabel }}<span class="action-confirm__count" :class="{ over: reasonOver }">{{ reason.length }}/{{ reasonMaxlength }}</span></span>
        <BaseTextarea v-model="reason" :rows="3" :disabled="submitting" :invalid="reasonOver || undefined" :placeholder="reasonPlaceholder" />
      </label>
      <label v-else-if="mode === 'confirm'" class="action-confirm__checkbox">
        <input v-model="checked" type="checkbox" :disabled="submitting" />{{ confirmCheckboxLabel }}
      </label>

      <p v-if="errorMessage" class="action-confirm__error" role="alert">{{ errorMessage }}</p>
    </form>
    <template #footer>
      <!-- 危险确认的初始焦点必须落在安全侧（取消），而不是 header 的关闭钮或破坏性主钮。 -->
      <BaseButton type="button" variant="ghost" autofocus :disabled="submitting" @click="requestClose">{{ cancelLabel }}</BaseButton>
      <!-- Direct click wiring (not type="submit" + form attr): the only text field is a
           textarea, so Enter never submits — and jsdom does not implement form-attr submits. -->
      <BaseButton type="button" :variant="variant" :loading="submitting" :disabled="confirmDisabled" @click="submit">{{ confirmLabel }}</BaseButton>
    </template>
  </BaseDialog>
</template>

<style scoped>
.action-confirm__content { display: grid; gap: var(--cs-space-16); }
.action-confirm__impact { margin: 0; color: var(--cs-text); font-size: var(--cs-text-sm); line-height: var(--cs-leading-normal); }
.action-confirm__field { display: grid; gap: var(--cs-space-4); }
.action-confirm__field > span { color: var(--cs-text-secondary); font-size: var(--cs-text-sm); font-weight: var(--cs-weight-semibold); }
.action-confirm__count { color: var(--cs-text-muted); font-weight: var(--cs-weight-regular); }
.action-confirm__count.over { color: var(--cs-danger); font-weight: var(--cs-weight-semibold); }
.action-confirm__checkbox { display: flex; align-items: center; gap: var(--cs-space-8); color: var(--cs-danger); font-size: var(--cs-text-sm); font-weight: var(--cs-weight-semibold); }
.action-confirm__error { margin: 0; color: var(--cs-danger); font-size: var(--cs-text-sm); }
</style>
