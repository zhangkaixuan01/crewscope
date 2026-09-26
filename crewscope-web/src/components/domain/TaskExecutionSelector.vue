<script setup lang="ts">
import { TriangleAlert } from '@lucide/vue'
import { computed } from 'vue'
import type { TaskExecution } from '../../domains/task/types'
import { taskExecutionStatusLabels } from '../../domains/task/labels'
import StatusBadge from '../base/StatusBadge.vue'

/**
 * The explicit execution chooser (contract §4.1): which Task Execution the workspace is anchored to.
 *
 * Choosing is a read-side act — it re-anchors the evidence being viewed — so it stays available in
 * every state except a coordinate conflict, where the two URL spellings disagree and the member must
 * pick one spelling before anything else happens.
 */
const props = defineProps<{
  attempts: TaskExecution[]
  selectedExecutionId: string | null
  currentExecutionId: string | null
  /** The conflicting pair from `parseExecutionSelection`; non-null means writes are blocked. */
  conflict: { unified: string, alias: string } | null
  onSelect: (executionId: string) => void
  onResolveConflict: (executionId: string) => void
}>()

const historyView = computed(() => Boolean(
  props.selectedExecutionId && !props.attempts.some(attempt => attempt.id === props.selectedExecutionId),
))

function shortId(value: string): string {
  return value.length > 12 ? `${value.slice(0, 8)}…` : value
}

function tone(status: TaskExecution['status']): 'success' | 'warning' | 'danger' | 'info' | 'neutral' {
  if (status === 'COMPLETED') return 'success'
  if (status === 'FAILED' || status === 'CANCELLED') return 'danger'
  if (status === 'PAUSED' || status === 'WAITING') return 'warning'
  if (status === 'RUNNING' || status === 'CLAIMED' || status === 'PREPARING' || status === 'RECOVERING') return 'info'
  return 'neutral'
}
</script>

<template>
  <section class="execution-selector" aria-label="执行选择">
    <p class="execution-selector__heading">执行选择</p>

    <div v-if="conflict" class="execution-conflict" role="alert">
      <TriangleAlert :size="15" aria-hidden="true" />
      <div>
        <strong>执行坐标冲突</strong>
        <span>taskExecution={{ shortId(conflict.unified) }} 与 attempt={{ shortId(conflict.alias) }} 指向不同执行。写动作已禁止，请选择要用的一侧；选择会改写为统一参数并清除另一侧。</span>
      </div>
      <div class="execution-conflict__actions">
        <button type="button" @click="onResolveConflict(conflict.unified)">用此执行 · taskExecution</button>
        <button type="button" @click="onResolveConflict(conflict.alias)">用此执行 · attempt</button>
      </div>
    </div>

    <template v-else>
      <div v-if="historyView" class="execution-history" role="status">
        <span>正在查看历史执行 <code>{{ shortId(selectedExecutionId ?? '') }}</code>，不在当前执行列表中，按历史证据只读呈现。</span>
        <button
          v-if="currentExecutionId"
          type="button"
          @click="onSelect(currentExecutionId)"
        >回到当前执行</button>
      </div>
      <ul v-if="attempts.length" class="execution-selector__list">
        <li v-for="attempt in attempts" :key="attempt.id">
          <button
            type="button"
            :class="{ selected: attempt.id === selectedExecutionId }"
            :aria-current="attempt.id === selectedExecutionId ? 'true' : undefined"
            @click="onSelect(attempt.id)"
          >
            <span>Attempt {{ attempt.attempt }}</span>
            <StatusBadge :tone="tone(attempt.status)" dot>{{ taskExecutionStatusLabels[attempt.status] }}</StatusBadge>
            <small v-if="attempt.id === currentExecutionId">当前</small>
          </button>
        </li>
      </ul>
      <p v-else-if="!historyView" class="execution-selector__empty">这个 Task 还没有执行记录；从工作项的委托流程启动。</p>
    </template>
  </section>
</template>

<style scoped>
.execution-selector { display: grid; gap: var(--cs-space-8); }
.execution-selector__heading { margin: 0; color: var(--cs-text-muted); font-size: var(--cs-text-xs); font-weight: var(--cs-weight-semibold); letter-spacing: .08em; text-transform: uppercase; }
.execution-selector__list { display: flex; flex-wrap: wrap; gap: var(--cs-space-8); padding: 0; margin: 0; list-style: none; }
.execution-selector__list button { display: inline-flex; align-items: center; gap: var(--cs-space-8); padding: var(--cs-space-4) var(--cs-space-12); border: 1px solid var(--cs-border); border-radius: 999px; background: var(--cs-surface-subtle); color: var(--cs-text-secondary); font-size: var(--cs-text-xs); font-weight: var(--cs-weight-semibold); cursor: pointer; }
.execution-selector__list button:hover { border-color: var(--cs-border-accent); background: var(--cs-surface-accent); }
.execution-selector__list button.selected { border-color: var(--cs-border-accent-strong); background: var(--cs-surface-accent); box-shadow: 0 0 0 2px var(--cs-ring-brand); color: var(--cs-text-brand); }
.execution-selector__list small { color: var(--cs-text-brand); font-size: var(--cs-text-xs); font-weight: var(--cs-weight-semibold); }
.execution-selector__empty { margin: 0; color: var(--cs-text-muted); font-size: var(--cs-text-xs); line-height: var(--cs-leading-normal); }
.execution-history { display: flex; align-items: center; justify-content: space-between; gap: var(--cs-space-12); padding: var(--cs-space-8) var(--cs-space-12); border: 1px solid var(--cs-warning-border); border-radius: 9px; background: var(--cs-warning-soft); color: var(--cs-warning); font-size: var(--cs-text-xs); line-height: var(--cs-leading-normal); }
.execution-history code { font-family: var(--cs-font-mono); }
.execution-history button { flex: 0 0 auto; border: 1px solid var(--cs-warning-border); border-radius: 7px; background: var(--cs-surface); color: var(--cs-warning); font-size: var(--cs-text-xs); font-weight: var(--cs-weight-semibold); cursor: pointer; }
.execution-conflict { display: grid; gap: var(--cs-space-8); padding: var(--cs-space-12); border: 1px solid var(--cs-danger-border); border-radius: 9px; background: var(--cs-danger-soft); color: var(--cs-danger); }
.execution-conflict > svg { justify-self: start; }
.execution-conflict strong { font-size: var(--cs-text-sm); }
.execution-conflict span { display: block; margin-top: var(--cs-space-4); color: var(--cs-text-secondary); font-size: var(--cs-text-xs); line-height: var(--cs-leading-normal); }
.execution-conflict__actions { display: flex; flex-wrap: wrap; gap: var(--cs-space-8); }
.execution-conflict__actions button { padding: var(--cs-space-8) var(--cs-space-12); border: 1px solid var(--cs-danger-border); border-radius: 8px; background: var(--cs-surface); color: var(--cs-danger); font-size: var(--cs-text-xs); font-weight: var(--cs-weight-semibold); cursor: pointer; }
.execution-conflict__actions button:hover { background: var(--cs-danger-soft); }
</style>
