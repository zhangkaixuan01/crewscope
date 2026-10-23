<script setup lang="ts">
import { RefreshCw } from '@lucide/vue'
import { computed, inject, ref, watch } from 'vue'
import { AUTH_PRINCIPAL, can, permissions } from '../app/auth'
import { useNetworkStatus } from '../app/network'
import BaseButton from '../components/base/BaseButton.vue'
import RelativeTime from '../components/base/RelativeTime.vue'
import OperationsWorkspace from '../components/domain/OperationsWorkspace.vue'
import StatePanel from '../components/feedback/StatePanel.vue'
import AppShell from '../components/layout/AppShell.vue'
import { useScopeStore } from '../domains/scope/store'
import { useTeamOpsStore } from '../domains/teamops/store'
import type { ProjectionCommand, RecoveryCandidate, TeamOpsScope } from '../domains/teamops/types'
import { useAutoRefresh } from '../composables/useAutoRefresh'
import { usePageRequestScope } from '../composables/usePageRequestScope'

const pageRequests = usePageRequestScope()

const principal = inject(AUTH_PRINCIPAL)
const scopeStore = useScopeStore()
const store = useTeamOpsStore()
const online = useNetworkStatus()
const autoRefresh = ref(true)
const autoRefreshTimer = useAutoRefresh(() => void refresh(true), {
  interval: 15_000,
  // 离线保留已读事实、不开后台定时器制造失败流量。
  active: () => Boolean(scope.value && online.value && autoRefresh.value),
})
const scope = computed<TeamOpsScope | null>(() => principal && scopeStore.state.selectedTeamId
  ? { organizationId: principal.organizationId, teamId: scopeStore.state.selectedTeamId }
  : null)
const canManage = computed(() => Boolean(principal && can(principal, permissions.operationsManage)))
const lastRefreshedAt = ref<string | null>(null)

watch(
  () => [scopeStore.state.phase, scope.value?.organizationId, scope.value?.teamId, canManage.value] as const,
  async ([phase]) => {
    const pageOwner = pageRequests.capture()
    autoRefreshTimer.stop()
    if (phase !== 'ready' || !scope.value) return
    store.activateScope(scope.value)
    await refresh(true)
    if (!pageOwner.isCurrent()) return
    autoRefreshTimer.start()
  },
  { immediate: true },
)

// 离线/关自动刷新即停，恢复即重启；页面隐藏的闸在 composable 里（M12b-F03）。
watch([online, autoRefresh], () => autoRefreshTimer.start())

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

</script>

<template>
  <!-- M13b-F10（L06）：「运行与发布」是工作区页面（主 rail「工作」组），不再套设置外壳——
    设置目录是它不需要的第二套导航，还会把内容宽度从 1156 压到 946。 -->
  <AppShell title="运行与发布" eyebrow="运维 · 健康与演示证据">
    <template #actions>
      <label class="auto-refresh"><input v-model="autoRefresh" type="checkbox">15 秒自动刷新</label>
      <span v-if="lastRefreshedAt" class="last-refreshed">上次刷新于 <RelativeTime :value="lastRefreshedAt" /></span>
      <BaseButton size="small" variant="secondary" :disabled="!scope" :aria-describedby="scope ? undefined : 'operations-scope-reason'" @click="refresh(true)"><RefreshCw :size="14" />刷新</BaseButton>
    </template>
    <div class="operations-page page-shell">
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
    </div>
  </AppShell>
</template>

<style scoped>
.auto-refresh { display: flex; align-items: center; gap: var(--cs-space-8); color: var(--cs-text-muted); font-size: var(--cs-text-xs); font-weight: var(--cs-weight-semibold); }
.auto-refresh input { accent-color: var(--cs-focus); }
.last-refreshed { color: var(--cs-text-muted); font-size: var(--cs-text-xs); }
</style>
