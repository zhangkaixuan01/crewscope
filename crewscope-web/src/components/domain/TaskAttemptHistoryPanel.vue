<script setup lang="ts">
import { computed } from 'vue'
import { History } from '@lucide/vue'
import type { TaskExecution } from '../../domains/task/types'
import { taskExecutionStatusLabels, taskExecutionWaitReasonLabels, taskFailureClassLabels } from '../../domains/task/labels'
import { enumLabel } from '../../domains/shared/labels'
import StatusBadge from '../base/StatusBadge.vue'
import type { SemanticTone } from '../base/types'

const props = defineProps<{
  attempts: TaskExecution[]
  selectedExecutionId: string | null
  currentExecutionId: string | null
  principalName: (principalId: string) => string
  displayDate: (value: string) => string
  factTone: (value: string) => SemanticTone
}>()

const emit = defineEmits<{ selectAttempt: [executionId: string] }>()
const orderedAttempts = computed(() => [...props.attempts].sort((left, right) => right.attempt - left.attempt))
const selectedAttempt = computed(() => props.attempts.find(item => item.id === props.selectedExecutionId) ?? null)

function label(value: string | null | undefined, labels: Record<string, string>): string { return enumLabel(value, labels) }
function attemptFailureLabel(terminal: TaskExecution['terminal']): string {
  if (!terminal) return ''
  if (terminal.failureClass) return label(terminal.failureClass, taskFailureClassLabels)
  return terminal.failureCode ? `失败码 ${terminal.failureCode}` : ''
}
</script>

<template>
  <section class="detail-card attempt-card">
    <div class="section-heading"><div><p>Execution history</p><h3>Attempt 历史 <span>{{ attempts.length }}</span></h3></div><History :size="17" /></div>
    <ul class="attempt-list" aria-label="Task attempts">
      <li v-for="attempt in orderedAttempts" :key="attempt.id">
        <button
          type="button"
          :class="{ selected: attempt.id === selectedExecutionId }"
          :aria-pressed="attempt.id === selectedExecutionId"
          @click="emit('selectAttempt', attempt.id)"
        >
          <span><strong>Attempt {{ attempt.attempt }}</strong><small>{{ attempt.id === currentExecutionId ? '当前' : '历史' }}</small></span>
          <StatusBadge :tone="factTone(attempt.status)" dot>{{ label(attempt.status, taskExecutionStatusLabels) }}</StatusBadge>
          <em v-if="attempt.waiting">{{ label(attempt.waiting.reason, taskExecutionWaitReasonLabels) }}</em>
          <em v-else-if="attempt.terminal?.failureClass || attempt.terminal?.failureCode">{{ attemptFailureLabel(attempt.terminal) }}</em>
        </button>
      </li>
    </ul>
    <dl v-if="selectedAttempt" class="compact-facts attempt-facts">
      <div><dt>执行者</dt><dd>{{ selectedAttempt.executorPrincipalId ? principalName(selectedAttempt.executorPrincipalId) : '等待认领' }}</dd></div>
      <div><dt>优先级</dt><dd>{{ selectedAttempt.priority }}</dd></div>
      <div><dt>可运行时间</dt><dd>{{ displayDate(selectedAttempt.notBefore) }}</dd></div>
      <div><dt>最大尝试</dt><dd>{{ selectedAttempt.maxAttempts }}</dd></div>
    </dl>
  </section>
</template>

<style scoped>
.detail-card { min-width: 0; padding: var(--cs-space-16); border: 1px solid var(--cs-border); border-radius: var(--cs-radius-md); background: var(--cs-surface); }.section-heading { display: flex; align-items: center; justify-content: space-between; gap: var(--cs-space-12); margin-bottom: var(--cs-space-12); }.section-heading p { margin: 0 0 var(--cs-space-2); color: var(--cs-text-brand); font-size: var(--cs-text-xs); font-weight: var(--cs-weight-semibold); letter-spacing: .08em; text-transform: uppercase; }.section-heading h3 { margin: 0; font-size: var(--cs-text-base); }.section-heading h3 span { color: var(--cs-text-muted); font-weight: var(--cs-weight-medium); }.section-heading > svg { color: var(--cs-text-muted); }.attempt-list { display: grid; gap: var(--cs-space-8); padding: 0; margin: 0; list-style: none; }.attempt-list button { display: grid; width: 100%; grid-template-columns: minmax(0, 1fr) auto; align-items: center; gap: var(--cs-space-4) var(--cs-space-8); padding: var(--cs-space-8); border: 1px solid var(--cs-border); border-radius: 9px; background: var(--cs-surface-subtle); text-align: left; cursor: pointer; }.attempt-list button.selected { border-color: var(--cs-border-accent-strong); background: var(--cs-surface-accent); box-shadow: 0 0 0 2px var(--cs-ring-brand); }.attempt-list button > span { display: flex; align-items: baseline; gap: var(--cs-space-8); }.attempt-list strong { font-size: var(--cs-text-sm); }.attempt-list small, .attempt-list em { color: var(--cs-text-muted); font-size: var(--cs-text-xs); font-style: normal; }.attempt-list em { grid-column: 1 / -1; }.compact-facts { display: grid; grid-template-columns: 1fr 1fr; margin: var(--cs-space-12) 0 0; }.compact-facts > div { min-width: 0; padding: var(--cs-space-8) 0; border-top: 1px solid var(--cs-border); }.compact-facts dt { color: var(--cs-text-muted); font-size: var(--cs-text-xs); }.compact-facts dd { min-width: 0; margin: var(--cs-space-4) 0 0; overflow: hidden; color: var(--cs-text-secondary); font-size: var(--cs-text-xs); font-weight: var(--cs-weight-semibold); text-overflow: ellipsis; white-space: nowrap; }
</style>
