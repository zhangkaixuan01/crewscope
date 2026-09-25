<script setup lang="ts">
import { RefreshCw } from '@lucide/vue'
import { computed, inject, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { AUTH_PRINCIPAL, can, permissions } from '../app/auth'
import { useNetworkStatus } from '../app/network'
import BaseButton from '../components/base/BaseButton.vue'
import OperationsWorkspace from '../components/domain/OperationsWorkspace.vue'
import StatePanel from '../components/feedback/StatePanel.vue'
import SettingsShell from '../components/settings/SettingsShell.vue'
import { useScopeStore } from '../domains/scope/store'
import { useTeamOpsStore } from '../domains/teamops/store'
import type { ProjectionCommand, RecoveryCandidate, TeamOpsScope } from '../domains/teamops/types'
import { formatRelativeTime } from '../composables/useRelativeTime'
import { usePageRequestScope } from '../composables/usePageRequestScope'

const pageRequests = usePageRequestScope()

const principal = inject(AUTH_PRINCIPAL)
const scopeStore = useScopeStore()
const store = useTeamOpsStore()
const online = useNetworkStatus()
const autoRefresh = ref(true)
const scope = computed<TeamOpsScope | null>(() => principal && scopeStore.state.selectedTeamId
  ? { organizationId: principal.organizationId, teamId: scopeStore.state.selectedTeamId }
  : null)
const canManage = computed(() => Boolean(principal && can(principal, permissions.operationsManage)))
let refreshTimer: number | null = null
const lastRefreshedAt = ref<string | null>(null)
const pageVisible = ref(typeof document === 'undefined' ? true : !document.hidden)

watch(
  () => [scopeStore.state.phase, scope.value?.organizationId, scope.value?.teamId, canManage.value] as const,
  async ([phase]) => {
    const pageOwner = pageRequests.capture()
    stopTimer()
    if (phase !== 'ready' || !scope.value) return
    store.activateScope(scope.value)
    await refresh(true)
    if (!pageOwner.isCurrent()) return
    startTimer()
  },
  { immediate: true },
)

watch([online, autoRefresh], () => { stopTimer(); startTimer() })
onBeforeUnmount(stopTimer)

async function refresh(force = false): Promise<void> {
  const pageOwner = pageRequests.capture()
  if (!scope.value) return
  await Promise.all([
    store.loadOperationsHealth(force),
    canManage.value ? store.loadDiagnostics(force) : Promise.resolve(),
  ])
  if (!pageOwner.isCurrent()) return
  lastRefreshedAt.value = new Date().toISOString()
}

async function recover(target: RecoveryCandidate, confirmation: string, idempotencyKey: string): Promise<void> {
  const pageOwner = pageRequests.capture()
  const success = await store.recover(target, confirmation, idempotencyKey)
  if (!pageOwner.isCurrent()) return
  await refresh(true)
  if (!pageOwner.isCurrent()) return
  if (!success && store.state.command.phase === 'conflict') return
}

async function runProjectionCommand(command: ProjectionCommand, idempotencyKey: string): Promise<void> {
  const pageOwner = pageRequests.capture()
  await store.runProjectionCommand(command, idempotencyKey)
  if (!pageOwner.isCurrent()) return
  await refresh(true)
  if (!pageOwner.isCurrent()) return
}

function startTimer(): void {
  stopTimer()
  // Offline mode retains the last facts and never lets a background timer create failing traffic.
  if (!scope.value || !online.value || !autoRefresh.value || !pageVisible.value) return
  refreshTimer = window.setInterval(() => { void refresh(true) }, 15_000)
}
function stopTimer(): void { if (refreshTimer !== null) window.clearInterval(refreshTimer); refreshTimer = null }

function onVisibilityChange(): void {
  pageVisible.value = !document.hidden
  stopTimer()
  if (pageVisible.value) {
    void refresh(true)
    startTimer()
  }
}

onMounted(() => document.addEventListener('visibilitychange', onVisibilityChange))
onBeforeUnmount(() => document.removeEventListener('visibilitychange', onVisibilityChange))
</script>

<template>
  <SettingsShell title="运行与发布" eyebrow="运维 · 健康与演示证据">
    <template #actions>
      <label class="auto-refresh"><input v-model="autoRefresh" type="checkbox">15 秒自动刷新</label>
      <span v-if="lastRefreshedAt" class="last-refreshed">上次刷新于 {{ formatRelativeTime(lastRefreshedAt) }}</span>
      <BaseButton size="small" variant="secondary" :disabled="!scope" :aria-describedby="scope ? undefined : 'operations-scope-reason'" @click="refresh(true)"><RefreshCw :size="14" />刷新</BaseButton>
    </template>
    <StatePanel v-if="scopeStore.state.phase === 'loading'" state="loading" title="正在恢复 Team Scope" />
    <StatePanel v-else-if="!scope" id="operations-scope-reason" state="empty" title="请选择 Team" description="运行健康属于明确的 Organization 与 Team。" />
    <OperationsWorkspace
      v-else :key="scope.teamId" :phase="store.state.operationsHealth.phase" :error="store.state.operationsHealth.error"
      :health="store.state.operationsHealth.value" :diagnostics-phase="store.state.diagnostics.phase"
      :diagnostics-error="store.state.diagnostics.error" :diagnostics="store.state.diagnostics.value"
      :command="store.state.command" :can-manage="canManage" :online="online"
      @refresh="refresh(true)" @recover="recover"
      @projection-command="runProjectionCommand" @clear-command="store.clearCommand"
    />
  </SettingsShell>
</template>

<style scoped>
.auto-refresh { display: flex; align-items: center; gap: var(--cs-space-8); color: var(--cs-text-muted); font-size: var(--cs-text-xs); font-weight: var(--cs-weight-semibold); }
.auto-refresh input { accent-color: var(--cs-focus); }
.last-refreshed { color: var(--cs-text-muted); font-size: var(--cs-text-xs); }
</style>
