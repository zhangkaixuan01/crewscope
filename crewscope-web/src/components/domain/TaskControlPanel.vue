<script setup lang="ts">
import { CirclePause, CircleStop, Play, RotateCcw, ShieldCheck, TriangleAlert, WifiOff, X } from '@lucide/vue'
import { computed, nextTick, ref, useTemplateRef, watch } from 'vue'
import type {
  MemberTaskCommandOperation,
  TaskCommandVersionConflict,
  TaskExecution,
} from '../../domains/task/types'
import BaseButton from '../base/BaseButton.vue'
import StatusBadge from '../base/StatusBadge.vue'

const props = defineProps<{
  attempt: TaskExecution | null
  canControl: boolean
  online: boolean
  pending: MemberTaskCommandOperation | null
  errorMessage: string | null
  retryable: boolean
  versionConflict: TaskCommandVersionConflict | null
  onCommand: (operation: MemberTaskCommandOperation, reason?: string) => Promise<void>
  onRetry: () => Promise<void>
  onClearFeedback: () => void
}>()

const dialog = useTemplateRef<HTMLElement>('dialog')
const reasonInput = useTemplateRef<HTMLTextAreaElement>('reasonInput')
const operation = ref<MemberTaskCommandOperation | null>(null)
const reason = ref('')
const submitted = ref(false)
let trigger: HTMLElement | null = null

const retryableFailureClasses = new Set([
  'TRANSIENT', 'RATE_LIMITED', 'TIMEOUT', 'RUNTIME_UNAVAILABLE', 'MODEL_UNAVAILABLE',
  'TOOL_UNAVAILABLE', 'RESOURCE_EXHAUSTED', 'RECOVERY_INTERRUPTED',
])
const cancellableStatuses = new Set([
  'CREATED', 'READY', 'CLAIMED', 'PREPARING', 'RUNNING', 'WAITING',
  'PAUSE_REQUESTED', 'PAUSED', 'RECOVERING', 'MANUAL_TAKEOVER',
])

const availableOperations = computed<MemberTaskCommandOperation[]>(() => {
  const attempt = props.attempt
  if (!props.canControl || !attempt) return []
  const available: MemberTaskCommandOperation[] = []
  if (attempt.status === 'RUNNING') available.push('PAUSE')
  if (attempt.status === 'PAUSED') available.push('RESUME')
  if (cancellableStatuses.has(attempt.status)) available.push('CANCEL')
  if (attempt.status === 'FAILED'
    && attempt.attempt < attempt.maxAttempts
    && attempt.terminal?.failureClass
    && retryableFailureClasses.has(attempt.terminal.failureClass)) available.push('RETRY')
  return available
})
const selectedDefinition = computed(() => operation.value ? definitions[operation.value] : null)
const needsReason = computed(() => operation.value === 'PAUSE' || operation.value === 'CANCEL')
const reasonValid = computed(() => !needsReason.value || (
  reason.value.trim().length > 0
  && reason.value.trim().length <= 500
  && !/[\u0000-\u001f\u007f-\u009f]/.test(reason.value.trim())
))

watch(
  () => [props.attempt?.id, props.attempt?.status, availableOperations.value.join(','), props.pending] as const,
  () => {
    if (operation.value && !props.pending && !availableOperations.value.includes(operation.value)) closeDialog()
  },
)

function openDialog(next: MemberTaskCommandOperation, event: MouseEvent): void {
  if (!availableOperations.value.includes(next) || props.pending) return
  props.onClearFeedback()
  trigger = event.currentTarget instanceof HTMLElement ? event.currentTarget : null
  operation.value = next
  reason.value = ''
  submitted.value = false
  void nextTick(() => (needsReason.value ? reasonInput.value : dialog.value)?.focus())
}

function closeDialog(): void {
  if (props.pending) return
  operation.value = null
  reason.value = ''
  submitted.value = false
  const returnTarget = trigger
  trigger = null
  void nextTick(() => returnTarget?.focus())
}

async function submit(): Promise<void> {
  submitted.value = true
  if (!operation.value || !reasonValid.value || !props.online || props.pending) return
  try {
    await props.onCommand(operation.value, needsReason.value ? reason.value.trim() : undefined)
    closeDialog()
  } catch {
    // Store retains the exact command for safe retry or publishes refreshed conflict facts.
  }
}

async function retryOriginal(): Promise<void> {
  if (!props.online || props.pending) return
  try {
    await props.onRetry()
  } catch {
    // The same idempotency key remains available while the error is retryable.
  }
}

function handleDialogKeydown(event: KeyboardEvent): void {
  event.stopPropagation()
  if (event.key === 'Escape') {
    event.preventDefault()
    closeDialog()
    return
  }
  if (event.key !== 'Tab' || !dialog.value) return
  const controls = [...dialog.value.querySelectorAll<HTMLElement>('button:not(:disabled), textarea:not(:disabled)')]
    .filter(element => element.offsetParent !== null)
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

const definitions: Record<MemberTaskCommandOperation, {
  label: string
  title: string
  impact: string
}> = {
  PAUSE: {
    label: '暂停', title: '暂停当前执行',
    impact: '命令会在 AgentScope 的下一个安全点生效。已提交的外部结果、审计记录和当前 attempt 会保留。',
  },
  RESUME: {
    label: '恢复', title: '恢复当前执行',
    impact: '执行会从同一 AgentRun、待处理 Pause 中断和已确认快照继续，不会创建新的 attempt。',
  },
  CANCEL: {
    label: '取消', title: '取消当前 Task',
    impact: '运行中的执行会先请求安全停止；已产生的外部副作用和审计证据不会回滚，Task 最终收敛为已取消。',
  },
  RETRY: {
    label: '重试', title: '创建新的执行 Attempt',
    impact: '失败 attempt 会作为历史证据保留；服务端重新校验责任、Agent Profile 和 Provider Binding 后创建新的 READY attempt。',
  },
}
</script>

<template>
  <section class="task-control-panel" aria-labelledby="task-control-heading">
    <div class="task-control-heading">
      <div><p>Durable control</p><h3 id="task-control-heading">执行控制</h3></div>
      <ShieldCheck :size="17" aria-hidden="true" />
    </div>

    <div v-if="attempt" class="task-control-current">
      <span>当前 Attempt {{ attempt.attempt }}</span>
      <StatusBadge :tone="attempt.status === 'FAILED' || attempt.status === 'CANCELLED' ? 'danger' : attempt.status === 'PAUSED' || attempt.status === 'WAITING' ? 'warning' : 'info'" dot>{{ attempt.status }}</StatusBadge>
    </div>

    <div v-if="availableOperations.length" class="task-control-actions">
      <BaseButton
        v-if="availableOperations.includes('PAUSE')"
        size="small"
        variant="secondary"
        :disabled="Boolean(pending) || !online"
        :loading="pending === 'PAUSE'"
        aria-label="暂停当前 Task"
        @click="openDialog('PAUSE', $event)"
      ><CirclePause :size="13" />暂停</BaseButton>
      <BaseButton
        v-if="availableOperations.includes('RESUME')"
        size="small"
        :disabled="Boolean(pending) || !online"
        :loading="pending === 'RESUME'"
        aria-label="恢复当前 Task"
        @click="openDialog('RESUME', $event)"
      ><Play :size="13" />恢复</BaseButton>
      <BaseButton
        v-if="availableOperations.includes('RETRY')"
        size="small"
        :disabled="Boolean(pending) || !online"
        :loading="pending === 'RETRY'"
        aria-label="重试当前 Task"
        @click="openDialog('RETRY', $event)"
      ><RotateCcw :size="13" />重试</BaseButton>
      <BaseButton
        v-if="availableOperations.includes('CANCEL')"
        size="small"
        variant="ghost"
        :disabled="Boolean(pending) || !online"
        :loading="pending === 'CANCEL'"
        aria-label="取消当前 Task"
        @click="openDialog('CANCEL', $event)"
      ><CircleStop :size="13" />取消</BaseButton>
    </div>
    <p v-else class="task-control-note">
      {{ !canControl ? '当前成员没有这个 Task 的 Owner 或 Executor 控制责任。' : attempt ? '当前 attempt 没有可用控制操作。' : '正在恢复当前 attempt。' }}
    </p>

    <p v-if="!online" class="task-control-offline" role="status"><WifiOff :size="13" />当前离线，控制命令将在恢复网络后才可提交。</p>
    <div v-if="versionConflict" class="task-control-conflict" role="alert">
      <TriangleAlert :size="15" /><div><strong>执行事实已变化</strong><span>{{ definitions[versionConflict.operation].label }}基于 v{{ versionConflict.attemptedVersion }}，服务端当前版本为 {{ versionConflict.currentVersion === null ? '未知' : `v${versionConflict.currentVersion}` }}。页面已刷新。</span></div>
    </div>
    <div v-if="errorMessage" class="task-control-error" role="alert">
      <span>{{ errorMessage }}</span>
      <BaseButton v-if="retryable" size="small" variant="secondary" :disabled="!online" :loading="Boolean(pending)" @click="retryOriginal">使用原命令重试</BaseButton>
    </div>

    <div v-if="operation && selectedDefinition" class="task-command-backdrop" @mousedown.self="closeDialog">
      <form
        ref="dialog"
        class="task-command-dialog"
        role="dialog"
        aria-modal="true"
        :aria-label="selectedDefinition.title"
        tabindex="-1"
        @submit.prevent="submit"
        @keydown="handleDialogKeydown"
      >
        <header><div><p>Attempt {{ attempt?.attempt }} · v{{ attempt?.version }}</p><h4>{{ selectedDefinition.title }}</h4></div><button type="button" aria-label="关闭 Task 控制确认" :disabled="Boolean(pending)" @click="closeDialog"><X :size="17" /></button></header>
        <p class="task-command-impact">{{ selectedDefinition.impact }}</p>
        <label v-if="needsReason"><span>{{ operation === 'PAUSE' ? '暂停原因' : '取消原因' }}</span><textarea ref="reasonInput" v-model="reason" rows="4" maxlength="500" :disabled="Boolean(pending)" :aria-invalid="submitted && !reasonValid" placeholder="说明团队可见的控制原因" /></label>
        <p v-if="submitted && !reasonValid" class="task-command-validation" role="alert">请输入 1–500 个不含控制字符的原因。</p>
        <footer><BaseButton type="button" variant="ghost" :disabled="Boolean(pending)" @click="closeDialog">返回</BaseButton><BaseButton type="submit" :variant="operation === 'CANCEL' ? 'danger' : 'primary'" :disabled="!online" :loading="pending === operation">确认{{ selectedDefinition.label }}</BaseButton></footer>
      </form>
    </div>
  </section>
</template>

<style scoped>
.task-control-panel { padding: 14px; border: 1px solid var(--cs-brand-200); border-radius: var(--cs-radius-md); background: linear-gradient(145deg, var(--cs-brand-50), var(--cs-surface) 72%); }.task-control-heading, .task-control-current { display: flex; align-items: center; justify-content: space-between; gap: 10px; }.task-control-heading p, .task-control-heading h3 { margin: 0; }.task-control-heading p { color: var(--cs-brand-600); font-size: 8px; font-weight: 750; letter-spacing: .08em; text-transform: uppercase; }.task-control-heading h3 { margin-top: 2px; font-size: 12px; }.task-control-heading > svg { color: var(--cs-brand-600); }.task-control-current { padding: 9px 0 8px; margin-top: 9px; border-top: 1px solid var(--cs-brand-100); color: var(--cs-text-secondary); font-size: 9px; font-weight: 700; }.task-control-actions { display: flex; flex-wrap: wrap; gap: 6px; }.task-control-actions :deep(.base-button:last-child) { margin-left: auto; }.task-control-note { margin: 7px 0 0; color: var(--cs-text-muted); font-size: 9px; line-height: 1.5; }.task-control-offline { display: flex; align-items: center; gap: 6px; margin: 9px 0 0; color: var(--cs-warning); font-size: 9px; }.task-control-conflict, .task-control-error { margin-top: 9px; border-radius: 8px; font-size: 9px; }.task-control-conflict { display: flex; align-items: flex-start; gap: 7px; padding: 9px; background: var(--cs-warning-soft); color: var(--cs-warning); }.task-control-conflict svg { flex: 0 0 auto; }.task-control-conflict strong, .task-control-conflict span { display: block; }.task-control-conflict span { margin-top: 2px; color: var(--cs-text-secondary); line-height: 1.45; }.task-control-error { display: flex; align-items: center; justify-content: space-between; gap: 8px; padding: 8px 9px; background: #fff6f5; color: var(--cs-danger); }.task-command-backdrop { position: fixed; inset: 0; z-index: 90; display: grid; place-items: center; padding: 18px; background: rgb(21 35 29 / 32%); backdrop-filter: blur(3px); }.task-command-dialog { width: min(510px, 100%); overflow: hidden; border: 1px solid var(--cs-border); border-radius: 14px; background: var(--cs-surface); box-shadow: var(--cs-shadow-float); }.task-command-dialog > header { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; padding: 17px 18px 13px; border-bottom: 1px solid var(--cs-border); }.task-command-dialog header p, .task-command-dialog header h4 { margin: 0; }.task-command-dialog header p { color: var(--cs-text-muted); font: 8px var(--cs-font-mono); }.task-command-dialog header h4 { margin-top: 3px; font-size: 15px; }.task-command-dialog header button { display: grid; width: 31px; height: 31px; place-items: center; border-radius: 8px; background: var(--cs-surface-subtle); cursor: pointer; }.task-command-impact { margin: 0; padding: 14px 18px 10px; color: var(--cs-text-secondary); font-size: 10px; line-height: 1.6; }.task-command-dialog label { display: grid; gap: 6px; padding: 3px 18px 8px; color: var(--cs-text-secondary); font-size: 9px; font-weight: 750; }.task-command-dialog textarea { width: 100%; padding: 9px 10px; border: 1px solid var(--cs-border-strong); border-radius: 9px; background: var(--cs-surface-subtle); color: var(--cs-text); font: 10px var(--cs-font-sans); resize: vertical; }.task-command-dialog textarea[aria-invalid="true"] { border-color: var(--cs-danger); }.task-command-validation { margin: 0 18px; color: var(--cs-danger); font-size: 9px; }.task-command-dialog footer { display: flex; justify-content: flex-end; gap: 7px; padding: 14px 18px 17px; }
@media (max-width: 767px) { .task-control-actions { display: grid; grid-template-columns: 1fr 1fr; }.task-control-actions :deep(.base-button:last-child) { margin-left: 0; }.task-command-backdrop { align-items: end; padding: 0; }.task-command-dialog { width: 100%; border-radius: 16px 16px 0 0; }.task-command-dialog footer { display: grid; grid-template-columns: 1fr 1fr; }.task-control-error { align-items: stretch; flex-direction: column; } }
</style>
