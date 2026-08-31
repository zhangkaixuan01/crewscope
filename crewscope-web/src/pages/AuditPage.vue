<script setup lang="ts">
import { RefreshCw } from '@lucide/vue'
import { computed, inject, watch } from 'vue'
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
import { auditEventCategories, auditOutcomes, type AuditEventCategory, type AuditFilter, type AuditOutcome, type TeamOpsScope } from '../domains/teamops/types'

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
const selectedEventId = computed(() => uuidQuery(route.query.auditEvent))
const selectedEvent = computed(() => (store.state.audit.value ?? []).find(item => item.eventId === selectedEventId.value) ?? null)
const chainId = computed(() => uuidQuery(route.query.chain))
const correlation = computed(() => chainId.value ? store.state.correlations[chainId.value] ?? null : null)

watch(
  () => [scopeStore.state.phase, scope.value?.organizationId, scope.value?.teamId, JSON.stringify(activeFilter.value)] as const,
  async ([phase]) => {
    if (phase !== 'ready' || !scope.value) return
    store.activateScope(scope.value)
    await Promise.all([store.loadAudit(activeFilter.value, false, true), scopeStore.loadMembers()])
    if (chainId.value) await store.loadCorrelation(chainId.value, false, true)
  },
  { immediate: true },
)

watch(chainId, id => {
  if (id && scope.value) void store.loadCorrelation(id)
})

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
  if (!online.value || !canExport.value) return
  await store.exportAudit(activeFilter.value, maximumRows)
  const value = store.state.auditExport.value
  if (!value || store.state.auditExport.phase !== 'ready') return
  downloadJson(value)
  // Export generation is itself auditable; refresh so the new fact becomes visible.
  await store.loadAudit(activeFilter.value, false, true)
}

function downloadJson(value: unknown): void {
  const url = URL.createObjectURL(new Blob([JSON.stringify(value, null, 2)], { type: 'application/vnd.crewscope.audit-export+json' }))
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = 'crewscope-audit-export.json'
  anchor.click()
  URL.revokeObjectURL(url)
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
  <AppShell title="审计中心" eyebrow="Govern / Audit explorer">
    <template #actions><BaseButton variant="secondary" size="small" :disabled="!scope || !online" @click="store.loadAudit(activeFilter, false, true)"><RefreshCw :size="14" />刷新</BaseButton></template>
    <StatePanel v-if="scopeStore.state.phase === 'loading'" state="loading" title="正在恢复 Team Scope" />
    <StatePanel v-else-if="!scope" state="empty" title="请选择 Team" description="审计事实始终属于明确的 Organization 与 Team。" />
    <AuditExplorer
      v-else
      :phase="store.state.audit.phase" :items="store.state.audit.value ?? []" :error="store.state.audit.error"
      :next-cursor="store.state.audit.nextCursor" :loading-more="store.state.audit.loadingMore"
      :selected-event="selectedEvent" :correlation="correlation" :initial-filter="filterForm"
      :correlation-id="chainId"
      :online="online" :can-export="canExport" :export-phase="store.state.auditExport.phase" :export-error="store.state.auditExport.error"
      :principal-names="principalNames"
      @apply-filter="applyFilter" @retry="store.loadAudit(activeFilter, false, true)" @load-more="store.loadAudit(activeFilter, true)"
      @select="selectEvent" @close-detail="closeDetail" @open-correlation="openCorrelation" @close-correlation="closeCorrelation"
      @retry-correlation="id => store.loadCorrelation(id, false, true)" @load-more-correlation="id => store.loadCorrelation(id, true)"
      @navigate="href => router.push(href)" @export="exportAudit"
    />
  </AppShell>
</template>
