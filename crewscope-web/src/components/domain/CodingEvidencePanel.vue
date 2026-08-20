<script setup lang="ts">
import { CheckCircle2, Clock3, Download, FileCheck2, ListChecks, RefreshCw, ShieldCheck, SquareTerminal, TriangleAlert } from '@lucide/vue'
import { computed, ref, watch } from 'vue'
import type { CodingPhase, CodingResource } from '../../domains/coding/store'
import type { ArtifactTextDocument, CommandEvidenceSummary, EvidencePage, TestEvidenceSummary } from '../../domains/coding/types'
import type { SemanticTone } from '../base/types'
import StatusBadge from '../base/StatusBadge.vue'
import StatePanel from '../feedback/StatePanel.vue'

const props = defineProps<{
  taskId: string
  executionId: string
  commandsPhase: CodingPhase
  commands: EvidencePage<CommandEvidenceSummary> | null
  commandsErrorMessage: string | null
  testsPhase: CodingPhase
  tests: EvidencePage<TestEvidenceSummary> | null
  testsErrorMessage: string | null
  commandLog: (evidenceId: string) => CodingResource<ArtifactTextDocument> | null
  testReport: (evidenceId: string) => CodingResource<ArtifactTextDocument> | null
  onLoadCommandsMore: () => void
  onLoadTestsMore: () => void
  onLoadCommandLog: (evidenceId: string, more?: boolean) => void
  onLoadTestReport: (evidenceId: string, more?: boolean) => void
}>()

const selectedCommandId = ref<string | null>(null)
const selectedTestId = ref<string | null>(null)
const orderedCommands = computed(() => [...(props.commands?.items ?? [])].sort((a, b) => a.sequence - b.sequence))
const orderedTests = computed(() => [...(props.tests?.items ?? [])].sort((a, b) => b.sequence - a.sequence))
const selectedCommand = computed(() => orderedCommands.value.find(item => item.id === selectedCommandId.value) ?? orderedCommands.value[0] ?? null)
const selectedTest = computed(() => orderedTests.value.find(item => item.id === selectedTestId.value) ?? orderedTests.value[0] ?? null)
const selectedLog = computed(() => selectedCommand.value ? props.commandLog(selectedCommand.value.id) : null)
const selectedReport = computed(() => selectedTest.value ? props.testReport(selectedTest.value.id) : null)
const orderedAcceptance = computed(() => [...(selectedTest.value?.acceptance ?? [])].sort((a, b) => a.criterionIndex - b.criterionIndex))

watch(orderedCommands, items => {
  if (!items.some(item => item.id === selectedCommandId.value)) selectedCommandId.value = items[0]?.id ?? null
}, { immediate: true })
watch(orderedTests, items => {
  if (!items.some(item => item.id === selectedTestId.value)) selectedTestId.value = items[0]?.id ?? null
}, { immediate: true })

function tone(status: string): SemanticTone {
  if (['EXITED', 'PASSED', 'COMPLETED'].includes(status)) return 'success'
  if (['TIMED_OUT', 'FAILED', 'ERROR', 'REJECTED'].includes(status)) return 'danger'
  if (['SKIPPED', 'CANCELLED'].includes(status)) return 'warning'
  return 'neutral'
}

function duration(command: CommandEvidenceSummary): string {
  const milliseconds = new Date(command.finishedAt).getTime() - new Date(command.startedAt).getTime()
  if (!Number.isFinite(milliseconds) || milliseconds < 0) return '—'
  return milliseconds < 1000 ? `${milliseconds} ms` : `${(milliseconds / 1000).toFixed(milliseconds < 10_000 ? 1 : 0)} s`
}

function bytes(value: number): string {
  if (value < 1024) return `${value} B`
  return `${(value / 1024).toFixed(value < 10 * 1024 ? 1 : 0)} KiB`
}

/** A second display-only guard covers common secret shapes without interpreting ANSI or HTML. */
function safeText(value: string): string {
  return value
    .replace(/(bearer\s+)[a-z0-9._~+\/-]+/gi, '$1[REDACTED]')
    .replace(/((?:token|password|secret|api[_-]?key)\s*[=:]\s*)[^\s,;]+/gi, '$1[REDACTED]')
}

function download(document: ArtifactTextDocument): void {
  if (!document.complete || !document.filename) return
  const copy = new Uint8Array(document.bytes.byteLength)
  copy.set(document.bytes)
  const url = URL.createObjectURL(new Blob([copy.buffer], { type: document.contentType }))
  const anchor = window.document.createElement('a')
  anchor.href = url
  anchor.download = document.filename
  anchor.click()
  setTimeout(() => URL.revokeObjectURL(url), 0)
}
</script>

<template>
  <section class="evidence-panel detail-card" aria-labelledby="coding-evidence-title" data-testid="coding-evidence-panel">
    <header class="evidence-heading">
      <div><p>Durable evidence · Read only</p><h3 id="coding-evidence-title">命令、测试与验收证据</h3></div>
      <span><ShieldCheck :size="13" aria-hidden="true" />只读观察面</span>
    </header>

    <div class="evidence-layout">
      <section class="command-column" aria-labelledby="command-evidence-title">
        <header><SquareTerminal :size="14" /><strong id="command-evidence-title">CommandEvidence</strong><small>{{ commands?.items.length ?? 0 }}</small></header>
        <StatePanel v-if="commandsPhase === 'loading' && !commands?.items.length" compact state="loading" title="正在加载命令证据" />
        <StatePanel v-else-if="commandsPhase === 'error' && !commands?.items.length" compact state="error" :description="commandsErrorMessage ?? undefined" @retry="onLoadCommandsMore" />
        <StatePanel v-else-if="!orderedCommands.length" compact state="empty" title="尚未产生命令证据" />
        <div v-else class="command-list" role="group" aria-label="命令证据列表">
          <button
            v-for="command in orderedCommands"
            :key="command.id"
            type="button"
            :class="{ selected: command.id === selectedCommand?.id }"
            :aria-pressed="command.id === selectedCommand?.id"
            @click="selectedCommandId = command.id"
          >
            <span>#{{ command.sequence }} · {{ command.commandKind }}</span>
            <strong>{{ command.toolKey }}</strong>
            <StatusBadge :tone="tone(command.termination)">{{ command.termination }}</StatusBadge>
          </button>
        </div>
        <button v-if="commands?.nextCursor" class="load-more" type="button" :disabled="commandsPhase === 'loading'" @click="onLoadCommandsMore">
          <RefreshCw :size="11" />{{ commandsPhase === 'loading' ? '读取中…' : '更多命令' }}
        </button>
      </section>

      <section class="evidence-main">
        <article v-if="selectedCommand" class="command-detail">
          <header><div><small>Command #{{ selectedCommand.sequence }}</small><h4>{{ selectedCommand.summary }}</h4></div><StatusBadge :tone="tone(selectedCommand.termination)" dot>{{ selectedCommand.termination }}</StatusBadge></header>
          <dl>
            <div><dt>Kind / Tool</dt><dd>{{ selectedCommand.commandKind }} · {{ selectedCommand.toolKey }}</dd></div>
            <div><dt>Exit Code</dt><dd>{{ selectedCommand.exitCode ?? '—' }}</dd></div>
            <div><dt><Clock3 :size="11" />执行时长</dt><dd>{{ duration(selectedCommand) }}</dd></div>
            <div><dt>Timeout</dt><dd>{{ selectedCommand.timeoutSeconds }} s</dd></div>
          </dl>
          <p v-if="selectedCommand.failureClassification" class="failure"><TriangleAlert :size="12" />{{ selectedCommand.failureClassification }}</p>

          <div class="artifact-heading"><strong>有界命令日志</strong><small>{{ selectedLog?.value ? `${bytes(selectedLog.value.loadedBytes)} / ${bytes(selectedLog.value.totalSize)}` : bytes(selectedCommand.commandLog.sizeBytes) }}</small></div>
          <StatePanel v-if="selectedLog?.phase === 'loading' && !selectedLog.value" compact state="loading" title="正在读取首个日志页" />
          <StatePanel v-else-if="selectedLog?.phase === 'error' && !selectedLog.value" compact state="error" :description="selectedLog.errorMessage ?? undefined" @retry="onLoadCommandLog(selectedCommand.id)" />
          <div v-else-if="selectedLog?.value" class="artifact-view">
            <pre role="region" tabindex="0" aria-label="只读命令日志"><code>{{ safeText(selectedLog.value.content) }}</code></pre>
            <footer>
              <span v-if="!selectedLog.value.complete">日志仍有后续字节页</span><span v-else>完整性校验通过</span>
              <button v-if="!selectedLog.value.complete" type="button" :disabled="selectedLog.phase === 'loading'" @click="onLoadCommandLog(selectedCommand.id, true)">继续加载</button>
              <button v-else-if="selectedLog.value.filename" type="button" @click="download(selectedLog.value)"><Download :size="11" />下载日志</button>
            </footer>
            <p v-if="selectedLog.phase === 'error'" class="artifact-error" role="alert">{{ selectedLog.errorMessage }} <button type="button" @click="onLoadCommandLog(selectedCommand.id, true)">重试当前页</button></p>
          </div>
          <button v-else class="read-artifact" type="button" @click="onLoadCommandLog(selectedCommand.id)">读取首个日志页</button>
        </article>

        <section class="test-section" aria-labelledby="test-evidence-title">
          <header><div><FileCheck2 :size="14" /><strong id="test-evidence-title">TestEvidence</strong></div><button v-if="tests?.nextCursor" type="button" :disabled="testsPhase === 'loading'" @click="onLoadTestsMore">更多测试轮次</button></header>
          <StatePanel v-if="testsPhase === 'loading' && !tests?.items.length" compact state="loading" title="正在加载测试证据" />
          <StatePanel v-else-if="testsPhase === 'error' && !tests?.items.length" compact state="error" :description="testsErrorMessage ?? undefined" @retry="onLoadTestsMore" />
          <StatePanel v-else-if="!orderedTests.length" compact state="empty" title="尚未产生测试证据" />
          <template v-else-if="selectedTest">
            <div class="test-tabs" role="group" aria-label="测试证据轮次">
              <button v-for="test in orderedTests" :key="test.id" type="button" :aria-pressed="test.id === selectedTest.id" :class="{ selected: test.id === selectedTest.id }" @click="selectedTestId = test.id">#{{ test.sequence }} · {{ test.failed + test.errors ? '未通过' : '通过' }}</button>
            </div>
            <div class="test-summary">
              <div><span>Total</span><strong>{{ selectedTest.total }}</strong></div><div class="passed"><span>Passed</span><strong>{{ selectedTest.passed }}</strong></div><div class="failed"><span>Failed</span><strong>{{ selectedTest.failed }}</strong></div><div class="failed"><span>Errors</span><strong>{{ selectedTest.errors }}</strong></div><div><span>Skipped</span><strong>{{ selectedTest.skipped }}</strong></div>
            </div>
            <p class="test-message">Generation {{ selectedTest.diffGeneration }} · {{ selectedTest.summary }}</p>
            <p v-if="selectedTest.failureClassification" class="failure"><TriangleAlert :size="12" />{{ selectedTest.failureClassification }}</p>
            <div class="acceptance"><header><ListChecks :size="13" /><strong>验收结果</strong></header><ol><li v-for="item in orderedAcceptance" :key="item.criterionIndex"><CheckCircle2 v-if="item.status === 'PASSED'" :size="13" /><TriangleAlert v-else :size="13" /><div><strong>{{ item.criterion }}</strong><span>{{ item.summary }}</span></div><StatusBadge :tone="tone(item.status)">{{ item.status }}</StatusBadge></li></ol><p v-if="!orderedAcceptance.length">当前测试轮次没有验收条目。</p></div>
            <div v-if="selectedTest.testReport" class="report-block">
              <div class="artifact-heading"><strong>有界测试报告</strong><small>{{ selectedReport?.value ? `${bytes(selectedReport.value.loadedBytes)} / ${bytes(selectedReport.value.totalSize)}` : bytes(selectedTest.testReport.sizeBytes) }}</small></div>
              <div v-if="selectedReport?.value" class="artifact-view"><pre role="region" tabindex="0" aria-label="只读测试报告"><code>{{ safeText(selectedReport.value.content) }}</code></pre><footer><span>{{ selectedReport.value.complete ? '完整性校验通过' : '报告仍有后续字节页' }}</span><button v-if="!selectedReport.value.complete" type="button" @click="onLoadTestReport(selectedTest.id, true)">继续加载</button><button v-else-if="selectedReport.value.filename" type="button" @click="download(selectedReport.value)"><Download :size="11" />下载报告</button></footer><p v-if="selectedReport.phase === 'error'" class="artifact-error" role="alert">{{ selectedReport.errorMessage }} <button type="button" @click="onLoadTestReport(selectedTest.id, true)">重试当前页</button></p></div>
              <StatePanel v-else-if="selectedReport?.phase === 'loading'" compact state="loading" title="正在读取首个报告页" />
              <StatePanel v-else-if="selectedReport?.phase === 'error'" compact state="error" :description="selectedReport.errorMessage ?? undefined" @retry="onLoadTestReport(selectedTest.id)" />
              <button v-else class="read-artifact" type="button" @click="onLoadTestReport(selectedTest.id)">读取首个报告页</button>
            </div>
          </template>
        </section>
      </section>
    </div>
  </section>
</template>

<style scoped>
.evidence-panel{padding:0;overflow:hidden}.evidence-heading{display:flex;min-height:58px;align-items:center;justify-content:space-between;gap:12px;padding:11px 14px;border-bottom:1px solid var(--cs-border);background:linear-gradient(110deg,var(--cs-brand-50),var(--cs-surface) 62%)}.evidence-heading p,.evidence-heading h3{margin:0}.evidence-heading p{color:var(--cs-brand-600);font-size:8px;font-weight:800;letter-spacing:.08em;text-transform:uppercase}.evidence-heading h3{margin-top:2px;font-size:13px}.evidence-heading>span{display:flex;align-items:center;gap:4px;color:var(--cs-brand-700);font-size:8px}.evidence-layout{display:grid;grid-template-columns:minmax(190px,.28fr) minmax(0,1fr)}.command-column{min-width:0;border-right:1px solid var(--cs-border);background:var(--cs-surface-subtle)}.command-column>header,.test-section>header{display:flex;min-height:40px;align-items:center;gap:6px;padding:8px 10px;border-bottom:1px solid var(--cs-border);color:var(--cs-text-secondary);font-size:9px}.command-column>header svg,.test-section>header svg{color:var(--cs-brand-600)}.command-column>header small{margin-left:auto;color:var(--cs-text-muted)}.command-column :deep(.state-panel){border:0;border-radius:0}.command-list{display:grid;gap:4px;max-height:350px;overflow:auto;padding:7px}.command-list>button{display:grid;grid-template-columns:minmax(0,1fr) auto;gap:3px 6px;padding:8px;border:1px solid transparent;border-radius:8px;background:transparent;text-align:left;cursor:pointer}.command-list>button.selected{border-color:var(--cs-brand-200);background:var(--cs-brand-50)}.command-list span,.command-list strong{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.command-list span{color:var(--cs-text-muted);font-size:7px}.command-list strong{font:8px var(--cs-font-mono)}.command-list :deep(.status-badge){grid-row:1/3;grid-column:2}.load-more,.read-artifact,.test-section>header button{display:flex;align-items:center;justify-content:center;gap:4px;margin:7px;padding:6px 8px;border:1px solid var(--cs-border);border-radius:7px;background:var(--cs-surface);color:var(--cs-brand-700);font-size:8px;font-weight:750;cursor:pointer}.evidence-main{min-width:0}.command-detail{padding:12px;border-bottom:1px solid var(--cs-border)}.command-detail>header{display:flex;align-items:flex-start;justify-content:space-between;gap:8px}.command-detail h4,.command-detail small{margin:0}.command-detail h4{margin-top:2px;font-size:11px}.command-detail small{color:var(--cs-text-muted);font-size:8px}.command-detail dl{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:6px;margin:10px 0}.command-detail dl>div{padding:7px;border:1px solid var(--cs-border);border-radius:7px;background:var(--cs-surface-subtle)}.command-detail dt{display:flex;align-items:center;gap:3px;color:var(--cs-text-muted);font-size:7px}.command-detail dd{margin:3px 0 0;overflow:hidden;font:8px var(--cs-font-mono);text-overflow:ellipsis;white-space:nowrap}.failure,.artifact-error{margin:6px 0;padding:7px;border-radius:7px;background:var(--cs-danger-soft);color:var(--cs-danger);font-size:8px}.failure{display:flex;align-items:center;gap:4px}.artifact-heading{display:flex;align-items:center;justify-content:space-between;margin-top:10px;padding:6px 1px;font-size:8px}.artifact-heading small{color:var(--cs-text-muted);font:7px var(--cs-font-mono)}.artifact-view{overflow:hidden;border:1px solid var(--cs-border);border-radius:8px;background:#fbfcfb}.artifact-view pre{max-height:220px;margin:0;overflow:auto;padding:9px;outline:0;color:var(--cs-text-secondary);font:8px/1.55 var(--cs-font-mono);white-space:pre-wrap;word-break:break-word}.artifact-view pre:focus-visible{box-shadow:inset 0 0 0 2px var(--cs-focus)}.artifact-view footer{display:flex;align-items:center;gap:7px;padding:6px 8px;border-top:1px solid var(--cs-border);background:var(--cs-surface-subtle);color:var(--cs-text-muted);font-size:7px}.artifact-view footer button{display:flex;align-items:center;gap:3px;margin-left:auto;color:var(--cs-brand-700);font-size:8px;font-weight:750;cursor:pointer}.artifact-error{margin:0;border-radius:0}.artifact-error button{color:inherit;font-weight:800;text-decoration:underline;cursor:pointer}.read-artifact{width:100%;margin:0}.test-section>header{justify-content:space-between}.test-section>header>div{display:flex;align-items:center;gap:6px}.test-section>header button{margin:0}.test-section>:deep(.state-panel){border:0;border-radius:0}.test-tabs{display:flex;gap:4px;overflow:auto;padding:8px 10px 0}.test-tabs button{flex:0 0 auto;padding:5px 7px;border:1px solid var(--cs-border);border-radius:6px;background:var(--cs-surface);color:var(--cs-text-muted);font-size:8px;cursor:pointer}.test-tabs button.selected{border-color:var(--cs-brand-200);background:var(--cs-brand-50);color:var(--cs-brand-700);font-weight:800}.test-summary{display:grid;grid-template-columns:repeat(5,minmax(0,1fr));margin:8px 10px;border:1px solid var(--cs-border);border-radius:8px;background:var(--cs-surface-subtle)}.test-summary>div{padding:7px;border-right:1px solid var(--cs-border)}.test-summary>div:last-child{border:0}.test-summary span,.test-summary strong{display:block}.test-summary span{color:var(--cs-text-muted);font-size:7px}.test-summary strong{margin-top:2px;font:10px var(--cs-font-mono)}.test-summary .passed strong{color:#237a50}.test-summary .failed strong{color:#b34e56}.test-message{margin:0 10px;color:var(--cs-text-secondary);font-size:9px}.acceptance{margin:10px}.acceptance>header{display:flex;align-items:center;gap:5px;color:var(--cs-text-secondary);font-size:9px}.acceptance ol{display:grid;gap:5px;margin:7px 0 0;padding:0;list-style:none}.acceptance li{display:grid;grid-template-columns:auto minmax(0,1fr) auto;align-items:start;gap:6px;padding:7px;border:1px solid var(--cs-border);border-radius:7px;background:var(--cs-surface-subtle)}.acceptance li>svg{color:var(--cs-brand-600)}.acceptance li strong,.acceptance li span{display:block}.acceptance li strong{font-size:8px}.acceptance li span,.acceptance>p{margin-top:2px;color:var(--cs-text-muted);font-size:8px}.report-block{margin:10px}.report-block .artifact-heading{margin-top:0}@media(max-width:720px){.evidence-layout{grid-template-columns:1fr}.command-column{border-right:0;border-bottom:1px solid var(--cs-border)}.command-list{max-height:220px}.command-detail dl{grid-template-columns:repeat(2,minmax(0,1fr))}.evidence-heading{align-items:flex-start;flex-direction:column}.test-summary{grid-template-columns:repeat(3,1fr)}.test-summary>div:nth-child(3){border-right:0}.test-summary>div:nth-child(-n+3){border-bottom:1px solid var(--cs-border)}}
</style>
