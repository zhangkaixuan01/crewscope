<script setup lang="ts">
import {
  Activity, CheckCircle2, CircleAlert, Gauge, History, Inbox,
  RefreshCw, RotateCcw, Send, ShieldCheck, Siren, X,
} from '@lucide/vue'
import { computed, nextTick, ref, watch } from 'vue'
import { RouterLink } from 'vue-router'
import BaseButton from '../base/BaseButton.vue'
import StatusBadge from '../base/StatusBadge.vue'
import StatePanel from '../feedback/StatePanel.vue'
import type { TeamOpsCommandState, TeamOpsPhase } from '../../domains/teamops/store'
import type { TeamOpsErrorState } from '../../domains/teamops/errors'
import type {
  AdministratorDiagnostics, OperationsHealthLevel, OperationsHealthSummary,
  ProjectionCommand, ProjectionDiagnostic, RecoveryCandidate,
} from '../../domains/teamops/types'

const props = defineProps<{
  phase: TeamOpsPhase
  error: TeamOpsErrorState | null
  health: OperationsHealthSummary | null
  diagnosticsPhase: TeamOpsPhase
  diagnosticsError: TeamOpsErrorState | null
  diagnostics: AdministratorDiagnostics | null
  command: TeamOpsCommandState
  canManage: boolean
  online: boolean
}>()

const emit = defineEmits<{
  refresh: []
  recover: [target: RecoveryCandidate, confirmation: string, idempotencyKey: string]
  projectionCommand: [command: ProjectionCommand, idempotencyKey: string]
  clearCommand: []
}>()

type ProjectionOperation = 'start' | 'validate' | 'switch' | 'cancel' | 'fail'
type PendingAction =
  | { kind: 'recovery', candidate: RecoveryCandidate, confirmation: string, title: string }
  | { kind: 'projection', operation: ProjectionOperation, projection: ProjectionDiagnostic, confirmation: string, title: string }

const pending = ref<PendingAction | null>(null)
const confirmationInput = ref('')
const failureCode = ref('OPERATOR_MARKED_FAILED')
const idempotencyKey = ref('')
const dialog = ref<HTMLElement | null>(null)
const heading = ref<HTMLElement | null>(null)
let opener: HTMLElement | null = null

const summary = computed(() => props.health ?? props.diagnostics?.summary ?? null)
const isInitialFailure = computed(() => props.phase === 'error' && !summary.value)
const commandPending = computed(() => props.command.phase === 'pending')
const confirmationMatches = computed(() => pending.value !== null && confirmationInput.value === pending.value.confirmation)
const failureCodeValid = computed(() => /^[A-Z][A-Z0-9]*(?:_[A-Z0-9]+)*$/.test(failureCode.value) && failureCode.value.length <= 80)
const canSubmit = computed(() => props.online
  && (props.command.phase === 'idle' || (props.command.phase === 'error' && props.command.error?.retryable))
  && confirmationMatches.value
  && (pending.value?.kind !== 'projection' || pending.value.operation !== 'fail' || failureCodeValid.value))

const componentLabels: Record<string, string> = {
  PROJECTION: 'Projection', OUTBOX: 'Outbox', DEAD_LETTER: 'Dead Letter', CURSOR: 'Cursor', NOTIFICATION: 'Notification',
}
const actionLabels: Record<string, string> = {
  REPLAY_OUTBOX_DEAD_LETTER: '回放 Outbox', REPLAY_PROJECTION_DEAD_LETTER: '回放 Projection', RETRY_NOTIFICATION_DELIVERY: '重试通知',
}

watch(() => props.command.phase, phase => {
  if (phase === 'success') closeDialog(true)
})

function openRecovery(candidate: RecoveryCandidate, event?: Event): void {
  open({ kind: 'recovery', candidate, confirmation: candidate.confirmation, title: actionLabels[candidate.action] ?? candidate.action }, event)
}

function openProjection(projection: ProjectionDiagnostic, operation: ProjectionOperation, event?: Event): void {
  const phrases = {
    start: projection.startConfirmation,
    validate: projection.validateConfirmation,
    switch: projection.switchConfirmation,
    cancel: projection.cancelConfirmation,
    fail: projection.failConfirmation,
  }
  const titles = { start: '启动影子重建', validate: '验证影子代际', switch: '切换活跃代际', cancel: '取消影子重建', fail: '标记重建失败' }
  const confirmation = phrases[operation]
  if (!confirmation) return
  open({ kind: 'projection', operation, projection, confirmation, title: titles[operation] }, event)
}

function open(action: PendingAction, event?: Event): void {
  emit('clearCommand')
  opener = event?.currentTarget instanceof HTMLElement ? event.currentTarget : document.activeElement instanceof HTMLElement ? document.activeElement : null
  pending.value = action
  confirmationInput.value = ''
  failureCode.value = 'OPERATOR_MARKED_FAILED'
  // Transport retries within this open dialog reuse one key; closing starts a new command intent.
  idempotencyKey.value = crypto.randomUUID()
  void nextTick(() => heading.value?.focus())
}

function closeDialog(force = false): void {
  if (!force && commandPending.value) return
  pending.value = null
  confirmationInput.value = ''
  failureCode.value = 'OPERATOR_MARKED_FAILED'
  idempotencyKey.value = ''
  const target = opener
  opener = null
  void nextTick(() => target?.focus())
}

function submit(): void {
  const action = pending.value
  if (!action || !canSubmit.value) return
  if (action.kind === 'recovery') {
    emit('recover', action.candidate, confirmationInput.value, idempotencyKey.value)
    return
  }
  const command = projectionCommand(action)
  if (command) emit('projectionCommand', command, idempotencyKey.value)
}

function projectionCommand(action: Extract<PendingAction, { kind: 'projection' }>): ProjectionCommand | null {
  // Every coordinate comes from one diagnostics snapshot; the browser never guesses a version.
  const projection = action.projection
  const confirmation = confirmationInput.value
  if (action.operation === 'start') return {
    operation: 'start', projectionName: projection.projectionName,
    body: { expectedDefinitionVersion: projection.definitionVersion, expectedPointerVersion: projection.pointerVersion, confirmation },
  }
  if (projection.shadowGeneration == null || projection.shadowGenerationVersion == null
    || !projection.rebuildJobId || projection.rebuildJobVersion == null) return null
  if (action.operation === 'validate') return {
    operation: 'validate', projectionName: projection.projectionName, generation: projection.shadowGeneration,
    body: { expectedDefinitionVersion: projection.definitionVersion, rebuildJobId: projection.rebuildJobId,
      expectedGenerationVersion: projection.shadowGenerationVersion, expectedJobVersion: projection.rebuildJobVersion, confirmation },
  }
  if (action.operation === 'switch') return {
    operation: 'switch', projectionName: projection.projectionName, generation: projection.shadowGeneration,
    body: { expectedDefinitionVersion: projection.definitionVersion, previousActiveGeneration: projection.activeGeneration,
      rebuildJobId: projection.rebuildJobId, expectedPointerVersion: projection.pointerVersion,
      expectedPreviousGenerationVersion: projection.activeGenerationVersion,
      expectedTargetGenerationVersion: projection.shadowGenerationVersion, expectedJobVersion: projection.rebuildJobVersion, confirmation },
  }
  if (action.operation === 'fail') return {
    operation: 'fail', projectionName: projection.projectionName,
    generation: projection.shadowGeneration, rebuildJobId: projection.rebuildJobId,
    body: { expectedGenerationVersion: projection.shadowGenerationVersion, expectedJobVersion: projection.rebuildJobVersion, failureCode: failureCode.value, confirmation },
  }
  return {
    operation: 'cancel', projectionName: projection.projectionName,
    generation: projection.shadowGeneration, rebuildJobId: projection.rebuildJobId,
    body: { expectedGenerationVersion: projection.shadowGenerationVersion, expectedJobVersion: projection.rebuildJobVersion, confirmation },
  }
}

function handleDialogKeydown(event: KeyboardEvent): void {
  if (event.key === 'Escape') {
    event.preventDefault()
    closeDialog()
    return
  }
  if (event.key !== 'Tab' || !dialog.value) return
  const focusable = [...dialog.value.querySelectorAll<HTMLElement>('button:not([disabled]), input:not([disabled]), [tabindex]:not([tabindex="-1"])')]
  const first = focusable[0]
  const last = focusable.at(-1)
  if (!first || !last) return
  if (event.shiftKey && (document.activeElement === first || document.activeElement === heading.value)) {
    event.preventDefault(); last.focus()
  } else if (!event.shiftKey && document.activeElement === last) {
    event.preventDefault(); first.focus()
  }
}

function tone(level: OperationsHealthLevel): 'success' | 'warning' | 'danger' {
  if (level === 'HEALTHY') return 'success'
  if (level === 'DEGRADED') return 'warning'
  return 'danger'
}
function displayTime(value: string): string { return new Intl.DateTimeFormat('zh-CN', { dateStyle: 'short', timeStyle: 'medium' }).format(new Date(value)) }
function short(value: string): string { return value.length > 20 ? `${value.slice(0, 12)}…${value.slice(-6)}` : value }
function receiptId(): string | null { return props.command.receipt && 'commandId' in props.command.receipt ? props.command.receipt.commandId : null }
</script>

<template>
  <div class="operations-workspace">
    <StatePanel v-if="phase === 'loading' && !summary" state="loading" title="正在读取运行健康" description="聚合 Projection、Outbox、Dead Letter、Cursor 与 Notification 的低基数摘要。" />
    <StatePanel v-else-if="isInitialFailure" state="error" title="运行健康暂时不可用" :description="error?.message" @retry="emit('refresh')" />

    <template v-if="summary">
      <section class="health-hero" aria-labelledby="health-heading">
        <div>
          <p class="eyebrow">Operations health</p>
          <h2 id="health-heading"><Gauge :size="20" aria-hidden="true" />团队执行链路</h2>
          <p>只展示固定组件与有界计数，不暴露事件载荷、成员身份或错误原文。</p>
        </div>
        <div class="health-hero__status">
          <StatusBadge :tone="tone(summary.health)">{{ summary.health }}</StatusBadge>
          <span>观测于 {{ displayTime(summary.observedAt) }}</span>
        </div>
      </section>

      <div v-if="phase === 'error'" class="inline-warning" role="status"><CircleAlert :size="15" />刷新失败，保留上次健康事实。{{ error?.message }}</div>
      <div v-if="!online" class="inline-warning" role="status"><CircleAlert :size="15" />离线期间保留当前摘要，并暂停自动刷新与管理命令。</div>

      <section class="health-grid" aria-label="运行组件健康摘要">
        <article v-for="component in summary.components" :key="component.component" class="health-card">
          <header><strong>{{ componentLabels[component.component] }}</strong><StatusBadge :tone="tone(component.health)">{{ component.health }}</StatusBadge></header>
          <dl>
            <div><dt>Backlog</dt><dd>{{ component.backlog }}</dd></div>
            <div><dt>In flight</dt><dd>{{ component.inFlight }}</dd></div>
            <div><dt>Failures</dt><dd>{{ component.failures }}</dd></div>
            <div><dt>Affected</dt><dd>{{ component.affected }}</dd></div>
          </dl>
          <footer><span>最旧等待 {{ component.oldestOutstandingAgeSeconds }}s</span><span v-if="component.stale" class="stale">STALE</span></footer>
        </article>
      </section>
    </template>

    <section class="evidence-panel" aria-labelledby="evidence-heading">
      <header><div><p class="eyebrow">MVP evidence</p><h2 id="evidence-heading">一键演示证据入口</h2></div><span>入口集合 · 不代替验收结论</span></header>
      <nav class="evidence-links" aria-label="MVP 演示证据">
        <RouterLink :to="{ name: 'activity', query: $route.query }"><Activity :size="17" /><span>Team Activity<small>执行事实与 Correlation</small></span></RouterLink>
        <RouterLink :to="{ name: 'inbox', query: $route.query }"><Inbox :size="17" /><span>我的 Inbox<small>待办、确认与异常</small></span></RouterLink>
        <RouterLink :to="{ name: 'team-observer', query: $route.query }"><Gauge :size="17" /><span>Team Observer<small>只读团队摘要</small></span></RouterLink>
        <RouterLink v-if="canManage" :to="{ name: 'audit', query: $route.query }"><ShieldCheck :size="17" /><span>审计中心<small>操作者与结果证据</small></span></RouterLink>
        <RouterLink v-if="canManage" :to="{ name: 'lark-settings', query: $route.query }"><Send :size="17" /><span>飞书与通知<small>Provider 与投递状态</small></span></RouterLink>
      </nav>
    </section>

    <section v-if="canManage" class="admin-panel" aria-labelledby="admin-heading">
      <header><div><p class="eyebrow">Administrator</p><h2 id="admin-heading">Projection 与恢复管理</h2></div><StatusBadge tone="warning">强确认</StatusBadge></header>
      <StatePanel v-if="diagnosticsPhase === 'loading' && !diagnostics" state="loading" title="正在读取管理员诊断" />
      <StatePanel v-else-if="diagnosticsPhase === 'error' && !diagnostics" state="error" title="管理员诊断暂时不可用" :description="diagnosticsError?.message" @retry="emit('refresh')" />
      <div v-if="diagnosticsPhase === 'error' && diagnostics" class="inline-warning" role="status"><CircleAlert :size="15" />诊断刷新失败，管理命令已禁用，请先重新加载。</div>

      <div v-if="diagnostics" class="projection-list">
        <article v-for="projection in diagnostics.projections" :key="projection.projectionName" class="projection-card">
          <header><div><strong>{{ projection.projectionName }}</strong><small>Definition v{{ projection.definitionVersion }} · Pointer v{{ projection.pointerVersion }}</small></div><StatusBadge :tone="projection.deadLetterCount ? 'danger' : projection.lagSeconds ? 'warning' : 'success'">Lag {{ projection.lagSeconds }}s</StatusBadge></header>
          <div class="generation-flow">
            <div><span>ACTIVE</span><strong>G{{ projection.activeGeneration }}</strong><small>v{{ projection.activeGenerationVersion }}</small></div>
            <span aria-hidden="true">→</span>
            <div :class="{ muted: projection.shadowGeneration == null }"><span>SHADOW</span><strong>{{ projection.shadowGeneration == null ? '—' : `G${projection.shadowGeneration}` }}</strong><small>{{ projection.shadowStatus ?? '未创建' }}<template v-if="projection.shadowGenerationVersion != null"> · v{{ projection.shadowGenerationVersion }}</template></small></div>
          </div>
          <dl class="projection-metrics"><div><dt>Gap</dt><dd>{{ projection.gapCount }}</dd></div><div><dt>Dead Letter</dt><dd>{{ projection.deadLetterCount }}</dd></div><div><dt>Failure</dt><dd>{{ projection.latestFailureCode ?? '—' }}</dd></div><div><dt>Job</dt><dd>{{ projection.rebuildJobId ? short(projection.rebuildJobId) : '—' }}</dd></div></dl>
          <div class="projection-actions">
            <BaseButton size="small" variant="secondary" :disabled="!online || commandPending || diagnosticsPhase === 'error' || projection.shadowGeneration != null" @click="openProjection(projection, 'start', $event)"><RotateCcw :size="13" />影子重建</BaseButton>
            <BaseButton v-if="projection.validateConfirmation" size="small" variant="secondary" :disabled="!online || commandPending || diagnosticsPhase === 'error'" @click="openProjection(projection, 'validate', $event)">验证</BaseButton>
            <BaseButton v-if="projection.switchConfirmation" size="small" :disabled="!online || commandPending || diagnosticsPhase === 'error'" @click="openProjection(projection, 'switch', $event)">切换</BaseButton>
            <BaseButton v-if="projection.cancelConfirmation" size="small" variant="ghost" :disabled="!online || commandPending || diagnosticsPhase === 'error'" @click="openProjection(projection, 'cancel', $event)">取消</BaseButton>
            <BaseButton v-if="projection.failConfirmation" size="small" variant="danger" :disabled="!online || commandPending || diagnosticsPhase === 'error'" @click="openProjection(projection, 'fail', $event)">标记失败</BaseButton>
          </div>
        </article>
        <StatePanel v-if="diagnostics.projections.length === 0" state="empty" title="暂无 Projection 定义" />
      </div>

      <div v-if="diagnostics" class="recovery-section">
        <header><div><Siren :size="17" /><strong>Dead Letter 与恢复候选</strong></div><span>{{ diagnostics.recoveryCandidates.length }} 个</span></header>
        <ul v-if="diagnostics.recoveryCandidates.length">
          <li v-for="candidate in diagnostics.recoveryCandidates" :key="candidate.referenceHash">
            <div><strong>{{ actionLabels[candidate.action] }}</strong><small>{{ candidate.type }} · {{ short(candidate.referenceHash) }}</small></div>
            <BaseButton size="small" variant="secondary" :disabled="!online || commandPending || diagnosticsPhase === 'error'" @click="openRecovery(candidate, $event)"><History :size="13" />执行恢复</BaseButton>
          </li>
        </ul>
        <p v-else class="empty-line"><CheckCircle2 :size="16" />当前没有可恢复的失败项。</p>
      </div>
    </section>

    <section v-if="command.phase !== 'idle' && !pending" class="command-result" :class="command.phase" aria-live="polite">
      <div><CheckCircle2 v-if="command.phase === 'success'" :size="17" /><CircleAlert v-else :size="17" /><strong>{{ command.phase === 'success' ? '命令已接受' : command.phase === 'conflict' ? '版本已变化' : '命令执行失败' }}</strong></div>
      <p v-if="command.phase === 'success'">Command {{ receiptId() ? short(receiptId()!) : '—' }}。已回读权威健康与诊断；不会自动重放。</p>
      <p v-else>{{ command.error?.message }}<template v-if="command.error?.currentVersion != null"> · 服务端版本 v{{ command.error.currentVersion }}</template></p>
      <BaseButton size="small" variant="ghost" @click="emit('clearCommand')">关闭</BaseButton>
    </section>

    <div v-if="pending" class="dialog-backdrop" @mousedown.self="closeDialog()">
      <section ref="dialog" class="confirmation-dialog" role="dialog" aria-modal="true" aria-labelledby="operations-confirm-title" @keydown="handleDialogKeydown">
        <header><div><p class="eyebrow">Strong confirmation</p><h2 id="operations-confirm-title" ref="heading" tabindex="-1">{{ pending.title }}</h2></div><button type="button" aria-label="关闭确认窗口" :disabled="commandPending" @click="closeDialog()"><X :size="18" /></button></header>
        <p>该操作会写入审计并使用新的 Idempotency-Key。输入服务端生成的完整确认短语后才能执行。</p>
        <code>{{ pending.confirmation }}</code>
        <label>确认短语<input v-model="confirmationInput" autocomplete="off" spellcheck="false" :disabled="commandPending"></label>
        <label v-if="pending.kind === 'projection' && pending.operation === 'fail'">失败码<input v-model.trim="failureCode" autocomplete="off" spellcheck="false" :aria-invalid="!failureCodeValid" :disabled="commandPending"><small>大写下划线诊断码，最多 80 字符。</small></label>
        <div v-if="command.phase === 'conflict' || command.phase === 'error'" class="dialog-error" role="alert">{{ command.error?.message }}。{{ command.phase === 'error' && command.error?.retryable ? '可以使用原 Idempotency-Key 重试同一输入。' : '请关闭并回读最新诊断，不会自动重放。' }}</div>
        <footer><BaseButton variant="ghost" :disabled="commandPending" @click="closeDialog()">取消</BaseButton><BaseButton :variant="pending.kind === 'projection' && pending.operation === 'fail' ? 'danger' : 'primary'" :disabled="!canSubmit" @click="submit"><RefreshCw v-if="commandPending" class="spin" :size="14" />确认执行</BaseButton></footer>
      </section>
    </div>
  </div>
</template>

<style scoped>
.operations-workspace { display: grid; gap: 16px; max-width: 1420px; margin: 0 auto; color: var(--cs-text); }
.eyebrow { margin: 0 0 3px; color: var(--cs-brand-700); font-size: 9px; font-weight: 800; letter-spacing: .1em; text-transform: uppercase; }
.health-hero, .evidence-panel, .admin-panel { border: 1px solid var(--cs-border); border-radius: 14px; background: var(--cs-surface); box-shadow: 0 8px 26px rgb(28 58 43 / 5%); }
.health-hero { display: flex; align-items: center; justify-content: space-between; gap: 16px; padding: 19px 21px; background: linear-gradient(120deg, #f6fcf8, #fff); }
.health-hero h2, .evidence-panel h2, .admin-panel h2 { display: flex; align-items: center; gap: 8px; margin: 0; font-size: 16px; }
.health-hero p:last-child { margin: 6px 0 0; color: var(--cs-text-muted); font-size: 11px; }
.health-hero__status { display: grid; justify-items: end; gap: 6px; color: var(--cs-text-muted); font-size: 9px; }
.health-grid { display: grid; grid-template-columns: repeat(5, minmax(0, 1fr)); gap: 10px; }
.health-card { padding: 13px; border: 1px solid var(--cs-border); border-radius: 12px; background: var(--cs-surface); }
.health-card header { display: flex; align-items: center; justify-content: space-between; gap: 6px; }
.health-card strong { font-size: 11px; }
.health-card dl { display: grid; grid-template-columns: 1fr 1fr; gap: 9px; margin: 14px 0; }
.health-card dl div, .projection-metrics div { display: grid; gap: 2px; }
.health-card dt, .projection-metrics dt { color: var(--cs-text-muted); font-size: 8px; text-transform: uppercase; }
.health-card dd, .projection-metrics dd { margin: 0; font: 700 15px var(--cs-font-display); }
.health-card footer { display: flex; justify-content: space-between; color: var(--cs-text-muted); font-size: 8px; }.stale { color: var(--cs-danger); font-weight: 800; }
.inline-warning { display: flex; align-items: center; gap: 7px; padding: 9px 12px; border: 1px solid #efd9a7; border-radius: 9px; background: #fffaf0; color: #77531c; font-size: 10px; }
.evidence-panel, .admin-panel { padding: 18px; }.evidence-panel > header, .admin-panel > header, .recovery-section > header { display: flex; align-items: center; justify-content: space-between; gap: 12px; }.evidence-panel > header > span, .recovery-section > header > span { color: var(--cs-text-muted); font-size: 9px; }
.evidence-links { display: grid; grid-template-columns: repeat(5, minmax(0, 1fr)); gap: 9px; margin-top: 14px; }
.evidence-links a { display: flex; min-height: 58px; align-items: center; gap: 9px; padding: 10px; border: 1px solid var(--cs-border); border-radius: 10px; background: var(--cs-surface-subtle); color: var(--cs-text); }
.evidence-links a:hover { border-color: var(--cs-brand-300); background: var(--cs-brand-50); }.evidence-links span, .evidence-links small { display: block; }.evidence-links span { font-size: 10px; font-weight: 750; }.evidence-links small { margin-top: 2px; color: var(--cs-text-muted); font-size: 8px; font-weight: 500; }
.projection-list { display: grid; gap: 10px; margin-top: 15px; }.projection-card { padding: 14px; border: 1px solid var(--cs-border); border-radius: 11px; background: var(--cs-surface-subtle); }.projection-card > header { display: flex; align-items: center; justify-content: space-between; }.projection-card > header strong, .projection-card > header small { display: block; }.projection-card > header strong { font-size: 12px; }.projection-card > header small { margin-top: 3px; color: var(--cs-text-muted); font-size: 8px; }
.generation-flow { display: grid; grid-template-columns: minmax(0, 1fr) auto minmax(0, 1fr); align-items: center; gap: 10px; margin: 12px 0; }.generation-flow > div { display: grid; grid-template-columns: auto auto 1fr; align-items: baseline; gap: 8px; padding: 9px 11px; border: 1px solid #d9e9dd; border-radius: 9px; background: #f7fcf8; }.generation-flow span { color: var(--cs-text-muted); font-size: 8px; font-weight: 800; }.generation-flow strong { font-size: 14px; }.generation-flow small { color: var(--cs-text-muted); font-size: 9px; }.generation-flow .muted { background: #f6f7f6; opacity: .7; }
.projection-metrics { display: grid; grid-template-columns: repeat(4, 1fr); gap: 8px; margin: 0; }.projection-metrics div { padding: 7px 9px; border-radius: 7px; background: white; }.projection-metrics dd { overflow: hidden; font-size: 10px; text-overflow: ellipsis; white-space: nowrap; }.projection-actions { display: flex; flex-wrap: wrap; gap: 7px; margin-top: 12px; }
.recovery-section { margin-top: 18px; padding-top: 15px; border-top: 1px solid var(--cs-border); }.recovery-section > header > div { display: flex; align-items: center; gap: 7px; font-size: 11px; }.recovery-section ul { display: grid; gap: 7px; margin: 10px 0 0; padding: 0; list-style: none; }.recovery-section li { display: flex; align-items: center; justify-content: space-between; gap: 10px; padding: 9px 10px; border: 1px solid var(--cs-border); border-radius: 9px; }.recovery-section li strong, .recovery-section li small { display: block; }.recovery-section li strong { font-size: 10px; }.recovery-section li small { margin-top: 2px; color: var(--cs-text-muted); font-size: 8px; }.empty-line { display: flex; align-items: center; gap: 7px; color: var(--cs-text-muted); font-size: 10px; }
.command-result { display: grid; grid-template-columns: 1fr auto; gap: 4px 12px; padding: 11px 13px; border: 1px solid var(--cs-border); border-radius: 10px; background: var(--cs-surface); }.command-result > div { display: flex; align-items: center; gap: 7px; font-size: 10px; }.command-result p { grid-column: 1; margin: 0; color: var(--cs-text-muted); font-size: 9px; }.command-result.success { border-color: #b8ddc2; }.command-result.conflict, .command-result.error { border-color: #ebc2bb; }
.dialog-backdrop { position: fixed; inset: 0; z-index: 100; display: grid; place-items: center; padding: 18px; background: rgb(18 28 23 / 42%); backdrop-filter: blur(3px); }.confirmation-dialog { width: min(520px, 100%); padding: 19px; border: 1px solid var(--cs-border); border-radius: 15px; background: white; box-shadow: 0 24px 70px rgb(10 30 19 / 24%); }.confirmation-dialog > header { display: flex; align-items: flex-start; justify-content: space-between; }.confirmation-dialog h2 { margin: 0; font-size: 16px; }.confirmation-dialog > header button { display: grid; width: 32px; height: 32px; place-items: center; border: 0; border-radius: 7px; background: var(--cs-surface-subtle); cursor: pointer; }.confirmation-dialog > p { color: var(--cs-text-secondary); font-size: 10px; line-height: 1.6; }.confirmation-dialog code { display: block; overflow-wrap: anywhere; padding: 10px; border: 1px solid #d8e8dc; border-radius: 8px; background: #f6fbf7; color: #245c38; font-size: 10px; }.confirmation-dialog label { display: grid; gap: 6px; margin-top: 12px; color: var(--cs-text-secondary); font-size: 9px; font-weight: 700; }.confirmation-dialog input { min-height: 37px; padding: 0 10px; border: 1px solid var(--cs-border-strong); border-radius: 8px; font: 11px var(--cs-font-sans); }.confirmation-dialog label small { color: var(--cs-text-muted); font-weight: 500; }.confirmation-dialog footer { display: flex; justify-content: flex-end; gap: 8px; margin-top: 17px; }.dialog-error { margin-top: 12px; padding: 8px; border-radius: 7px; background: #fff1ef; color: #94382f; font-size: 9px; }.spin { animation: spin 1s linear infinite; }@keyframes spin { to { transform: rotate(360deg); } }
@media (max-width: 1100px) { .health-grid { grid-template-columns: repeat(3, 1fr); }.evidence-links { grid-template-columns: repeat(3, 1fr); } }
@media (max-width: 767px) { .health-hero { align-items: flex-start; }.health-hero__status { justify-items: start; }.health-grid { grid-template-columns: 1fr 1fr; }.evidence-links { grid-template-columns: 1fr 1fr; }.projection-metrics { grid-template-columns: 1fr 1fr; }.generation-flow { grid-template-columns: 1fr; }.generation-flow > span { display: none; }.recovery-section li { align-items: flex-start; flex-direction: column; }.confirmation-dialog { max-height: calc(100vh - 28px); overflow: auto; } }
@media (max-width: 430px) { .health-hero { flex-direction: column; }.health-grid, .evidence-links { grid-template-columns: 1fr; } }
@media (prefers-reduced-motion: reduce) { .spin { animation: none; } }
</style>
