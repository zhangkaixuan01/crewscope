<script setup lang="ts">
import { Activity, AlertTriangle, KeyRound, RefreshCw, RotateCw, ShieldCheck, X } from '@lucide/vue'
import { computed, nextTick, ref, useTemplateRef, watch } from 'vue'
import { isTopmostModal } from '../../app/dialog'
import type { ModelCommandState, ModelResource } from '../../domains/model/store'
import type { ModelConnectionSummary } from '../../domains/model/types'
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
  rotate: [connection: ModelConnectionSummary]
  suspend: [connectionId: string]
  revoke: [connectionId: string, reason: string]
}>()

const revokeOpen = ref(false)
const revokeDialog = useTemplateRef<HTMLElement>('revokeDialog')
const revokeTrigger = ref<HTMLElement | null>(null)
const revokeReason = ref('OWNER_REQUESTED')
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
  const labels: Record<string, string> = {
    AUTHENTICATION_FAILED: '身份验证失败', ENDPOINT_UNREACHABLE: 'Provider 不可达',
    TIMEOUT: '验证超时', RATE_LIMITED: '触发速率限制', PROVIDER_REJECTED: 'Provider 拒绝请求',
    POLICY_REJECTED: '组织策略拒绝',
  }
  return code ? labels[code] ?? code : '无'
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
        <div class="summary-status"><StatusBadge :tone="statusTone(connection)" dot>{{ connection.status }}</StatusBadge><StatusBadge :tone="healthTone(connection)" dot>{{ connection.healthStatus }}</StatusBadge></div>
      </div>

      <dl class="detail-facts">
        <div><dt>Region</dt><dd>{{ connection.region }}</dd></div>
        <div><dt>Credential Version</dt><dd>{{ connection.credentialVersion }}</dd></div>
        <div><dt>Billing</dt><dd>{{ connection.billingSubjectType }}</dd></div>
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
          <BaseButton v-if="connection.status === 'ACTIVE'" size="small" variant="secondary" :loading="pending && command.operation === 'verify'" :disabled="pending" @click="emit('verify', connection.id)"><Activity :size="14" />验证健康</BaseButton>
          <BaseButton size="small" variant="secondary" :disabled="pending" @click="emit('rotate', connection)"><RotateCw :size="14" />轮换凭证</BaseButton>
          <BaseButton v-if="connection.status === 'ACTIVE'" size="small" variant="secondary" :loading="pending && command.operation === 'suspend'" :disabled="pending" @click="emit('suspend', connection.id)">停用连接</BaseButton>
          <BaseButton size="small" variant="danger" :disabled="pending" @click="openRevoke">永久撤销</BaseButton>
        </div>
        <p v-else-if="connection.status === 'REVOKED'" class="terminal-note">连接已永久撤销 · {{ connection.revocationReason }}</p>
        <p v-else class="read-only-note">服务端仍会在每次命令中重新校验权限。</p>
        <p v-if="connection.status === 'SUSPENDED'" class="recovery-note">当前公开 API 尚未提供重新启用命令；可轮换或撤销凭证，恢复入口将在服务端契约交付后开放。</p>
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
        <label><span>稳定撤销原因</span><select v-model="revokeReason" :disabled="pending"><option value="OWNER_REQUESTED">Owner 主动撤销</option><option value="CREDENTIAL_REVOKED">凭证已在 Provider 撤销</option><option value="PROVIDER_DISABLED">Provider 已停用</option><option value="POLICY_REVOKED">策略撤销</option><option value="SECURITY_INCIDENT">安全事件</option></select></label>
        <label class="confirm-check"><input v-model="revokeConfirmed" type="checkbox" :disabled="pending" />我确认永久撤销且无法恢复</label>
        <p v-if="commandApplies && (command.phase === 'error' || command.phase === 'conflict')" class="command-error" role="alert">{{ command.errorMessage }}</p>
        <footer><BaseButton variant="ghost" :disabled="pending" @click="closeRevoke">取消</BaseButton><BaseButton variant="danger" :loading="pending && command.operation === 'revoke'" :disabled="!revokeConfirmed" @click="confirmRevoke">确认永久撤销</BaseButton></footer>
      </section>
    </div>
  </section>
</template>

<style scoped>
.connection-detail { overflow: hidden; }.detail-heading { display: grid; grid-template-columns: 40px minmax(0, 1fr) 32px; gap: 11px; align-items: start; padding: 17px 18px; border-bottom: 1px solid var(--cs-border); background: linear-gradient(135deg, var(--cs-brand-50), var(--cs-surface) 65%); }.detail-icon { display: grid; width: 40px; height: 40px; place-items: center; border-radius: 12px; background: var(--cs-brand-100); color: var(--cs-brand-700); }.detail-heading h2 { margin: 0 0 2px; font-size: 16px; }.detail-heading div > span { color: var(--cs-text-muted); font-size: 9px; }.detail-heading > button { display: grid; width: 32px; height: 32px; place-items: center; border-radius: 8px; background: var(--cs-surface); cursor: pointer; }.detail-summary { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; padding: 16px 18px 10px; }.detail-summary p { margin: 0; color: var(--cs-brand-700); font-size: 9px; font-weight: 750; }.detail-summary h3 { margin: 3px 0 2px; font-size: 15px; }.detail-summary span { color: var(--cs-text-muted); font-size: 8px; }.summary-status { display: flex; flex-wrap: wrap; justify-content: flex-end; gap: 6px; }.detail-facts { display: grid; grid-template-columns: repeat(3, 1fr); gap: 7px; margin: 0; padding: 0 18px 16px; }.detail-facts div { min-width: 0; padding: 9px 10px; border-radius: 8px; background: var(--cs-surface-subtle); }.detail-facts dt { color: var(--cs-text-muted); font-size: 8px; text-transform: uppercase; }.detail-facts dd { overflow: hidden; margin-top: 3px; color: var(--cs-text-secondary); font-size: 9px; font-weight: 700; text-overflow: ellipsis; white-space: nowrap; }.health-failure { display: flex; gap: 9px; margin: 0 18px 14px; padding: 11px 12px; border: 1px solid #efc7c1; border-radius: var(--cs-radius-sm); background: #fff6f5; color: var(--cs-danger); }.health-failure svg { flex: 0 0 auto; }.health-failure strong, .health-failure span { display: block; }.health-failure strong { font-size: 10px; }.health-failure span { margin-top: 2px; color: var(--cs-text-secondary); font-size: 9px; }.command-error { margin: 0 18px 12px; color: var(--cs-danger); font-size: 10px; }.connection-actions { display: grid; gap: 10px; margin: 0 18px 14px; padding: 13px; border: 1px solid var(--cs-border); border-radius: var(--cs-radius-md); }.connection-actions h3 { margin: 1px 0 0; font-size: 12px; }.connection-actions > div > span, .terminal-note, .read-only-note, .recovery-note { display: block; margin: 3px 0 0; color: var(--cs-text-muted); font-size: 9px; }.action-buttons { display: flex; flex-wrap: wrap; gap: 7px; }.recovery-note { padding: 8px 9px; border-radius: 8px; background: var(--cs-warning-soft); color: var(--cs-warning); }.audit-evidence { display: flex; gap: 9px; margin: 0 18px 18px; padding: 11px 12px; border: 1px solid var(--cs-brand-200); border-radius: var(--cs-radius-sm); background: var(--cs-brand-50); color: var(--cs-brand-700); }.audit-evidence svg { flex: 0 0 auto; }.audit-evidence h3 { margin: 0; font-size: 10px; }.audit-evidence p { margin: 3px 0 0; color: var(--cs-text-secondary); font-size: 9px; }.revoke-backdrop { position: fixed; inset: 0; z-index: 110; display: grid; place-items: center; padding: 18px; background: rgb(21 35 29 / 40%); backdrop-filter: blur(3px); }.revoke-dialog { width: min(500px, 100%); padding: 18px; border: 1px solid #e7b9b2; border-radius: var(--cs-radius-md); background: var(--cs-surface); box-shadow: var(--cs-shadow-float); }.revoke-dialog header { display: flex; gap: 10px; color: var(--cs-danger); }.revoke-dialog header p, .revoke-dialog header h3 { margin: 0; }.revoke-dialog header p { font-size: 8px; font-weight: 750; letter-spacing: .08em; text-transform: uppercase; }.revoke-dialog header h3 { margin-top: 2px; font-size: 15px; }.revoke-dialog > p { margin: 13px 0; color: var(--cs-text-secondary); font-size: 10px; line-height: 1.55; }.revoke-dialog > label:not(.confirm-check) { display: grid; gap: 6px; color: var(--cs-text-secondary); font-size: 9px; font-weight: 750; }.revoke-dialog select { min-height: 39px; padding: 0 10px; border: 1px solid var(--cs-border-strong); border-radius: var(--cs-radius-sm); background: var(--cs-surface); }.confirm-check { display: flex; align-items: center; gap: 7px; margin-top: 13px; color: var(--cs-danger); font-size: 10px; font-weight: 700; }.revoke-dialog .command-error { margin: 12px 0 0; }.revoke-dialog footer { display: flex; justify-content: flex-end; gap: 7px; margin-top: 17px; }
@media (max-width: 650px) { .detail-facts { grid-template-columns: 1fr 1fr; }.detail-summary { display: grid; }.summary-status { justify-content: flex-start; }.revoke-backdrop { align-items: end; padding: 0; }.revoke-dialog { border-radius: 18px 18px 0 0; }.revoke-dialog footer { display: grid; }.revoke-dialog footer > * { width: 100%; } }
</style>
