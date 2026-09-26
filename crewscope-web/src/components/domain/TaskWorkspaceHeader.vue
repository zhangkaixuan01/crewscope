<script setup lang="ts">
import type { TaskDetails, TaskExecution, TaskStatus } from '../../domains/task/types'
import { taskStatusLabels } from '../../domains/task/labels'
import type { WorkspaceAction } from '../../domains/task/workspaceAction'
import type { SemanticTone } from '../base/types'
import StatusBadge from '../base/StatusBadge.vue'
import StatePanel from '../feedback/StatePanel.vue'
import TaskExecutionSelector from './TaskExecutionSelector.vue'
import WorkspaceResponsibilityStrip from './WorkspaceResponsibilityStrip.vue'
import WorkspacePrimaryAction from './WorkspacePrimaryAction.vue'

/**
 * The workspace header (contract §4.1): title / key / work status, then the outcome-or-waiting fact
 * (filled by the outcome slot), then execution choice, responsibility and the single primary action.
 *
 * It is orchestration only — every panel below keeps its own domain component untouched.
 */
defineProps<{
  objective: string
  shortId: string
  version: number
  status: TaskStatus
  cancelled: boolean
  attempts: TaskExecution[]
  selectedExecutionId: string | null
  currentExecutionId: string | null
  conflict: { unified: string, alias: string } | null
  action: WorkspaceAction
  actionDisabled?: boolean
  responsibilityLines: Array<{ role: string, name: string }>
  onSelect: (executionId: string) => void
  onResolveConflict: (executionId: string) => void
  onOperation: (operation: 'RESUME') => void
  onLocate: (anchor: 'ws-execution' | 'ws-review') => void
}>()

function tone(status: TaskStatus): SemanticTone {
  if (status === 'COMPLETED') return 'success'
  if (status === 'WAITING') return 'warning'
  if (status === 'FAILED' || status === 'CANCELLED') return 'danger'
  return status === 'ACTIVE' ? 'agent' : 'neutral'
}
</script>

<template>
  <header class="workspace-header">
    <div class="workspace-header__title">
      <StatusBadge :tone="tone(status)" dot>{{ taskStatusLabels[status] }}</StatusBadge>
      <span class="mono">Task {{ shortId }} · v{{ version }}</span>
    </div>
    <h2 class="workspace-header__objective">{{ objective }}</h2>
    <StatePanel
      v-if="cancelled"
      class="task-lifecycle-state"
      compact
      state="cancelled"
      title="Task 已取消"
      description="耐久历史、已产生结果与审计证据继续保留，当前 Task 不再执行。"
    />
    <div v-if="$slots.outcome" class="workspace-header__outcome"><slot name="outcome" /></div>
    <TaskExecutionSelector
      :attempts="attempts"
      :selected-execution-id="selectedExecutionId"
      :current-execution-id="currentExecutionId"
      :conflict="conflict"
      :on-select="onSelect"
      :on-resolve-conflict="onResolveConflict"
    />
    <WorkspaceResponsibilityStrip :lines="responsibilityLines" />
    <WorkspacePrimaryAction
      :action="action"
      :disabled="actionDisabled"
      @operation="onOperation"
      @locate="onLocate"
    />
  </header>
</template>

<style scoped>
.workspace-header { display: grid; gap: var(--cs-space-12); padding: var(--cs-space-20); border: 1px solid var(--cs-border); border-radius: var(--cs-radius-md); background: var(--cs-surface); }
.workspace-header__title { display: flex; align-items: center; justify-content: space-between; gap: var(--cs-space-12); }
.workspace-header__title > span { color: var(--cs-text-muted); font-size: var(--cs-text-xs); }
.workspace-header__objective { margin: 0; font-size: var(--cs-text-lg); line-height: var(--cs-leading-tight); }
.workspace-header__outcome { display: grid; gap: var(--cs-space-8); }
.task-lifecycle-state { margin: calc(var(--cs-space-4) * -1) 0 var(--cs-space-4); }
</style>
