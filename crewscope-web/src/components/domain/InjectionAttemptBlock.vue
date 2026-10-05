<script setup lang="ts">
import { computed } from 'vue'
import {
  injectionDegradationLabel,
  injectionReferenceTypeLabels,
  injectionTrimLayerLabel,
  injectionTrimReasonLabel,
} from '../../domains/injection/labels'
import type {
  InjectionAttempt,
  InjectionFeedbackInput,
  InjectionReference,
} from '../../domains/injection/types'
import BaseButton from '../base/BaseButton.vue'
import RelativeTime from '../base/RelativeTime.vue'
import StatusBadge from '../base/StatusBadge.vue'

const props = defineProps<{
  attempt: InjectionAttempt
  /** The pending feedback quadruple, if a mark is in flight — rides its row as the spinner. */
  pendingReference: InjectionFeedbackInput | null
  /** Offline closes the command entry (the compact panel above already says why). */
  online: boolean
}>()

const emit = defineEmits<{
  mark: [reference: InjectionFeedbackInput]
}>()

const injected = computed(() => props.attempt.references.filter(row => row.stage === 'INJECTED'))
const candidates = computed(() => props.attempt.references.filter(row => row.stage === 'CANDIDATE'))

function pendingFor(row: InjectionReference): boolean {
  const pending = props.pendingReference
  return pending !== null
    && pending.type === row.type && pending.sourceId === row.sourceId
    && pending.version === row.version && pending.contentHash === row.contentHash
}

function toInput(row: InjectionReference): InjectionFeedbackInput {
  return { type: row.type, sourceId: row.sourceId, version: row.version, contentHash: row.contentHash }
}
</script>

<template>
  <article class="attempt-block" :aria-label="`第 ${attempt.attempt} 次尝试的注入清单`">
    <header class="attempt-block__head">
      <h4>第 {{ attempt.attempt }} 次尝试</h4>
      <span class="mono">清单 {{ attempt.manifestId.slice(0, 8) }}</span>
      <span>封存于 <RelativeTime :value="attempt.createdAt" /></span>
    </header>

    <dl class="attempt-block__budget">
      <div><dt>Prompt 总预算</dt><dd>{{ attempt.budget.totalTokens.toLocaleString() }} token</dd></div>
      <div><dt>知识条目层</dt><dd>{{ attempt.budget.knowledgeTokens.toLocaleString() }}</dd></div>
      <div><dt>仓库片段层</dt><dd>{{ attempt.budget.chunkTokens.toLocaleString() }}</dd></div>
      <div><dt>记忆偏好层</dt><dd>{{ attempt.budget.memoryTokens.toLocaleString() }}</dd></div>
    </dl>

    <!-- Degradations are facts of this assembly, never a disguised empty result (S01 §3.4). -->
    <p v-if="attempt.degradations.length" class="attempt-block__degradations">
      <StatusBadge v-for="code in attempt.degradations" :key="code" tone="warning">{{ injectionDegradationLabel(code) }}</StatusBadge>
    </p>

    <table v-if="attempt.trims.length" class="attempt-block__trims">
      <caption>预算裁剪记录</caption>
      <thead><tr><th scope="col">层</th><th scope="col">裁掉条数</th><th scope="col">原因</th></tr></thead>
      <tbody>
        <tr v-for="(trim, index) in attempt.trims" :key="index">
          <td>{{ injectionTrimLayerLabel(trim.layer) }}</td>
          <td>{{ trim.trimmedCount }}</td>
          <td>{{ injectionTrimReasonLabel(trim.reason) }}</td>
        </tr>
      </tbody>
    </table>

    <div class="attempt-block__zone">
      <h5>已注入（{{ injected.length }}）</h5>
      <p v-if="injected.length === 0" class="attempt-block__empty">本次尝试没有送入模型的内容。</p>
      <ul v-else class="attempt-block__references">
        <li v-for="row in injected" :key="`${row.type}:${row.sourceId}:${row.version}:${row.contentHash}`">
          <StatusBadge tone="info">{{ injectionReferenceTypeLabels[row.type] }}</StatusBadge>
          <span class="mono attempt-block__source">{{ row.sourceId }} · v{{ row.version }}</span>
          <span class="mono attempt-block__hash">{{ row.contentHash.slice(0, 8) }}</span>
          <BaseButton
            v-if="!row.notApplicable"
            variant="ghost"
            size="small"
            :loading="pendingFor(row)"
            :disabled="!online"
            @click="emit('mark', toInput(row))"
          >标记不适用</BaseButton>
          <!-- No DELETE endpoint exists (contract §3): the mark is final, wording says so. -->
          <span v-else class="attempt-block__marked">你已标记不适用（不可撤销）</span>
        </li>
      </ul>
    </div>

    <div v-if="candidates.length" class="attempt-block__zone attempt-block__zone--candidate">
      <h5>候选（预算裁剪，{{ candidates.length }}）</h5>
      <p class="attempt-block__note">以下候选在组装 Prompt 前被预算裁剪，从未送入模型，不是可评判对象。</p>
      <ul class="attempt-block__references">
        <li v-for="row in candidates" :key="`${row.type}:${row.sourceId}:${row.version}:${row.contentHash}`">
          <StatusBadge tone="neutral">{{ injectionReferenceTypeLabels[row.type] }}</StatusBadge>
          <span class="mono attempt-block__source">{{ row.sourceId }} · v{{ row.version }}</span>
          <span class="mono attempt-block__hash">{{ row.contentHash.slice(0, 8) }}</span>
        </li>
      </ul>
    </div>

    <div class="attempt-block__zone">
      <h5>模型声明引用</h5>
      <!-- null = no receipt yet; [] = a receipt claiming zero references (contract §2 keeps them apart). -->
      <p v-if="attempt.claimed === null" class="attempt-block__note">模型尚未提交引用回执。</p>
      <p v-else-if="attempt.claimed.length === 0" class="attempt-block__note">已提交回执：零声明。</p>
      <ul v-else class="attempt-block__references">
        <li v-for="row in attempt.claimed" :key="`${row.type}:${row.sourceId}:${row.version}:${row.contentHash}`">
          <StatusBadge tone="success">{{ injectionReferenceTypeLabels[row.type] }}</StatusBadge>
          <span class="mono attempt-block__source">{{ row.sourceId }} · v{{ row.version }}</span>
          <span class="mono attempt-block__hash">{{ row.contentHash.slice(0, 8) }}</span>
        </li>
      </ul>
    </div>
  </article>
</template>

<style scoped>
.attempt-block { display: flex; min-width: 0; flex-direction: column; gap: var(--cs-space-12); padding: var(--cs-space-16); border: 1px solid var(--cs-border); border-radius: var(--cs-radius-md); background: var(--cs-surface); }
.attempt-block__head { display: flex; flex-wrap: wrap; align-items: baseline; gap: var(--cs-space-8); }
.attempt-block__head h4 { margin: 0; font-size: var(--cs-text-base); }
.attempt-block__head span { color: var(--cs-text-muted); font-size: var(--cs-text-xs); }
.attempt-block__budget { display: flex; margin: 0; flex-wrap: wrap; gap: var(--cs-space-8) var(--cs-space-16); color: var(--cs-text-muted); font-size: var(--cs-text-xs); }
.attempt-block__budget div { display: flex; gap: var(--cs-space-4); }
.attempt-block__budget dt { margin: 0; }
.attempt-block__budget dd { margin: 0; color: var(--cs-text); }
.attempt-block__degradations { display: flex; margin: 0; flex-wrap: wrap; gap: var(--cs-space-4); }
.attempt-block__trims { width: 100%; font-size: var(--cs-text-xs); text-align: left; border-collapse: collapse; }
.attempt-block__trims caption { caption-side: top; padding-bottom: var(--cs-space-4); color: var(--cs-text-muted); text-align: left; }
.attempt-block__trims th, .attempt-block__trims td { padding: var(--cs-space-4) var(--cs-space-8) var(--cs-space-4) 0; border-bottom: 1px solid var(--cs-border); font-weight: var(--cs-weight-regular); }
.attempt-block__trims th { color: var(--cs-text-muted); }
.attempt-block__zone { display: flex; min-width: 0; flex-direction: column; gap: var(--cs-space-8); }
.attempt-block__zone h5 { margin: 0; color: var(--cs-text-muted); font-size: var(--cs-text-xs); font-weight: var(--cs-weight-semibold); }
.attempt-block__references { display: flex; margin: 0; padding: 0; flex-direction: column; gap: var(--cs-space-8); list-style: none; }
.attempt-block__references li { display: flex; flex-wrap: wrap; align-items: center; gap: var(--cs-space-8); }
.attempt-block__source { min-width: 0; overflow-wrap: anywhere; font-size: var(--cs-text-xs); }
.attempt-block__hash { flex: 0 0 auto; color: var(--cs-text-muted); font-size: var(--cs-text-xs); }
.attempt-block__marked { color: var(--cs-text-muted); font-size: var(--cs-text-xs); }
.attempt-block__empty, .attempt-block__note { margin: 0; color: var(--cs-text-muted); font-size: var(--cs-text-xs); }
.mono { font-family: var(--cs-font-mono, ui-monospace, monospace); }
@media (max-width: 767px) { .attempt-block__references li { align-items: flex-start; } }
</style>
