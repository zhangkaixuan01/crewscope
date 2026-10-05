<script setup lang="ts">
import { GitBranchPlus, ListRestart, RefreshCw, X } from '@lucide/vue'
import { computed, inject, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { AUTH_PRINCIPAL, can, permissions } from '../app/auth'
import { useNetworkStatus } from '../app/network'
import AppShell from '../components/layout/AppShell.vue'
import BaseButton from '../components/base/BaseButton.vue'
import RelativeTime from '../components/base/RelativeTime.vue'
import RepositoryBuildDialog from '../components/domain/RepositoryBuildDialog.vue'
import KnowledgeIndexJobList from '../components/domain/KnowledgeIndexJobList.vue'
import StatePanel from '../components/feedback/StatePanel.vue'
import { usePageRequestScope } from '../composables/usePageRequestScope'
import { useKnowledgeIndexStore } from '../domains/knowledge/index-store'
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
const online = useNetworkStatus()

const buildOpen = ref(false)
const autoRefresh = ref(true)
const lastRefreshedAt = ref<string | null>(null)
const pageVisible = ref(typeof document === 'undefined' ? true : !document.hidden)
let refreshTimer: number | null = null

const scope = computed<SettingsScope | null>(() => principal && scopeStore.state.selectedTeamId
  ? { organizationId: principal.organizationId, teamId: scopeStore.state.selectedTeamId }
  : null)
const scopeMissing = computed(() => scopeStore.state.phase === 'ready' && !scope.value)
/** D5: commands and the technical block are knowledge-manager-only; the read surface is not. */
const canManage = computed(() => Boolean(principal && can(principal, permissions.knowledgeManage)))

const sourceFilter = computed<KnowledgeIndexJobSource | 'ALL'>(() => oneOf(route.query.source, ['ALL', ...knowledgeIndexJobSources] as const, 'ALL'))
const statusFilter = computed<KnowledgeIndexJobStatus | 'ALL'>(() => oneOf(route.query.status, ['ALL', ...knowledgeIndexJobStatuses] as const, 'ALL'))
const filter = computed<KnowledgeIndexJobFilter>(() => ({
  ...(sourceFilter.value === 'ALL' ? {} : { source: sourceFilter.value }),
  ...(statusFilter.value === 'ALL' ? {} : { status: statusFilter.value }),
}))

const cancellingJobId = computed(() =>
  store.state.command.phase === 'pending' && store.state.command.operation === 'cancel' ? store.state.command.jobId : null)
const rebuildPending = computed(() => store.state.command.phase === 'pending' && store.state.command.operation === 'rebuild')
const buildPending = computed(() => store.state.command.phase === 'pending' && store.state.command.operation === 'repository-build')
const buildError = computed(() => buildOpen.value && store.state.command.operation === 'repository-build' && store.state.command.phase === 'error'
  ? store.state.command
  : null)
/** The dialog owns repository-build errors while it stays open; every other outcome pages here. */
const banner = computed(() => {
  const command = store.state.command
  if (command.phase !== 'success' && command.phase !== 'error') return null
  if (buildError.value) return null
  return command
})

watch(
  () => [
    scopeStore.state.phase,
    scope.value?.organizationId,
    scope.value?.teamId,
    sourceFilter.value,
    statusFilter.value,
  ] as const,
  async ([phase]) => {
    const pageOwner = pageRequests.capture()
    stopTimer()
    store.clearCommand()
    buildOpen.value = false
    if (phase !== 'ready' || !scope.value) return
    store.activateScope(scope.value)
    await store.loadJobs(filter.value, false, true)
    if (!pageOwner.isCurrent()) return
    lastRefreshedAt.value = new Date().toISOString()
    startTimer()
  },
  { immediate: true },
)

watch(
  () => [scopeStore.state.phase, route.query.source, route.query.status] as const,
  ([phase]) => {
    if (phase !== 'ready') return
    const query = { ...route.query }
    let changed = false
    changed = normalizeQuery(query, 'source', sourceFilter.value, 'ALL') || changed
    changed = normalizeQuery(query, 'status', statusFilter.value, 'ALL') || changed
    if (changed) void router.replace({ query })
  },
  { immediate: true },
)

watch([online, autoRefresh], () => { stopTimer(); startTimer() })
onBeforeUnmount(stopTimer)

function changeSource(value: KnowledgeIndexJobSource | 'ALL'): void {
  replaceFilter('source', value, 'ALL')
}

function changeStatus(value: KnowledgeIndexJobStatus | 'ALL'): void {
  replaceFilter('status', value, 'ALL')
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
  if (success) await refresh()
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

function closeBuild(): void {
  buildOpen.value = false
  store.clearCommand()
}

function startTimer(): void {
  stopTimer()
  // Offline mode retains the last facts and never lets a background timer create failing traffic.
  if (!scope.value || !online.value || !autoRefresh.value || !pageVisible.value) return
  refreshTimer = window.setInterval(() => { void refresh() }, 15_000)
}
function stopTimer(): void { if (refreshTimer !== null) window.clearInterval(refreshTimer); refreshTimer = null }

function onVisibilityChange(): void {
  pageVisible.value = !document.hidden
  stopTimer()
  if (pageVisible.value) {
    void refresh()
    startTimer()
  }
}

onMounted(() => document.addEventListener('visibilitychange', onVisibilityChange))
onBeforeUnmount(() => document.removeEventListener('visibilitychange', onVisibilityChange))

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

function queryValue(value: unknown): string | null {
  return typeof value === 'string' && value.length > 0 ? value : null
}
</script>

<template>
  <AppShell title="索引作业" eyebrow="团队 · 知识管理">
    <template #actions>
      <label class="auto-refresh"><input v-model="autoRefresh" type="checkbox">15 秒自动刷新</label>
      <span v-if="lastRefreshedAt" class="last-refreshed">上次刷新于 <RelativeTime :value="lastRefreshedAt" /></span>
      <BaseButton variant="secondary" size="small" :disabled="!scope || !online" :aria-describedby="scopeMissing ? 'knowledge-index-scope-reason' : undefined" @click="refresh"><RefreshCw :size="14" />刷新</BaseButton>
      <BaseButton v-if="canManage" variant="secondary" size="small" :disabled="!online" :loading="rebuildPending" @click="rebuild"><ListRestart :size="14" />重建知识索引</BaseButton>
      <BaseButton v-if="canManage" variant="secondary" size="small" :disabled="!online" @click="buildOpen = true"><GitBranchPlus :size="14" />构建仓库索引</BaseButton>
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
        :can-manage="canManage"
        :cancelling-job-id="cancellingJobId"
        @change-source="changeSource"
        @change-status="changeStatus"
        @load-more="loadMoreJobs"
        @cancel="cancelJob"
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
</style>
