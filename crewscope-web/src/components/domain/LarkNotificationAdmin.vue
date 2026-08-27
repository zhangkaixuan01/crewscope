<script setup lang="ts">
import {
  BellRing, CheckCircle2, CircleOff, KeyRound, Link2, RefreshCw, RotateCw,
  Send, ShieldCheck, Unplug, UserCheck, UsersRound, X,
} from '@lucide/vue'
import { computed, nextTick, reactive, ref, watch } from 'vue'
import BaseButton from '../base/BaseButton.vue'
import StatusBadge from '../base/StatusBadge.vue'
import StatePanel from '../feedback/StatePanel.vue'
import type { TeamMemberSummary } from '../../domains/scope/types'
import type { TeamOpsCommandState, TeamOpsPhase } from '../../domains/teamops/store'
import type { TeamOpsErrorState } from '../../domains/teamops/errors'
import {
  inboxItemTypes, larkMappingStatuses, notificationDeliveryStatuses,
  type InboxItemType, type LarkConnection, type LarkHealth, type LarkMapping,
  type LarkMappingStatus, type NotificationDelivery, type NotificationDeliveryStatus,
  type NotificationPreference, type NotificationTemplate,
} from '../../domains/teamops/types'

const props = defineProps<{
  phase: TeamOpsPhase
  error: TeamOpsErrorState | null
  connections: LarkConnection[]
  selectedConnection: LarkConnection | null
  health: LarkHealth | null
  mappings: LarkMapping[]
  mappingPhase: TeamOpsPhase
  mappingError: TeamOpsErrorState | null
  mappingNextCursor: string | null
  mappingLoadingMore: boolean
  members: TeamMemberSummary[]
  currentMemberId: string | null
  templates: NotificationTemplate[]
  preference: NotificationPreference | null
  deliveries: NotificationDelivery[]
  deliveryPhase: TeamOpsPhase
  deliveryError: TeamOpsErrorState | null
  deliveryNextCursor: string | null
  deliveryLoadingMore: boolean
  selectedDelivery: NotificationDelivery | null
  command: TeamOpsCommandState
  online: boolean
  selectedTab: 'connection' | 'mapping' | 'notification'
  mappingStatus: LarkMappingStatus | null
  deliveryStatus: NotificationDeliveryStatus | null
  deliveryType: InboxItemType | null
  recipient: string
}>()

const emit = defineEmits<{
  tab: [value: 'connection' | 'mapping' | 'notification']
  refresh: []
  selectConnection: [id: string]
  createConnection: [input: { tenantKey: string, appId: string, appSecret: string, expiresAt: string | null }, key: string]
  rotateConnection: [id: string, input: { appId: string, appSecret: string }, key: string]
  revokeConnection: [id: string, reason: string, key: string]
  preflight: [bindingId: string, version: number]
  health: [bindingId: string]
  verifyMember: [bindingId: string, version: number, memberId: string, openId: string, key: string]
  confirmMapping: [memberId: string, bindingId: string, proofId: string, key: string]
  mappingFilter: [status: LarkMappingStatus | null]
  loadMoreMappings: []
  revokeMapping: [mappingId: string, version: number, reason: string, key: string]
  savePreference: [memberId: string, input: { enabled: boolean, enabledItemTypes: InboxItemType[], mutedUntil: string | null }, key: string]
  deliveryFilter: [value: { status: NotificationDeliveryStatus | null, itemType: InboxItemType | null, recipient: string }]
  loadMoreDeliveries: []
  selectDelivery: [id: string]
  closeDelivery: []
  redeliver: [id: string, key: string]
  clearCommand: []
}>()

const credentialDialog = ref<'create' | 'rotate' | null>(null)
const createForm = reactive({ tenantKey: '', appId: '', appSecret: '', expiresAt: '' })
const rotateForm = reactive({ appId: '', appSecret: '' })
const revokeReason = ref('')
const mappingMemberId = ref('')
const openId = ref('')
const verified = ref<{ memberId: string, bindingId: string, proofId: string } | null>(null)
const preferenceForm = reactive<{ enabled: boolean, enabledItemTypes: InboxItemType[], mutedUntil: string }>({ enabled: true, enabledItemTypes: [...inboxItemTypes], mutedUntil: '' })
const localMappingStatus = ref<LarkMappingStatus | ''>(props.mappingStatus ?? '')
const deliveryFilter = reactive({ status: props.deliveryStatus ?? '', itemType: props.deliveryType ?? '', recipient: props.recipient })
const dialogHeading = ref<HTMLElement | null>(null)
const dialogRoot = ref<HTMLElement | null>(null)
let dialogOpener: HTMLElement | null = null

const activeBinding = computed(() => props.selectedConnection?.providerBindingId ?? null)
const canMutate = computed(() => props.online && props.command.phase !== 'pending')
const initialFailure = computed(() => props.phase === 'error' && props.connections.length === 0)
const mappingInitialFailure = computed(() => props.mappingPhase === 'error' && props.mappings.length === 0)
const deliveryInitialFailure = computed(() => props.deliveryPhase === 'error' && props.deliveries.length === 0)
const failedDelivery = computed(() => props.selectedDelivery?.status === 'FAILED_FINAL')

watch(() => props.currentMemberId, value => {
  if (!mappingMemberId.value && value) mappingMemberId.value = value
}, { immediate: true })

watch(() => props.preference, value => {
  if (!value) return
  preferenceForm.enabled = value.enabled
  preferenceForm.enabledItemTypes = [...value.enabledItemTypes]
  preferenceForm.mutedUntil = toLocalDate(value.mutedUntil)
}, { immediate: true })

watch(() => props.command, command => {
  if (command.phase !== 'success') return
  if (command.operation === 'lark-member-verify' && command.receipt && 'domainEventId' in command.receipt && mappingMemberId.value && activeBinding.value) {
    verified.value = { memberId: mappingMemberId.value, bindingId: activeBinding.value, proofId: command.receipt.domainEventId }
    // Exact open_id is deliberately erased immediately after the one-way verification call.
    openId.value = ''
  }
  if (command.operation === 'lark-create' || command.operation === 'lark-rotate') closeCredentialDialog()
}, { deep: true })

function openCredentialDialog(mode: 'create' | 'rotate'): void {
  emit('clearCommand')
  dialogOpener = document.activeElement instanceof HTMLElement ? document.activeElement : null
  credentialDialog.value = mode
  void nextTick(() => dialogHeading.value?.focus())
}

function closeCredentialDialog(): void {
  if (props.command.phase === 'pending') return
  credentialDialog.value = null
  createForm.tenantKey = createForm.appId = createForm.appSecret = createForm.expiresAt = ''
  rotateForm.appId = rotateForm.appSecret = ''
  const opener = dialogOpener
  dialogOpener = null
  void nextTick(() => opener?.focus())
}

function handleDialogKeydown(event: KeyboardEvent): void {
  if (event.key === 'Escape') {
    event.preventDefault()
    closeCredentialDialog()
    return
  }
  if (event.key !== 'Tab' || !dialogRoot.value) return
  const focusable = [...dialogRoot.value.querySelectorAll<HTMLElement>(
    'button:not([disabled]), input:not([disabled]), select:not([disabled]), [tabindex]:not([tabindex="-1"])',
  )].filter(element => !element.hidden)
  if (focusable.length === 0) return
  const first = focusable[0]!
  const last = focusable.at(-1)!
  if (event.shiftKey && (document.activeElement === first || document.activeElement === dialogHeading.value)) {
    event.preventDefault()
    last.focus()
  } else if (!event.shiftKey && document.activeElement === last) {
    event.preventDefault()
    first.focus()
  }
}

function submitCredential(): void {
  if (!canMutate.value) return
  if (credentialDialog.value === 'create' && createForm.tenantKey && createForm.appId && createForm.appSecret) {
    emit('createConnection', {
      tenantKey: createForm.tenantKey, appId: createForm.appId, appSecret: createForm.appSecret,
      expiresAt: createForm.expiresAt ? new Date(createForm.expiresAt).toISOString() : null,
    }, crypto.randomUUID())
  } else if (credentialDialog.value === 'rotate' && props.selectedConnection && rotateForm.appId && rotateForm.appSecret) {
    emit('rotateConnection', props.selectedConnection.connectionId, { ...rotateForm }, crypto.randomUUID())
  }
}

function verify(): void {
  if (!canMutate.value || !activeBinding.value || props.selectedConnection?.providerBindingVersion == null || !mappingMemberId.value || !openId.value.trim()) return
  verified.value = null
  emit('verifyMember', activeBinding.value, props.selectedConnection.providerBindingVersion, mappingMemberId.value, openId.value.trim(), crypto.randomUUID())
}

function confirm(): void {
  if (!canMutate.value || !verified.value) return
  emit('confirmMapping', verified.value.memberId, verified.value.bindingId, verified.value.proofId, crypto.randomUUID())
  verified.value = null
}

function toggleItemType(value: InboxItemType): void {
  preferenceForm.enabledItemTypes = preferenceForm.enabledItemTypes.includes(value)
    ? preferenceForm.enabledItemTypes.filter(item => item !== value)
    : [...preferenceForm.enabledItemTypes, value]
}

function savePreference(): void {
  if (!canMutate.value || !props.currentMemberId || preferenceForm.enabledItemTypes.length === 0) return
  emit('savePreference', props.currentMemberId, {
    enabled: preferenceForm.enabled,
    enabledItemTypes: preferenceForm.enabledItemTypes,
    mutedUntil: preferenceForm.mutedUntil ? new Date(preferenceForm.mutedUntil).toISOString() : null,
  }, crypto.randomUUID())
}

function applyMappingFilter(): void {
  emit('mappingFilter', localMappingStatus.value || null)
}

function applyDeliveryFilter(): void {
  emit('deliveryFilter', {
    status: deliveryFilter.status as NotificationDeliveryStatus || null,
    itemType: deliveryFilter.itemType as InboxItemType || null,
    recipient: deliveryFilter.recipient.trim(),
  })
}

function connectionTone(status: LarkConnection['status']): 'success' | 'warning' | 'danger' {
  if (status === 'ACTIVE') return 'success'
  if (status === 'SUSPENDED') return 'warning'
  return 'danger'
}
function healthTone(status: LarkHealth['status']): 'success' | 'warning' | 'danger' { return status === 'HEALTHY' ? 'success' : status === 'RATE_LIMITED' || status === 'PROVIDER_UNAVAILABLE' ? 'warning' : 'danger' }
function mappingTone(status: LarkMappingStatus): 'success' | 'warning' | 'danger' { return status === 'ACTIVE' ? 'success' : status === 'INVALIDATED' ? 'danger' : 'warning' }
function deliveryTone(status: NotificationDeliveryStatus): 'success' | 'warning' | 'danger' | 'neutral' {
  if (status === 'SUCCEEDED') return 'success'
  if (status === 'FAILED_FINAL' || status === 'INVALIDATED') return 'danger'
  if (status === 'READY' || status === 'RUNNING' || status === 'RETRY_WAIT' || status === 'RECONCILING') return 'warning'
  return 'neutral'
}
function shortId(value: string | null): string { return value ? `${value.slice(0, 8)}…` : '—' }
function newKey(): string { return crypto.randomUUID() }
function displayTime(value: string | null): string { return value ? new Intl.DateTimeFormat('zh-CN', { dateStyle: 'short', timeStyle: 'short' }).format(new Date(value)) : '—' }
function toLocalDate(value: string | null): string {
  if (!value) return ''
  const date = new Date(value)
  const offset = date.getTimezoneOffset() * 60_000
  return new Date(date.getTime() - offset).toISOString().slice(0, 16)
}
</script>

<template>
  <section class="lark-admin" aria-labelledby="lark-admin-title">
    <section class="hero panel">
      <span><ShieldCheck :size="23" /></span>
      <div><p>Team collaboration provider</p><h2 id="lark-admin-title">飞书与团队通知</h2><small>连接、成员身份和通知投递都绑定当前 Team，并保留强版本与审计回执。</small></div>
      <StatusBadge :tone="online ? 'success' : 'warning'">{{ online ? '安全管理可用' : '离线只读' }}</StatusBadge>
    </section>

    <nav class="tabs panel" aria-label="飞书管理视图">
      <button v-for="item in ([['connection','连接与健康'],['mapping','成员映射'],['notification','通知中心']] as const)" :key="item[0]" type="button" :class="{ active: selectedTab === item[0] }" @click="emit('tab', item[0])">{{ item[1] }}</button>
    </nav>

    <StatePanel v-if="command.phase === 'conflict'" compact state="conflict" title="命令使用的版本已过期" :description="`服务端当前版本 v${command.error?.currentVersion ?? '未知'}，已停止自动重放。`" @retry="emit('refresh')" />
    <StatePanel v-else-if="command.phase === 'error'" compact :state="command.error?.kind === 'forbidden' ? 'forbidden' : command.error?.kind === 'offline' ? 'offline' : 'error'" :description="command.error?.message" />
    <div v-else-if="command.phase === 'success' && command.receipt" class="receipt" role="status"><CheckCircle2 :size="15" /><span><strong>命令已受理</strong><small class="mono">Receipt {{ shortId(command.receipt.commandId) }} · Correlation {{ shortId('correlationId' in command.receipt ? command.receipt.correlationId : null) }}</small></span></div>

    <template v-if="selectedTab === 'connection'">
      <div class="connection-grid">
        <section class="panel connection-list" aria-label="飞书连接列表">
          <header><div><p>Credential boundary</p><h3>Team Connection</h3></div><BaseButton size="small" :disabled="!canMutate || connections.some(item => item.status === 'ACTIVE')" @click="openCredentialDialog('create')"><KeyRound :size="13" />创建连接</BaseButton></header>
          <StatePanel v-if="phase === 'loading'" state="loading" title="正在加载飞书连接" />
          <StatePanel v-else-if="error?.kind === 'forbidden'" state="forbidden" title="无权管理 Provider" />
          <StatePanel v-else-if="!online && connections.length === 0" state="offline" />
          <StatePanel v-else-if="initialFailure" state="error" :description="error?.message" @retry="emit('refresh')" />
          <StatePanel v-else-if="connections.length === 0" state="empty" title="尚未连接飞书" description="使用 Team 的 App 凭证创建一条单向 Secret 连接。" />
          <button v-for="item in connections" v-else :key="item.connectionId" class="connection-row" type="button" :class="{ selected: item.connectionId === selectedConnection?.connectionId }" @click="emit('selectConnection', item.connectionId)">
            <span><strong>{{ item.maskedAppId }}</strong><small class="mono">{{ shortId(item.connectionId) }}</small></span><StatusBadge :tone="connectionTone(item.status)">{{ item.status }}</StatusBadge><small>v{{ item.version }}</small>
          </button>
        </section>

        <section class="panel connection-detail" aria-label="飞书连接详情">
          <StatePanel v-if="!selectedConnection" state="empty" title="选择一条 Connection" description="查看公开元数据、预检和实时安全健康。" />
          <template v-else>
            <header><div><p>Public metadata only</p><h3>{{ selectedConnection.maskedAppId }}</h3></div><StatusBadge :tone="connectionTone(selectedConnection.status)">{{ selectedConnection.status }}</StatusBadge></header>
            <dl><div><dt>Binding</dt><dd class="mono">{{ selectedConnection.providerBindingId ?? '未创建' }}</dd></div><div><dt>Binding 强版本</dt><dd class="mono">{{ selectedConnection.providerBindingVersion == null ? '—' : `v${selectedConnection.providerBindingVersion}` }}</dd></div><div><dt>Credential</dt><dd>{{ selectedConnection.credentialStatus }}</dd></div><div><dt>Credential 强版本</dt><dd class="mono">v{{ selectedConnection.version }}</dd></div><div><dt>过期时间</dt><dd>{{ displayTime(selectedConnection.expiresAt) }}</dd></div><div><dt>更新时间</dt><dd>{{ displayTime(selectedConnection.updatedAt) }}</dd></div></dl>
            <section v-if="health" class="health"><span><ShieldCheck :size="18" /></span><div><strong>安全健康</strong><small>{{ health.evidenceCode }} · {{ displayTime(health.checkedAt) }}</small></div><StatusBadge :tone="healthTone(health.status)">{{ health.status }}</StatusBadge></section>
            <footer>
              <BaseButton variant="secondary" size="small" :disabled="!canMutate || !selectedConnection.providerBindingId || selectedConnection.providerBindingVersion == null" @click="selectedConnection.providerBindingId && selectedConnection.providerBindingVersion != null && emit('preflight', selectedConnection.providerBindingId, selectedConnection.providerBindingVersion)"><Link2 :size="13" />Preflight</BaseButton>
              <BaseButton variant="secondary" size="small" :disabled="!online || !selectedConnection.providerBindingId" @click="selectedConnection.providerBindingId && emit('health', selectedConnection.providerBindingId)"><RefreshCw :size="13" />健康检查</BaseButton>
              <BaseButton variant="secondary" size="small" :disabled="!canMutate || selectedConnection.status !== 'ACTIVE'" @click="openCredentialDialog('rotate')"><RotateCw :size="13" />轮换凭证</BaseButton>
            </footer>
            <form v-if="selectedConnection.status === 'ACTIVE'" class="danger-zone" @submit.prevent="emit('revokeConnection', selectedConnection.connectionId, revokeReason, newKey())"><label>撤销原因<input v-model.trim="revokeReason" maxlength="500" autocomplete="off" required></label><BaseButton variant="danger" size="small" type="submit" :disabled="!canMutate || !revokeReason"><Unplug :size="13" />撤销</BaseButton></form>
          </template>
        </section>
      </div>
    </template>

    <template v-else-if="selectedTab === 'mapping'">
      <section class="mapping-flow panel">
        <header><div><p>Exact identity proof</p><h3>精确成员映射</h3></div><StatusBadge tone="neutral">open_id 不留存于浏览器状态</StatusBadge></header>
        <div class="mapping-steps">
          <label><span>1 · CrewScope 成员</span><select v-model="mappingMemberId"><option value="">请选择成员</option><option v-for="member in members" :key="member.id" :value="member.id">{{ member.userPrincipalId === members.find(item => item.id === currentMemberId)?.userPrincipalId ? '当前成员 · ' : '' }}{{ shortId(member.id) }}</option></select></label>
          <form @submit.prevent="verify"><label><span>2 · 精确飞书 open_id</span><input v-model="openId" type="password" maxlength="200" autocomplete="off" placeholder="仅用于本次验证" required></label><BaseButton size="small" type="submit" :disabled="!canMutate || !activeBinding || selectedConnection?.providerBindingVersion == null || !mappingMemberId || !openId.trim()"><UserCheck :size="13" />验证身份</BaseButton></form>
          <div class="proof"><span>3 · Proof Receipt</span><strong v-if="verified" class="mono">{{ shortId(verified.proofId) }}</strong><small v-else>验证通过后生成一次性安全坐标</small><BaseButton size="small" :disabled="!canMutate || !verified" @click="confirm"><UsersRound :size="13" />确认映射</BaseButton></div>
        </div>
      </section>
      <section class="panel table-panel" aria-label="成员映射历史">
        <header><div><p>Team directory</p><h3>成员映射</h3></div><form @submit.prevent="applyMappingFilter"><select v-model="localMappingStatus" aria-label="成员映射状态"><option value="">全部状态</option><option v-for="status in larkMappingStatuses" :key="status" :value="status">{{ status }}</option></select><BaseButton variant="secondary" size="small" type="submit">筛选</BaseButton></form></header>
        <StatePanel v-if="mappingPhase === 'loading'" state="loading" />
        <StatePanel v-else-if="mappingError?.kind === 'forbidden'" state="forbidden" />
        <StatePanel v-else-if="mappingInitialFailure" state="error" :description="mappingError?.message" @retry="applyMappingFilter" />
        <StatePanel v-else-if="mappings.length === 0" state="empty" title="当前筛选没有成员映射" />
        <template v-else><StatePanel v-if="!online" compact state="offline" title="正在展示最近同步的成员映射" /><StatePanel v-else-if="mappingError?.kind === 'cursor-expired'" compact state="error" title="成员映射 Cursor 已过期" description="已加载映射保持可读，刷新首屏后可继续。" @retry="applyMappingFilter" /><StatePanel v-else-if="mappingError" compact state="error" :description="mappingError.message" @retry="applyMappingFilter" /><table><thead><tr><th>成员</th><th>Binding</th><th>状态</th><th>验证时间</th><th>版本</th><th></th></tr></thead><tbody><tr v-for="item in mappings" :key="item.mappingId"><td class="mono" data-label="成员">{{ shortId(item.memberId) }}</td><td class="mono" data-label="Binding">{{ shortId(item.providerBindingId) }}</td><td data-label="状态"><StatusBadge :tone="mappingTone(item.status)">{{ item.status }}</StatusBadge><small v-if="item.terminalReason">{{ item.terminalReason }}</small></td><td data-label="验证时间">{{ displayTime(item.verifiedAt) }}</td><td class="mono" data-label="版本">v{{ item.version }}</td><td data-label="操作"><BaseButton v-if="item.status === 'ACTIVE'" variant="ghost" size="small" :disabled="!canMutate" @click="emit('revokeMapping', item.mappingId, item.version, 'ADMIN_REVOKED', newKey())">撤销</BaseButton></td></tr></tbody></table><footer v-if="mappingNextCursor"><BaseButton variant="secondary" size="small" :loading="mappingLoadingMore" :disabled="!online" @click="emit('loadMoreMappings')">加载更多</BaseButton></footer></template>
      </section>
    </template>

    <template v-else>
      <div class="notification-grid">
        <section class="panel preference">
          <header><div><p>Fixed templates only</p><h3>我的通知偏好</h3></div><BellRing :size="19" /></header>
          <StatePanel v-if="!currentMemberId" compact state="empty" title="当前 Principal 尚未匹配 Team Member" />
          <form v-else @submit.prevent="savePreference">
            <label class="switch"><input v-model="preferenceForm.enabled" type="checkbox"><span>启用飞书通知</span></label>
            <fieldset><legend>通知类型</legend><label v-for="type in inboxItemTypes" :key="type"><input type="checkbox" :checked="preferenceForm.enabledItemTypes.includes(type)" @change="toggleItemType(type)">{{ type }}</label></fieldset>
            <label>DND 至<input v-model="preferenceForm.mutedUntil" type="datetime-local"></label>
            <p>固定模板 {{ templates.filter(item => item.status === 'PUBLISHED').length }} 个；DND 期间新通知保持在 CrewScope Inbox。</p>
            <BaseButton size="small" type="submit" :disabled="!canMutate || preferenceForm.enabledItemTypes.length === 0">保存偏好</BaseButton>
          </form>
        </section>
        <section class="panel delivery-list" aria-label="通知投递历史">
          <header><div><p>Receipt & recovery</p><h3>通知投递历史</h3></div></header>
          <form class="delivery-filter" @submit.prevent="applyDeliveryFilter"><select v-model="deliveryFilter.status" aria-label="投递状态"><option value="">全部状态</option><option v-for="status in notificationDeliveryStatuses" :key="status" :value="status">{{ status }}</option></select><select v-model="deliveryFilter.itemType" aria-label="通知类型"><option value="">全部类型</option><option v-for="type in inboxItemTypes" :key="type" :value="type">{{ type }}</option></select><input v-model.trim="deliveryFilter.recipient" aria-label="Recipient Member UUID" placeholder="Recipient Member UUID" autocomplete="off"><BaseButton variant="secondary" size="small" type="submit">筛选</BaseButton></form>
          <StatePanel v-if="deliveryPhase === 'loading'" state="loading" />
          <StatePanel v-else-if="deliveryError?.kind === 'forbidden'" state="forbidden" />
          <StatePanel v-else-if="deliveryInitialFailure" state="error" :description="deliveryError?.message" @retry="applyDeliveryFilter" />
          <StatePanel v-else-if="deliveries.length === 0" state="empty" title="当前筛选没有投递事实" />
          <template v-else><StatePanel v-if="!online" compact state="offline" title="正在展示最近同步的通知投递" /><StatePanel v-else-if="deliveryError?.kind === 'cursor-expired'" compact state="error" title="通知投递 Cursor 已过期" description="已加载投递事实保持可读，刷新首屏后可继续。" @retry="applyDeliveryFilter" /><StatePanel v-else-if="deliveryError" compact state="error" :description="deliveryError.message" @retry="applyDeliveryFilter" /><button v-for="item in deliveries" :key="item.deliveryId" class="delivery-row" type="button" :class="{ selected: item.deliveryId === selectedDelivery?.deliveryId }" @click="emit('selectDelivery', item.deliveryId)"><span><strong>{{ item.itemType }}</strong><small>{{ displayTime(item.updatedAt) }} · 尝试 {{ item.attemptCount }} 次</small></span><StatusBadge :tone="deliveryTone(item.status)">{{ item.status }}</StatusBadge><span class="mono">{{ shortId(item.deliveryId) }}</span></button><footer v-if="deliveryNextCursor"><BaseButton variant="secondary" size="small" :loading="deliveryLoadingMore" :disabled="!online" @click="emit('loadMoreDeliveries')">加载更多</BaseButton></footer></template>
        </section>
        <aside v-if="selectedDelivery" class="panel delivery-detail" aria-label="通知投递详情"><header><div><p>Safe delivery fact</p><h3>投递详情</h3></div><button type="button" aria-label="关闭投递详情" @click="emit('closeDelivery')"><X :size="17" /></button></header><div class="delivery-result"><StatusBadge :tone="deliveryTone(selectedDelivery.status)">{{ selectedDelivery.status }}</StatusBadge><strong>{{ selectedDelivery.itemType }}</strong><small>{{ selectedDelivery.evidenceCode ?? '暂无终态证据' }}</small></div><dl><div><dt>Delivery</dt><dd class="mono">{{ selectedDelivery.deliveryId }}</dd></div><div><dt>Recipient</dt><dd class="mono">{{ selectedDelivery.recipientMemberId }}</dd></div><div><dt>Binding</dt><dd class="mono">{{ selectedDelivery.providerBindingId }}</dd></div><div><dt>Template</dt><dd class="mono">{{ shortId(selectedDelivery.template.templateId) }} · v{{ selectedDelivery.template.version }}</dd></div><div><dt>Failure</dt><dd>{{ selectedDelivery.failureCode ?? '—' }}</dd></div><div><dt>Redelivery of</dt><dd class="mono">{{ selectedDelivery.redeliveryOf ?? '—' }}</dd></div><div><dt>版本</dt><dd class="mono">v{{ selectedDelivery.version }}</dd></div></dl><footer><BaseButton :disabled="!canMutate || !failedDelivery" @click="emit('redeliver', selectedDelivery.deliveryId, newKey())"><Send :size="13" />再次投递</BaseButton><small v-if="!failedDelivery">只有 FAILED_FINAL 投递可由管理员显式重投。</small></footer></aside>
      </div>
    </template>

    <div v-if="credentialDialog" class="dialog-backdrop" @mousedown.self="closeCredentialDialog"><section ref="dialogRoot" role="dialog" aria-modal="true" aria-labelledby="credential-title" class="credential-dialog panel" @keydown="handleDialogKeydown"><header><div><p>One-way secret input</p><h2 id="credential-title" ref="dialogHeading" tabindex="-1">{{ credentialDialog === 'create' ? '创建飞书连接' : '轮换飞书凭证' }}</h2></div><button type="button" aria-label="关闭凭证对话框" @click="closeCredentialDialog"><X :size="17" /></button></header><form @submit.prevent="submitCredential"><label v-if="credentialDialog === 'create'">Tenant Key<input v-model="createForm.tenantKey" type="password" maxlength="200" autocomplete="off" required></label><label>App ID<input v-if="credentialDialog === 'create'" v-model="createForm.appId" type="password" maxlength="200" autocomplete="off" required><input v-else v-model="rotateForm.appId" type="password" maxlength="200" autocomplete="off" required></label><label>App Secret<input v-if="credentialDialog === 'create'" v-model="createForm.appSecret" type="password" maxlength="1000" autocomplete="new-password" required><input v-else v-model="rotateForm.appSecret" type="password" maxlength="1000" autocomplete="new-password" required></label><label v-if="credentialDialog === 'create'">凭证过期时间（可选）<input v-model="createForm.expiresAt" type="datetime-local"></label><p><CircleOff :size="14" />凭证只进入本次 HTTPS 命令，不写入 Store、URL、日志或回执。</p><footer><BaseButton variant="ghost" @click="closeCredentialDialog">取消</BaseButton><BaseButton type="submit" :loading="command.phase === 'pending'" :disabled="!canMutate">确认提交</BaseButton></footer></form></section></div>
  </section>
</template>

<style scoped>
.lark-admin{display:grid;max-width:1280px;gap:13px;margin:0 auto}.hero{display:grid;grid-template-columns:44px minmax(0,1fr) auto;align-items:center;gap:13px;padding:16px 18px}.hero>span{display:grid;width:44px;height:44px;place-items:center;border-radius:13px;background:var(--cs-brand-100);color:var(--cs-brand-700)}.hero p,.panel header p,.credential-dialog header p{margin:0;color:var(--cs-brand-700);font-size:8px;font-weight:800;letter-spacing:.1em;text-transform:uppercase}.hero h2{margin:2px 0;font-size:17px}.hero small{color:var(--cs-text-muted);font-size:9px}.tabs{display:flex;padding:4px}.tabs button{min-height:36px;padding:0 14px;border-radius:8px;color:var(--cs-text-muted);font-size:10px;font-weight:750;cursor:pointer}.tabs button.active{background:var(--cs-brand-100);color:var(--cs-brand-800)}.receipt{display:flex;align-items:center;gap:8px;padding:10px 12px;border:1px solid #b9dfc5;border-radius:var(--cs-radius-sm);background:#f2fbf5;color:var(--cs-success)}.receipt span,.receipt strong,.receipt small{display:block}.receipt strong{font-size:9px}.receipt small{margin-top:2px;color:var(--cs-text-muted);font-size:7px}.connection-grid,.notification-grid{display:grid;grid-template-columns:minmax(300px,.72fr) minmax(0,1.28fr);align-items:start;gap:13px}.panel>header{display:flex;align-items:center;justify-content:space-between;gap:10px;padding:13px 14px;border-bottom:1px solid var(--cs-border)}.panel>header h3{margin:2px 0 0;font-size:14px}.connection-row,.delivery-row{display:grid;width:100%;grid-template-columns:minmax(0,1fr) auto auto;align-items:center;gap:10px;padding:12px 14px;border-bottom:1px solid var(--cs-border);text-align:left;cursor:pointer}.connection-row:hover,.connection-row.selected,.delivery-row:hover,.delivery-row.selected{background:#f2faf4}.connection-row span strong,.connection-row span small,.delivery-row span strong,.delivery-row span small{display:block}.connection-row span small,.delivery-row small{margin-top:3px;color:var(--cs-text-muted);font-size:7px}.connection-row>small{font-size:8px}.connection-detail>dl,.delivery-detail>dl{display:grid;grid-template-columns:1fr 1fr;gap:0 12px;margin:0;padding:7px 14px}.connection-detail dl div,.delivery-detail dl div{padding:9px 0;border-bottom:1px solid var(--cs-border)}dt{color:var(--cs-text-muted);font-size:7px;font-weight:750;text-transform:uppercase}dd{overflow-wrap:anywhere;margin:3px 0 0;font-size:9px}.health{display:grid;grid-template-columns:30px 1fr auto;align-items:center;gap:8px;margin:8px 14px;padding:10px;border:1px solid var(--cs-border);border-radius:9px;background:var(--cs-surface-subtle)}.health>span{display:grid;width:30px;height:30px;place-items:center;border-radius:8px;background:var(--cs-brand-100);color:var(--cs-brand-700)}.health strong,.health small{display:block}.health strong{font-size:9px}.health small{margin-top:2px;color:var(--cs-text-muted);font-size:7px}.connection-detail>footer{display:flex;flex-wrap:wrap;gap:7px;padding:12px 14px}.danger-zone{display:grid;grid-template-columns:1fr auto;align-items:end;gap:8px;padding:12px 14px;border-top:1px solid #edd0cc;background:#fff9f8}.danger-zone label,.credential-dialog label,.preference form>label{display:grid;gap:4px;color:var(--cs-text-muted);font-size:8px;font-weight:750}input,select{min-width:0;min-height:36px;padding:0 9px;border:1px solid var(--cs-border);border-radius:8px;background:#fff;color:var(--cs-text);font-size:10px}.mapping-flow>header{align-items:flex-start}.mapping-steps{display:grid;grid-template-columns:1fr 1.4fr 1fr;gap:12px;padding:14px}.mapping-steps label,.mapping-steps form,.proof{display:grid;align-content:start;gap:7px}.mapping-steps label>span,.proof>span{color:var(--cs-text-muted);font-size:8px;font-weight:750}.mapping-steps form{grid-template-columns:1fr auto;align-items:end}.mapping-steps form label{display:grid}.proof{padding:9px;border:1px solid var(--cs-border);border-radius:9px;background:var(--cs-surface-subtle)}.proof strong,.proof small{font-size:8px}.proof small{color:var(--cs-text-muted)}.table-panel>header form{display:flex;gap:7px}.table-panel table{width:100%;border-collapse:collapse}.table-panel th{padding:9px 12px;background:var(--cs-surface-subtle);color:var(--cs-text-muted);font-size:8px;text-align:left}.table-panel td{padding:11px 12px;border-top:1px solid var(--cs-border);font-size:9px}.table-panel td small{display:block;margin-top:3px;color:var(--cs-text-muted);font-size:7px}.table-panel>footer,.delivery-list>footer{display:flex;justify-content:center;padding:11px}.notification-grid{grid-template-columns:330px minmax(0,1fr)}.notification-grid:has(.delivery-detail){grid-template-columns:290px minmax(0,1fr) 340px}.preference form{display:grid;gap:12px;padding:14px}.switch{display:flex!important;align-items:center;gap:7px;color:var(--cs-text)!important;font-size:10px!important}.switch input,.preference fieldset input{min-height:0}.preference fieldset{display:grid;grid-template-columns:1fr 1fr;gap:7px;padding:10px;border:1px solid var(--cs-border);border-radius:9px}.preference legend{padding:0 5px;color:var(--cs-text-muted);font-size:8px;font-weight:750}.preference fieldset label{display:flex;align-items:center;gap:5px;font-size:8px}.preference form>p{margin:0;color:var(--cs-text-muted);font-size:8px}.preference form>:deep(.base-button){justify-self:start}.delivery-filter{display:grid;grid-template-columns:150px 135px minmax(160px,1fr) auto;gap:7px;padding:10px;border-bottom:1px solid var(--cs-border)}.delivery-row>span:last-child{font-size:7px}.delivery-detail{position:sticky;top:12px}.delivery-detail header button,.credential-dialog header button{display:grid;width:31px;height:31px;place-items:center;border-radius:8px;background:var(--cs-surface-subtle);cursor:pointer}.delivery-result{padding:14px;border-bottom:1px solid var(--cs-border)}.delivery-result strong,.delivery-result small{display:block}.delivery-result strong{margin-top:9px;font-size:13px}.delivery-result small{margin-top:3px;color:var(--cs-text-muted);font-size:8px}.delivery-detail>footer{display:grid;gap:6px;padding:12px 14px}.delivery-detail>footer small{color:var(--cs-text-muted);font-size:7px}.dialog-backdrop{position:fixed;inset:0;z-index:100;display:grid;place-items:center;padding:18px;background:rgb(17 28 22 / 48%)}.credential-dialog{width:min(460px,100%);box-shadow:0 22px 65px rgb(11 23 16 / 25%)}.credential-dialog form{display:grid;gap:12px;padding:15px}.credential-dialog form>p{display:flex;gap:6px;margin:0;padding:9px;border-radius:8px;background:var(--cs-brand-100);color:var(--cs-brand-800);font-size:8px}.credential-dialog footer{display:flex;justify-content:flex-end;gap:7px;padding-top:4px}
.notification-grid:has(.delivery-detail) .delivery-filter{grid-template-columns:1fr 1fr}.notification-grid:has(.delivery-detail) .delivery-filter input{grid-column:1/-1}
@media(max-width:1050px){.notification-grid:has(.delivery-detail){grid-template-columns:280px minmax(0,1fr)}.delivery-detail{position:static;grid-column:1/-1;grid-row:1}.mapping-steps{grid-template-columns:1fr 1fr}.proof{grid-column:1/-1}}
@media(max-width:780px){.connection-grid,.notification-grid,.notification-grid:has(.delivery-detail){grid-template-columns:1fr}.connection-detail,.delivery-detail{grid-row:1}.delivery-filter{grid-template-columns:1fr 1fr}.mapping-steps{grid-template-columns:1fr}.mapping-steps form{grid-template-columns:1fr}.proof{grid-column:auto}.table-panel thead{position:absolute;overflow:hidden;width:1px;height:1px;clip:rect(0 0 0 0)}.table-panel tbody,.table-panel tr,.table-panel td{display:block}.table-panel tr{padding:9px 12px;border-top:1px solid var(--cs-border)}.table-panel td{display:grid;grid-template-columns:95px 1fr;padding:4px 0;border:0}.table-panel td:before{content:attr(data-label);color:var(--cs-text-muted);font-size:7px;font-weight:750}.hero{grid-template-columns:40px 1fr}.hero>:last-child{grid-column:1/-1;justify-self:start}}
@media(max-width:480px){.tabs{display:grid;grid-template-columns:1fr}.delivery-filter,.notification-grid:has(.delivery-detail) .delivery-filter{grid-template-columns:1fr}.connection-detail>dl,.delivery-detail>dl{grid-template-columns:1fr}.danger-zone{grid-template-columns:1fr}.panel>header{align-items:flex-start}.table-panel>header{flex-direction:column}.table-panel>header form{width:100%}.table-panel>header form select{flex:1}.preference fieldset{grid-template-columns:1fr}}
</style>
