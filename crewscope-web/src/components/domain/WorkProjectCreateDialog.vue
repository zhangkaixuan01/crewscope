<script setup lang="ts">
import { BriefcaseBusiness, CheckCircle2, LoaderCircle, ShieldCheck, X } from '@lucide/vue'
import { computed, nextTick, onBeforeUnmount, onMounted, ref, useId, useTemplateRef, watch } from 'vue'
import { isTopmostModal } from '../../app/dialog'
import type { CreateWorkProjectInput } from '../../domains/scope/types'
import BaseButton from '../base/BaseButton.vue'

type AvailabilityPhase = 'idle' | 'checking' | 'available' | 'unavailable' | 'error'

const props = defineProps<{
  teamName: string
  submitting: boolean
  retryable: boolean
  errorMessage: string | null
  checkKey: (key: string, signal?: AbortSignal) => Promise<boolean>
}>()

const emit = defineEmits<{
  close: []
  submit: [input: CreateWorkProjectInput, idempotencyKey: string]
  inputChanged: []
}>()

const dialog = useTemplateRef<HTMLElement>('dialog')
const keyInput = useTemplateRef<HTMLInputElement>('keyInput')
const titleId = `${useId()}-title`
const key = ref('')
const name = ref('')
const submitted = ref(false)
const availability = ref<AvailabilityPhase>('idle')
let availabilityTimer: ReturnType<typeof setTimeout> | null = null
let availabilityAbort: AbortController | null = null
let attemptKey = ''
let returnTarget: HTMLElement | null = null

const normalizedKey = computed(() => key.value.trim().toUpperCase())
const normalizedName = computed(() => name.value.trim())
const keyFormatValid = computed(() => /^[A-Z][A-Z0-9]{1,9}$/.test(normalizedKey.value))
const nameValid = computed(() => normalizedName.value.length >= 1 && normalizedName.value.length <= 200)
const retryingAcceptedCommand = computed(() => props.retryable && Boolean(attemptKey))
const valid = computed(() => keyFormatValid.value
  && nameValid.value
  && (availability.value === 'available' || retryingAcceptedCommand.value))

watch([key, name], () => {
  // A changed field represents a new logical command; only an unchanged retry reuses its key.
  attemptKey = ''
  submitted.value = false
  emit('inputChanged')
})

watch(() => [props.errorMessage, props.retryable] as const, ([errorMessage, retryable]) => {
  if (errorMessage && !retryable) attemptKey = ''
})

watch(normalizedKey, () => scheduleAvailabilityCheck(), { immediate: true })

onMounted(() => {
  returnTarget = document.activeElement instanceof HTMLElement ? document.activeElement : null
  void nextTick(() => keyInput.value?.focus())
})
onBeforeUnmount(() => {
  cancelAvailabilityCheck()
  if (returnTarget?.isConnected) returnTarget.focus()
})

function scheduleAvailabilityCheck(): void {
  cancelAvailabilityCheck()
  if (!keyFormatValid.value) {
    availability.value = 'idle'
    return
  }
  availability.value = 'checking'
  const checkedKey = normalizedKey.value
  availabilityTimer = setTimeout(async () => {
    availabilityTimer = null
    const controller = new AbortController()
    availabilityAbort = controller
    try {
      const available = await props.checkKey(checkedKey, controller.signal)
      if (!controller.signal.aborted && normalizedKey.value === checkedKey) {
        availability.value = available ? 'available' : 'unavailable'
      }
    } catch (error) {
      if (!controller.signal.aborted && !(error instanceof DOMException && error.name === 'AbortError')) {
        availability.value = 'error'
      }
    } finally {
      if (availabilityAbort === controller) availabilityAbort = null
    }
  }, 250)
}

function cancelAvailabilityCheck(): void {
  if (availabilityTimer) clearTimeout(availabilityTimer)
  availabilityTimer = null
  availabilityAbort?.abort()
  availabilityAbort = null
}

function requestClose(): void {
  if (!props.submitting) emit('close')
}

function submit(): void {
  submitted.value = true
  if (!valid.value) return
  if (!attemptKey) attemptKey = crypto.randomUUID()
  emit('submit', { key: normalizedKey.value, name: normalizedName.value }, attemptKey)
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
  const controls = [...dialog.value.querySelectorAll<HTMLElement>('button:not(:disabled), input:not(:disabled)')]
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
    <div class="project-create-backdrop" @click.self="requestClose">
      <form ref="dialog" class="project-create-dialog panel" role="dialog" aria-modal="true" :aria-labelledby="titleId" tabindex="-1" @submit.prevent="submit" @keydown="handleKeydown">
        <header>
          <span class="dialog-icon"><BriefcaseBusiness :size="20" aria-hidden="true" /></span>
          <div><p class="eyebrow">Team WorkProject</p><h2 :id="titleId">创建 WorkProject</h2><span>在 {{ teamName }} 的默认 Workspace 中建立工作、仓库与执行范围。</span></div>
          <button type="button" aria-label="关闭创建 WorkProject" :disabled="submitting" @click="requestClose"><X :size="18" /></button>
        </header>

        <div class="project-create-content">
          <label class="project-field">
            <span>项目 Key</span>
            <input ref="keyInput" v-model="key" maxlength="10" autocomplete="off" autocapitalize="characters" spellcheck="false" placeholder="例如：CREW" :disabled="submitting" :aria-invalid="submitted && (!keyFormatValid || availability === 'unavailable')" />
            <small>2–10 位大写字母或数字，必须以字母开头；输入会按大写提交。</small>
          </label>
          <p v-if="normalizedKey && !keyFormatValid" class="field-message error">请输入符合规则的项目 Key。</p>
          <p v-else-if="availability === 'checking'" class="field-message"><LoaderCircle class="spinning" :size="13" />正在检查 Key…</p>
          <p v-else-if="availability === 'available'" class="field-message available"><CheckCircle2 :size="13" />这个 Key 可以使用。</p>
          <p v-else-if="availability === 'unavailable'" class="field-message" :class="{ error: !retryingAcceptedCommand }">
            {{ retryingAcceptedCommand ? '原创建命令可能已经占用此 Key，可以使用原请求重试同步事实。' : '这个 Key 已被当前 Team 使用。' }}
          </p>
          <p v-else-if="availability === 'error'" class="field-message error">暂时无法确认 Key 是否可用，已停止提交。</p>

          <label class="project-field">
            <span>项目名称</span>
            <input v-model="name" maxlength="200" autocomplete="off" placeholder="例如：CrewScope Platform" :disabled="submitting" :aria-invalid="submitted && !nameValid" />
            <small>用于团队成员识别这个工作范围，创建后会自动选中。</small>
          </label>

          <section class="project-boundary" aria-label="WorkProject 创建边界">
            <ShieldCheck :size="18" aria-hidden="true" />
            <div><strong>服务端范围校验</strong><span>Organization、Team、默认 Workspace 和管理权限由服务端解析；浏览器只提交 Key 与名称。</span></div>
          </section>
          <p v-if="errorMessage" class="command-error" role="alert">{{ errorMessage }}</p>
        </div>

        <footer>
          <BaseButton type="button" variant="ghost" :disabled="submitting" @click="requestClose">取消</BaseButton>
          <BaseButton type="submit" :loading="submitting" :disabled="!valid">{{ retryable ? '使用原请求重试' : '创建并选中' }}</BaseButton>
        </footer>
      </form>
    </div>
  </Teleport>
</template>

<style scoped>
.project-create-backdrop { position: fixed; inset: 0; z-index: var(--cs-z-dialog); display: grid; place-items: center; padding: var(--cs-space-20); background: var(--cs-scrim); backdrop-filter: blur(3px); }.project-create-dialog { width: min(610px, 100%); max-height: calc(100dvh - 40px); overflow-y: auto; box-shadow: var(--cs-shadow-float); }.project-create-dialog > header { display: grid; grid-template-columns: 42px minmax(0, 1fr) 32px; align-items: start; gap: var(--cs-space-12); padding: var(--cs-space-20); border-bottom: 1px solid var(--cs-border); }.dialog-icon { display: grid; width: 42px; height: 42px; place-items: center; border-radius: 12px; background: var(--cs-agent-soft); color: var(--cs-agent); }.project-create-dialog h2 { margin: 0 0 var(--cs-space-4); font-size: var(--cs-text-lg); }.project-create-dialog header div > span { color: var(--cs-text-muted); font-size: var(--cs-text-sm); }.project-create-dialog header button { display: grid; width: 32px; height: 32px; place-items: center; border-radius: 8px; background: var(--cs-surface-subtle); cursor: pointer; }.project-create-content { display: grid; gap: var(--cs-space-12); padding: var(--cs-space-20) var(--cs-space-20) var(--cs-space-4); }.project-field { display: grid; gap: var(--cs-space-8); }.project-field > span { color: var(--cs-text-secondary); font-size: var(--cs-text-sm); font-weight: var(--cs-weight-semibold); }.project-field input { width: 100%; min-height: 40px; padding: 0 var(--cs-space-12); border: 1px solid var(--cs-border-strong); border-radius: var(--cs-radius-sm); background: var(--cs-surface); color: var(--cs-text); }.project-field input[aria-invalid='true'] { border-color: var(--cs-danger); }.project-field small { color: var(--cs-text-muted); font-size: var(--cs-text-xs); }.field-message { display: flex; align-items: center; gap: var(--cs-space-4); margin: calc(var(--cs-space-4) * -1) 0 0; color: var(--cs-text-muted); font-size: var(--cs-text-xs); }.field-message.available { color: var(--cs-success); }.field-message.error, .command-error { color: var(--cs-danger); }.project-boundary { display: flex; align-items: flex-start; gap: var(--cs-space-8); padding: var(--cs-space-12) var(--cs-space-12); border: 1px solid var(--cs-border); border-radius: var(--cs-radius-sm); background: var(--cs-surface-subtle); color: var(--cs-text-brand); }.project-boundary svg { flex: 0 0 auto; }.project-boundary strong, .project-boundary span { display: block; }.project-boundary strong { font-size: var(--cs-text-sm); }.project-boundary span { margin-top: var(--cs-space-2); color: var(--cs-text-muted); font-size: var(--cs-text-xs); }.command-error { margin: 0; font-size: var(--cs-text-sm); }.project-create-dialog > footer { display: flex; justify-content: flex-end; gap: var(--cs-space-8); padding: var(--cs-space-16) var(--cs-space-20) var(--cs-space-20); }.spinning { animation: spin var(--cs-motion-spin) linear infinite; }@keyframes spin { to { transform: rotate(360deg); } }@media (prefers-reduced-motion: reduce) { .spinning { animation: none; } }
@media (max-width: 767px) { .project-create-backdrop { align-items: end; padding: 0; }.project-create-dialog { width: 100%; max-height: calc(100dvh - 12px); border-radius: 18px 18px 0 0; }.project-create-dialog > header { padding: var(--cs-space-16) var(--cs-space-16); }.project-create-content { padding-inline: var(--cs-space-16); }.project-field input { font-size: var(--cs-text-md); }.project-create-dialog > footer { display: grid; padding-inline: var(--cs-space-16); }.project-create-dialog > footer > * { width: 100%; } }
</style>
