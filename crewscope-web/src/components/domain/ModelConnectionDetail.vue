<script setup lang="ts">
import { Activity, AlertTriangle, KeyRound, RefreshCw, RotateCw, ShieldCheck, X } from '@lucide/vue'
import { computed, nextTick, ref, useTemplateRef, watch } from 'vue'
import { isTopmostModal } from '../../app/dialog'
import type { ModelCommandState, ModelResource } from '../../domains/model/store'
import { modelConnectionRevocationReasons, type ModelConnectionSummary } from '../../domains/model/types'
import { enumLabel } from '../../domains/shared/labels'
import {
  connectionStatusLabels,
  healthStatusLabels,
  modelConnectionHealthFailureCodeLabels,
  modelConnectionRevocationReasonLabels,
  modelSubjectTypeLabels,
} from '../../domains/settings/labels'
import type { Etagged } from '../../domains/settings/types'
import BaseButton from '../base/BaseButton.vue'
import StatusBadge from '../base/StatusBadge.vue'
import StatePanel from '../feedback/StatePanel.vue'

const props = defineProps<{
  resource: Readonly<ModelResource<Etagged<ModelConnectionSummary>>> | null
  canManage: boolean
  command: Readonly<ModelCommandState>
}>()

const emit = defineEmits<{
  close: []
  refresh: []
  verify: [connectionId: string]
  activate: [connectionId: string]
  rotate: [connection: ModelConnectionSummary]
  suspend: [connectionId: string]
  revoke: [connectionId: string, reason: string]
}>()

const revokeOpen = ref(false)
const revokeDialog = useTemplateRef<HTMLElement>('revokeDialog')
const revokeTrigger = ref<HTMLElement | null>(null)
const revokeReason = ref('OWNER_REQUESTED')
const revocationReasons = modelConnectionRevocationReasons
const revokeConfirmed = ref(false)
const connection = computed(() => props.resource?.value?.value ?? null)
const resourceState = computed(() => props.resource)
const pending = computed(() => props.command.phase === 'pending' && props.command.connectionId === connection.value?.id)
const commandApplies = computed(() => Boolean(connection.value && props.command.connectionId === connection.value.id))

watch(() => connection.value?.id, () => closeRevoke())
watch(() => props.command.phase, phase => {
  if (phase === 'success') closeRevoke()
})

function openRevoke(event?: MouseEvent): void {
  if (event?.currentTarget instanceof HTMLElement) revokeTrigger.value = event.currentTarget
  revokeOpen.value = true
  void nextTick(() => revokeDialog.value?.focus())
}

function closeRevoke(): void {
  revokeOpen.value = false
  revokeReason.value = 'OWNER_REQUESTED'
  revokeConfirmed.value = false
  void nextTick(() => revokeTrigger.value?.focus())
}

function confirmRevoke(): void {
  if (!connection.value || !revokeConfirmed.value) return
  emit('revoke', connection.value.id, revokeReason.value)
}

function handleRevokeKeydown(event: KeyboardEvent): void {
  if (!isTopmostModal(revokeDialog.value)) return
  event.stopPropagation()
  if (event.key === 'Escape') {
    event.preventDefault()
    closeRevoke()
    return
  }
  if (event.key !== 'Tab' || !revokeDialog.value) return
  const controls = [...revokeDialog.value.querySelectorAll<HTMLElement>(
    'button:not(:disabled), input:not(:disabled), select:not(:disabled)',
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

function healthTone(value: ModelConnectionSummary): 'success' | 'warning' | 'danger' | 'neutral' {
  if (value.healthStatus === 'HEALTHY') return 'success'
  if (value.healthStatus === 'UNHEALTHY') return 'danger'
  return 'neutral'
}

function statusTone(value: ModelConnectionSummary): 'success' | 'warning' | 'danger' {
  if (value.status === 'ACTIVE') return 'success'
  if (value.status === 'SUSPENDED') return 'warning'
  return 'danger'
}

function ownerLabel(value: ModelConnectionSummary): string {
  if (value.ownerType === 'USER') return '我的连接'
  if (value.ownerType === 'TEAM') return '团队连接'
  return '组织连接'
}

function failureLabel(code: string | null): string {
  return code ? enumLabel(code, modelConnectionHealthFailureCodeLabels) : '无'
}

/** Connection facts quote four registry enums, so each is read through its own label map. */
function label(value: string | null | undefined, labels: Record<string, string>): string {
  return enumLabel(value, labels)
}

function formatTime(value: string | null): string {
  if (!value) return '尚未记录'
  const parsed = new Date(value)
  return Number.isNaN(parsed.valueOf()) ? value : parsed.toLocaleString('zh-CN', { hour12: false })
}
</script>

<template>
  <section class="connection-detail panel" aria-labelledby="connection-detail-title">
    <header class="detail-heading">
      <span class="detail-icon"><KeyRound :size="20" /></span>
      <div><p class="eyebrow">Connection authority</p><h2 id="connection-detail-title">模型连接详情</h2><span>安全公开投影 · 不包含 Endpoint、Credential ID 或 Key</span></div>
      <button type="button" aria-label="关闭模型连接详情" @click="emit('close')"><X :size="18" /></button>
    </header>

    <StatePanel v-if="!resourceState || resourceState.phase === 'idle' || resourceState.phase === 'loading'" state="loading" compact title="正在读取连接权威事实" />
    <StatePanel v-else-if="resourceState.phase === 'error'" state="error" compact :description="resourceState.errorMessage ?? undefined" @retry="emit('refresh')" />
    <template v-else-if="connection">
      <div class="detail-summary">
        <div><p>{{ ownerLabel(connection) }}</p><h3>{{ connection.providerKey }}</h3><span class="mono">{{ connection.id }}</span></div>
        <div class="summary-status"><StatusBadge :tone="statusTone(connection)" dot>{{ label(connection.status, connectionStatusLabels) }}</StatusBadge><StatusBadge :tone="healthTone(connection)" dot>{{ label(connection.healthStatus, healthStatusLabels) }}</StatusBadge></div>
      </div>

      <dl class="detail-facts">
        <div><dt>Region</dt><dd>{{ connection.region }}</dd></div>
        <div><dt>Credential Version</dt><dd>{{ connection.credentialVersion }}</dd></div>
        <div><dt>Billing</dt><dd>{{ label(connection.billingSubjectType, modelSubjectTypeLabels) }}</dd></div>
        <div><dt>Connection Version</dt><dd>{{ connection.version }}</dd></div>
        <div><dt>最近验证</dt><dd>{{ formatTime(connection.checkedAt) }}</dd></div>
        <div><dt>最近健康</dt><dd>{{ formatTime(connection.lastHealthyAt) }}</dd></div>
      </dl>

      <section v-if="connection.healthStatus === 'UNHEALTHY'" class="health-failure" role="status">
        <AlertTriangle :size="18" /><div><strong>{{ failureLabel(connection.healthFailureCode) }}</strong><span>连续失败 {{ connection.consecutiveFailures }} 次。页面只展示稳定失败码，Provider 原始错误保持在服务端边界内。</span></div>
      </section>

      <StatePanel
        v-if="commandApplies && command.phase === 'conflict'"
        state="conflict"
        compact
        :description="command.errorMessage ?? undefined"
        @retry="emit('refresh')"
      />
      <p v-else-if="commandApplies && command.phase === 'error'" class="command-error" role="alert">{{ command.errorMessage }}</p>

      <section class="connection-actions" aria-labelledby="connection-actions-title">
        <div><p class="eyebrow">Lifecycle</p><h3 id="connection-actions-title">连接操作</h3><span v-if="!canManage">当前成员可查看 Team Connection，管理操作需要 Provider Manager 权限。</span></div>
        <div v-if="canManage && connection.status !== 'REVOKED'" class="action-buttons">
          <BaseButton v-if="connection.status === 'ACTIVE' || connection.status === 'SUSPENDED'" size="small" variant="secondary" :loading="pending && command.operation === 'verify'" :disabled="pending" @click="emit('verify', connection.id)"><Activity :size="14" />验证健康</BaseButton>
          <BaseButton v-if="connection.status === 'SUSPENDED'" size="small" variant="secondary" :loading="pending && command.operation === 'activate'" :disabled="pending || connection.healthStatus !== 'HEALTHY'" aria-describedby="model-connection-activate-reason" @click="emit('activate', connection.id)"><Activity :size="14" />重新启用</BaseButton>
          <p v-if="connection.status === 'SUSPENDED'" id="model-connection-activate-reason" class="sr-only">{{ connection.healthStatus === 'HEALTHY' ? '健康检查通过后可以重新启用连接。' : '连接必须先通过健康检查，才能重新启用。' }}</p>
          <BaseButton size="small" variant="secondary" :disabled="pending" @click="emit('rotate', connection)"><RotateCw :size="14" />轮换凭证</BaseButton>
          <BaseButton v-if="connection.status === 'ACTIVE'" size="small" variant="secondary" :loading="pending && command.operation === 'suspend'" :disabled="pending" @click="emit('suspend', connection.id)">停用连接</BaseButton>
          <BaseButton size="small" variant="danger" :disabled="pending" @click="openRevoke">永久撤销</BaseButton>
        </div>
        <p v-else-if="connection.status === 'REVOKED'" class="terminal-note">连接已永久撤销 · {{ label(connection.revocationReason, modelConnectionRevocationReasonLabels) }}</p>
        <p v-else class="read-only-note">服务端仍会在每次命令中重新校验权限。</p>
        <p v-if="connection.status === 'SUSPENDED'" class="recovery-note">停用连接不会删除凭证；只有当前凭证健康状态为“健康”时才能重新启用。凭证轮换后需先验证健康。</p>
      </section>

      <section class="audit-evidence" aria-labelledby="connection-audit-title">
        <ShieldCheck :size="18" />
        <div><h3 id="connection-audit-title">审计证据入口</h3><p v-if="commandApplies && command.receipt">最近命令已接受 · Correlation <span class="mono">{{ command.receipt.correlationId }}</span> · Version {{ command.receipt.committedVersion }}</p><p v-else>创建、验证、轮换、停用和撤销均返回 Command Receipt。完整审计时间线将在统一 Audit 查询 API 交付后接入。</p></div>
      </section>
    </template>

    <div v-if="revokeOpen && connection" class="revoke-backdrop" @click.self="closeRevoke">
      <section ref="revokeDialog" class="revoke-dialog" role="alertdialog" aria-modal="true" aria-labelledby="revoke-title" aria-describedby="revoke-impact" tabindex="-1" @keydown="handleRevokeKeydown">
        <header><AlertTriangle :size="20" /><div><p>Irreversible action</p><h3 id="revoke-title">永久撤销模型连接</h3></div></header>
        <p id="revoke-impact">撤销会终止该 Connection 的凭证使用，且当前 API 不支持恢复。已引用此连接的新执行会失败关闭。</p>
        <label><span>稳定撤销原因</span><select v-model="revokeReason" :disabled="pending"><option v-for="reason in revocationReasons" :key="reason" :value="reason">{{ modelConnectionRevocationReasonLabels[reason] }}</option></select></label>
        <label class="confirm-check"><input v-model="revokeConfirmed" type="checkbox" :disabled="pending" />我确认永久撤销且无法恢复</label>
        <p v-if="commandApplies && (command.phase === 'error' || command.phase === 'conflict')" class="command-error" role="alert">{{ command.errorMessage }}</p>
        <footer><BaseButton variant="ghost" :disabled="pending" @click="closeRevoke">取消</BaseButton><BaseButton variant="danger" :loading="pending && command.operation === 'revoke'" :disabled="!revokeConfirmed" @click="confirmRevoke">确认永久撤销</BaseButton></footer>
      </section>
    </div>
  </section>
</template>

<style scoped>
.connection-detail { overflow: hidden; }.detail-heading { display: grid; grid-template-columns: 40px minmax(0, 1fr) 32px; gap: var(--cs-space-12); align-items: start; padding: var(--cs-space-16) var(--cs-space-20); border-bottom: 1px solid var(--cs-border); background: linear-gradient(135deg, var(--cs-surface-accent), var(--cs-surface) 65%); }.detail-icon { display: grid; width: 40px; height: 40px; place-items: center; border-radius: 12px; background: var(--cs-surface-accent-strong); color: var(--cs-text-brand); }.detail-heading h2 { margin: 0 0 var(--cs-space-2); font-size: var(--cs-text-md); }.detail-heading div > span { color: var(--cs-text-muted); font-size: var(--cs-text-xs); }.detail-heading > button { display: grid; width: 32px; height: 32px; place-items: center; border-radius: 8px; background: var(--cs-surface); cursor: pointer; }.detail-summary { display: flex; align-items: flex-start; justify-content: space-between; gap: var(--cs-space-16); padding: var(--cs-space-16) var(--cs-space-20) var(--cs-space-12); }.detail-summary p { margin: 0; color: var(--cs-text-brand); font-size: var(--cs-text-xs); font-weight: var(--cs-weight-semibold); }.detail-summary h3 { margin: var(--cs-space-4) 0 var(--cs-space-2); font-size: var(--cs-text-md); }.detail-summary span { color: var(--cs-text-muted); font-size: var(--cs-text-xs); }.summary-status { display: flex; flex-wrap: wrap; justify-content: flex-end; gap: var(--cs-space-8); }.detail-facts { display: grid; grid-template-columns: repeat(3, 1fr); gap: var(--cs-space-8); margin: 0; padding: 0 var(--cs-space-20) var(--cs-space-16); }.detail-facts div { min-width: 0; padding: var(--cs-space-8) var(--cs-space-12); border-radius: 8px; background: var(--cs-surface-subtle); }.detail-facts dt { color: var(--cs-text-muted); font-size: var(--cs-text-xs); text-transform: uppercase; }.detail-facts dd { overflow: hidden; margin-top: var(--cs-space-4); color: var(--cs-text-secondary); font-size: var(--cs-text-xs); font-weight: var(--cs-weight-semibold); text-overflow: ellipsis; white-space: nowrap; }.health-failure { display: flex; gap: var(--cs-space-8); margin: 0 var(--cs-space-20) var(--cs-space-16); padding: var(--cs-space-12) var(--cs-space-12); border: 1px solid var(--cs-danger-border); border-radius: var(--cs-radius-sm); background: var(--cs-danger-soft); color: var(--cs-danger); }.health-failure svg { flex: 0 0 auto; }.health-failure strong, .health-failure span { display: block; }.health-failure strong { font-size: var(--cs-text-sm); }.health-failure span { margin-top: var(--cs-space-2); color: var(--cs-text-secondary); font-size: var(--cs-text-xs); }.command-error { margin: 0 var(--cs-space-20) var(--cs-space-12); color: var(--cs-danger); font-size: var(--cs-text-sm); }.connection-actions { display: grid; gap: var(--cs-space-12); margin: 0 var(--cs-space-20) var(--cs-space-16); padding: var(--cs-space-12); border: 1px solid var(--cs-border); border-radius: var(--cs-radius-md); }.connection-actions h3 { margin: var(--cs-space-2) 0 0; font-size: var(--cs-text-base); }.connection-actions > div > span, .terminal-note, .read-only-note, .recovery-note { display: block; margin: var(--cs-space-4) 0 0; color: var(--cs-text-muted); font-size: var(--cs-text-xs); }.action-buttons { display: flex; flex-wrap: wrap; gap: var(--cs-space-8); }.recovery-note { padding: var(--cs-space-8) var(--cs-space-8); border-radius: 8px; background: var(--cs-warning-soft); color: var(--cs-warning); }.audit-evidence { display: flex; gap: var(--cs-space-8); margin: 0 var(--cs-space-20) var(--cs-space-20); padding: var(--cs-space-12) var(--cs-space-12); border: 1px solid var(--cs-border-accent); border-radius: var(--cs-radius-sm); background: var(--cs-surface-accent); color: var(--cs-text-brand); }.audit-evidence svg { flex: 0 0 auto; }.audit-evidence h3 { margin: 0; font-size: var(--cs-text-sm); }.audit-evidence p { margin: var(--cs-space-4) 0 0; color: var(--cs-text-secondary); font-size: var(--cs-text-xs); }.revoke-backdrop { position: fixed; inset: 0; z-index: var(--cs-z-dialog); display: grid; place-items: center; padding: var(--cs-space-20); background: var(--cs-scrim); backdrop-filter: blur(3px); }.revoke-dialog { width: min(500px, 100%); padding: var(--cs-space-20); border: 1px solid var(--cs-danger-border); border-radius: var(--cs-radius-md); background: var(--cs-surface); box-shadow: var(--cs-shadow-float); }.revoke-dialog header { display: flex; gap: var(--cs-space-12); color: var(--cs-danger); }.revoke-dialog header p, .revoke-dialog header h3 { margin: 0; }.revoke-dialog header p { font-size: var(--cs-text-xs); font-weight: var(--cs-weight-semibold); letter-spacing: .08em; text-transform: uppercase; }.revoke-dialog header h3 { margin-top: var(--cs-space-2); font-size: var(--cs-text-md); }.revoke-dialog > p { margin: var(--cs-space-12) 0; color: var(--cs-text-secondary); font-size: var(--cs-text-sm); line-height: var(--cs-leading-normal); }.revoke-dialog > label:not(.confirm-check) { display: grid; gap: var(--cs-space-8); color: var(--cs-text-secondary); font-size: var(--cs-text-xs); font-weight: var(--cs-weight-semibold); }.revoke-dialog select { min-height: 39px; padding: 0 var(--cs-space-12); border: 1px solid var(--cs-border-strong); border-radius: var(--cs-radius-sm); background: var(--cs-surface); }.confirm-check { display: flex; align-items: center; gap: var(--cs-space-8); margin-top: var(--cs-space-12); color: var(--cs-danger); font-size: var(--cs-text-sm); font-weight: var(--cs-weight-semibold); }.revoke-dialog .command-error { margin: var(--cs-space-12) 0 0; }.revoke-dialog footer { display: flex; justify-content: flex-end; gap: var(--cs-space-8); margin-top: var(--cs-space-16); }
@media (max-width: 650px) { .detail-facts { grid-template-columns: 1fr 1fr; }.detail-summary { display: grid; }.summary-status { justify-content: flex-start; }.revoke-backdrop { align-items: end; padding: 0; }.revoke-dialog { border-radius: 18px 18px 0 0; }.revoke-dialog footer { display: grid; }.revoke-dialog footer > * { width: 100%; } }
</style>
