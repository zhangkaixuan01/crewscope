<script setup lang="ts">
import {
  Binary,
  FileCode2,
  FileDiff,
  Folder,
  GitCompareArrows,
  RefreshCw,
  Search,
  Wifi,
  WifiOff,
} from '@lucide/vue'
import { computed, ref, watch } from 'vue'
import { flattenDiffTree, patchForFile, projectWorkspaceDiff } from '../../domains/coding/diff'
import type { CodingPhase } from '../../domains/coding/store'
import type { CodingAttemptSummary, CodingPatchDocument, DiffFileSummary } from '../../domains/coding/types'
import type { TaskLiveState } from '../../domains/task/store'
import type { TaskEventPage } from '../../domains/task/types'
import StatusBadge from '../base/StatusBadge.vue'
import StatePanel from '../feedback/StatePanel.vue'

const props = defineProps<{
  attempt: CodingAttemptSummary
  eventPage: TaskEventPage | null
  liveState: TaskLiveState | null
  patchPhase: CodingPhase
  patch: CodingPatchDocument | null
  patchErrorMessage: string | null
  onLoadPatch: () => void
  onReconcile: () => void
}>()

const search = ref('')
const selectedPath = ref<string | null>(null)
const projection = computed(() => projectWorkspaceDiff(
  props.eventPage?.items ?? [],
  props.attempt.executionId,
  props.attempt.details!.workspace.id,
  props.attempt.details!.diffManifest,
  Boolean(props.liveState?.projectionGap),
))
const matchingFiles = computed(() => {
  const query = search.value.trim().toLocaleLowerCase()
  return query
    ? projection.value.files.filter(file => `${file.path} ${file.oldPath ?? ''}`.toLocaleLowerCase().includes(query))
    : projection.value.files
})
const visibleFiles = computed(() => matchingFiles.value.slice(0, 400))
const treeRows = computed(() => flattenDiffTree(visibleFiles.value))
const selectedFile = computed(() => projection.value.files.find(file => file.path === selectedPath.value)
  ?? projection.value.files[0]
  ?? null)
const selectedPatch = computed(() => props.patch && selectedFile.value
  ? patchForFile(props.patch.content, selectedFile.value)
  : null)
const patchLines = computed(() => (selectedPatch.value?.split('\n') ?? []).slice(0, 2_000))
const patchRenderTruncated = computed(() => (selectedPatch.value?.split('\n').length ?? 0) > 2_000)
const streamLabel = computed(() => {
  if (projection.value.status === 'reconciled') return '已按权威快照对账'
  if (projection.value.status === 'gap') return '实时序列存在缺口'
  if (props.liveState?.phase === 'reconnecting') return '实时流正在续传'
  if (props.liveState?.phase === 'error') return '实时流不可用'
  if (props.liveState?.phase === 'connected') return projection.value.status === 'live' ? '实时流已连接 · Diff 已同步' : '实时流已连接'
  if (projection.value.status === 'snapshot') return '权威 Diff 快照'
  return projection.value.status === 'live' ? '实时 Diff 已同步' : '等待首次 Diff'
})

watch(
  () => projection.value.files.map(file => file.path).join('\n'),
  () => {
    if (!projection.value.files.some(file => file.path === selectedPath.value)) {
      selectedPath.value = projection.value.files[0]?.path ?? null
    }
  },
  { immediate: true },
)

function changeLabel(kind: string): string {
  return ({ ADDED: 'A', MODIFIED: 'M', DELETED: 'D', RENAMED: 'R', COPIED: 'C' } as Record<string, string>)[kind] ?? kind.slice(0, 1)
}

function changeTone(kind: string): 'success' | 'warning' | 'danger' | 'info' | 'neutral' {
  if (kind === 'ADDED') return 'success'
  if (kind === 'DELETED') return 'danger'
  if (kind === 'RENAMED' || kind === 'COPIED') return 'info'
  return kind === 'MODIFIED' ? 'warning' : 'neutral'
}

function lineKind(line: string): string {
  if (line.startsWith('+++') || line.startsWith('---')) return 'meta'
  if (line.startsWith('+')) return 'addition'
  if (line.startsWith('-')) return 'deletion'
  if (line.startsWith('@@')) return 'hunk'
  if (line.startsWith('diff --git') || line.startsWith('index ')) return 'meta'
  return 'context'
}
</script>

<template>
  <section class="diff-explorer detail-card" aria-labelledby="diff-explorer-title" data-testid="coding-diff-explorer">
    <header class="diff-heading">
      <div>
        <p>Workspace changes · Durable stream</p>
        <h3 id="diff-explorer-title">Diff Explorer</h3>
      </div>
      <div class="diff-heading__status" role="status" aria-live="polite">
        <WifiOff v-if="liveState?.phase === 'reconnecting' || liveState?.phase === 'error' || projection.status === 'gap'" :size="13" />
        <Wifi v-else :size="13" />
        <span>{{ streamLabel }}</span>
      </div>
    </header>

    <StatePanel
      v-if="projection.status === 'empty' && !eventPage"
      compact
      state="loading"
      title="正在读取 Diff 历史"
      description="历史 Cursor 与实时流使用同一条耐久 Task Timeline。"
    />
    <StatePanel
      v-else-if="projection.status === 'empty'"
      compact
      state="empty"
      title="尚未产生代码变更"
      description="Coding Agent 写入受控文件后，这里会出现实时文件投影。"
    />

    <template v-else>
      <div class="diff-stats">
        <div><FileDiff :size="14" /><span>Changed</span><strong>{{ projection.files.length }}</strong></div>
        <div class="diff-stats__addition"><span>Additions</span><strong>+{{ projection.additions }}</strong></div>
        <div class="diff-stats__deletion"><span>Deletions</span><strong>-{{ projection.deletions }}</strong></div>
        <div><GitCompareArrows :size="14" /><span>Generation</span><strong>{{ projection.generation }}</strong></div>
      </div>

      <div v-if="projection.status === 'gap'" class="diff-gap" role="alert">
        <span>实时 Diff 序列无法安全续接，文件投影已停止增量合并。</span>
        <button type="button" @click="onReconcile"><RefreshCw :size="12" />读取权威快照</button>
      </div>

      <div class="diff-workspace">
        <aside class="diff-tree" aria-label="变更文件树">
          <label class="diff-search">
            <Search :size="13" aria-hidden="true" />
            <span class="sr-only">筛选变更文件</span>
            <input v-model="search" type="search" placeholder="筛选路径" />
          </label>
          <div class="diff-tree__list">
            <template v-for="row in treeRows" :key="row.key">
              <div v-if="row.kind === 'folder'" class="diff-tree__folder" :style="{ '--depth': row.depth }">
                <Folder :size="12" aria-hidden="true" /><span>{{ row.name }}</span>
              </div>
              <button
                v-else
                type="button"
                class="diff-tree__file"
                :class="{ selected: selectedFile?.path === row.path }"
                :style="{ '--depth': row.depth }"
                :aria-pressed="selectedFile?.path === row.path"
                :title="row.path"
                @click="selectedPath = row.path"
              >
                <Binary v-if="row.file?.binary" :size="12" aria-hidden="true" />
                <FileCode2 v-else :size="12" aria-hidden="true" />
                <span>{{ row.name }}</span>
                <StatusBadge :tone="changeTone(row.file?.changeKind ?? '')">{{ changeLabel(row.file?.changeKind ?? '') }}</StatusBadge>
              </button>
            </template>
          </div>
          <p v-if="matchingFiles.length > visibleFiles.length" class="diff-tree__limit">
            已显示前 {{ visibleFiles.length }} / {{ matchingFiles.length }} 个文件，请按路径继续筛选。
          </p>
        </aside>

        <article class="patch-view" aria-live="polite">
          <header v-if="selectedFile">
            <div>
              <strong>{{ selectedFile.path }}</strong>
              <small v-if="selectedFile.oldPath">from {{ selectedFile.oldPath }}</small>
            </div>
            <span><b>+{{ selectedFile.additions }}</b><i>-{{ selectedFile.deletions }}</i></span>
          </header>

          <div v-if="selectedFile?.binary" class="patch-message">
            <Binary :size="22" /><strong>Binary 变更</strong><span>二进制内容不进入浏览器 Patch 视图。</span>
          </div>
          <div v-else-if="!attempt.details?.diffManifest" class="patch-message">
            <Wifi :size="22" /><strong>实时文件摘要已同步</strong><span>Patch 内容在最终 DiffArtifact 发布后通过独立授权入口读取。</span>
          </div>
          <StatePanel v-else-if="patchPhase === 'loading'" compact state="loading" title="正在分段读取 Patch" description="按字节分页读取并在完整性稳定后呈现。" />
          <StatePanel v-else-if="patchPhase === 'error'" compact state="error" title="Patch 暂时不可用" :description="patchErrorMessage ?? undefined" @retry="onLoadPatch" />
          <div v-else-if="patchPhase === 'idle'" class="patch-message">
            <FileDiff :size="22" /><strong>Patch 已可读取</strong><span>内容由 Task、attempt 与 Artifact 关系授权。</span>
            <button type="button" @click="onLoadPatch">读取单文件 Patch</button>
          </div>
          <div v-else-if="!selectedPatch" class="patch-message">
            <FileDiff :size="22" /><strong>当前文件没有文本 Patch</strong><span>文件可能只发生元数据变化，或 Artifact 已按预算截断。</span>
          </div>
          <div v-else class="patch-code" role="region" :aria-label="`${selectedFile?.path} Patch`" tabindex="0">
            <code>
              <span v-for="(line, index) in patchLines" :key="`${index}:${line}`" :class="`patch-line patch-line--${lineKind(line)}`"><i>{{ index + 1 }}</i><b>{{ line || ' ' }}</b></span>
            </code>
            <p v-if="selectedFile?.patchTruncated || patchRenderTruncated">Patch 展示已按服务端或浏览器渲染预算截断。</p>
          </div>
        </article>
      </div>
    </template>
  </section>
</template>

<style scoped>
.diff-explorer { padding: 0; overflow: hidden; }.diff-heading { display: flex; min-height: 58px; align-items: center; justify-content: space-between; gap: 12px; padding: 11px 14px; border-bottom: 1px solid var(--cs-border); }.diff-heading p, .diff-heading h3 { margin: 0; }.diff-heading p { color: var(--cs-brand-600); font-size: 8px; font-weight: 800; letter-spacing: .08em; text-transform: uppercase; }.diff-heading h3 { margin-top: 2px; font-size: 13px; }.diff-heading__status { display: flex; align-items: center; gap: 5px; color: var(--cs-text-muted); font-size: 8px; }.diff-heading__status svg { color: var(--cs-brand-600); }.diff-explorer > :deep(.state-panel) { min-height: 112px; border: 0; border-radius: 0; }.diff-stats { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); border-bottom: 1px solid var(--cs-border); background: var(--cs-surface-subtle); }.diff-stats > div { display: grid; min-width: 0; grid-template-columns: auto 1fr; align-items: center; gap: 2px 6px; padding: 9px 12px; border-right: 1px solid var(--cs-border); }.diff-stats > div:last-child { border-right: 0; }.diff-stats svg { grid-row: 1 / 3; color: var(--cs-text-muted); }.diff-stats span { color: var(--cs-text-muted); font-size: 7px; text-transform: uppercase; }.diff-stats strong { font: 11px var(--cs-font-mono); }.diff-stats__addition strong { color: #237a50; }.diff-stats__deletion strong { color: #b34e56; }.diff-gap { display: flex; align-items: center; justify-content: space-between; gap: 10px; padding: 8px 12px; border-bottom: 1px solid #efd4aa; background: var(--cs-warning-soft); color: #7c4a12; font-size: 8px; }.diff-gap button, .patch-message button { display: inline-flex; align-items: center; gap: 4px; padding: 5px 7px; border-radius: 6px; background: rgb(255 255 255 / 70%); color: inherit; font-size: 8px; font-weight: 800; cursor: pointer; }.diff-workspace { display: grid; min-height: 350px; grid-template-columns: minmax(220px, .34fr) minmax(0, 1fr); }.diff-tree { min-width: 0; border-right: 1px solid var(--cs-border); background: var(--cs-surface-subtle); }.diff-search { display: flex; align-items: center; gap: 6px; margin: 9px; padding: 0 8px; border: 1px solid var(--cs-border); border-radius: 8px; background: var(--cs-surface); color: var(--cs-text-muted); }.diff-search input { width: 100%; min-width: 0; height: 31px; background: transparent; font-size: 9px; outline: 0; }.diff-tree__list { max-height: 430px; overflow: auto; padding: 0 6px 8px; }.diff-tree__folder, .diff-tree__file { --indent: calc(var(--depth) * 12px); padding-left: calc(7px + var(--indent)); }.diff-tree__folder { display: flex; align-items: center; gap: 5px; min-height: 25px; color: var(--cs-text-muted); font-size: 8px; font-weight: 750; }.diff-tree__file { display: grid; width: 100%; min-height: 29px; grid-template-columns: auto minmax(0, 1fr) auto; align-items: center; gap: 5px; padding-right: 6px; border: 1px solid transparent; border-radius: 7px; color: var(--cs-text-secondary); text-align: left; cursor: pointer; }.diff-tree__file > span { overflow: hidden; font: 8px var(--cs-font-mono); text-overflow: ellipsis; white-space: nowrap; }.diff-tree__file.selected { border-color: var(--cs-brand-200); background: var(--cs-brand-50); color: var(--cs-brand-800); }.diff-tree__file :deep(.status-badge) { min-width: 19px; justify-content: center; padding-inline: 4px; }.diff-tree__limit { margin: 0; padding: 8px 10px; border-top: 1px solid var(--cs-border); color: var(--cs-text-muted); font-size: 8px; line-height: 1.45; }.patch-view { min-width: 0; background: #fbfcfb; }.patch-view > header { display: flex; min-height: 49px; align-items: center; justify-content: space-between; gap: 10px; padding: 8px 12px; border-bottom: 1px solid var(--cs-border); background: var(--cs-surface); }.patch-view header strong, .patch-view header small { display: block; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }.patch-view header strong { font: 9px var(--cs-font-mono); }.patch-view header small { margin-top: 3px; color: var(--cs-text-muted); font: 7px var(--cs-font-mono); }.patch-view header > span { display: flex; gap: 7px; font: 8px var(--cs-font-mono); }.patch-view header b { color: #237a50; }.patch-view header i { color: #b34e56; font-style: normal; }.patch-view > :deep(.state-panel) { min-height: 250px; border: 0; }.patch-message { display: grid; min-height: 250px; place-content: center; justify-items: center; gap: 6px; padding: 24px; color: var(--cs-text-muted); text-align: center; }.patch-message svg { color: var(--cs-brand-500); }.patch-message strong { color: var(--cs-text-secondary); font-size: 10px; }.patch-message span { max-width: 320px; font-size: 8px; line-height: 1.5; }.patch-message button { margin-top: 4px; background: var(--cs-brand-100); color: var(--cs-brand-700); }.patch-code { max-height: 430px; overflow: auto; outline: none; }.patch-code:focus-visible { box-shadow: inset 0 0 0 2px var(--cs-focus); }.patch-code code { display: table; min-width: 100%; padding: 6px 0; font: 8px/1.55 var(--cs-font-mono); }.patch-line { display: table-row; }.patch-line > i { display: table-cell; width: 1%; padding: 0 9px; color: #9aa29e; font-style: normal; text-align: right; user-select: none; }.patch-line > b { display: table-cell; padding-right: 12px; font-weight: 450; white-space: pre; }.patch-line--addition { background: #eef8f1; color: #286645; }.patch-line--deletion { background: #fdf0f1; color: #96434b; }.patch-line--hunk { background: #eef4fa; color: #496c89; }.patch-line--meta { color: var(--cs-text-muted); }.patch-code > p { margin: 0; padding: 8px 11px; background: var(--cs-warning-soft); color: #7c4a12; font-size: 8px; }.sr-only { position: absolute; width: 1px; height: 1px; overflow: hidden; clip: rect(0, 0, 0, 0); white-space: nowrap; }
@media (max-width: 720px) { .diff-heading { align-items: flex-start; flex-direction: column; }.diff-stats { grid-template-columns: repeat(2, 1fr); }.diff-stats > div:nth-child(2) { border-right: 0; }.diff-stats > div:nth-child(-n+2) { border-bottom: 1px solid var(--cs-border); }.diff-workspace { grid-template-columns: 1fr; }.diff-tree { border-right: 0; border-bottom: 1px solid var(--cs-border); }.diff-tree__list { max-height: 250px; }.patch-code { max-height: 460px; } }
</style>
