<script setup lang="ts">
import { CirclePause, CircleStop, Play, RotateCcw, ShieldCheck, TriangleAlert, WifiOff } from '@lucide/vue'
import { computed, ref, useId, watch } from 'vue'
import type {
  MemberTaskCommandOperation,
  TaskCommandVersionConflict,
  TaskExecution,
} from '../../domains/task/types'
import { taskExecutionStatusLabels } from '../../domains/task/labels'
import BaseButton from '../base/BaseButton.vue'
import BaseDialog from '../base/BaseDialog.vue'
import StatusBadge from '../base/StatusBadge.vue'

const props = defineProps<{
  attempt: TaskExecution | null
  canControl: boolean
  online: boolean
  pending: MemberTaskCommandOperation | null
  errorMessage: string | null
  retryable: boolean
  versionConflict: TaskCommandVersionConflict | null
  onCommand: (operation: MemberTaskCommandOperation, reason?: string, agentConfigurationRevision?: number) => Promise<void>
  onRetry: () => Promise<void>
  onClearFeedback: () => void
}>()

const commandFormId = useId()
const impactId = useId()
const operation = ref<MemberTaskCommandOperation | null>(null)
const reason = ref('')
const retryRevision = ref('')
const submitted = ref(false)

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
const retryRevisionValid = computed(() => operation.value !== 'RETRY'
  || retryRevision.value === ''
  || (Number.isInteger(Number(retryRevision.value)) && Number(retryRevision.value) >= 1))

watch(
  () => [props.attempt?.id, props.attempt?.status, availableOperations.value.join(','), props.pending] as const,
  () => {
    if (operation.value && !props.pending && !availableOperations.value.includes(operation.value)) closeDialog()
  },
)

function openDialog(next: MemberTaskCommandOperation): void {
  if (!availableOperations.value.includes(next) || props.pending) return
  props.onClearFeedback()
  operation.value = next
  reason.value = ''
  retryRevision.value = ''
  submitted.value = false
  // 初始焦点：需要原因的操作落在原因输入框、其余落在确认钮，均由 autofocus 交给 BaseDialog 的 trap 承接。
}

function closeDialog(): void {
  if (props.pending) return
  operation.value = null
  reason.value = ''
  retryRevision.value = ''
  submitted.value = false
}

async function submit(): Promise<void> {
  submitted.value = true
  if (!operation.value || !reasonValid.value || !retryRevisionValid.value || !props.online || props.pending) return
  try {
    if (operation.value === 'RETRY') {
      await props.onCommand(operation.value, undefined, retryRevision.value ? Number(retryRevision.value) : undefined)
    } else {
      await props.onCommand(operation.value, needsReason.value ? reason.value.trim() : undefined)
    }
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

const dialogOpen = computed(() => Boolean(operation.value && selectedDefinition.value))

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
    impact: '留空会沿用父 attempt 固定配置；填写 Revision 会重新执行模型预检并为新 attempt 固定该配置。失败 attempt 继续保留为历史证据。',
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
      <StatusBadge :tone="attempt.status === 'FAILED' || attempt.status === 'CANCELLED' ? 'danger' : attempt.status === 'PAUSED' || attempt.status === 'WAITING' ? 'warning' : 'info'" dot>{{ taskExecutionStatusLabels[attempt.status] }}</StatusBadge>
    </div>

    <div v-if="availableOperations.length" class="task-control-actions">
      <BaseButton
        v-if="availableOperations.includes('PAUSE')"
        size="small"
        variant="secondary"
        :disabled="Boolean(pending) || !online"
        :loading="pending === 'PAUSE'"
        aria-label="暂停当前 Task"
        @click="openDialog('PAUSE')"
      ><CirclePause :size="13" />暂停</BaseButton>
      <BaseButton
        v-if="availableOperations.includes('RESUME')"
        size="small"
        :disabled="Boolean(pending) || !online"
        :loading="pending === 'RESUME'"
        aria-label="恢复当前 Task"
        @click="openDialog('RESUME')"
      ><Play :size="13" />恢复</BaseButton>
      <BaseButton
        v-if="availableOperations.includes('RETRY')"
        size="small"
        :disabled="Boolean(pending) || !online"
        :loading="pending === 'RETRY'"
        aria-label="重试当前 Task"
        @click="openDialog('RETRY')"
      ><RotateCcw :size="13" />重试</BaseButton>
      <BaseButton
        v-if="availableOperations.includes('CANCEL')"
        size="small"
        variant="ghost"
        :disabled="Boolean(pending) || !online"
        :loading="pending === 'CANCEL'"
        aria-label="取消当前 Task"
        @click="openDialog('CANCEL')"
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

    <BaseDialog
      :open="dialogOpen"
      :title="selectedDefinition?.title ?? ''"
      :eyebrow="`Attempt ${attempt?.attempt} · v${attempt?.version}`"
      width="min(510px, 100%)"
      :described-by="impactId"
      :close-disabled="Boolean(pending)"
      close-label="关闭 Task 控制确认"
      @close="closeDialog"
    >
      <form :id="commandFormId" class="task-command-form" @submit.prevent="submit">
        <p :id="impactId" class="task-command-impact">{{ selectedDefinition?.impact }}</p>
        <label v-if="needsReason"><span>{{ operation === 'PAUSE' ? '暂停原因' : '取消原因' }}</span><textarea v-model="reason" rows="4" maxlength="500" autofocus :disabled="Boolean(pending)" :aria-invalid="submitted && !reasonValid" placeholder="说明团队可见的控制原因" /></label>
        <label v-if="operation === 'RETRY'"><span>切换 Configuration Revision <small>可选；留空沿用父 attempt</small></span><input v-model="retryRevision" type="number" min="1" step="1" inputmode="numeric" :disabled="Boolean(pending)" :aria-invalid="submitted && !retryRevisionValid" placeholder="例如 4"></label>
        <p v-if="submitted && !reasonValid" class="task-command-validation" role="alert">请输入 1–500 个不含控制字符的原因。</p>
        <p v-if="submitted && !retryRevisionValid" class="task-command-validation" role="alert">Configuration Revision 必须是大于等于 1 的整数。</p>
      </form>
      <template #footer>
        <BaseButton type="button" variant="ghost" :disabled="Boolean(pending)" @click="closeDialog">返回</BaseButton>
        <BaseButton type="submit" :form="commandFormId" :variant="operation === 'CANCEL' ? 'danger' : 'primary'" :disabled="!online" :loading="pending === operation" autofocus>确认{{ selectedDefinition?.label }}</BaseButton>
      </template>
    </BaseDialog>
  </section>
</template>

<style scoped>
.task-control-panel { padding: var(--cs-space-16); border: 1px solid var(--cs-border-accent); border-radius: var(--cs-radius-md); background: linear-gradient(145deg, var(--cs-surface-accent), var(--cs-surface) 72%); }.task-control-heading, .task-control-current { display: flex; align-items: center; justify-content: space-between; gap: var(--cs-space-12); }.task-control-heading p, .task-control-heading h3 { margin: 0; }.task-control-heading p { color: var(--cs-text-brand); font-size: var(--cs-text-xs); font-weight: var(--cs-weight-semibold); letter-spacing: .08em; text-transform: uppercase; }.task-control-heading h3 { margin-top: var(--cs-space-2); font-size: var(--cs-text-base); }.task-control-heading > svg { color: var(--cs-text-brand); }.task-control-current { padding: var(--cs-space-8) 0 var(--cs-space-8); margin-top: var(--cs-space-8); border-top: 1px solid var(--cs-border-accent); color: var(--cs-text-secondary); font-size: var(--cs-text-xs); font-weight: var(--cs-weight-semibold); }.task-control-actions { display: flex; flex-wrap: wrap; gap: var(--cs-space-8); }.task-control-actions :deep(.base-button:last-child) { margin-left: auto; }.task-control-note { margin: var(--cs-space-8) 0 0; color: var(--cs-text-muted); font-size: var(--cs-text-xs); line-height: var(--cs-leading-normal); }.task-control-offline { display: flex; align-items: center; gap: var(--cs-space-8); margin: var(--cs-space-8) 0 0; color: var(--cs-warning); font-size: var(--cs-text-xs); }.task-control-conflict, .task-control-error { margin-top: var(--cs-space-8); border-radius: 8px; font-size: var(--cs-text-xs); }.task-control-conflict { display: flex; align-items: flex-start; gap: var(--cs-space-8); padding: var(--cs-space-8); background: var(--cs-warning-soft); color: var(--cs-warning); }.task-control-conflict svg { flex: 0 0 auto; }.task-control-conflict strong, .task-control-conflict span { display: block; }.task-control-conflict span { margin-top: var(--cs-space-2); color: var(--cs-text-secondary); line-height: var(--cs-leading-normal); }.task-control-error { display: flex; align-items: center; justify-content: space-between; gap: var(--cs-space-8); padding: var(--cs-space-8) var(--cs-space-8); background: var(--cs-danger-soft); color: var(--cs-danger); }.task-command-form { display: grid; gap: var(--cs-space-8); }.task-command-impact { margin: 0; color: var(--cs-text-secondary); font-size: var(--cs-text-sm); line-height: var(--cs-leading-normal); }.task-command-form label { display: grid; gap: var(--cs-space-8); color: var(--cs-text-secondary); font-size: var(--cs-text-xs); font-weight: var(--cs-weight-semibold); }.task-command-form label small { color: var(--cs-text-muted); font-weight: var(--cs-weight-medium); }.task-command-form textarea, .task-command-form input { width: 100%; padding: var(--cs-space-8) var(--cs-space-12); border: 1px solid var(--cs-border-strong); border-radius: 9px; background: var(--cs-surface-subtle); color: var(--cs-text); font: var(--cs-text-base) var(--cs-font-sans); resize: vertical; }.task-command-form [aria-invalid="true"] { border-color: var(--cs-danger); }.task-command-validation { margin: 0; color: var(--cs-danger); font-size: var(--cs-text-xs); }
@media (max-width: 767px) { .task-control-actions { display: grid; grid-template-columns: 1fr 1fr; }.task-control-actions :deep(.base-button:last-child) { margin-left: 0; }.task-control-error { align-items: stretch; flex-direction: column; } }
</style>
