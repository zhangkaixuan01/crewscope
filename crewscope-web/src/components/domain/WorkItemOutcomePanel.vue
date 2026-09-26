<script setup lang="ts">
import { CheckCircle2, Copy, TriangleAlert } from '@lucide/vue'
import { computed, ref } from 'vue'
import { enumLabel } from '../../domains/shared/labels'
import { taskExecutionStatusLabels, taskExecutionWaitReasonLabels } from '../../domains/task/labels'
import type { WorkItemExecutionSummary } from '../../domains/workitem/types'
import { resolveReferenceTask, resolveWorkItemOutcome, type ResultReference } from '../../domains/workitem/outcome'
import BaseButton from '../base/BaseButton.vue'
import StatusBadge from '../base/StatusBadge.vue'
import type { SemanticTone } from '../base/types'

/**
 * The WorkItem-level outcome block (contract §4.1 / R11): delivered output linked to its pinned
 * evidence reference, or the honest waiting fact. The summary text is A06's server-authoritative
 * line shown verbatim; a reference that cannot be safely resolved to a Task stays raw with a
 * copy affordance instead of becoming a guessed navigation.
 */
const props = defineProps<{
  summary: WorkItemExecutionSummary | null
  principalName: (principalId: string) => string
  onOpenReference?: (reference: ResultReference, taskId: string) => void
}>()

const outcome = computed(() => resolveWorkItemOutcome(props.summary))
const openTaskId = computed(() => {
  if (!outcome.value || !props.summary || outcome.value.state !== 'DELIVERED') return null
  return outcome.value.reference ? resolveReferenceTask(outcome.value.reference, props.summary) : null
})
const copied = ref(false)

const stateLabels: Record<string, string> = {
  DELIVERED: '已交付',
  AWAITING_DECISION: '待成员决定',
  WAITING: '执行进行中',
  NOT_STARTED: '尚未启动',
  NO_SUMMARY: '尚无成果摘要',
}
const stateTones: Record<string, SemanticTone> = {
  DELIVERED: 'success',
  AWAITING_DECISION: 'warning',
  WAITING: 'info',
  NOT_STARTED: 'neutral',
  NO_SUMMARY: 'neutral',
}
const tone = computed<SemanticTone>(() => {
  if (!outcome.value) return 'neutral'
  if (outcome.value.state === 'DELIVERED' && outcome.value.testsFailed) return 'warning'
  return stateTones[outcome.value.state] ?? 'neutral'
})
const waitingLines = computed(() => (outcome.value?.blockedReasons ?? []).map(reason => ({
  key: `${reason.executionId}:${reason.code}`,
  text: `${enumLabel(reason.code, taskExecutionWaitReasonLabels)}${reason.waitingOnPrincipalId ? ` · ${props.principalName(reason.waitingOnPrincipalId)}` : ''}`,
})))

function openReference(): void {
  const reference = outcome.value?.reference
  if (reference && openTaskId.value && props.onOpenReference) props.onOpenReference(reference, openTaskId.value)
}

async function copyReference(): Promise<void> {
  const raw = props.summary?.resultSourceReference
  if (!raw) return
  try {
    await navigator.clipboard.writeText(raw)
    copied.value = true
    setTimeout(() => { copied.value = false }, 2000)
  } catch {
    // Clipboard access can be denied; the raw reference stays visible either way.
  }
}

function shortHash(value: string): string {
  return value.length > 12 ? `${value.slice(0, 8)}…` : value
}
</script>

<template>
  <section v-if="outcome" class="work-item-outcome" data-testid="work-item-outcome" aria-label="成果或等待事实">
    <header>
      <StatusBadge :tone="tone" dot>{{ stateLabels[outcome.state] }}</StatusBadge>
      <strong v-if="outcome.state === 'DELIVERED'">本次成果已固定到执行证据</strong>
      <strong v-else-if="outcome.state === 'AWAITING_DECISION'">{{ outcome.pendingReviewCount }} 个评审等待成员决定</strong>
      <strong v-else-if="outcome.state === 'WAITING'">{{ outcome.activeTaskCount }} 个 Task 进行中 · {{ enumLabel(outcome.executionStatus, taskExecutionStatusLabels) }}</strong>
      <strong v-else-if="outcome.state === 'NOT_STARTED'">这个工作项还没有启动 Task</strong>
      <strong v-else>执行已结束，服务端没有可展示的成果摘要</strong>
    </header>

    <p v-if="outcome.deliveredSummary" class="delivered-line" :class="{ 'tests-failed': outcome.testsFailed }">{{ outcome.deliveredSummary }}</p>

    <div v-if="outcome.state === 'DELIVERED' && summary?.resultSourceReference" class="reference-line">
      <CheckCircle2 v-if="outcome.reference && !outcome.testsFailed" :size="14" />
      <TriangleAlert v-else-if="outcome.reference" :size="14" />
      <template v-if="outcome.reference?.kind === 'coding-attempt'">
        <BaseButton v-if="openTaskId" size="small" variant="secondary" @click="openReference">查看证据锚 {{ shortHash(outcome.reference.finalHash) }}</BaseButton>
        <span v-else class="mono">{{ summary.resultSourceReference }}</span>
      </template>
      <span v-else-if="outcome.reference" class="mono">{{ summary.resultSourceReference }}</span>
      <BaseButton size="small" variant="secondary" :title="`结果引用：${summary.resultSourceReference}`" @click="copyReference"><Copy :size="13" />{{ copied ? '已复制' : '复制引用' }}</BaseButton>
    </div>

    <ul v-if="waitingLines.length && outcome.state !== 'DELIVERED'" class="waiting-lines">
      <li v-for="line in waitingLines" :key="line.key">{{ line.text }}</li>
    </ul>
  </section>
</template>

<style scoped>
.work-item-outcome { display: grid; gap: var(--cs-space-8); margin-top: var(--cs-space-12); padding: var(--cs-space-12); border: 1px solid var(--cs-border); border-radius: 10px; background: var(--cs-surface-subtle); }
.work-item-outcome > header { display: flex; align-items: center; gap: var(--cs-space-8); }
.work-item-outcome > header strong { flex: 1; font-size: var(--cs-text-sm); }
.delivered-line { margin: 0; color: var(--cs-text-secondary); font-size: var(--cs-text-sm); line-height: var(--cs-leading-normal); }
.delivered-line.tests-failed { color: var(--cs-warning); font-weight: var(--cs-weight-semibold); }
.reference-line { display: flex; align-items: center; gap: var(--cs-space-8); flex-wrap: wrap; }
.reference-line > svg { color: var(--cs-text-muted); flex: none; }
.reference-line .mono { min-width: 0; color: var(--cs-text-muted); font-size: var(--cs-text-xs); overflow-wrap: anywhere; }
.waiting-lines { display: grid; gap: var(--cs-space-4); padding: 0; margin: 0; list-style: none; color: var(--cs-text-muted); font-size: var(--cs-text-xs); line-height: var(--cs-leading-normal); }
</style>
