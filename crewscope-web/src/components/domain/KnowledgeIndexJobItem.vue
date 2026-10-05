<script setup lang="ts">
import { ChevronDown, ChevronUp } from '@lucide/vue'
import { computed, ref } from 'vue'
import {
  knowledgeFailureCodeLabel,
  knowledgeIndexJobSourceLabels,
  knowledgeIndexJobStatusLabels,
} from '../../domains/knowledge/labels'
import type { KnowledgeIndexJob, KnowledgeIndexJobStatus } from '../../domains/knowledge/types'
import BaseButton from '../base/BaseButton.vue'
import RelativeTime from '../base/RelativeTime.vue'
import StatusBadge from '../base/StatusBadge.vue'

const props = defineProps<{
  job: KnowledgeIndexJob
  /** Managers alone see the technical block — claimedBy/lease/createdBy are recovery facts. */
  canManage: boolean
  cancelling: boolean
}>()

const emit = defineEmits<{
  cancel: [jobId: string]
}>()

const expanded = ref(false)

/** Only a still-QUEUED job can be cancelled (contract §2: everything else answers 409). */
const cancellable = computed(() => props.job.status === 'QUEUED')

function statusTone(status: KnowledgeIndexJobStatus): 'neutral' | 'info' | 'success' | 'warning' | 'danger' {
  if (status === 'READY') return 'success'
  if (status === 'FAILED') return 'danger'
  if (status === 'CANCELLED') return 'neutral'
  if (status === 'QUEUED') return 'neutral'
  return 'info'
}

/** KNOWLEDGE_ENTRY names its entry, REPOSITORY its binding and commit — never a canonical key. */
const target = computed(() => {
  if (props.job.source === 'KNOWLEDGE_ENTRY') {
    return props.job.entryId ? `条目 ${props.job.entryId.slice(0, 8)}` : '条目 —'
  }
  const key = props.job.indexKey
  return key ? `绑定 ${key.bindingId.slice(0, 8)} @ ${key.commit.slice(0, 8)}` : '仓库 —'
})
</script>

<template>
  <li class="job-item">
    <div class="job-item__row">
      <div class="job-item__head">
        <span class="job-item__badges">
          <StatusBadge tone="neutral">{{ knowledgeIndexJobSourceLabels[job.source] }}</StatusBadge>
          <StatusBadge :tone="statusTone(job.status)">{{ knowledgeIndexJobStatusLabels[job.status] }}</StatusBadge>
          <StatusBadge v-if="job.status === 'FAILED' && job.failureCode" tone="warning">{{ knowledgeFailureCodeLabel(job.failureCode) }}</StatusBadge>
        </span>
        <span class="job-item__target">{{ target }}</span>
      </div>
      <dl class="job-item__meta">
        <div><dt>分片</dt><dd>{{ job.chunksDone }}/{{ job.chunksTotal }}</dd></div>
        <div><dt>创建</dt><dd><RelativeTime :value="job.createdAt" /></dd></div>
      </dl>
      <div class="job-item__actions">
        <BaseButton
          v-if="canManage && cancellable"
          variant="ghost"
          size="small"
          :loading="cancelling"
          @click="emit('cancel', job.id)"
        >取消作业</BaseButton>
        <button
          type="button"
          class="job-item__toggle"
          :aria-expanded="expanded ? 'true' : 'false'"
          @click="expanded = !expanded"
        >
          {{ expanded ? '收起详情' : '展开详情' }}
          <ChevronUp v-if="expanded" :size="14" aria-hidden="true" />
          <ChevronDown v-else :size="14" aria-hidden="true" />
        </button>
      </div>
    </div>

    <!-- Recovery facts (who holds the lease, who created the job) are manager-only, mirroring
         the entry audit block: an ordinary member's read surface stays complete without them. -->
    <div v-if="expanded && canManage" class="job-item__detail">
      <dl class="job-item__facts">
        <div><dt>作业 ID</dt><dd class="job-item__code">{{ job.id }}</dd></div>
        <div><dt>尝试次数</dt><dd>{{ job.attempt }}</dd></div>
        <div><dt>持有租约</dt><dd>{{ job.claimedBy ?? '无（排队中或已结束）' }}</dd></div>
        <div><dt>租约到期</dt><dd>{{ job.leaseExpiresAt ?? '—' }}</dd></div>
        <div><dt>代构建序号</dt><dd>{{ job.generationBuildSequence }}</dd></div>
        <div><dt>创建者</dt><dd class="job-item__code">{{ job.createdBy }}</dd></div>
        <div v-if="job.entryId"><dt>条目 ID</dt><dd class="job-item__code">{{ job.entryId }}</dd></div>
        <div v-if="job.projectId"><dt>项目 ID</dt><dd class="job-item__code">{{ job.projectId }}</dd></div>
        <div v-if="job.indexKey" class="job-item__wide">
          <dt>索引键</dt>
          <dd class="job-item__code">绑定 {{ job.indexKey.bindingId }} · 提交 {{ job.indexKey.commit }} · 策略 {{ job.indexKey.chunkPolicyHash.slice(0, 8) }} · 模型 {{ job.indexKey.modelKey }}@{{ job.indexKey.modelRevision }}</dd>
        </div>
        <div v-if="job.failureCode" class="job-item__wide">
          <dt>失败码</dt>
          <!-- 开放模型健康码兜底原样显示（knowledgeFailureCodeLabel），此处不再重复裸码。 -->
          <dd class="job-item__code">{{ knowledgeFailureCodeLabel(job.failureCode) }}</dd>
        </div>
      </dl>
    </div>
    <p v-else-if="expanded" class="job-item__restricted">技术详情仅对具有知识管理权限的成员展示。</p>
  </li>
</template>

<style scoped>
.job-item { display: flex; min-width: 0; flex-direction: column; gap: var(--cs-space-8); padding: var(--cs-space-12); border: 1px solid var(--cs-border); border-radius: var(--cs-radius-md); background: var(--cs-surface); }
.job-item__row { display: grid; grid-template-columns: minmax(0, 1fr) auto; align-items: center; gap: var(--cs-space-8) var(--cs-space-12); }
.job-item__head { display: flex; min-width: 0; flex-wrap: wrap; align-items: center; gap: var(--cs-space-8); }
.job-item__badges { display: flex; flex-wrap: wrap; gap: var(--cs-space-4); }
.job-item__target { min-width: 0; overflow-wrap: anywhere; color: var(--cs-text-muted); font-size: var(--cs-text-xs); }
.job-item__meta { display: flex; margin: 0; gap: var(--cs-space-16); color: var(--cs-text-muted); font-size: var(--cs-text-xs); }
.job-item__meta div { display: flex; gap: var(--cs-space-4); }
.job-item__meta dt { margin: 0; }
.job-item__meta dd { margin: 0; }
.job-item__actions { grid-column: 1 / -1; display: flex; justify-content: flex-end; gap: var(--cs-space-8); }
.job-item__toggle { display: inline-flex; align-items: center; gap: var(--cs-space-4); border: none; background: none; color: var(--cs-text-muted); font-size: var(--cs-text-xs); cursor: pointer; }
.job-item__toggle:hover { color: var(--cs-text); }
.job-item__toggle:focus-visible { outline: none; box-shadow: var(--cs-focus-ring); border-radius: var(--cs-radius-sm); }
.job-item__detail { border-top: 1px dashed var(--cs-border); padding-top: var(--cs-space-8); }
.job-item__facts { display: grid; margin: 0; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: var(--cs-space-8) var(--cs-space-16); font-size: var(--cs-text-xs); }
.job-item__facts div { display: flex; min-width: 0; flex-direction: column; gap: var(--cs-space-2); }
.job-item__facts dt { margin: 0; color: var(--cs-text-muted); }
.job-item__facts dd { margin: 0; overflow-wrap: anywhere; }
.job-item__wide { grid-column: 1 / -1; }
.job-item__code { font-family: var(--cs-font-mono, ui-monospace, monospace); }
.job-item__restricted { margin: 0; color: var(--cs-text-muted); font-size: var(--cs-text-xs); }
@media (max-width: 767px) { .job-item__facts { grid-template-columns: 1fr; } }
</style>
