<script setup lang="ts">
import { RefreshCw } from '@lucide/vue'
import { computed, inject, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { AUTH_PRINCIPAL } from '../app/auth'
import { useNetworkStatus } from '../app/network'
import BaseButton from '../components/base/BaseButton.vue'
import InboxWorkspace from '../components/domain/InboxWorkspace.vue'
import AppShell from '../components/layout/AppShell.vue'
import { useScopeStore } from '../domains/scope/store'
import { useTeamOpsStore } from '../domains/teamops/store'
import {
  inboxDispositionStatuses,
  inboxItemTypes,
  inboxSourceStatuses,
  type InboxDispositionStatus,
  type InboxFilter,
  type InboxItemType,
  type InboxSourceStatus,
  type TeamOpsScope,
} from '../domains/teamops/types'

const route = useRoute()
const router = useRouter()
const principal = inject(AUTH_PRINCIPAL)
const scopeStore = useScopeStore()
const store = useTeamOpsStore()
const online = useNetworkStatus()
const commandAttempt = ref<{ itemId: string, status: Exclude<InboxDispositionStatus, 'UNREAD'>, key: string } | null>(null)

const scope = computed<TeamOpsScope | null>(() => principal && scopeStore.state.selectedTeamId
  ? { organizationId: principal.organizationId, teamId: scopeStore.state.selectedTeamId }
  : null)
const itemType = computed<InboxItemType>(() => oneOf(route.query.inboxType, inboxItemTypes, 'OWNERSHIP'))
const sourceStatus = computed<InboxSourceStatus>(() => oneOf(route.query.sourceStatus, inboxSourceStatuses, 'OPEN'))
const dispositionStatus = computed<InboxDispositionStatus | 'ALL'>(() => oneOf(route.query.disposition, ['ALL', ...inboxDispositionStatuses] as const, 'ALL'))
const selectedItemId = computed(() => uuidQuery(route.query.inboxItem))
const filter = computed<InboxFilter>(() => ({
  itemTypes: [itemType.value],
  sourceStatuses: [sourceStatus.value],
  dispositionStatuses: dispositionStatus.value === 'ALL' ? undefined : [dispositionStatus.value],
}))
const detailResource = computed(() => selectedItemId.value ? store.state.inboxDetails[selectedItemId.value] ?? null : null)
const targetResource = computed(() => selectedItemId.value ? store.state.inboxTargets[selectedItemId.value] ?? null : null)

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
    if (phase !== 'ready' || !scope.value) return
    store.activateScope(scope.value)
    commandAttempt.value = null
    store.clearCommand()
    await Promise.all([
      store.loadInbox(filter.value, false, true),
      store.loadInboxCounts(true),
    ])
  },
  { immediate: true },
)

watch(
  () => [scopeStore.state.phase, route.query.inboxType, route.query.sourceStatus, route.query.disposition, route.query.inboxItem] as const,
  ([phase]) => {
    if (phase !== 'ready') return
    const query = { ...route.query }
    let changed = false
    changed = normalizeQuery(query, 'inboxType', itemType.value, 'OWNERSHIP') || changed
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

function changeType(value: InboxItemType): void {
  replaceFilter('inboxType', value, 'OWNERSHIP')
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
  if (!scope.value || !online.value) return
  await Promise.all([
    store.loadInbox(filter.value, false, true),
    store.loadInboxCounts(true),
  ])
  if (selectedItemId.value) await store.loadInboxDetail(selectedItemId.value, true)
}

function retryDetail(itemId: string): void {
  void store.loadInboxDetail(itemId, true)
}

async function openTarget(itemId: string): Promise<void> {
  if (!online.value) return
  await store.loadInboxTarget(itemId, true)
  const target = store.state.inboxTargets[itemId]?.value
  if (target) await router.push(target.href)
}

async function changeDisposition(itemId: string, status: Exclude<InboxDispositionStatus, 'UNREAD'>): Promise<void> {
  if (!online.value) return
  const current = commandAttempt.value
  if (!current || current.itemId !== itemId || current.status !== status) {
    commandAttempt.value = { itemId, status, key: crypto.randomUUID() }
  }
  const success = await store.changeInboxDisposition(itemId, status, commandAttempt.value!.key)
  if (success) {
    commandAttempt.value = null
    await Promise.all([
      store.loadInbox(filter.value, false, true),
      store.loadInboxCounts(true),
      store.loadInboxDetail(itemId, true),
    ])
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
  } else if (!store.state.command.error?.retryable) {
    commandAttempt.value = null
  }
}

function loadMore(): void {
  void store.loadInbox(filter.value, true)
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
  <AppShell title="我的 Inbox" eyebrow="Collaborate / Member queue">
    <template #actions><BaseButton variant="secondary" size="small" :disabled="!scope || !online" @click="reload"><RefreshCw :size="14" />刷新</BaseButton></template>

    <InboxWorkspace
      v-if="scopeStore.state.phase === 'ready' && scope"
      :phase="store.state.inbox.phase"
      :items="store.state.inbox.value ?? []"
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
      @select="selectItem"
      @close-detail="closeDetail"
      @change-type="changeType"
      @change-source-status="changeSourceStatus"
      @change-disposition-status="changeDispositionStatus"
      @retry="reload"
      @retry-detail="retryDetail"
      @load-more="loadMore"
      @open-target="openTarget"
      @change-disposition="changeDisposition"
    />
  </AppShell>
</template>
