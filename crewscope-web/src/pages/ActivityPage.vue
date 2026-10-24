<script setup lang="ts">
import BaseSelect from '../components/base/BaseSelect.vue'
import ScanToolbar from '../components/base/ScanToolbar.vue'
import { RefreshCw, X } from '@lucide/vue'
import { computed, inject, onUnmounted, ref, watch } from 'vue'
import { RouterLink, useRoute, useRouter, type RouteLocationRaw } from 'vue-router'
import { queryValue } from '../utils/queryParams'
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
import { useDetailPaneFocus } from '../composables/useDetailPaneFocus'
import { useTeamOpsStore } from '../domains/teamops/store'
import { activityCategories, type ActivityItem, type TeamOpsScope } from '../domains/teamops/types'
import { enumLabel } from '../domains/shared/labels'
import RelativeTime from '../components/base/RelativeTime.vue'
import {
  activityCategoryLabels,
  activityEventTypeLabels,
  activityRealtimePhaseLabels,
  activityReferenceTypeLabels,
  activitySubjectTypeLabels,
  auditActorTypeLabels,
} from '../domains/teamops/labels'
import { useListSort } from '../composables/useListSort'
import { usePageRequestScope } from '../composables/usePageRequestScope'

const pageRequests = usePageRequestScope()

const route = useRoute()
const router = useRouter()
const principal = inject(AUTH_PRINCIPAL)
const scopeStore = useScopeStore()
const store = useTeamOpsStore()
const realtime = useActivityRealtimeStore()
const online = useNetworkStatus()
const activitySort = useListSort({ defaultKey: 'occurredAt' as const, defaultDirection: 'desc', allowedKeys: ['occurredAt'] as const })
const activitySortLabel = computed(() => activitySort.direction.value === 'asc' ? '最早优先' : '最新优先')
const principalNames = computed(() => principalNameDirectory(scopeStore.state.members))
const selectedActor = computed(() => queryValue(route.query.actor))
const actorOptions = computed(() => scopeStore.state.members.filter(member => member.status === 'ACTIVE'))
const scope = computed<TeamOpsScope | null>(() => principal && scopeStore.state.selectedTeamId
  ? { organizationId: principal.organizationId, teamId: scopeStore.state.selectedTeamId }
  : null)
const selectedCategory = computed(() => queryValue(route.query.category) ?? 'ALL')
const selectedEventId = computed(() => queryValue(route.query.event))
const selectedDetail = computed(() => selectedEventId.value
  ? store.state.activityDetails[`team:${selectedEventId.value}`] ?? null
  : null)
// R25: the offline panel says how stale the readable activity facts are, not just that they are stale.
const lastSyncedAt = ref<string | null>(null)
watch(() => store.state.teamActivity.phase, phase => {
  if (phase === 'ready') lastSyncedAt.value = new Date().toISOString()
})

const filteredItems = computed(() => [...(store.state.teamActivity.value ?? [])].sort((left, right) => {
  const delta = new Date(left.occurredAt).getTime() - new Date(right.occurredAt).getTime()
  return activitySort.direction.value === 'asc' ? delta : -delta
}))
const activityFilter = computed(() => ({
  categories: selectedCategory.value === 'ALL' ? undefined : [selectedCategory.value],
  actorPrincipalIds: selectedActor.value ? [selectedActor.value] : undefined,
}))
const categories = activityCategories

watch(
  () => [scopeStore.state.phase, scope.value?.organizationId, scope.value?.teamId, JSON.stringify(activityFilter.value)] as const,
  async ([phase]) => {
    const pageOwner = pageRequests.captureSelection()
    if (phase !== 'ready' || !scope.value) {
      realtime.stop()
      return
    }
    store.activateScope(scope.value)
    await Promise.all([store.loadTeamActivity(activityFilter.value, false, true), scopeStore.loadMembers()])
    if (!pageOwner.isCurrent()) return
    if (!scope.value || store.state.teamActivity.error?.kind === 'forbidden') return
    realtime.start(scope.value, store.state.teamActivity.resumeCursor, activityFilter.value)
    if (selectedEventId.value) await store.loadActivityDetail(selectedEventId.value)
  },
  { immediate: true },
)

watch(online, value => realtime.setOnline(value), { immediate: true })
watch(selectedEventId, eventId => { if (eventId && scope.value) void store.loadActivityDetail(eventId) })
// M12b-Q01: the sort direction flows one way — query → state. The toggle below writes the
// query directly and the watch replays it into the composable; a second state→query watch
// had to guard against its own echo and would drift the moment a second sort key appears.
watch(() => route.query.sortDirection, value => {
  if (value === 'asc' || value === 'desc') activitySort.setSort('occurredAt', value)
}, { immediate: true })
function cycleSortQuery(): void {
  const next = activitySort.direction.value === 'asc' ? 'desc' : 'asc'
  const query = { ...route.query }
  if (next === 'desc') delete query.sortDirection
  else query.sortDirection = next
  void router.replace({ query })
}
onUnmounted(() => realtime.stop())

async function reload(): Promise<void> {
  const pageOwner = pageRequests.captureSelection()
  if (!scope.value) return
  realtime.stop()
  await store.loadTeamActivity(activityFilter.value, false, true)
  if (!pageOwner.isCurrent()) return
  if (store.state.teamActivity.error?.kind !== 'forbidden') realtime.start(scope.value, store.state.teamActivity.resumeCursor, activityFilter.value)
}

async function recoverCursor(): Promise<void> {
  const pageOwner = pageRequests.captureSelection()
  if (!scope.value) return
  await store.loadTeamActivity(activityFilter.value, false, true)
  if (!pageOwner.isCurrent()) return
  realtime.retry(store.state.teamActivity.resumeCursor)
}

/** M13b-F11（C4）：详情焦点生命周期——打开聚焦标题、关闭还原触发按钮；深链冷进入无触发按钮可还原。 */
const detailHeading = ref<HTMLElement | null>(null)
const detailFocus = useDetailPaneFocus(detailHeading)
watch(selectedEventId, eventId => {
  if (eventId) void detailFocus.focusHeading()
  else detailFocus.restoreTrigger()
})

function select(item: ActivityItem): void {
  detailFocus.captureTrigger()
  void router.replace({ query: { ...route.query, event: item.eventId } })
}
function closeDetail(): void {
  detailFocus.restoreTrigger()
  const query = { ...route.query }
  delete query.event
  void router.replace({ query })
}
function updateCategory(value: string | number | null): void {
  const query = { ...route.query }
  if (!value || value === 'ALL') delete query.category
  else query.category = String(value)
  void router.replace({ query })
}
function updateActor(value: string | number | null): void {
  const query = { ...route.query }
  if (value) query.actor = String(value)
  else delete query.actor
  void router.replace({ query })
}
function displayTime(value: string): string { return new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value)) }

/**
 * M13b-F08（R27）：详情面板的 Subject 行与流内卡片同源——关联对象是可点链接，不再露出
 * subject.id 的裸 UUID；没有 references 时只保留类型标签。
 */
function referenceTarget(reference: ActivityItem['references'][number], eventId: string): RouteLocationRaw {
  const query = { ...route.query }
  if (reference.type === 'WORK_ITEM') return { name: 'work', query: { ...query, workItem: reference.id } }
  if (reference.type === 'CONVERSATION') return { name: 'conversation', query: { ...query, conversation: reference.id } }
  return { name: 'activity', query: { ...query, event: eventId } }
}
function referenceLabel(type: string): string {
  return enumLabel(type, activityReferenceTypeLabels)
}
function actorName(principalId: string | null, type: string): string {
  const typeLabel = enumLabel(type, auditActorTypeLabels)
  return principalId ? principalDisplayName(principalNames.value, principalId, typeLabel) : typeLabel
}
</script>

<template>
  <AppShell title="团队 Activity" eyebrow="观测 · 共享事实">
    <template #actions><BaseButton variant="secondary" size="small" :disabled="!scope || !online" :aria-describedby="scope ? undefined : 'activity-scope-reason'" @click="reload"><RefreshCw :size="14" aria-hidden="true" />刷新</BaseButton></template>

    <StatePanel v-if="scopeStore.state.phase === 'loading'" state="loading" title="正在恢复 Team Scope" />
    <StatePanel v-else-if="!scope" id="activity-scope-reason" state="empty" title="请选择 Team" />

    <div v-else class="activity-page page-shell">
      <section class="summary-strip panel" aria-label="Activity 概览">
        <h2>{{ store.state.teamActivity.value?.length ?? 0 }} 条已加载事实</h2>
        <!-- M13b-F10（L05）：连接态收敛进 strip——徽标报相位，note 说明缓存事实与最近同步（R25），
             列表前不再插恢复提示 panel，首条事实不被推走。 -->
        <p class="summary-strip__note">
          <template v-if="!online || realtime.state.phase === 'offline'">离线 · 缓存事实保持可读，恢复后从耐久 Cursor 补齐<template v-if="lastSyncedAt">（最近同步于 <RelativeTime :value="lastSyncedAt" />）</template></template>
          <template v-else-if="realtime.state.phase === 'reconnecting' || realtime.state.phase === 'connecting'">实时流中断，正在补发 · 已加载事实保持可读</template>
          <template v-else-if="store.state.teamActivity.error">权威读取异常（{{ store.state.teamActivity.error.message }}）· 已加载事实保持可读</template>
          <template v-else>实时流只推进公开 Activity DTO，领域详情仍由权威 API 回读</template>
        </p>
        <StatusBadge role="status" :tone="realtime.state.phase === 'live' ? 'success' : 'warning'" dot>{{ activityRealtimePhaseLabels[realtime.state.phase] }}</StatusBadge>
      </section>

      <ScanToolbar aria-label="Activity 筛选">
        <template #count>{{ filteredItems.length }} 条当前结果</template>
        <template #filters>
          <label>类别<BaseSelect :model-value="selectedCategory" @update:model-value="updateCategory"><option value="ALL">全部类别</option><option v-for="category in categories" :key="category" :value="category">{{ activityCategoryLabels[category] }}</option></BaseSelect></label>
          <label>Actor<BaseSelect :model-value="selectedActor" aria-label="按成员筛选" @update:model-value="updateActor"><option value="">全部成员</option><option v-for="member in actorOptions" :key="member.userPrincipalId" :value="member.userPrincipalId">{{ member.displayName }}</option></BaseSelect></label>
        </template>
        <template #side>
          <BaseButton variant="ghost" size="small" :aria-label="`按时间${activitySortLabel}`" @click="cycleSortQuery">{{ activitySortLabel }}</BaseButton>
        </template>
      </ScanToolbar>

      <div class="activity-workspace" :class="{ 'has-detail': selectedEventId }">
        <ActivityStream
          :phase="store.state.teamActivity.phase" :items="filteredItems" :next-cursor="store.state.teamActivity.nextCursor"
          :loading-more="store.state.teamActivity.loadingMore" :error="store.state.teamActivity.error ?? realtime.state.error"
          :last-synced-at="lastSyncedAt"
          :realtime-phase="realtime.state.phase" :online="online"
          :principal-names="principalNames"
          @retry="realtime.state.phase === 'cursor-expired' ? recoverCursor() : reload()"
          @load-more="store.loadTeamActivity(activityFilter, true)" @select="select"
        />

        <aside v-if="selectedEventId" class="activity-detail panel" aria-label="Activity 事件详情">
          <header><div><h2 ref="detailHeading" tabindex="-1">事件详情</h2></div><button type="button" aria-label="关闭事件详情" @click="closeDetail"><X :size="17" /></button></header>
          <StatePanel v-if="!selectedDetail || selectedDetail.phase === 'loading'" compact state="loading" />
          <StatePanel v-else-if="selectedDetail.phase === 'error'" compact :state="selectedDetail.error?.kind === 'forbidden' ? 'forbidden' : 'error'" :description="selectedDetail.error?.message" @retry="store.loadActivityDetail(selectedEventId, null, true)" />
          <template v-else-if="selectedDetail.value">
            <!-- M13b-F12（二轮 A18）：详情字段统一中文术语（与术语表对齐：事件标识/类别/操作者/关联对象），
             事件类型走词表而非裸常量。 -->
            <dl><div><dt>事件标识</dt><dd class="mono">{{ selectedDetail.value.eventId }}</dd></div><div><dt>类型</dt><dd>{{ enumLabel(selectedDetail.value.eventType, activityEventTypeLabels) }}</dd></div><div><dt>类别</dt><dd>{{ enumLabel(selectedDetail.value.category, activityCategoryLabels) }}</dd></div><div><dt>操作者</dt><dd>{{ actorName(selectedDetail.value.actor.principalId, selectedDetail.value.actor.type) }}</dd></div><div><dt>关联对象</dt><dd>{{ enumLabel(selectedDetail.value.subject.type, activitySubjectTypeLabels) }}<template v-for="reference in selectedDetail.value.references" :key="`${reference.type}:${reference.id}`"> <RouterLink :to="referenceTarget(reference, selectedDetail.value.eventId)">{{ referenceLabel(reference.type) }}</RouterLink></template></dd></div><div><dt>发生时间</dt><dd>{{ displayTime(selectedDetail.value.occurredAt) }}</dd></div></dl>
            <section><h3>公开摘要</h3><p v-if="Object.keys(selectedDetail.value.payload.values).length === 0">此事件没有公开摘要字段。</p><dl v-else><div v-for="(value, key) in selectedDetail.value.payload.values" :key="key"><dt>{{ key }}</dt><dd>{{ value }}</dd></div></dl></section>
          </template>
        </aside>
      </div>
    </div>
  </AppShell>
</template>

<style scoped>
/* M13b-F09（S5）：宽度交给 page-shell 的 --cs-content-max，本页不再自带 1240。 */
.activity-page { display: grid; gap: var(--cs-space-16); }
.activity-workspace { display: grid; grid-template-columns: minmax(0, 1fr); gap: var(--cs-space-16); align-items: start; }.activity-workspace.has-detail { grid-template-columns: minmax(0, 1fr) 330px; }.activity-detail { position: sticky; top: 12px; overflow: hidden; }.activity-detail > header { display: flex; align-items: center; justify-content: space-between; padding: var(--cs-space-16) var(--cs-space-16); border-bottom: 1px solid var(--cs-border); }.activity-detail h2 { margin: var(--cs-space-2) 0 0; font-size: var(--cs-text-md); }.activity-detail header button { display: grid; width: 32px; height: 32px; place-items: center; border-radius: 8px; background: var(--cs-surface-subtle); cursor: pointer; }.activity-detail > dl, .activity-detail > section { margin: 0; padding: var(--cs-space-16) var(--cs-space-16); }.activity-detail > section { border-top: 1px solid var(--cs-border); }.activity-detail h3 { margin: 0 0 var(--cs-space-8); font-size: var(--cs-text-sm); }.activity-detail section > p { color: var(--cs-text-muted); font-size: var(--cs-text-xs); }.activity-detail dl { display: grid; gap: var(--cs-space-8); }.activity-detail dt { color: var(--cs-text-muted); font-size: var(--cs-text-xs); font-weight: var(--cs-weight-semibold); text-transform: uppercase; }.activity-detail dd { overflow-wrap: anywhere; margin: var(--cs-space-2) 0 0; font-size: var(--cs-text-xs); }
@media (max-width: 820px) { .activity-workspace.has-detail { grid-template-columns: 1fr; }.activity-detail { position: static; grid-row: 1; } } @media (max-width: 520px) { .activity-page { gap: var(--cs-space-12); } }

/* M12b-F01：悬停反馈补齐——此前这些可点元素对指针移动完全沉默。 */
.activity-detail header button:hover:not(:disabled) { background: var(--cs-surface-accent); }
.activity-detail header button:active:not(:disabled) { transform: scale(.97); }
.activity-detail header button { transition: background-color var(--cs-motion-fast) var(--cs-ease-out), border-color var(--cs-motion-fast) var(--cs-ease-out), transform var(--cs-motion-fast) var(--cs-ease-out); }
</style>
