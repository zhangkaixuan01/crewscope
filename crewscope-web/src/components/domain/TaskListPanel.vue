<script setup lang="ts">
import { Bot, CircleUserRound, Clock3, Filter, LoaderCircle, RefreshCw } from '@lucide/vue'
import { computed } from 'vue'
import type { TaskPhase } from '../../domains/task/store'
import { taskStatuses, type TaskStatus, type TaskSummary } from '../../domains/task/types'
import { taskExecutionWaitReasonLabels } from '../../domains/task/labels'
import { enumLabel } from '../../domains/shared/labels'
import BaseButton from '../base/BaseButton.vue'
import StatusBadge from '../base/StatusBadge.vue'
import StatePanel from '../feedback/StatePanel.vue'
import type { SemanticTone } from '../base/types'

const props = defineProps<{
  phase: TaskPhase
  items: TaskSummary[]
  status: TaskStatus | 'all'
  ownerPrincipalId: string | 'all'
  owners: Array<{ principalId: string, displayName: string }>
  selectedTaskId: string | null
  nextCursor: string | null
  loadingMore: boolean
  errorMessage: string | null
  onStatusChange: (value: TaskStatus | 'all') => void
  onOwnerChange: (value: string | 'all') => void
  onSelect: (task: TaskSummary) => void
  onOpenWorkItem: (task: TaskSummary) => void
  onRetry: () => void
  onLoadMore: () => void
}>()

/** 状态与负责人筛选由父页面持有；空结果里唯一可执行的下一步就是把这两个可选筛选复位。 */
const hasFilter = computed(() => props.status !== 'all' || props.ownerPrincipalId !== 'all')

function clearFilters(): void {
  props.onStatusChange('all')
  props.onOwnerChange('all')
}

const statusLabels: Record<TaskStatus, string> = {
  CREATED: '已创建', ACTIVE: '执行中', WAITING: '等待中', COMPLETED: '已完成',
  FAILED: '失败', CANCELLED: '已取消',
}

function statusTone(status: TaskStatus): SemanticTone {
  if (status === 'COMPLETED') return 'success'
  if (status === 'FAILED' || status === 'CANCELLED') return 'danger'
  if (status === 'WAITING') return 'warning'
  return status === 'ACTIVE' ? 'agent' : 'neutral'
}

function executionLabel(task: TaskSummary): string {
  return task.currentAttempt === null
    ? '尚未创建 attempt'
    : `Attempt ${task.currentAttempt} · ${task.currentExecutionStatus ?? 'UNKNOWN'}`
}

function ownerName(task: TaskSummary, owners: Array<{ principalId: string, displayName: string }>): string {
  return owners.find(owner => owner.principalId === task.ownerPrincipalId)?.displayName
    ?? (task.ownerPrincipalId ? `成员 ${task.ownerPrincipalId.slice(0, 8)}` : '未记录 Owner')
}
</script>

<template>
  <section class="task-panel panel" aria-labelledby="control-task-heading">
    <header class="task-panel__header">
      <div>
        <p class="eyebrow">Control Mode · Durable runtime</p>
        <h2 id="control-task-heading">Agent Tasks</h2>
        <span>团队可观测的委托任务、当前 attempt 与等待原因。</span>
      </div>
      <div class="task-filters" aria-label="Task 筛选">
        <label><span>状态</span><select :value="status" @change="onStatusChange(($event.target as HTMLSelectElement).value as TaskStatus | 'all')"><option value="all">全部状态</option><option v-for="value in taskStatuses" :key="value" :value="value">{{ statusLabels[value] }}</option></select></label>
        <label><span>负责人</span><select :value="ownerPrincipalId" @change="onOwnerChange(($event.target as HTMLSelectElement).value)"><option value="all">全部负责人</option><option v-for="owner in owners" :key="owner.principalId" :value="owner.principalId">{{ owner.displayName }}</option></select></label>
      </div>
    </header>

    <StatePanel v-if="phase === 'loading' || phase === 'idle'" state="loading" title="正在加载 Agent Tasks" />
    <StatePanel v-else-if="phase === 'error'" state="error" :description="errorMessage ?? undefined" @retry="onRetry" />
    <StatePanel v-else-if="phase === 'empty'" state="empty" title="当前筛选下没有 Task" description="从工作项详情选择“交给 Agent 处理”，Task 会出现在这里。"><template v-if="hasFilter" #action><BaseButton variant="secondary" size="small" @click="clearFilters"><Filter :size="13" />清空状态与负责人筛选</BaseButton></template></StatePanel>
    <div v-else class="task-list">
      <article v-for="task in items" :key="task.id" class="task-card" :class="{ selected: task.id === selectedTaskId }">
        <button class="task-card__main" type="button" :data-task-id="task.id" :aria-label="`查看 Task：${task.objective}`" :aria-pressed="task.id === selectedTaskId" @click="onSelect(task)">
          <span class="task-card__icon"><Bot :size="17" /></span>
          <span class="task-card__copy">
            <span class="task-card__top"><strong>{{ task.objective }}</strong><StatusBadge :tone="statusTone(task.status)" dot>{{ statusLabels[task.status] }}</StatusBadge></span>
            <span class="task-card__meta"><span><CircleUserRound :size="13" />{{ ownerName(task, owners) }}</span><span><Clock3 :size="13" />{{ executionLabel(task) }}</span></span>
            <span v-if="task.currentWaitingReason" class="task-card__waiting">等待原因 · {{ enumLabel(task.currentWaitingReason, taskExecutionWaitReasonLabels) }}</span>
          </span>
        </button>
        <BaseButton size="small" variant="ghost" @click="onOpenWorkItem(task)">工作项</BaseButton>
      </article>
      <div v-if="nextCursor || errorMessage" class="task-list__more">
        <p v-if="errorMessage" role="alert">{{ errorMessage }}</p>
        <BaseButton v-if="nextCursor" size="small" variant="secondary" :loading="loadingMore" @click="onLoadMore"><LoaderCircle v-if="loadingMore" :size="13" /><span v-else>加载更多</span></BaseButton>
        <BaseButton v-else-if="errorMessage" size="small" variant="ghost" @click="onRetry"><RefreshCw :size="13" />重试</BaseButton>
      </div>
    </div>
  </section>
</template>

<style scoped>
.task-panel { overflow: hidden; }.task-panel__header { display: flex; align-items: end; justify-content: space-between; gap: var(--cs-space-20); padding: var(--cs-space-20); border-bottom: 1px solid var(--cs-border); }.task-panel__header h2 { margin: 0; font-size: var(--cs-text-lg); }.task-panel__header > div:first-child > span { color: var(--cs-text-muted); font-size: var(--cs-text-xs); }.task-filters { display: grid; min-width: min(390px, 48%); grid-template-columns: 1fr 1fr; gap: var(--cs-space-8); }.task-filters label { display: grid; gap: var(--cs-space-4); color: var(--cs-text-secondary); font-size: var(--cs-text-xs); font-weight: var(--cs-weight-semibold); }.task-filters select { min-height: var(--cs-density-control-height); padding: 0 var(--cs-space-8); border: 1px solid var(--cs-border-strong); border-radius: var(--cs-radius-sm); background: var(--cs-surface-subtle); color: var(--cs-text); font: var(--cs-text-base) var(--cs-font-sans); }.task-panel :deep(.state-panel) { border: 0; border-radius: 0; }.task-list { display: grid; gap: var(--cs-space-8); padding: var(--cs-space-12); }.task-card { display: grid; grid-template-columns: minmax(0, 1fr) auto; align-items: center; gap: var(--cs-space-8); border: 1px solid var(--cs-border); border-radius: var(--cs-radius-md); background: var(--cs-surface); }.task-card.selected { border-color: var(--cs-border-accent-strong); box-shadow: 0 0 0 2px var(--cs-ring-brand); }.task-card__main { display: grid; min-width: 0; grid-template-columns: 36px minmax(0, 1fr); align-items: center; gap: var(--cs-space-12); padding: var(--cs-space-12) var(--cs-space-4) var(--cs-space-12) var(--cs-space-12); text-align: left; cursor: pointer; }.task-card__icon { display: grid; width: 36px; height: 36px; place-items: center; border-radius: 10px; background: var(--cs-agent-soft); color: var(--cs-agent); }.task-card__copy, .task-card__top, .task-card__meta { display: flex; min-width: 0; }.task-card__copy { flex-direction: column; gap: var(--cs-space-4); }.task-card__top { align-items: center; justify-content: space-between; gap: var(--cs-space-12); }.task-card__top strong { overflow: hidden; font-size: var(--cs-text-sm); text-overflow: ellipsis; white-space: nowrap; }.task-card__meta { flex-wrap: wrap; gap: var(--cs-space-12); color: var(--cs-text-muted); font-size: var(--cs-text-xs); }.task-card__meta span { display: inline-flex; align-items: center; gap: var(--cs-space-4); }.task-card__waiting { width: fit-content; padding: var(--cs-space-4) var(--cs-space-8); border-radius: 6px; background: var(--cs-warning-soft); color: var(--cs-warning); font-size: var(--cs-text-xs); }.task-card > :deep(button:last-child) { margin-right: var(--cs-space-12); }.task-list__more { display: grid; justify-items: center; gap: var(--cs-space-8); padding: var(--cs-space-8); }.task-list__more p { margin: 0; color: var(--cs-danger); font-size: var(--cs-text-xs); }
@media (max-width: 767px) { .task-panel__header { align-items: stretch; flex-direction: column; gap: var(--cs-space-16); padding: var(--cs-space-16); }.task-filters { min-width: 0; grid-template-columns: 1fr; }.task-list { padding: var(--cs-space-8); }.task-card { grid-template-columns: 1fr; }.task-card__main { padding: var(--cs-space-12); }.task-card > :deep(button:last-child) { width: calc(100% - 20px); margin: 0 var(--cs-space-12) var(--cs-space-12); }.task-card__top { align-items: flex-start; }.task-card__top strong { white-space: normal; } }
</style>
