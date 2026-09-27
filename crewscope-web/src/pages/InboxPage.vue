<script setup lang="ts">
import { secureId } from '../api/secureId'
import { RefreshCw } from '@lucide/vue'
import { computed, inject, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { AUTH_PRINCIPAL } from '../app/auth'
import { useNetworkStatus } from '../app/network'
import BaseButton from '../components/base/BaseButton.vue'
import InboxWorkspace from '../components/domain/InboxWorkspace.vue'
import StatePanel from '../components/feedback/StatePanel.vue'
import AppShell from '../components/layout/AppShell.vue'
import { useScopeStore } from '../domains/scope/store'
import { useTeamOpsStore } from '../domains/teamops/store'
import {
  inboxDispositionStatuses,
  inboxItemTypes,
  inboxSourceStatuses,
  type InboxBatchEntryResult,
  type InboxBatchOutcome,
  type InboxBatchReport,
  type InboxBatchStatus,
  type InboxDispositionStatus,
  type InboxFilter,
  type InboxItemType,
  type InboxSourceStatus,
  type TeamOpsScope,
} from '../domains/teamops/types'
import { usePageRequestScope } from '../composables/usePageRequestScope'

const pageRequests = usePageRequestScope()

const route = useRoute()
const router = useRouter()
const principal = inject(AUTH_PRINCIPAL)
const scopeStore = useScopeStore()
const store = useTeamOpsStore()
const online = useNetworkStatus()
const commandAttempt = ref<{ itemId: string, status: InboxDispositionStatus, key: string } | null>(null)
/** One batch's own frozen report; switching filters never rewrites it (contract §4.5). */
const batchReport = ref<InboxBatchReport | null>(null)

const scope = computed<TeamOpsScope | null>(() => principal && scopeStore.state.selectedTeamId
  ? { organizationId: principal.organizationId, teamId: scopeStore.state.selectedTeamId }
  : null)
type InboxView = InboxItemType | 'ALL'
/** Scope 恢复完成后仍没有选中 Team，才对「刷新」的禁用态给出原因，加载期间的短暂禁用不算。 */
const scopeMissing = computed(() => scopeStore.state.phase === 'ready' && !scope.value)
const itemType = computed<InboxView>(() => oneOf(route.query.inboxType, ['ALL', ...inboxItemTypes] as const, 'ALL'))
const sourceStatus = computed<InboxSourceStatus>(() => oneOf(route.query.sourceStatus, inboxSourceStatuses, 'OPEN'))
const dispositionStatus = computed<InboxDispositionStatus | 'ALL'>(() => oneOf(route.query.disposition, ['ALL', ...inboxDispositionStatuses] as const, 'ALL'))
const selectedItemId = computed(() => uuidQuery(route.query.inboxItem))
const filter = computed<InboxFilter>(() => ({
  itemTypes: itemType.value === 'ALL' ? undefined : [itemType.value],
  sourceStatuses: [sourceStatus.value],
  dispositionStatuses: dispositionStatus.value === 'ALL' ? undefined : [dispositionStatus.value],
}))
const detailResource = computed(() => selectedItemId.value ? store.state.inboxDetails[selectedItemId.value] ?? null : null)
const targetResource = computed(() => selectedItemId.value ? store.state.inboxTargets[selectedItemId.value] ?? null : null)

// R25: the offline panel says how stale the readable Inbox facts are, not just that they are stale.
const lastSyncedAt = ref<string | null>(null)
watch(() => store.state.inbox.phase, phase => {
  if (phase === 'ready') lastSyncedAt.value = new Date().toISOString()
})

watch(
  () => [
    scopeStore.state.phase,
    scope.value?.organizationId,
    scope.value?.teamId,
    itemType.value,
    sourceStatus.value,
    dispositionStatus.value,
  ] as const,
  async ([phase]) => {
    const pageOwner = pageRequests.capture()
    if (phase !== 'ready' || !scope.value) return
    store.activateScope(scope.value)
    commandAttempt.value = null
    store.clearCommand()
    await Promise.all([
      store.loadInbox(filter.value, false, true),
      store.loadInboxCounts(true),
    ])
    if (!pageOwner.isCurrent()) return
  },
  { immediate: true },
)

watch(
  () => [scopeStore.state.phase, route.query.inboxType, route.query.sourceStatus, route.query.disposition, route.query.inboxItem] as const,
  ([phase]) => {
    if (phase !== 'ready') return
    const query = { ...route.query }
    let changed = false
    changed = normalizeQuery(query, 'inboxType', itemType.value, 'ALL') || changed
    changed = normalizeQuery(query, 'sourceStatus', sourceStatus.value, 'OPEN') || changed
    changed = normalizeQuery(query, 'disposition', dispositionStatus.value, 'ALL') || changed
    if (route.query.inboxItem != null && !selectedItemId.value) {
      delete query.inboxItem
      changed = true
    }
    if (changed) void router.replace({ query })
  },
  { immediate: true },
)

watch(
  () => [scopeStore.state.phase, scope.value?.teamId, selectedItemId.value] as const,
  ([phase, _teamId, itemId]) => {
    commandAttempt.value = null
    store.clearCommand()
    if (phase === 'ready' && scope.value && itemId) void store.loadInboxDetail(itemId)
  },
  { immediate: true },
)

function selectItem(itemId: string): void {
  void router.replace({ query: { ...route.query, inboxItem: itemId } })
}

function closeDetail(): void {
  const query = { ...route.query }
  delete query.inboxItem
  void router.replace({ query })
}

function changeType(value: InboxView): void {
  replaceFilter('inboxType', value, 'ALL')
}

function changeSourceStatus(value: InboxSourceStatus): void {
  replaceFilter('sourceStatus', value, 'OPEN')
}

function changeDispositionStatus(value: InboxDispositionStatus | 'ALL'): void {
  replaceFilter('disposition', value, 'ALL')
}

function replaceFilter(key: string, value: string, defaultValue: string): void {
  const query = { ...route.query }
  if (value === defaultValue) delete query[key]
  else query[key] = value
  delete query.inboxItem
  void router.replace({ query })
}

async function reload(): Promise<void> {
  const pageOwner = pageRequests.capture()
  if (!scope.value || !online.value) return
  await Promise.all([
    store.loadInbox(filter.value, false, true),
    store.loadInboxCounts(true),
  ])
  if (!pageOwner.isCurrent()) return
  if (selectedItemId.value) await store.loadInboxDetail(selectedItemId.value, true)
}

function retryDetail(itemId: string): void {
  void store.loadInboxDetail(itemId, true)
}

/** The source link failed to resolve; re-read the target without navigating (R25). */
function retryTarget(itemId: string): void {
  void store.loadInboxTarget(itemId, true)
}

async function openTarget(itemId: string): Promise<void> {
  const pageOwner = pageRequests.captureSelection()
  if (!online.value) return
  await store.loadInboxTarget(itemId, true)
  if (!pageOwner.isCurrent()) return
  const target = store.state.inboxTargets[itemId]?.value
  if (target) await router.push(target.href)
}

async function changeDisposition(itemId: string, status: InboxDispositionStatus): Promise<void> {
  const pageOwner = pageRequests.captureSelection()
  if (!online.value) return
  const current = commandAttempt.value
  if (!current || current.itemId !== itemId || current.status !== status) {
    commandAttempt.value = { itemId, status, key: secureId() }
  }
  const success = await store.changeInboxDisposition(itemId, status, commandAttempt.value!.key)
  if (!pageOwner.isCurrent()) return
  if (success) {
    commandAttempt.value = null
    await Promise.all([
      store.loadInbox(filter.value, false, true),
      store.loadInboxCounts(true),
      store.loadInboxDetail(itemId, true),
    ])
    if (!pageOwner.isCurrent()) return
    return
  }
  if (store.state.command.phase === 'conflict') {
    // A refreshed version represents a new command attempt and receives a new Idempotency-Key.
    commandAttempt.value = null
    await Promise.all([
      store.loadInbox(filter.value, false, true),
      store.loadInboxCounts(true),
      store.loadInboxDetail(itemId, true),
    ])
    if (!pageOwner.isCurrent()) return
  } else if (!store.state.command.error?.retryable) {
    commandAttempt.value = null
  }
}

function loadMore(): void {
  void store.loadInbox(filter.value, true)
}

/**
 * Reads the shared command slot only when it provably holds this item's own outcome; anything
 * else (a stale receipt, an aborted epoch) means the result was never observed — 「未知」 per
 * contract §4.5, confirmed before any retry rather than blindly resubmitted.
 */
function classifyBatchOutcome(itemId: string): InboxBatchEntryResult {
  const command = store.state.command
  if (command.operation !== 'inbox-disposition' || command.targetId !== itemId) {
    return { itemId, outcome: 'unknown', message: '命令结果未确认。' }
  }
  if (command.phase === 'success') return { itemId, outcome: 'success', message: null }
  const error = command.error
  if (command.phase === 'conflict') return { itemId, outcome: 'conflict', message: error?.message ?? null }
  if (error && (error.kind === 'forbidden' || error.status === 400 || error.status === 422)) {
    return { itemId, outcome: 'rejected', message: error.message }
  }
  return { itemId, outcome: 'unknown', message: error?.message ?? null }
}

async function batchDisposition(itemIds: string[], status: InboxBatchStatus): Promise<void> {
  const pageOwner = pageRequests.captureSelection()
  if (!online.value || itemIds.length === 0) return
  // Each item keeps its own idempotency key; one conflict cannot silently
  // replay or overwrite a neighbouring item's disposition. TeamOps intentionally
  // serializes commands, so execute the batch in order instead of racing them.
  batchReport.value = { status, results: [] }
  for (const itemId of itemIds) {
    await store.changeInboxDisposition(itemId, status, secureId())
    if (!pageOwner.isCurrent()) return
    batchReport.value = { status, results: [...batchReport.value.results, classifyBatchOutcome(itemId)] }
  }
  await Promise.all([store.loadInbox(filter.value, false, true), store.loadInboxCounts(true)])
  if (!pageOwner.isCurrent()) return
}

function normalizeQuery(query: Record<string, unknown>, key: string, value: string, defaultValue: string): boolean {
  const current = queryValue(query[key])
  const expected = value === defaultValue ? null : value
  if (current === expected) return false
  if (expected) query[key] = expected
  else delete query[key]
  return true
}

function oneOf<T extends string>(value: unknown, allowed: readonly T[], fallback: T): T {
  const parsed = queryValue(value)
  return parsed && allowed.includes(parsed as T) ? parsed as T : fallback
}

function uuidQuery(value: unknown): string | null {
  const parsed = queryValue(value)
  return parsed && /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(parsed) ? parsed : null
}

function queryValue(value: unknown): string | null {
  return typeof value === 'string' && value.length > 0 ? value : null
}
</script>

<template>
  <AppShell title="我的 Inbox" eyebrow="协作 · 成员队列">
    <template #actions><BaseButton variant="secondary" size="small" :disabled="!scope || !online" :aria-describedby="scopeMissing ? 'inbox-scope-reason' : undefined" @click="reload"><RefreshCw :size="14" />刷新</BaseButton></template>

    <StatePanel v-if="scopeMissing" id="inbox-scope-reason" state="empty" title="请选择 Team" description="Inbox 始终属于明确的 Organization 与 Team。" />

    <InboxWorkspace
      v-if="scopeStore.state.phase === 'ready' && scope"
      :phase="store.state.inbox.phase"
      :items="store.state.inbox.value ?? []"
      :last-synced-at="lastSyncedAt"
      :counts-phase="store.state.inboxCounts.phase"
      :counts="store.state.inboxCounts.value"
      :counts-error="store.state.inboxCounts.error"
      :next-cursor="store.state.inbox.nextCursor"
      :loading-more="store.state.inbox.loadingMore"
      :error="store.state.inbox.error"
      :selected-item-id="selectedItemId"
      :detail-phase="detailResource?.phase ?? 'idle'"
      :detail="detailResource?.value ?? null"
      :detail-error="detailResource?.error ?? null"
      :target-phase="targetResource?.phase ?? 'idle'"
      :target-error="targetResource?.error ?? null"
      :command="store.state.command"
      :item-type="itemType"
      :source-status="sourceStatus"
      :disposition-status="dispositionStatus"
      :online="online"
      :batch-report="batchReport"
      @select="selectItem"
      @close-detail="closeDetail"
      @change-type="changeType"
      @change-source-status="changeSourceStatus"
      @change-disposition-status="changeDispositionStatus"
      @retry="reload"
      @retry-detail="retryDetail"
      @retry-target="retryTarget"
      @load-more="loadMore"
      @open-target="openTarget"
      @change-disposition="changeDisposition"
      @batch-disposition="batchDisposition"
      @dismiss-batch-report="batchReport = null"
    />
  </AppShell>
</template>
