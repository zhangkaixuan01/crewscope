<script setup lang="ts">
import { RefreshCw } from '@lucide/vue'
import { computed, inject, onBeforeUnmount, watch } from 'vue'
import { usePageRequestScope } from '../composables/usePageRequestScope'
import { useRoute, useRouter, type LocationQueryRaw } from 'vue-router'
import { enumQuery, oneOf, uuidQuery } from '../utils/queryParams'
import { AUTH_PRINCIPAL } from '../app/auth'
import { useNetworkStatus } from '../app/network'
import BaseButton from '../components/base/BaseButton.vue'
import LarkNotificationAdmin from '../components/domain/LarkNotificationAdmin.vue'
import StatePanel from '../components/feedback/StatePanel.vue'
import SettingsShell from '../components/settings/SettingsShell.vue'
import { useScopeStore } from '../domains/scope/store'
import { useTeamOpsStore } from '../domains/teamops/store'
import {
  inboxItemTypes, larkMappingStatuses, notificationDeliveryStatuses,
  type InboxItemType, type LarkMappingStatus, type NotificationDeliveryStatus,
  type TeamOpsScope,
} from '../domains/teamops/types'

const route = useRoute()
const router = useRouter()
const principal = inject(AUTH_PRINCIPAL)
const scopeStore = useScopeStore()
const store = useTeamOpsStore()
const online = useNetworkStatus()
const scope = computed<TeamOpsScope | null>(() => principal && scopeStore.state.selectedTeamId
  ? { organizationId: principal.organizationId, teamId: scopeStore.state.selectedTeamId }
  : null)
const tab = computed(() => oneOf(route.query.tab, ['connection', 'mapping', 'notification'] as const, 'connection'))
const connectionId = computed(() => uuidQuery(route.query.connection))
const mappingStatus = computed(() => enumQuery(route.query.mappingStatus, larkMappingStatuses))
const deliveryStatus = computed(() => enumQuery(route.query.deliveryStatus, notificationDeliveryStatuses))
const deliveryType = computed(() => enumQuery(route.query.deliveryType, inboxItemTypes))
const recipient = computed(() => uuidQuery(route.query.recipient))
const deliveryId = computed(() => uuidQuery(route.query.delivery))
const connections = computed(() => store.state.larkConnections.value ?? [])
const selectedConnection = computed(() => {
  const detail = connectionId.value ? store.state.larkConnectionDetails[connectionId.value]?.value?.value : null
  return detail ?? connections.value.find(item => item.connectionId === connectionId.value) ?? null
})
const activeBinding = computed(() => selectedConnection.value?.providerBindingId ?? null)
const health = computed(() => activeBinding.value ? store.state.larkHealth[activeBinding.value]?.value ?? null : null)
const currentMemberId = computed(() => scopeStore.state.members.find(member => member.userPrincipalId === principal?.id)?.id ?? null)
const preference = computed(() => currentMemberId.value ? store.state.notificationPreferences[currentMemberId.value]?.value?.value ?? null : null)
const selectedDelivery = computed(() => deliveryId.value ? store.state.notificationDeliveryDetails[deliveryId.value]?.value?.value ?? null : null)
const deliveryFilter = computed(() => ({
  statuses: deliveryStatus.value ? [deliveryStatus.value] : undefined,
  itemTypes: deliveryType.value ? [deliveryType.value] : undefined,
  recipientMemberId: recipient.value,
}))
// Both faces ride the shared composable (M12b-F05): the phase term reloads on the
// restoring→ready transition, and the connection-query whitelist keeps tab/filter query
// writes from voiding in-flight command continuations.
const pageRequests = usePageRequestScope(() => scopeStore.state.phase, () => connectionId.value)
onBeforeUnmount(() => { store.clearCommand() })

// Invalidation itself is the composable's sync watch; this callback only restarts the loads.
watch(
  pageRequests.coordinate,
  async () => {
    store.clearCommand()
    if (scopeStore.state.phase !== 'ready' || !scope.value) return
    store.activateScope(scope.value)
    const owner = pageRequests.capture()
    await scopeStore.loadMembers()
    if (!owner.isCurrent()) return
    await loadAll(true)
  },
  { immediate: true, flush: 'sync' },
)

watch(connectionId, async id => {
  store.clearCommand()
  if (id && scope.value) await store.loadLarkConnection(id)
}, { flush: 'sync' })

watch(deliveryId, async id => {
  if (id && scope.value) await store.loadNotificationDelivery(id)
})

watch(currentMemberId, async id => {
  if (id && scope.value) await store.loadNotificationPreference(id)
})

watch(() => [mappingStatus.value, deliveryStatus.value, deliveryType.value, recipient.value] as const, async () => {
  if (!scope.value) return
  await Promise.all([
    store.loadLarkMappings(mappingStatus.value, false, true),
    store.loadNotificationDeliveries(deliveryFilter.value, false, true),
  ])
})

async function loadAll(force = false): Promise<void> {
  if (!scope.value || scopeStore.state.phase !== 'ready') return
  const request = pageRequests.begin('load-all')
  const selection = pageRequests.captureSelection()
  try {
    await Promise.all([
      store.loadLarkConnections(force), store.loadLarkMappings(mappingStatus.value, false, force),
      store.loadNotificationTemplates(force), store.loadNotificationDeliveries(deliveryFilter.value, false, force),
      currentMemberId.value ? store.loadNotificationPreference(currentMemberId.value, force) : Promise.resolve(),
    ])
    if (!request.isCurrent() || !selection.isCurrent()) return
    await restoreConnection()
    if (!request.isCurrent()) return
    if (deliveryId.value) await store.loadNotificationDelivery(deliveryId.value, force)
  } finally { request.finish() }
}

async function restoreConnection(): Promise<void> {
  const owner = pageRequests.capture()
  const requested = connectionId.value
  const selected = connections.value.find(item => item.connectionId === requested) ?? connections.value[0] ?? null
  if (!selected) return
  if (selected.connectionId !== requested) await patchQuery({ connection: selected.connectionId })
  if (!owner.isCurrent() || connectionId.value !== selected.connectionId) return
  const selection = pageRequests.captureSelection()
  await store.loadLarkConnection(selected.connectionId)
  if (!selection.isCurrent()) return
  if (selected.providerBindingId) await store.loadLarkHealth(selected.providerBindingId)
}

async function selectConnection(id: string): Promise<void> {
  const owner = pageRequests.capture()
  store.clearCommand()
  await patchQuery({ connection: id })
  if (!owner.isCurrent() || connectionId.value !== id) return
  const selection = pageRequests.captureSelection()
  await store.loadLarkConnection(id, true)
  if (!selection.isCurrent()) return
  const connection = store.state.larkConnectionDetails[id]?.value?.value
  if (connection?.providerBindingId) await store.loadLarkHealth(connection.providerBindingId, true)
}

async function createConnection(input: { tenantKey: string, appId: string, appSecret: string, expiresAt: string | null }, key: string): Promise<void> {
  const owner = pageRequests.captureSelection()
  const before = new Set(connections.value.map(item => item.connectionId))
  const success = await store.createLarkConnection(0, input, key)
  if (!owner.isCurrent()) return
  if (!success) return refreshConflict()
  await store.loadLarkConnections(true)
  if (!owner.isCurrent()) return
  const created = connections.value.find(item => !before.has(item.connectionId))
  if (created) await selectConnection(created.connectionId)
}

async function rotateConnection(id: string, input: { appId: string, appSecret: string }, key: string, expectedVersion: number): Promise<void> {
  if (connectionId.value !== id || selectedConnection.value?.version !== expectedVersion) return
  const owner = pageRequests.captureSelection()
  const success = await store.rotateLarkConnection(id, input, key, expectedVersion)
  if (!owner.isCurrent()) return
  if (!success) return refreshConflict(id)
  await Promise.all([store.loadLarkConnections(true), store.loadLarkConnection(id, true)])
}

async function revokeConnection(id: string, reason: string, key: string): Promise<void> {
  const owner = pageRequests.captureSelection()
  const success = await store.revokeLarkConnection(id, reason, key)
  if (!owner.isCurrent()) return
  if (!success) return refreshConflict(id)
  await Promise.all([store.loadLarkConnections(true), store.loadLarkConnection(id, true)])
}

async function verifyMember(bindingId: string, version: number, memberId: string, openId: string, key: string, complete: (proofId: string | null) => void): Promise<void> {
  const owner = pageRequests.captureSelection()
  const selected = connectionId.value
  if (activeBinding.value !== bindingId || selectedConnection.value?.providerBindingVersion !== version
    || !scopeStore.state.members.some(member => member.id === memberId && member.status === 'ACTIVE')) { complete(null); return }
  const success = await store.verifyLarkMember(bindingId, version, openId, key)
  if (!owner.isCurrent()) { complete(null); return }
  const receipt = store.state.command.receipt
  complete(success && receipt && 'domainEventId' in receipt ? receipt.domainEventId : null)
  if (!success) await refreshConflict(selected ?? undefined)
}

async function confirmMapping(memberId: string, bindingId: string, proofId: string, key: string, complete: (committed: boolean) => void): Promise<void> {
  const owner = pageRequests.captureSelection()
  if (activeBinding.value !== bindingId) { complete(false); return }
  const success = await store.confirmLarkMapping({ memberId, providerBindingId: bindingId, proofId }, key)
  complete(success && owner.isCurrent())
  if (success && owner.isCurrent()) {
    await store.loadLarkMappings(mappingStatus.value, false, true)
  }
}

async function savePreference(memberId: string, input: { enabled: boolean, enabledItemTypes: InboxItemType[], mutedUntil: string | null }, key: string): Promise<void> {
  if (currentMemberId.value !== memberId) return
  const owner = pageRequests.capture()
  const success = await store.updateNotificationPreference(memberId, input, key)
  if (!owner.isCurrent() || currentMemberId.value !== memberId) return
  if (!success) return refreshPreferenceConflict(memberId)
  await store.loadNotificationPreference(memberId, true)
}

async function redeliver(id: string, key: string): Promise<void> {
  if (deliveryId.value !== id) return
  const owner = pageRequests.capture()
  const success = await store.redeliverNotification(id, key)
  if (!owner.isCurrent() || deliveryId.value !== id) return
  if (!success) return refreshDeliveryConflict(id)
  await Promise.all([
    store.loadNotificationDeliveries(deliveryFilter.value, false, true),
    store.loadNotificationDelivery(id, true),
  ])
}

async function revokeMapping(id: string, version: number, reason: string, key: string): Promise<void> {
  const owner = pageRequests.capture()
  const success = await store.revokeLarkMapping(id, version, reason, key)
  if (success && owner.isCurrent()) await store.loadLarkMappings(mappingStatus.value, false, true)
}

async function refreshConflict(id?: string): Promise<void> {
  if (store.state.command.phase !== 'conflict') return
  const owner = pageRequests.captureSelection()
  await store.loadLarkConnections(true)
  if (!owner.isCurrent()) return
  if (id) await store.loadLarkConnection(id, true)
}

async function readOriginal(id: string | null): Promise<void> {
  const owner = pageRequests.captureSelection()
  if (id && id !== connectionId.value) return
  await Promise.all([store.loadLarkConnections(true), id ? store.loadLarkConnection(id, true) : Promise.resolve()])
  if (!owner.isCurrent()) return
  await store.loadLarkMappings(mappingStatus.value, false, true)
}
async function refreshPreferenceConflict(id: string): Promise<void> {
  if (store.state.command.phase === 'conflict') await store.loadNotificationPreference(id, true)
}
async function refreshDeliveryConflict(id: string): Promise<void> {
  if (store.state.command.phase === 'conflict') await store.loadNotificationDelivery(id, true)
}

function setTab(value: 'connection' | 'mapping' | 'notification'): void { void patchQuery({ tab: value }) }
function setMappingFilter(status: LarkMappingStatus | null): void { void patchQuery({ mappingStatus: status, mapping: null }) }
function setDeliveryFilter(value: { status: NotificationDeliveryStatus | null, itemType: InboxItemType | null, recipient: string }): void {
  void patchQuery({ deliveryStatus: value.status, deliveryType: value.itemType, recipient: value.recipient || null, delivery: null })
}
function selectDelivery(id: string): void { void patchQuery({ delivery: id }) }
function closeDelivery(): void { void patchQuery({ delivery: null }) }

function patchQuery(values: Record<string, string | null>): Promise<unknown> {
  const query: LocationQueryRaw = { ...route.query }
  Object.entries(values).forEach(([key, value]) => {
    if (value) query[key] = value
    else delete query[key]
  })
  return router.replace({ query })
}

</script>

<template>
  <SettingsShell title="飞书与通知" eyebrow="设置 · 团队集成">
    <template #actions><BaseButton variant="secondary" size="small" :disabled="!scope || !online" :aria-describedby="scope ? undefined : 'lark-settings-scope-reason'" @click="loadAll(true)"><RefreshCw :size="14" />刷新</BaseButton></template>
    <StatePanel v-if="scopeStore.state.phase === 'loading'" state="loading" title="正在恢复 Team Scope" />
    <StatePanel v-else-if="!scope" id="lark-settings-scope-reason" state="empty" title="请选择 Team" description="飞书连接和通知配置始终属于明确的 Organization 与 Team。" />
    <LarkNotificationAdmin
      v-else
      :key="pageRequests.coordinate()"
      :phase="store.state.larkConnections.phase" :error="store.state.larkConnections.error" :connections="connections"
      :selected-connection="selectedConnection" :health="health" :mappings="store.state.larkMappings.value ?? []"
      :mapping-phase="store.state.larkMappings.phase" :mapping-error="store.state.larkMappings.error"
      :mapping-next-cursor="store.state.larkMappings.nextCursor" :mapping-loading-more="store.state.larkMappings.loadingMore"
      :members="scopeStore.state.members" :current-member-id="currentMemberId" :templates="store.state.notificationTemplates.value ?? []"
      :preference="preference" :deliveries="store.state.notificationDeliveries.value ?? []"
      :delivery-phase="store.state.notificationDeliveries.phase" :delivery-error="store.state.notificationDeliveries.error"
      :delivery-next-cursor="store.state.notificationDeliveries.nextCursor" :delivery-loading-more="store.state.notificationDeliveries.loadingMore"
      :selected-delivery="selectedDelivery" :command="store.state.command" :online="online" :selected-tab="tab"
      :mapping-status="mappingStatus" :delivery-status="deliveryStatus" :delivery-type="deliveryType" :recipient="recipient ?? ''"
      @tab="setTab" @refresh="loadAll(true)" @read-original="readOriginal" @select-connection="selectConnection" @create-connection="createConnection"
      @rotate-connection="rotateConnection" @revoke-connection="revokeConnection"
      @preflight="(id, version) => store.loadLarkPreflight(id, version, true)" @health="id => store.loadLarkHealth(id, true)"
      @verify-member="verifyMember" @confirm-mapping="confirmMapping" @mapping-filter="setMappingFilter"
      @load-more-mappings="store.loadLarkMappings(mappingStatus, true)" @revoke-mapping="revokeMapping"
      @save-preference="savePreference" @delivery-filter="setDeliveryFilter"
      @load-more-deliveries="store.loadNotificationDeliveries(deliveryFilter, true)" @select-delivery="selectDelivery"
      @close-delivery="closeDelivery" @redeliver="redeliver" @clear-command="store.clearCommand"
    />
  </SettingsShell>
</template>
