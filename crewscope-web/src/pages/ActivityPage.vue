<script setup lang="ts">
import { Activity, RefreshCw, X } from '@lucide/vue'
import { computed, inject, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { AUTH_PRINCIPAL } from '../app/auth'
import { useNetworkStatus } from '../app/network'
import BaseButton from '../components/base/BaseButton.vue'
import StatusBadge from '../components/base/StatusBadge.vue'
import ActivityStream from '../components/domain/ActivityStream.vue'
import StatePanel from '../components/feedback/StatePanel.vue'
import AppShell from '../components/layout/AppShell.vue'
import { useScopeStore } from '../domains/scope/store'
import { principalDisplayName, principalNameDirectory } from '../domains/scope/memberDirectory'
import { useActivityRealtimeStore } from '../domains/teamops/activityRealtimeStore'
import { useTeamOpsStore } from '../domains/teamops/store'
import type { ActivityItem, TeamOpsScope } from '../domains/teamops/types'

const route = useRoute()
const router = useRouter()
const principal = inject(AUTH_PRINCIPAL)
const scopeStore = useScopeStore()
const store = useTeamOpsStore()
const realtime = useActivityRealtimeStore()
const online = useNetworkStatus()
const principalNames = computed(() => principalNameDirectory(scopeStore.state.members))
const actorFilter = ref('')
const scope = computed<TeamOpsScope | null>(() => principal && scopeStore.state.selectedTeamId
  ? { organizationId: principal.organizationId, teamId: scopeStore.state.selectedTeamId }
  : null)
const selectedCategory = computed(() => queryValue(route.query.category) ?? 'ALL')
const selectedEventId = computed(() => queryValue(route.query.event))
const selectedDetail = computed(() => selectedEventId.value
  ? store.state.activityDetails[`team:${selectedEventId.value}`] ?? null
  : null)
const filteredItems = computed(() => (store.state.teamActivity.value ?? []).filter(item => {
  const categoryMatches = selectedCategory.value === 'ALL' || item.category === selectedCategory.value
  const actor = actorFilter.value.trim().toLowerCase()
  const actorMatches = !actor || item.actor.type.toLowerCase().includes(actor)
    || item.actor.principalId?.toLowerCase().includes(actor)
    || (item.actor.principalId
      ? principalDisplayName(principalNames.value, item.actor.principalId, item.actor.type).toLowerCase().includes(actor)
      : false)
  return categoryMatches && actorMatches
}))
const categories = computed(() => [...new Set((store.state.teamActivity.value ?? []).map(item => item.category))].sort())

watch(
  () => [scopeStore.state.phase, scope.value?.organizationId, scope.value?.teamId] as const,
  async ([phase]) => {
    if (phase !== 'ready' || !scope.value) {
      realtime.stop()
      return
    }
    store.activateScope(scope.value)
    await Promise.all([store.loadTeamActivity({}, false, true), scopeStore.loadMembers()])
    if (!scope.value || store.state.teamActivity.error?.kind === 'forbidden') return
    realtime.start(scope.value, store.state.teamActivity.resumeCursor)
    if (selectedEventId.value) await store.loadActivityDetail(selectedEventId.value)
  },
  { immediate: true },
)

watch(online, value => realtime.setOnline(value), { immediate: true })
watch(selectedEventId, eventId => { if (eventId && scope.value) void store.loadActivityDetail(eventId) })
onUnmounted(() => realtime.stop())

async function reload(): Promise<void> {
  if (!scope.value) return
  realtime.stop()
  await store.loadTeamActivity({}, false, true)
  if (store.state.teamActivity.error?.kind !== 'forbidden') realtime.start(scope.value, store.state.teamActivity.resumeCursor)
}

async function recoverCursor(): Promise<void> {
  if (!scope.value) return
  await store.loadTeamActivity({}, false, true)
  realtime.retry(store.state.teamActivity.resumeCursor)
}

function select(item: ActivityItem): void { void router.replace({ query: { ...route.query, event: item.eventId } }) }
function closeDetail(): void { const query = { ...route.query }; delete query.event; void router.replace({ query }) }
function updateCategory(event: Event): void {
  const value = event.target instanceof HTMLSelectElement ? event.target.value : 'ALL'
  const query = { ...route.query }
  if (value === 'ALL') delete query.category
  else query.category = value
  void router.replace({ query })
}
function queryValue(value: unknown): string | null { return typeof value === 'string' && value.length > 0 ? value : null }
function displayTime(value: string): string { return new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value)) }
function actorName(principalId: string | null, type: string): string {
  return principalId ? principalDisplayName(principalNames.value, principalId, type) : type === 'SYSTEM' ? '系统' : type
}
</script>

<template>
  <AppShell title="团队 Activity" eyebrow="Observe / Shared facts">
    <template #actions><BaseButton variant="secondary" size="small" :disabled="!scope || !online" @click="reload"><RefreshCw :size="14" aria-hidden="true" />刷新</BaseButton></template>

    <StatePanel v-if="scopeStore.state.phase === 'loading'" state="loading" title="正在恢复 Team Scope" />
    <StatePanel v-else-if="!scope" state="empty" title="请选择 Team" description="Activity 始终属于一个明确的 Organization 与 Team。" />

    <div v-else class="activity-page page-shell">
      <section class="activity-summary panel">
        <i><Activity :size="22" aria-hidden="true" /></i>
        <div><p class="eyebrow">Team pulse</p><h2>{{ store.state.teamActivity.value?.length ?? 0 }} 条已加载事实</h2><p>实时流只推进公开 Activity DTO，领域详情仍由权威 API 回读。</p></div>
        <StatusBadge :tone="realtime.state.phase === 'live' ? 'success' : 'warning'" dot>{{ realtime.state.phase }}</StatusBadge>
      </section>

      <section class="activity-toolbar panel" aria-label="Activity 筛选">
        <label>Category<select :value="selectedCategory" @change="updateCategory"><option value="ALL">全部类别</option><option v-for="category in categories" :key="category" :value="category">{{ category }}</option></select></label>
        <label>Actor<input v-model="actorFilter" type="search" placeholder="类型或 Principal ID" autocomplete="off"></label>
        <span>{{ filteredItems.length }} / {{ store.state.teamActivity.value?.length ?? 0 }}</span>
      </section>

      <div class="activity-workspace" :class="{ 'has-detail': selectedEventId }">
        <ActivityStream
          :phase="store.state.teamActivity.phase" :items="filteredItems" :next-cursor="store.state.teamActivity.nextCursor"
          :loading-more="store.state.teamActivity.loadingMore" :error="store.state.teamActivity.error ?? realtime.state.error"
          :realtime-phase="realtime.state.phase" :online="online"
          :principal-names="principalNames"
          @retry="realtime.state.phase === 'cursor-expired' ? recoverCursor() : reload()"
          @load-more="store.loadTeamActivity({}, true)" @select="select"
        />

        <aside v-if="selectedEventId" class="activity-detail panel" aria-label="Activity 事件详情">
          <header><div><p class="eyebrow">Event detail</p><h2>事件详情</h2></div><button type="button" aria-label="关闭事件详情" @click="closeDetail"><X :size="17" /></button></header>
          <StatePanel v-if="!selectedDetail || selectedDetail.phase === 'loading'" compact state="loading" />
          <StatePanel v-else-if="selectedDetail.phase === 'error'" compact :state="selectedDetail.error?.kind === 'forbidden' ? 'forbidden' : 'error'" :description="selectedDetail.error?.message" @retry="store.loadActivityDetail(selectedEventId, null, true)" />
          <template v-else-if="selectedDetail.value">
            <dl><div><dt>Event</dt><dd class="mono">{{ selectedDetail.value.eventId }}</dd></div><div><dt>类型</dt><dd>{{ selectedDetail.value.eventType }}</dd></div><div><dt>Category</dt><dd>{{ selectedDetail.value.category }}</dd></div><div><dt>Actor</dt><dd>{{ actorName(selectedDetail.value.actor.principalId, selectedDetail.value.actor.type) }}</dd></div><div><dt>Subject</dt><dd class="mono">{{ selectedDetail.value.subject.type }} · {{ selectedDetail.value.subject.id }}</dd></div><div><dt>发生时间</dt><dd>{{ displayTime(selectedDetail.value.occurredAt) }}</dd></div></dl>
            <section><h3>公开摘要</h3><p v-if="Object.keys(selectedDetail.value.payload.values).length === 0">此事件没有公开摘要字段。</p><dl v-else><div v-for="(value, key) in selectedDetail.value.payload.values" :key="key"><dt>{{ key }}</dt><dd>{{ value }}</dd></div></dl></section>
          </template>
        </aside>
      </div>
    </div>
  </AppShell>
</template>

<style scoped>
.activity-page { display: grid; gap: 14px; max-width: 1240px; margin: 0 auto; }.activity-summary { display: grid; grid-template-columns: 42px minmax(0, 1fr) auto; align-items: center; gap: 14px; padding: 16px 18px; }.activity-summary > i { display: grid; width: 42px; height: 42px; place-items: center; border-radius: 13px; background: var(--cs-brand-100); color: var(--cs-brand-700); }.activity-summary h2 { margin: 2px 0 3px; font-size: 17px; }.activity-summary p { margin: 0; color: var(--cs-text-muted); font-size: 10px; }
.activity-toolbar { display: grid; grid-template-columns: minmax(180px, 240px) minmax(220px, 1fr) auto; align-items: end; gap: 12px; padding: 12px 14px; }.activity-toolbar label { display: grid; gap: 5px; color: var(--cs-text-muted); font-size: 9px; font-weight: 750; }.activity-toolbar select, .activity-toolbar input { min-height: 36px; padding: 0 10px; border: 1px solid var(--cs-border); border-radius: 8px; background: #fff; font-size: 11px; }.activity-toolbar > span { padding-bottom: 9px; color: var(--cs-text-muted); font: 9px var(--cs-font-mono); }
.activity-workspace { display: grid; grid-template-columns: minmax(0, 1fr); gap: 14px; align-items: start; }.activity-workspace.has-detail { grid-template-columns: minmax(0, 1fr) 330px; }.activity-detail { position: sticky; top: 12px; overflow: hidden; }.activity-detail > header { display: flex; align-items: center; justify-content: space-between; padding: 14px 15px; border-bottom: 1px solid var(--cs-border); }.activity-detail h2 { margin: 2px 0 0; font-size: 15px; }.activity-detail header button { display: grid; width: 32px; height: 32px; place-items: center; border-radius: 8px; background: var(--cs-surface-subtle); cursor: pointer; }.activity-detail > dl, .activity-detail > section { margin: 0; padding: 14px 15px; }.activity-detail > section { border-top: 1px solid var(--cs-border); }.activity-detail h3 { margin: 0 0 9px; font-size: 11px; }.activity-detail section > p { color: var(--cs-text-muted); font-size: 9px; }.activity-detail dl { display: grid; gap: 8px; }.activity-detail dt { color: var(--cs-text-muted); font-size: 8px; font-weight: 750; text-transform: uppercase; }.activity-detail dd { overflow-wrap: anywhere; margin: 2px 0 0; font-size: 9px; }
@media (max-width: 820px) { .activity-workspace.has-detail { grid-template-columns: 1fr; }.activity-detail { position: static; grid-row: 1; }.activity-toolbar { grid-template-columns: 1fr 1fr; }.activity-toolbar > span { grid-column: 1 / -1; padding: 0; } } @media (max-width: 520px) { .activity-page { gap: 10px; }.activity-summary { grid-template-columns: 36px 1fr; padding: 13px; }.activity-summary > i { width: 36px; height: 36px; }.activity-summary > :last-child { grid-column: 1 / -1; justify-self: start; }.activity-toolbar { grid-template-columns: 1fr; }.activity-toolbar > span { grid-column: auto; }.activity-toolbar select, .activity-toolbar input { min-height: 42px; } }
</style>
