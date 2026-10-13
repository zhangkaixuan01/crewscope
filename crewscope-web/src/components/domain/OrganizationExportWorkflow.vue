<script setup lang="ts">
import { computed, ref } from 'vue'
import { RouterLink } from 'vue-router'
import { createFormCommandKeys } from '../../api/formCommandKeys'
import BaseBadge from '../base/BaseBadge.vue'
import BaseButton from '../base/BaseButton.vue'
import BaseTable from '../base/BaseTable.vue'
import { enumLabel } from '../../domains/shared/labels'
import {
  exportFamilyLabels,
  exportItemStateLabels,
  exportJobStatusLabels,
} from '../../domains/organization/labels'
import { organizationProblem } from '../../domains/organization/presentation'
import type { ExportResourceFamily } from '../../domains/organization/types'
import { useOrganizationStore } from '../../domains/organization/store'

/**
 * M12-F01 governance export workflow. The process call settles every PENDING item inside one
 * HTTP request, so the 200 response IS the terminal job — the panel never polls. Jobs have no
 * list endpoint: the panel tracks the one job this session created (deviation card explains
 * the audit-center history).
 */
const props = defineProps<{ organizationId: string }>()

const store = useOrganizationStore()
const flow = computed(() => store.state.exportFlow)
const job = computed(() => flow.value.job)
const problem = computed(() => flow.value.errorMessage !== null
  ? organizationProblem(flow.value.errorStatus, flow.value.errorCode) : null)
const items = computed(() => job.value?.items ?? [])
const manifest = computed(() => job.value?.manifest ?? null)
/** A RUNNING job with PENDING items is an interrupted run — the resume entry stays visible. */
const resumable = computed(() => job.value?.status === 'RUNNING'
  && items.value.some(item => item.state === 'PENDING'))
const cancellable = computed(() => job.value?.status === 'PENDING' || resumable.value)
const downloadable = computed(() => job.value?.status === 'COMPLETED' && manifest.value !== null)
const busy = computed(() => flow.value.busy !== null)
const problemText = computed(() => problem.value
  ? `${problem.value.title}：${problem.value.message}` : flow.value.errorMessage)

const intentKeys = createFormCommandKeys()
const includeVectors = ref(false)

async function createJob(): Promise<void> {
  store.clearExportFlow()
  await store.createExport(props.organizationId, { includeVectors: includeVectors.value },
    intentKeys.forInput([includeVectors.value]))
}

async function processJob(): Promise<void> {
  await store.processExport(props.organizationId)
}

async function cancelJob(): Promise<void> {
  await store.cancelExport(props.organizationId)
}

async function refreshJob(): Promise<void> {
  await store.loadExportJob(props.organizationId)
}

/**
 * The download answer is the frozen artifacts document itself; the browser copy is a local
 * re-serialization of what the gateway already mapped (no extra request, nothing persisted).
 */
async function downloadArtifacts(): Promise<void> {
  const artifacts = await store.fetchExportArtifacts(props.organizationId)
  if (!artifacts || !job.value) return
  const blob = new Blob([JSON.stringify(artifacts, null, 2)], { type: 'application/json' })
  const url = URL.createObjectURL(blob)
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = `export-${job.value.id}.json`
  anchor.click()
  URL.revokeObjectURL(url)
}

function timeText(value: string | null): string {
  return value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '—'
}

function checksumText(value: string | null): string {
  return value ? value.slice(0, 12) : '—'
}

/** Manifest family strings are server-authored; known ones read through the label layer. */
function familyLabel(family: string): string {
  return family in exportFamilyLabels
    ? exportFamilyLabels[family as ExportResourceFamily]
    : family
}
</script>

<template>
  <div class="org-export">
    <form class="org-export-form" @submit.prevent="createJob">
      <h3>发起数据导出</h3>
      <label class="org-export-vectors">
        <input v-model="includeVectors" type="checkbox" />
        包含向量数据
      </label>
      <p class="org-reason">向量数据默认不导出；勾选后将额外导出知识条目与仓库分块的向量内容。</p>
      <p class="org-reason">凭证类资源仅导出引用（ID、状态、时间），不包含任何密钥材料。</p>
      <!-- Outside the job panel on purpose: a replayed create answers no job, so the notice
        must live where it survives the missing panel. -->
      <p v-if="flow.errorMessage" role="alert" class="org-error">{{ problemText }}</p>
      <p v-else-if="flow.notice" class="org-ok" role="status">{{ flow.notice }}</p>
      <div class="org-actions">
        <BaseButton type="submit" :disabled="busy">
          {{ flow.busy === 'create' ? '创建中…' : '创建导出作业' }}
        </BaseButton>
      </div>
    </form>

    <template v-if="job">
      <section class="org-job" aria-labelledby="org-export-job-heading">
        <h3 id="org-export-job-heading">导出作业</h3>
        <dl class="org-job-facts">
          <div><dt>作业 ID</dt><dd class="org-mono">{{ job.id }}</dd></div>
          <div><dt>状态</dt><dd><BaseBadge>{{ enumLabel(job.status, exportJobStatusLabels) }}</BaseBadge></dd></div>
          <div><dt>发起人</dt><dd class="org-mono">{{ job.requestedByPrincipalId }}</dd></div>
          <div><dt>向量</dt><dd>{{ job.includeVectors ? '包含' : '不包含' }}</dd></div>
          <div><dt>创建时间</dt><dd>{{ timeText(job.createdAt) }}</dd></div>
          <div><dt>更新时间</dt><dd>{{ timeText(job.updatedAt) }}</dd></div>
          <div><dt>数据版本</dt><dd>{{ job.version }}</dd></div>
        </dl>
        <div class="org-actions">
          <BaseButton v-if="job.status === 'PENDING' || resumable" :disabled="busy" @click="processJob">
            {{ flow.busy === 'process' ? '处理中…' : resumable ? '继续处理' : '生成产物' }}
          </BaseButton>
          <BaseButton v-if="cancellable" variant="ghost" :disabled="busy" @click="cancelJob">
            {{ flow.busy === 'cancel' ? '取消中…' : '取消作业' }}
          </BaseButton>
          <BaseButton variant="secondary" :disabled="busy" @click="refreshJob">
            {{ flow.busy === 'load' ? '刷新中…' : '刷新' }}
          </BaseButton>
          <BaseButton v-if="downloadable" variant="secondary" :disabled="busy" @click="downloadArtifacts">
            {{ flow.busy === 'download' ? '准备中…' : '下载产物' }}
          </BaseButton>
        </div>
        <p v-if="flow.errorMessage" role="alert" class="org-error">{{ problemText }}</p>
        <p v-else-if="flow.notice" class="org-ok" role="status">{{ flow.notice }}</p>
        <p v-if="resumable" class="org-reason">上次处理被中断；「继续处理」将从第一个未完成的资源族续跑。</p>
        <p v-if="downloadable" class="org-reason">每次下载都会记录 SECURITY 审计事件。</p>
      </section>

      <BaseTable v-if="items.length" caption="资源族处理进度">
        <thead>
          <tr>
            <th scope="col">资源族</th>
            <th scope="col">批次</th>
            <th scope="col">行数</th>
            <th scope="col">状态</th>
            <th scope="col">校验和</th>
            <th scope="col">错误码</th>
            <th scope="col">处理时间</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="item in items" :key="item.id">
            <td>{{ enumLabel(item.resourceFamily, exportFamilyLabels) }}</td>
            <td>{{ item.batchSequence }}</td>
            <td>{{ item.rowCount }}</td>
            <td><BaseBadge>{{ enumLabel(item.state, exportItemStateLabels) }}</BaseBadge></td>
            <td class="org-mono" :title="item.checksum ?? undefined">{{ checksumText(item.checksum) }}</td>
            <td>{{ item.errorCode ?? '—' }}</td>
            <td>{{ timeText(item.processedAt) }}</td>
          </tr>
        </tbody>
      </BaseTable>

      <section v-if="manifest" class="org-manifest" aria-labelledby="org-export-manifest-heading">
        <h3 id="org-export-manifest-heading">对账清单</h3>
        <p class="org-reason">生成时间：{{ timeText(manifest.generatedAt) }}</p>
        <BaseTable caption="清单族摘要">
          <thead>
            <tr>
              <th scope="col">资源族</th>
              <th scope="col">分片数</th>
              <th scope="col">行数合计</th>
              <th scope="col">族校验和</th>
              <th scope="col">跳过原因</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="family in manifest.families" :key="family.family">
              <td>{{ familyLabel(family.family) }}</td>
              <td>{{ family.itemCount }}</td>
              <td>{{ family.pinnedRowCount }}</td>
              <td class="org-mono" :title="family.checksum">{{ family.checksum.slice(0, 12) }}</td>
              <td>{{ family.skipped.length ? family.skipped.join('、') : '—' }}</td>
            </tr>
          </tbody>
        </BaseTable>
      </section>
    </template>

    <p v-else class="org-reason">
      本会话尚未创建导出作业。作业没有列表端点：历史作业请在
      <RouterLink to="/audit">审计中心</RouterLink>查询治理审计事件。
    </p>
  </div>
</template>

<style scoped>
.org-export { display: grid; gap: var(--cs-space-16); }
.org-export-form { display: grid; gap: var(--cs-space-8); padding: var(--cs-space-16); border: 1px solid var(--cs-border); border-radius: var(--cs-radius-md); background: var(--cs-surface); }
.org-export-form h3, .org-job h3, .org-manifest h3 { margin: 0; font-size: var(--cs-text-md); color: var(--cs-text); }
.org-export-vectors { display: flex; align-items: center; gap: var(--cs-space-8); color: var(--cs-text-secondary); }
.org-job { display: grid; gap: var(--cs-space-12); padding: var(--cs-space-16); border: 1px solid var(--cs-border); border-radius: var(--cs-radius-md); background: var(--cs-surface); }
.org-job-facts { display: grid; grid-template-columns: repeat(auto-fit, minmax(160px, 1fr)); gap: var(--cs-space-8); margin: 0; }
.org-job-facts dt { color: var(--cs-text-muted); font-size: var(--cs-text-xs); }
.org-job-facts dd { margin: 0; min-width: 0; overflow-wrap: anywhere; font-size: var(--cs-text-sm); color: var(--cs-text-secondary); }
.org-manifest { display: grid; gap: var(--cs-space-8); }
.org-mono { font-family: var(--cs-font-mono, monospace); font-size: var(--cs-text-sm); }
.org-reason { margin: 0; color: var(--cs-text-muted); font-size: var(--cs-text-xs); }
.org-error { margin: 0; color: var(--cs-text-danger, #b3261e); font-size: var(--cs-text-sm); }
.org-ok { margin: 0; color: var(--cs-text-secondary); font-size: var(--cs-text-sm); }
.org-actions { display: flex; flex-wrap: wrap; gap: var(--cs-space-8); }
</style>
