<script setup lang="ts">
import { RefreshCw } from '@lucide/vue'
import { computed, inject, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import type { LocationQueryRaw } from 'vue-router'
import { AUTH_PRINCIPAL, can, permissions } from '../app/auth'
import { useNetworkStatus } from '../app/network'
import BaseButton from '../components/base/BaseButton.vue'
import AuditExplorer from '../components/domain/AuditExplorer.vue'
import StatePanel from '../components/feedback/StatePanel.vue'
import AppShell from '../components/layout/AppShell.vue'
import { useScopeStore } from '../domains/scope/store'
import { principalNameDirectory } from '../domains/scope/memberDirectory'
import { useTeamOpsStore } from '../domains/teamops/store'
import { auditEventCategories, auditOutcomes, type AuditEvent, type AuditEventCategory, type AuditFilter, type AuditOutcome, type TeamOpsScope } from '../domains/teamops/types'
import { usePageRequestScope } from '../composables/usePageRequestScope'

const pageRequests = usePageRequestScope()

interface AuditFilterForm {
  from: string
  to: string
  category: string
  outcome: string
  initiator: string
  actor: string
  agent: string
  subjectType: string
  subjectId: string
  providerBinding: string
  correlation: string
}

const route = useRoute()
const router = useRouter()
const principal = inject(AUTH_PRINCIPAL)
const scopeStore = useScopeStore()
const store = useTeamOpsStore()
const online = useNetworkStatus()
const canExport = computed(() => Boolean(principal && can(principal, permissions.governanceExport)))
const principalNames = computed(() => principalNameDirectory(scopeStore.state.members))
const scope = computed<TeamOpsScope | null>(() => principal && scopeStore.state.selectedTeamId
  ? { organizationId: principal.organizationId, teamId: scopeStore.state.selectedTeamId }
  : null)
const filterForm = computed<AuditFilterForm>(() => {
  const subjectId = uuidQuery(route.query.subjectId)
  const subjectType = subjectId ? queryValue(route.query.subjectType) : ''
  return {
    from: localDateQuery(route.query.from), to: localDateQuery(route.query.to),
    category: enumQuery(route.query.category, auditEventCategories), outcome: enumQuery(route.query.outcome, auditOutcomes),
    initiator: uuidQuery(route.query.initiator), actor: uuidQuery(route.query.actor), agent: uuidQuery(route.query.agent),
    subjectType: subjectType && subjectId ? subjectType : '', subjectId: subjectType ? subjectId : '',
    providerBinding: uuidQuery(route.query.providerBinding), correlation: uuidQuery(route.query.correlation),
  }
})
const activeFilter = computed<AuditFilter>(() => toAuditFilter(filterForm.value))

// R25: the offline panel says how stale the readable audit facts are, not just that they are stale.
const lastSyncedAt = ref<string | null>(null)
watch(() => store.state.audit.phase, phase => {
  if (phase === 'ready') lastSyncedAt.value = new Date().toISOString()
})
const selectedEventId = computed(() => uuidQuery(route.query.auditEvent))
const selectedEventById = computed(() => selectedEventId.value ? store.state.auditEventDetails[selectedEventId.value] ?? null : null)
// A deep link must resolve on a fresh window where the list page has not loaded its row: the
// by-id point read fills the detail, and null there is a stable "not visible in this Team".
const selectedEvent = computed(() => (store.state.audit.value ?? []).find(item => item.eventId === selectedEventId.value)
  ?? selectedEventById.value?.value ?? null)
const missingSelectedEvent = computed(() => Boolean(selectedEventId.value) && selectedEvent.value === null
  && (selectedEventById.value?.phase === 'ready' || selectedEventById.value?.phase === 'empty'))
const deepLinkError = computed(() => (selectedEventId.value && selectedEvent.value === null && selectedEventById.value?.phase === 'error')
  ? selectedEventById.value?.error ?? null
  : null)
const chainId = computed(() => uuidQuery(route.query.chain))
const correlation = computed(() => chainId.value ? store.state.correlations[chainId.value] ?? null : null)
const exportSummary = ref<{ count: number, possiblyIncomplete: boolean } | null>(null)

watch(
  () => [scopeStore.state.phase, scope.value?.organizationId, scope.value?.teamId, JSON.stringify(activeFilter.value)] as const,
  async ([phase]) => {
    const pageOwner = pageRequests.capture()
    if (phase !== 'ready' || !scope.value) return
    store.activateScope(scope.value)
    await Promise.all([store.loadAudit(activeFilter.value, false, true), scopeStore.loadMembers()])
    if (!pageOwner.isCurrent()) return
    if (chainId.value) await store.loadCorrelation(chainId.value, false, true)
  },
  { immediate: true },
)

watch(chainId, id => {
  if (id && scope.value) void store.loadCorrelation(id)
})

// Fires once the first page settles (or stays empty): a deep-linked event missing from that page
// gets its own point read instead of silently rendering no detail.
watch([selectedEventId, () => store.state.audit.phase], ([id, phase]) => {
  if (!id || !scope.value) return
  if (phase !== 'ready' && phase !== 'empty') return
  if ((store.state.audit.value ?? []).some(item => item.eventId === id)) return
  void store.loadAuditEvent(id)
}, { immediate: true })

function applyFilter(value: AuditFilterForm): void {
  const query: LocationQueryRaw = { ...route.query }
  const keys: Array<keyof AuditFilterForm> = ['from', 'to', 'category', 'outcome', 'initiator', 'actor', 'agent', 'subjectType', 'subjectId', 'providerBinding', 'correlation']
  keys.forEach(key => {
    if (value[key]) query[key] = value[key]
    else delete query[key]
  })
  delete query.auditEvent
  delete query.chain
  void router.replace({ query })
}

function selectEvent(eventId: string): void {
  const query: LocationQueryRaw = { ...route.query, auditEvent: eventId }
  delete query.chain
  void router.replace({ query })
}

function closeDetail(): void {
  const query: LocationQueryRaw = { ...route.query }
  delete query.auditEvent
  void router.replace({ query })
}

function openCorrelation(correlationId: string): void {
  const query: LocationQueryRaw = { ...route.query, chain: correlationId }
  delete query.auditEvent
  void router.replace({ query })
}

function closeCorrelation(): void {
  const query: LocationQueryRaw = { ...route.query }
  delete query.chain
  void router.replace({ query })
}

async function exportAudit(maximumRows: number): Promise<void> {
  const pageOwner = pageRequests.capture()
  if (!online.value || !canExport.value) return
  exportSummary.value = null
  await store.exportAudit(activeFilter.value, maximumRows)
  if (!pageOwner.isCurrent()) return
  const value = store.state.auditExport.value
  if (!value || store.state.auditExport.phase !== 'ready') return
  exportSummary.value = { count: value.events.length, possiblyIncomplete: value.events.length >= value.maximumRows }
  downloadCsv(value)
  // Export generation is itself auditable; refresh so the new fact becomes visible.
  await store.loadAudit(activeFilter.value, false, true)
  if (!pageOwner.isCurrent()) return
}

function downloadCsv(value: { generatedAt: string, events: AuditEvent[] }): void {
  const columns = ['eventId', 'eventType', 'category', 'outcome', 'occurredAt', 'actorType', 'actorId', 'subjectType', 'subjectId', 'correlationId']
  const rows = value.events.map(event => [
    event.eventId, event.eventType, event.category, event.outcome, event.occurredAt,
    event.identity.actorType, event.identity.actorId ?? '', event.subject.type, event.subject.id,
    event.correlation.correlationId,
  ])
  const csv = [columns, ...rows].map(row => row.map(csvCell).join(',')).join('\r\n')
  // UTF-8 BOM keeps Chinese labels readable in spreadsheet applications.
  const url = URL.createObjectURL(new Blob(['\uFEFF' + csv], { type: 'text/csv;charset=utf-8' }))
  const anchor = document.createElement('a')
  anchor.href = url
  const stamp = value.generatedAt.replace(/[:.]/g, '-')
  // The file name reflects the applied conditions that produced it, never the draft on screen.
  const summary = [activeFilter.value.categories?.[0] ?? '', activeFilter.value.outcomes?.[0] ?? ''].filter(Boolean).join('-') || 'all'
  anchor.download = 'crewscope-audit-' + summary + '-' + stamp + '.csv'
  anchor.click()
  URL.revokeObjectURL(url)
}

function csvCell(value: string): string {
  return /[",\r\n]/.test(value) ? '"' + value.replace(/"/g, '""') + '"' : value
}

function toAuditFilter(value: AuditFilterForm): AuditFilter {
  return {
    from: value.from ? new Date(value.from).toISOString() : null,
    to: value.to ? new Date(value.to).toISOString() : null,
    categories: value.category ? [value.category as AuditEventCategory] : undefined,
    outcomes: value.outcome ? [value.outcome as AuditOutcome] : undefined,
    initiatorIds: value.initiator ? [value.initiator] : undefined,
    actorIds: value.actor ? [value.actor] : undefined,
    agentPrincipalIds: value.agent ? [value.agent] : undefined,
    subjectTypes: value.subjectType ? [value.subjectType] : undefined,
    subjectIds: value.subjectId ? [value.subjectId] : undefined,
    providerBindingIds: value.providerBinding ? [value.providerBinding] : undefined,
    correlationIds: value.correlation ? [value.correlation] : undefined,
  }
}

function queryValue(value: unknown): string { return typeof value === 'string' ? value : '' }
function uuidQuery(value: unknown): string {
  const parsed = queryValue(value)
  return /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(parsed) ? parsed : ''
}
function localDateQuery(value: unknown): string {
  const parsed = queryValue(value)
  return /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}$/.test(parsed) && Number.isFinite(new Date(parsed).getTime()) ? parsed : ''
}
function enumQuery<T extends string>(value: unknown, choices: readonly T[]): T | '' {
  const parsed = queryValue(value)
  return choices.includes(parsed as T) ? parsed as T : ''
}
</script>

<template>
  <AppShell title="审计中心" eyebrow="治理 · 审计查询">
    <template #actions><BaseButton variant="secondary" size="small" :disabled="!scope || !online" :aria-describedby="scope ? undefined : 'audit-scope-reason'" @click="store.loadAudit(activeFilter, false, true)"><RefreshCw :size="14" />刷新</BaseButton></template>
    <StatePanel v-if="scopeStore.state.phase === 'loading'" state="loading" title="正在恢复 Team Scope" />
    <StatePanel v-else-if="!scope" id="audit-scope-reason" state="empty" title="请选择 Team" description="审计事实始终属于明确的 Organization 与 Team。" />
    <template v-else>
      <StatePanel v-if="deepLinkError" class="audit-deeplink-error" compact state="error" :description="deepLinkError.message" @retry="selectedEventId && store.loadAuditEvent(selectedEventId, true)" />
      <AuditExplorer
        :phase="store.state.audit.phase" :items="store.state.audit.value ?? []" :error="store.state.audit.error" :last-synced-at="lastSyncedAt"
        :next-cursor="store.state.audit.nextCursor" :loading-more="store.state.audit.loadingMore"
        :selected-event="selectedEvent" :missing-selected-event="missingSelectedEvent" :correlation="correlation" :initial-filter="filterForm"
        :correlation-id="chainId"
        :online="online" :can-export="canExport" :export-phase="store.state.auditExport.phase" :export-error="store.state.auditExport.error"
        :export-summary="exportSummary" :principal-names="principalNames" :principal-scope="scope"
        @apply-filter="applyFilter" @retry="store.loadAudit(activeFilter, false, true)" @load-more="store.loadAudit(activeFilter, true)"
        @select="selectEvent" @close-detail="closeDetail" @clear-deep-link="closeDetail" @open-correlation="openCorrelation" @close-correlation="closeCorrelation"
        @retry-correlation="id => store.loadCorrelation(id, false, true)" @load-more-correlation="id => store.loadCorrelation(id, true)"
        @navigate="href => router.push(href)" @export="exportAudit"
      />
    </template>
  </AppShell>
</template>

<style scoped>
.audit-deeplink-error { margin-bottom: var(--cs-space-12); }
</style>
