<script setup lang="ts">
import { CircleDashed, FileCode2, TriangleAlert } from '@lucide/vue'
import { computed } from 'vue'
import type { CodingAttemptSummary, TestEvidenceSummary } from '../../domains/coding/types'
import { enumLabel } from '../../domains/shared/labels'
import { taskExecutionStatusLabels } from '../../domains/task/labels'
import { referenceMatchesManifest, type ResultReference } from '../../domains/workitem/outcome'
import StatusBadge from '../base/StatusBadge.vue'
import BaseTooltip from '../base/BaseTooltip.vue'
import type { SemanticTone } from '../base/types'

/**
 * The outcome-or-waiting fact of contract §4.1, first screen of the Task workspace. It reports
 * what the selected execution actually delivered (diff facts + the latest test verdict, each
 * shown separately — never merged into one green completion), or the honest「nothing yet」.
 * The awaiting-decision and external-delivery lines below stay separately visible (R11/R13):
 * a pending review or an UNKNOWN/MANUAL_REVIEW dispatch is a fact in its own right, not a
 * footnote to a green completion.
 */
const props = defineProps<{
  attempt: CodingAttemptSummary | null
  latestTest: TestEvidenceSummary | null
  /** Pinned by the WorkItem panel jump: a mismatch against the loaded manifest is stated, not hidden. */
  pinnedReference?: ResultReference | null
  /** Reviews of this execution waiting for a human decision (OPEN / IN_PROGRESS). */
  reviewAwaitingCount?: number
  /** Highest「要求修改」round reached on any review of this execution. */
  modificationRound?: number
  /** Dispatch statuses of the delivery actions; only the human-attention ones are surfaced. */
  deliveryDispatchStatuses?: string[]
  /** Scrolls to the changes section so the evidence anchor is a link, not a decoration. */
  onLocateChanges?: () => void
}>()

const manifest = computed(() => props.attempt?.details?.diffManifest ?? null)
const testsFailed = computed(() => (props.latestTest?.failed ?? 0) > 0 || (props.latestTest?.errors ?? 0) > 0)
const pinnedMismatch = computed(() => referenceMatchesManifest(props.pinnedReference ?? null, manifest.value) === false)
const deliveryReconciling = computed(() => (props.deliveryDispatchStatuses ?? []).filter(status => status === 'UNKNOWN').length)
const deliveryManualReview = computed(() => (props.deliveryDispatchStatuses ?? []).filter(status => status === 'MANUAL_REVIEW').length)

const tone = computed<SemanticTone>(() => {
  if (!props.attempt?.coding) return 'neutral'
  if (!manifest.value) return 'info'
  return 'success'
})
const headline = computed(() => {
  if (!props.attempt?.coding) return '非编码执行 · 产物以运行时工件为准'
  if (!manifest.value) {
    return `尚无变更产出 · ${props.attempt ? enumLabel(props.attempt.executionStatus, taskExecutionStatusLabels) : ''}`
  }
  return `本次产出 ${manifest.value.fileCount} 个文件变更（+${manifest.value.additions}/−${manifest.value.deletions}）`
})
const testLine = computed(() => {
  const test = props.latestTest
  if (!test) return '测试证据尚未生成'
  const verdict = testsFailed.value ? '未通过' : '通过'
  const detail = test.errors > 0 ? `${test.passed}/${test.total}（${test.failed} 失败 · ${test.errors} 错误）` : `${test.passed}/${test.total}`
  return `测试${verdict} ${detail}`
})
const awaitingLines = computed(() => {
  const lines: string[] = []
  if ((props.reviewAwaitingCount ?? 0) > 0) {
    lines.push(`${props.reviewAwaitingCount} 项评审待人审${(props.modificationRound ?? 0) > 0 ? `（已到第 ${props.modificationRound} 轮要求修改）` : ''}`)
  }
  const deliveryParts: string[] = []
  if (deliveryReconciling.value > 0) deliveryParts.push(`${deliveryReconciling.value} 项待对账`)
  if (deliveryManualReview.value > 0) deliveryParts.push(`${deliveryManualReview.value} 项待人工判定`)
  if (deliveryParts.length > 0) lines.push(`外部交付 ${deliveryParts.join(' · ')}`)
  return lines
})

function shortHash(value: string): string {
  return value.length > 12 ? `${value.slice(0, 8)}…` : value
}
</script>

<template>
  <div v-if="attempt" class="task-outcome" data-testid="task-outcome">
    <header>
      <FileCode2 v-if="attempt.coding" :size="15" /><CircleDashed v-else :size="15" />
      <strong>{{ headline }}</strong>
      <StatusBadge :tone="tone" dot>{{ attempt.coding ? 'Coding 执行' : '非 Coding 执行' }}</StatusBadge>
    </header>
    <p v-if="manifest" class="outcome-evidence mono">
      证据锚
      <BaseTooltip v-if="onLocateChanges" text="定位到变更与测试区">
        <button type="button" class="evidence-anchor" @click="onLocateChanges">finalHash {{ shortHash(manifest.finalHash) }}</button>
      </BaseTooltip>
      <template v-else>finalHash {{ shortHash(manifest.finalHash) }}</template>
      · Diff 生成 {{ manifest.generation }}
    </p>
    <p class="outcome-test" :class="{ failed: testsFailed }">{{ testLine }}</p>
    <ul v-if="awaitingLines.length" class="outcome-awaiting" aria-label="待人审与外部交付事实">
      <li v-for="line in awaitingLines" :key="line"><TriangleAlert :size="13" />{{ line }}</li>
    </ul>
    <p v-if="pinnedMismatch" class="outcome-mismatch" role="status"><TriangleAlert :size="14" />证据版本不一致：入口引用锚定的 finalHash 与当前加载的 Diff 不相同，请以下方变更区实际内容为准。</p>
  </div>
</template>

<style scoped>
.task-outcome { display: grid; gap: var(--cs-space-4); padding: var(--cs-space-12) var(--cs-space-16); border: 1px solid var(--cs-border); border-radius: 10px; background: var(--cs-surface-subtle); }
.task-outcome > header { display: flex; align-items: center; gap: var(--cs-space-8); }
.task-outcome > header > svg { color: var(--cs-text-brand); flex: none; }
.task-outcome > header strong { flex: 1; font-size: var(--cs-text-sm); line-height: var(--cs-leading-normal); }
.outcome-evidence, .outcome-test, .outcome-mismatch, .outcome-awaiting { margin: 0; font-size: var(--cs-text-xs); }
.outcome-evidence { display: flex; flex-wrap: wrap; align-items: center; gap: var(--cs-space-4); color: var(--cs-text-muted); overflow-wrap: anywhere; }
.evidence-anchor { padding: 0; border: 0; background: none; color: var(--cs-text-brand); font: inherit; text-decoration: underline; cursor: pointer; }
.evidence-anchor:hover { color: var(--cs-text-brand-strong); }
.outcome-test { color: var(--cs-success); }
.outcome-test.failed { color: var(--cs-danger); font-weight: var(--cs-weight-semibold); }
.outcome-awaiting { display: grid; gap: var(--cs-space-4); padding: 0; margin: 0; list-style: none; color: var(--cs-warning); }
.outcome-awaiting li { display: flex; align-items: center; gap: var(--cs-space-4); }
.outcome-awaiting svg { flex: none; }
.outcome-mismatch { display: flex; align-items: center; gap: var(--cs-space-4); color: var(--cs-warning); }
</style>
