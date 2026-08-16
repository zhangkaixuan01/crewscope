<script setup lang="ts">
import { Bot, CircleUserRound, Clock3, LoaderCircle, RefreshCw } from '@lucide/vue'
import type { TaskPhase } from '../../domains/task/store'
import { taskStatuses, type TaskStatus, type TaskSummary } from '../../domains/task/types'
import BaseButton from '../base/BaseButton.vue'
import StatusBadge from '../base/StatusBadge.vue'
import StatePanel from '../feedback/StatePanel.vue'
import type { SemanticTone } from '../base/types'

defineProps<{
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
    <StatePanel v-else-if="phase === 'empty'" state="empty" title="当前筛选下没有 Task" description="从工作项详情选择“交给 Agent 处理”，Task 会出现在这里。" />
    <div v-else class="task-list">
      <article v-for="task in items" :key="task.id" class="task-card" :class="{ selected: task.id === selectedTaskId }">
        <button class="task-card__main" type="button" :data-task-id="task.id" :aria-label="`查看 Task：${task.objective}`" :aria-pressed="task.id === selectedTaskId" @click="onSelect(task)">
          <span class="task-card__icon"><Bot :size="17" /></span>
          <span class="task-card__copy">
            <span class="task-card__top"><strong>{{ task.objective }}</strong><StatusBadge :tone="statusTone(task.status)" dot>{{ statusLabels[task.status] }}</StatusBadge></span>
            <span class="task-card__meta"><span><CircleUserRound :size="13" />{{ ownerName(task, owners) }}</span><span><Clock3 :size="13" />{{ executionLabel(task) }}</span></span>
            <span v-if="task.currentWaitingReason" class="task-card__waiting">等待原因 · {{ task.currentWaitingReason }}</span>
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
.task-panel { overflow: hidden; }.task-panel__header { display: flex; align-items: end; justify-content: space-between; gap: 20px; padding: 18px; border-bottom: 1px solid var(--cs-border); }.task-panel__header h2 { margin: 0; font-size: 17px; }.task-panel__header > div:first-child > span { color: var(--cs-text-muted); font-size: 9px; }.task-filters { display: grid; min-width: min(390px, 48%); grid-template-columns: 1fr 1fr; gap: 8px; }.task-filters label { display: grid; gap: 5px; color: var(--cs-text-secondary); font-size: 9px; font-weight: 750; }.task-filters select { min-height: 34px; padding: 0 9px; border: 1px solid var(--cs-border-strong); border-radius: var(--cs-radius-sm); background: var(--cs-surface-subtle); color: var(--cs-text); font: 10px var(--cs-font-sans); }.task-panel :deep(.state-panel) { border: 0; border-radius: 0; }.task-list { display: grid; gap: 7px; padding: 10px; }.task-card { display: grid; grid-template-columns: minmax(0, 1fr) auto; align-items: center; gap: 8px; border: 1px solid var(--cs-border); border-radius: var(--cs-radius-md); background: var(--cs-surface); }.task-card.selected { border-color: var(--cs-brand-300); box-shadow: 0 0 0 2px var(--cs-brand-50); }.task-card__main { display: grid; min-width: 0; grid-template-columns: 36px minmax(0, 1fr); align-items: center; gap: 10px; padding: 11px 4px 11px 11px; text-align: left; cursor: pointer; }.task-card__icon { display: grid; width: 36px; height: 36px; place-items: center; border-radius: 10px; background: var(--cs-agent-soft); color: var(--cs-agent); }.task-card__copy, .task-card__top, .task-card__meta { display: flex; min-width: 0; }.task-card__copy { flex-direction: column; gap: 5px; }.task-card__top { align-items: center; justify-content: space-between; gap: 12px; }.task-card__top strong { overflow: hidden; font-size: 11px; text-overflow: ellipsis; white-space: nowrap; }.task-card__meta { flex-wrap: wrap; gap: 12px; color: var(--cs-text-muted); font-size: 9px; }.task-card__meta span { display: inline-flex; align-items: center; gap: 4px; }.task-card__waiting { width: fit-content; padding: 3px 6px; border-radius: 6px; background: var(--cs-warning-soft); color: #7c4a12; font-size: 9px; }.task-card > :deep(button:last-child) { margin-right: 10px; }.task-list__more { display: grid; justify-items: center; gap: 7px; padding: 8px; }.task-list__more p { margin: 0; color: var(--cs-danger); font-size: 9px; }
@media (max-width: 767px) { .task-panel__header { align-items: stretch; flex-direction: column; gap: 14px; padding: 15px; }.task-filters { min-width: 0; grid-template-columns: 1fr; }.task-list { padding: 8px; }.task-card { grid-template-columns: 1fr; }.task-card__main { padding: 10px; }.task-card > :deep(button:last-child) { width: calc(100% - 20px); margin: 0 10px 10px; }.task-card__top { align-items: flex-start; }.task-card__top strong { white-space: normal; } }
</style>
