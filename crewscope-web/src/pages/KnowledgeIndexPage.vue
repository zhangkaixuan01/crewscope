<script setup lang="ts">
import { GitBranchPlus, ListRestart, MoreHorizontal, RefreshCw, X } from '@lucide/vue'
import { computed, inject, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { normalizeQuery, oneOf, uuidQuery } from '../utils/queryParams'
import { AUTH_PRINCIPAL, can, permissions } from '../app/auth'
import { secureId } from '../api/secureId'
import { useNetworkStatus } from '../app/network'
import AppShell from '../components/layout/AppShell.vue'
import BaseButton from '../components/base/BaseButton.vue'
import BaseMenu from '../components/base/BaseMenu.vue'
import RelativeTime from '../components/base/RelativeTime.vue'
import RepositoryBuildDialog from '../components/domain/RepositoryBuildDialog.vue'
import KnowledgeIndexJobList from '../components/domain/KnowledgeIndexJobList.vue'
import ActionConfirmDialog from '../components/shared/ActionConfirmDialog.vue'
import StatePanel from '../components/feedback/StatePanel.vue'
import { useAutoRefresh } from '../composables/useAutoRefresh'
import { usePageRequestScope } from '../composables/usePageRequestScope'
import { useKnowledgeIndexStore } from '../domains/knowledge/index-store'
import { useKnowledgeStore } from '../domains/knowledge/store'
import {
  knowledgeIndexJobSources,
  knowledgeIndexJobStatuses,
  type KnowledgeIndexJobFilter,
  type KnowledgeIndexJobSource,
  type KnowledgeIndexJobStatus,
  type RepositoryBuildInput,
} from '../domains/knowledge/types'
import type { SettingsScope } from '../domains/settings/types'
import { useScopeStore } from '../domains/scope/store'

const pageRequests = usePageRequestScope()

const route = useRoute()
const router = useRouter()
const principal = inject(AUTH_PRINCIPAL)
const scopeStore = useScopeStore()
const store = useKnowledgeIndexStore()
// M13b-A04/R16：作业行要显示条目标题——作业 DTO 无可读字段，复用条目 store 的第一页做本地映射。
const knowledgeStore = useKnowledgeStore()
const online = useNetworkStatus()

const buildOpen = ref(false)
// M13b-F03（R31）：重建是整队列替换的显式操作——先确认影响再发命令，不再一键直发。
const rebuildConfirmOpen = ref(false)
const moreOpen = ref(false)
const autoRefresh = ref(true)
const autoRefreshTimer = useAutoRefresh(() => void refresh(), {
  interval: 15_000,
  // 离线保留已读事实、不开后台定时器制造失败流量。
  active: () => Boolean(scope.value && online.value && autoRefresh.value),
})
const lastRefreshedAt = ref<string | null>(null)

const scope = computed<SettingsScope | null>(() => principal && scopeStore.state.selectedTeamId
  ? { organizationId: principal.organizationId, teamId: scopeStore.state.selectedTeamId }
  : null)
const scopeMissing = computed(() => scopeStore.state.phase === 'ready' && !scope.value)
/** D5: commands and the technical block are knowledge-manager-only; the read surface is not. */
const canManage = computed(() => Boolean(principal && can(principal, permissions.knowledgeManage)))

const sourceFilter = computed<KnowledgeIndexJobSource | 'ALL'>(() => oneOf(route.query.source, ['ALL', ...knowledgeIndexJobSources] as const, 'ALL'))
const statusFilter = computed<KnowledgeIndexJobStatus | 'ALL'>(() => oneOf(route.query.status, ['ALL', ...knowledgeIndexJobStatuses] as const, 'ALL'))
/** R16: the entry-side deep link (?entryId=) narrows the listing to one entry's jobs. */
const entryFilterId = computed(() => uuidQuery(route.query.entryId))
const filter = computed<KnowledgeIndexJobFilter>(() => ({
  ...(sourceFilter.value === 'ALL' ? {} : { source: sourceFilter.value }),
  ...(statusFilter.value === 'ALL' ? {} : { status: statusFilter.value }),
  ...(entryFilterId.value ? { entryId: entryFilterId.value } : {}),
}))

/** entryId → readable title (draft title or entryKey — the L04 list-primary-text rule). */
const entryTitles = computed<Record<string, string>>(() => {
  const map: Record<string, string> = {}
  for (const entry of knowledgeStore.state.entries.value ?? []) {
    map[entry.id] = entry.draft?.title?.trim() || entry.entryKey
  }
  return map
})
const entryFilterLabel = computed(() => entryFilterId.value ? entryTitles.value[entryFilterId.value] ?? null : null)

const cancellingJobId = computed(() =>
  store.state.command.phase === 'pending' && store.state.command.operation === 'cancel' ? store.state.command.jobId : null)
/** The refresh slot names the entry (not the job) — the retried row spins while the 202 lands. */
const refreshingEntryId = computed(() =>
  store.state.command.phase === 'pending' && store.state.command.operation === 'refresh-entry' ? store.state.command.jobId : null)
const rebuildPending = computed(() => store.state.command.phase === 'pending' && store.state.command.operation === 'rebuild')
const buildPending = computed(() => store.state.command.phase === 'pending' && store.state.command.operation === 'repository-build')
const buildError = computed(() => buildOpen.value && store.state.command.operation === 'repository-build' && store.state.command.phase === 'error'
  ? store.state.command
  : null)
const rebuildError = computed(() => rebuildConfirmOpen.value && store.state.command.operation === 'rebuild' && store.state.command.phase === 'error'
  ? store.state.command
  : null)
/** The dialogs own their command errors while they stay open; every other outcome pages here. */
const banner = computed(() => {
  const command = store.state.command
  if (command.phase !== 'success' && command.phase !== 'error') return null
  if (buildError.value || rebuildError.value) return null
  return command
})

watch(
  () => [
    scopeStore.state.phase,
    scope.value?.organizationId,
    scope.value?.teamId,
    sourceFilter.value,
    statusFilter.value,
    entryFilterId.value,
  ] as const,
  async ([phase]) => {
    const pageOwner = pageRequests.capture()
    autoRefreshTimer.stop()
    store.clearCommand()
    buildOpen.value = false
    if (phase !== 'ready' || !scope.value) return
    store.activateScope(scope.value)
    // R16: the title map rides the entry store's first page (already loaded when coming from
    // the knowledge page; loaded here when the index page is entered cold).
    knowledgeStore.activateScope(scope.value)
    void knowledgeStore.loadEntries({}, false, true)
    await store.loadJobs(filter.value, false, true)
    if (!pageOwner.isCurrent()) return
    lastRefreshedAt.value = new Date().toISOString()
    autoRefreshTimer.start()
  },
  { immediate: true },
)

watch(
  () => [scopeStore.state.phase, route.query.source, route.query.status, route.query.entryId] as const,
  ([phase]) => {
    if (phase !== 'ready') return
    const query = { ...route.query }
    let changed = false
    changed = normalizeQuery(query, 'source', sourceFilter.value, 'ALL') || changed
    changed = normalizeQuery(query, 'status', statusFilter.value, 'ALL') || changed
    changed = normalizeQuery(query, 'entryId', entryFilterId.value ?? '', '') || changed
    if (changed) void router.replace({ query })
  },
  { immediate: true },
)

// 离线/关自动刷新即停，恢复即重启；页面隐藏的闸在 composable 里（M12b-F03）。
watch([online, autoRefresh], () => autoRefreshTimer.start())

function changeSource(value: KnowledgeIndexJobSource | 'ALL'): void {
  replaceFilter('source', value, 'ALL')
}

function changeStatus(value: KnowledgeIndexJobStatus | 'ALL'): void {
  replaceFilter('status', value, 'ALL')
}

function clearEntryFilter(): void {
  const query = { ...route.query }
  delete query.entryId
  void router.replace({ query })
}

function replaceFilter(key: string, value: string, defaultValue: string): void {
  const query = { ...route.query }
  if (value === defaultValue) delete query[key]
  else query[key] = value
  void router.replace({ query })
}

async function refresh(): Promise<void> {
  const pageOwner = pageRequests.capture()
  if (!scope.value || !online.value) return
  await store.loadJobs(filter.value, false, true)
  if (!pageOwner.isCurrent()) return
  lastRefreshedAt.value = new Date().toISOString()
}

function loadMoreJobs(): void {
  void store.loadJobs(filter.value, true)
}

async function rebuild(): Promise<void> {
  const pageOwner = pageRequests.captureSelection()
  if (!online.value || rebuildPending.value) return
  const success = await store.rebuild()
  if (!pageOwner.isCurrent()) return
  // The store invalidates the listing; the refresh pulls the fresh queue in immediately (D2).
  if (success) {
    rebuildConfirmOpen.value = false
    await refresh()
  }
}

function cancelRebuild(): void {
  rebuildConfirmOpen.value = false
  if (store.state.command.operation === 'rebuild' && store.state.command.phase === 'error') store.clearCommand()
}

async function submitBuild(input: RepositoryBuildInput): Promise<void> {
  const pageOwner = pageRequests.captureSelection()
  const success = await store.enqueueRepositoryBuild(input)
  if (!pageOwner.isCurrent()) return
  if (success) {
    buildOpen.value = false
    await refresh()
  }
}

async function cancelJob(jobId: string): Promise<void> {
  const pageOwner = pageRequests.captureSelection()
  if (!online.value) return
  await store.cancelJob(jobId)
  if (!pageOwner.isCurrent()) return
  // A 200 already replaced the row; a 404 means the row predates the listing — realign it.
  if (store.state.command.phase === 'error' && store.state.command.errorStatus === 404) await refresh()
}

/** R17: a failed knowledge-entry job retries per entry (A04) — each click is a fresh command. */
async function retryEntry(entryId: string): Promise<void> {
  const pageOwner = pageRequests.captureSelection()
  if (!online.value) return
  const success = await store.refreshEntry(entryId, secureId())
  if (!pageOwner.isCurrent()) return
  // The store invalidates the listing; pull the entry-filtered queue in immediately.
  if (success) await refresh()
}

function closeBuild(): void {
  buildOpen.value = false
  store.clearCommand()
}

</script>

<template>
  <AppShell title="索引作业" eyebrow="团队 · 知识管理">
    <!-- M13b-F10（L16）：header 只留「刷新」+「构建」；「重建」与自动刷新（含上次刷新时间）
         收进 overflow 菜单——≤767 下 5 个 header action 会排成约 190px 的三行。 -->
    <template #actions>
      <BaseButton variant="secondary" size="small" :disabled="!scope || !online" :aria-describedby="scopeMissing ? 'knowledge-index-scope-reason' : undefined" @click="refresh"><RefreshCw :size="14" />刷新</BaseButton>
      <BaseButton v-if="canManage" variant="secondary" size="small" :disabled="!online" @click="buildOpen = true"><GitBranchPlus :size="14" />构建仓库索引</BaseButton>
      <BaseMenu :open="moreOpen" label="更多索引动作" trigger-label="更多索引动作" initial-focus="none" placement="bottom-end" trigger-class="index-more-toggle touch-target" menu-class="index-more-menu" @update:open="moreOpen = $event">
        <template #trigger><MoreHorizontal :size="14" />更多</template>
        <div class="index-more">
          <p v-if="lastRefreshedAt" class="last-refreshed">上次刷新于 <RelativeTime :value="lastRefreshedAt" /></p>
          <label class="auto-refresh"><input v-model="autoRefresh" type="checkbox">15 秒自动刷新</label>
          <BaseButton v-if="canManage" variant="secondary" size="small" class="index-more__rebuild" :disabled="!online" :loading="rebuildPending" @click="rebuildConfirmOpen = true"><ListRestart :size="14" />重建知识索引</BaseButton>
        </div>
      </BaseMenu>
    </template>

    <StatePanel v-if="scopeMissing" id="knowledge-index-scope-reason" state="empty" title="请选择 Team" description="索引作业始终属于明确的 Organization 与 Team。" />

    <div v-if="scopeStore.state.phase === 'ready' && scope" class="index-workspace">
      <p v-if="banner?.phase === 'success'" class="index-banner index-banner--success" role="status">
        {{ banner.message }}
        <button type="button" aria-label="关闭提示" @click="store.clearCommand()"><X :size="14" aria-hidden="true" /></button>
      </p>
      <p v-else-if="banner?.phase === 'error'" class="index-banner index-banner--error" role="alert">
        {{ banner.errorMessage }}
        <button type="button" aria-label="关闭提示" @click="store.clearCommand()"><X :size="14" aria-hidden="true" /></button>
      </p>

      <KnowledgeIndexJobList
        :phase="store.state.jobs.phase"
        :items="store.state.jobs.value ?? []"
        :next-after="store.state.jobs.nextAfter"
        :loading-more="store.state.jobs.loadingMore"
        :error-message="store.state.jobs.errorMessage"
        :error-status="store.state.jobs.errorStatus"
        :online="online"
        :source-filter="sourceFilter"
        :status-filter="statusFilter"
        :entry-filter-id="entryFilterId"
        :entry-filter-label="entryFilterLabel"
        :entry-titles="entryTitles"
        :can-manage="canManage"
        :cancelling-job-id="cancellingJobId"
        :refreshing-entry-id="refreshingEntryId"
        @change-source="changeSource"
        @change-status="changeStatus"
        @clear-entry-filter="clearEntryFilter"
        @load-more="loadMoreJobs"
        @cancel="cancelJob"
        @retry-entry="retryEntry"
        @retry="refresh"
      />
    </div>

    <RepositoryBuildDialog
      v-if="buildOpen"
      :scope="scope"
      :submitting="buildPending"
      :error-message="buildError?.errorMessage ?? null"
      :error-code="buildError?.errorCode ?? null"
      :error-status="buildError?.errorStatus ?? null"
      @close="closeBuild"
      @submit="submitBuild"
    />

    <ActionConfirmDialog
      v-if="rebuildConfirmOpen"
      heading="重建知识索引"
      impact="会为当前 Team 重新排队全部条目的索引构建：现有队列被替换为新的重建任务，期间语义检索命中可能不完整，直到全部任务完成。条目内容本身不受影响。"
      confirm-label="确认重建"
      close-label="关闭重建确认"
      cancel-label="取消"
      width="min(500px, 100%)"
      :submitting="rebuildPending"
      :error-message="rebuildError?.errorMessage ?? null"
      @cancel="cancelRebuild"
      @confirm="rebuild"
    />
  </AppShell>
</template>

<style scoped>
.index-workspace { display: grid; min-width: 0; gap: var(--cs-space-12); }
.index-banner { display: flex; align-items: center; justify-content: space-between; gap: var(--cs-space-8); margin: 0; padding: var(--cs-space-8) var(--cs-space-12); border-radius: var(--cs-radius-md); font-size: var(--cs-text-sm); }
.index-banner--success { border: 1px solid var(--cs-border); background: var(--cs-surface-subtle); color: var(--cs-text-secondary); }
.index-banner--error { border: 1px solid var(--cs-danger-border); background: var(--cs-surface); color: var(--cs-danger); }
.index-banner button { display: grid; width: 28px; height: 28px; place-items: center; border: none; border-radius: 6px; background: transparent; color: inherit; cursor: pointer; }
.index-banner button:focus-visible { outline: none; box-shadow: var(--cs-focus-ring); }
.auto-refresh { display: flex; align-items: center; gap: var(--cs-space-8); color: var(--cs-text-muted); font-size: var(--cs-text-xs); font-weight: var(--cs-weight-semibold); }
.auto-refresh input { accent-color: var(--cs-focus); }
.last-refreshed { color: var(--cs-text-muted); font-size: var(--cs-text-xs); }
/* L16 的 overflow 触发钮与 header 里的 BaseButton 同档外观。 */
.index-more-toggle { display: inline-flex; min-height: 30px; align-items: center; gap: var(--cs-space-4); padding: 0 var(--cs-space-8); border: 1px solid var(--cs-border); border-radius: 8px; color: var(--cs-text-brand); font-size: var(--cs-text-xs); font-weight: var(--cs-weight-semibold); cursor: pointer; }
.index-more { display: grid; gap: var(--cs-space-8); min-width: 220px; padding: var(--cs-space-12); }
.index-more .last-refreshed { margin: 0; }
.index-more__rebuild { justify-self: start; }
/* 767 下 header actions 固定 2+1 两行（AppShell），trigger 落第二行左缘——bottom-end 右对齐
   会把 ~230px 弹层推出视口左缘（实测 left:-174）。窄断点改从 trigger 左缘向右展开并钳宽。 */
@media (max-width: 767px) {
  :deep(.index-more-menu) { left: 0; right: auto; width: min(244px, calc(100vw - 32px)); }
}

/* M12b-F01：悬停反馈补齐——此前这些可点元素对指针移动完全沉默。 */
.index-banner button:hover:not(:disabled) { background: var(--cs-surface-accent); }
.index-banner button:active:not(:disabled) { transform: scale(.97); }
.index-banner button { transition: background-color var(--cs-motion-fast) var(--cs-ease-out), border-color var(--cs-motion-fast) var(--cs-ease-out), transform var(--cs-motion-fast) var(--cs-ease-out); }
</style>
