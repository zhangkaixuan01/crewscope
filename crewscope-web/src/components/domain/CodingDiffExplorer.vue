<script setup lang="ts">
import { createRequestScope } from '../../api/requestScope'
import {
  Binary,
  Check,
  ChevronDown,
  ChevronRight,
  FileCode2,
  FileDiff,
  Folder,
  GitCompareArrows,
  RefreshCw,
  Search,
  Maximize2,
  Wifi,
  WifiOff,
} from '@lucide/vue'
import { computed, inject, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { AUTH_PRINCIPAL } from '../../app/auth'
import { usePreference } from '../../composables/usePreference'
import { useScopedUserState } from '../../composables/useScopedUserState'
import { flattenDiffTree, patchForFile, projectWorkspaceDiff } from '../../domains/coding/diff'
import type { CodingPhase } from '../../domains/coding/store'
import type { CodingAttemptSummary, CodingPatchDocument, DiffFileSummary } from '../../domains/coding/types'
import type { TaskLiveState } from '../../domains/task/store'
import type { TaskEventPage } from '../../domains/task/types'
import { useScopeStore } from '../../domains/scope/store'
import { clearReviewLineCommentDraftIfRevision, readReviewLineCommentDraft, writeReviewLineCommentDraft, type ReviewLineCommentCoordinate } from '../../domains/review/lineCommentDraft'
import type { ReviewCommentSide, ReviewFindingEvidence, ReviewLineComment } from '../../domains/review/types'
import { enumLabel, enumLabelOr } from '../../domains/shared/labels'
import { sha256Digest } from '../../domains/shared/sha256'
import { diffFileKindAbbreviations, diffFileKindLabels } from '../../domains/coding/labels'
import { hasSyntax, loadSyntax, plainTokenLine, syntaxLanguageLabel, syntaxTokenLines, type SyntaxToken } from '../../domains/coding/syntax'
import StatusBadge from '../base/StatusBadge.vue'
import StatePanel from '../feedback/StatePanel.vue'
import SafeMarkdown from './SafeMarkdown.vue'

const props = defineProps<{
  attempt: CodingAttemptSummary
  eventPage: TaskEventPage | null
  liveState: TaskLiveState | null
  patchPhase: CodingPhase
  patch: CodingPatchDocument | null
  patchErrorMessage: string | null
  reviewLocation?: ReviewFindingEvidence | null
  reviewComments?: ReviewLineComment[]
  onAddComment?: (input: {
    filePath: string
    side: ReviewCommentSide
    lineNumber: number
    hunkHeader: string
    lineContentHash: string
    diffGeneration: number
    content: string
  }) => Promise<ReviewLineComment | null> | ReviewLineComment | null
  onLoadPatch: () => void
  onReconcile: () => void
}>()

const search = ref('')
const selectedPath = ref<string | null>(null)
const principal = inject(AUTH_PRINCIPAL)
const scopeStore = useScopeStore()
const viewMode = usePreference<'unified' | 'split'>('cs.pref.diff.view-mode.v1', 'unified', { version: 1 })
// Viewed-file marks belong to one execution and one identity: they moved from the device-level
// preference key into the scoped `reading` kind so they never cross accounts or attempts
// (M9b-F05); the diff view-mode stays a device preference.
const viewedFiles = useScopedUserState<string[]>({
  scope: () => (principal && principal.accountId && scopeStore.state.selectedTeamId
    ? {
        accountId: principal.accountId, principalId: principal.id, organizationId: principal.organizationId,
        teamId: scopeStore.state.selectedTeamId, projectId: null, objectId: props.attempt.executionId,
      }
    : null),
  name: 'viewed-files',
  fallback: [],
  validate: (value): value is string[] => Array.isArray(value) && value.every(item => typeof item === 'string'),
})
const collapsedBlocks = ref(new Set<string>())
const treeWidth = ref(34)
const commentDraft = ref<{ line: ParsedPatchLine, side: ReviewCommentSide } | null>(null)
const commentText = ref('')
const commentError = ref<string | null>(null)
const commentSubmitting = ref(false)
const localComments = ref<ReviewLineComment[]>([])
let stopResize: (() => void) | null = null
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
const commentCoordinate = () => JSON.stringify([props.attempt.executionId, props.attempt.details?.workspace.id,
  selectedFile.value?.path, projection.value.generation])
const commentRequests = createRequestScope(commentCoordinate)
const reviewDraftScope = computed(() => principal && scopeStore.state.selectedTeamId
  ? { organizationId: principal.organizationId, teamId: scopeStore.state.selectedTeamId }
  : null)

function lineDraftCoordinate(line: ParsedPatchLine, side: ReviewCommentSide, filePath: string | null | undefined, generation: number): ReviewLineCommentCoordinate | null {
  const lineNumber = line.lineNumber(side)
  if (!lineNumber || !filePath) return null
  return { executionId: props.attempt.executionId, filePath, side, lineNumber, diffGeneration: generation }
}

watch([commentCoordinate, () => props.onAddComment], (_next, previous) => {
  // Persist the in-progress line comment under the coordinate it belongs to *before* the
  // switch clears it, so switching files or a diff re-projection never eats typed input.
  const openDraft = commentDraft.value
  const content = commentText.value.trim()
  if (openDraft && content && reviewDraftScope.value && typeof previous[0] === 'string') {
    const [, , previousPath, previousGeneration] = JSON.parse(previous[0]) as [string, string | undefined, string | null, number | undefined]
    const coordinate = lineDraftCoordinate(openDraft.line, openDraft.side, previousPath, previousGeneration ?? projection.value.generation)
    if (coordinate) writeReviewLineCommentDraft(reviewDraftScope.value, coordinate, content, principal)
  }
  commentRequests.invalidate()
  commentDraft.value = null
  commentText.value = ''
  commentError.value = null
  commentSubmitting.value = false
  localComments.value = []
}, { flush: 'sync' })
const selectedPatch = computed(() => props.patch && selectedFile.value
  ? patchForFile(props.patch.content, selectedFile.value)
  : null)
const parsedLines = computed(() => parsePatch(selectedPatch.value ?? ''))
const patchLines = computed(() => visiblePatchLines(parsedLines.value, collapsedBlocks.value))
const patchRenderTruncated = computed(() => parsedLines.value.length > 2_000)
const generatedFile = computed(() => Boolean(selectedFile.value && /(^|\/)(dist|build|target|generated)(\/|$)|\.min\.(js|css)$/.test(selectedFile.value.path)))
const largeFile = computed(() => Boolean(selectedFile.value && (selectedFile.value.patchTruncated || (props.patch?.sizeBytes ?? 0) > 1024 * 1024)))
const comments = computed(() => [...(props.reviewComments ?? []), ...localComments.value]
  .filter(comment => comment.filePath === selectedFile.value?.path)
  .sort((left, right) => left.lineNumber - right.lineNumber))
const selectedLanguage = computed(() => syntaxLanguageLabel(selectedFile.value?.path ?? ''))
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

watch(() => props.reviewComments, value => { localComments.value = value ? [] : localComments.value }, { deep: true })

function markViewed(path: string): void {
  if (!viewedFiles.value.value.includes(path)) viewedFiles.value.value = [...viewedFiles.value.value, path]
}

function toggleBlock(key: string): void {
  const next = new Set(collapsedBlocks.value)
  if (next.has(key)) next.delete(key)
  else next.add(key)
  collapsedBlocks.value = next
}

function toggleViewMode(): void { viewMode.value.value = viewMode.value.value === 'unified' ? 'split' : 'unified' }

function toggleFullscreen(event: Event): void {
  const target = event.currentTarget as HTMLElement | null
  void target?.closest('.patch-view')?.requestFullscreen?.()
}

function startResize(event: PointerEvent): void {
  const workspace = (event.currentTarget as HTMLElement).parentElement?.parentElement
  if (!workspace) return
  const move = (next: PointerEvent) => {
    const bounds = workspace.getBoundingClientRect()
    treeWidth.value = Math.min(60, Math.max(22, ((next.clientX - bounds.left) / bounds.width) * 100))
  }
  stopResize?.()
  const stop = () => { window.removeEventListener('pointermove', move); window.removeEventListener('pointerup', stop); stopResize = null }
  stopResize = stop
  window.addEventListener('pointermove', move); window.addEventListener('pointerup', stop)
  ;(event.currentTarget as HTMLElement).setPointerCapture?.(event.pointerId)
}

onBeforeUnmount(() => { stopResize?.(); commentRequests.dispose() })

// Typed line-comment input lands immediately under its exact coordinate, so a refresh, an
// accidental close or a coordinate switch never eats it (M9b-F05).
watch(commentText, value => {
  const openDraft = commentDraft.value
  const scope = reviewDraftScope.value
  if (!openDraft || !scope) return
  const coordinate = lineDraftCoordinate(openDraft.line, openDraft.side, selectedFile.value?.path, projection.value.generation)
  if (!coordinate) return
  if (value.trim()) writeReviewLineCommentDraft(scope, coordinate, value, principal)
  else clearReviewLineCommentDraftIfRevision(scope, coordinate, principal)
})

function openComment(line: ParsedPatchLine, side: ReviewCommentSide = line.oldLine !== null && line.newLine !== null ? 'NEW' : line.newLine !== null ? 'NEW' : 'OLD'): void {
  if (commentSubmitting.value) return
  if (!line.lineNumber(side)) return
  commentDraft.value = { line, side }
  commentError.value = null
  // Restore a previously typed comment only when the composer is untouched; a changed diff
  // generation rides in the storage key, so stale input against shifted lines never returns.
  const coordinate = lineDraftCoordinate(line, side, selectedFile.value?.path, projection.value.generation)
  commentText.value = coordinate && reviewDraftScope.value && !commentText.value.trim()
    ? readReviewLineCommentDraft(reviewDraftScope.value, coordinate, principal)?.content ?? ''
    : ''
  void nextTick(() => document.querySelector<HTMLTextAreaElement>('[data-testid="review-comment-input"]')?.focus())
}

function closeComment(): void {
  if (commentSubmitting.value) return
  commentDraft.value = null
  commentError.value = null
  // The typed content is already persisted under its coordinate; clearing the local copy lets
  // a re-open restore it instead of showing a stale half-state.
  commentText.value = ''
}

async function submitComment(): Promise<void> {
  if (commentSubmitting.value) return
  const owner = commentRequests.capture()
  const draft = commentDraft.value
  const content = commentText.value.trim()
  if (!draft || !content) { commentError.value = '请输入评论内容。'; return }
  const lineNumber = draft.line.lineNumber(draft.side)
  if (!lineNumber || !selectedFile.value) return
  const filePath = selectedFile.value.path
  const diffGeneration = projection.value.generation
  const addComment = props.onAddComment
  if (!addComment) { commentError.value = '当前没有可提交评论的 Review，请先发起 Review。'; return }
  commentSubmitting.value = true
  commentError.value = null
  try {
    const lineContentHash = await sha256(draft.line.text.replace(/^[+\- ]/, ''))
    if (!owner.isCurrent()) return
    const result = await addComment({
      filePath, side: draft.side, lineNumber,
      hunkHeader: draft.line.hunkHeader, lineContentHash,
      diffGeneration, content,
    })
    if (!owner.isCurrent()) return
    if (!result) { commentError.value = '评论尚未确认保存，请保留内容并核实原 Review。'; return }
    localComments.value = [...localComments.value, result]
    // The submitted text is on the server now; discard the browser draft for this coordinate
    // only when it still holds this diff generation.
    if (reviewDraftScope.value) {
      clearReviewLineCommentDraftIfRevision(reviewDraftScope.value, {
        executionId: props.attempt.executionId, filePath, side: draft.side, lineNumber, diffGeneration,
      }, principal)
    }
    commentDraft.value = null
    commentText.value = ''
  } catch (error) {
    if (owner.isCurrent()) commentError.value = error instanceof Error ? error.message : '评论暂时无法提交，请重试。'
  } finally { if (owner.isCurrent()) commentSubmitting.value = false }
}

async function sha256(value: string): Promise<string> {
  // The anchor hash is verified against the server's line hash, so there is no placeholder path:
  // without WebCrypto the pure implementation still produces the same digest.
  return sha256Digest(new TextEncoder().encode(value))
}

function handlePatchKeydown(event: KeyboardEvent): void {
  if (event.target !== event.currentTarget) return
  if (event.key === 'n' || event.key === 'p') {
    event.preventDefault()
    const lines = parsedLines.value.filter(line => line.kind === 'addition' || line.kind === 'deletion')
    const index = lines.findIndex(line => line.id === focusedLine.value)
    const next = event.key === 'n' ? lines[(index + 1) % lines.length] : lines[(index - 1 + lines.length) % lines.length]
    if (next) focusedLine.value = next.id
  }
}

const focusedLine = ref<string | null>(null)

watch(
  () => props.reviewLocation,
  location => {
    if (!location || !projection.value.files.some(file => file.path === location.path)) return
    search.value = ''
    selectedPath.value = location.path
  },
)

// The list has room for one letter, so the badge carries the abbreviation and the full wording is
// attached as its accessible name rather than dropped.
function changeLabel(kind: string): string {
  return enumLabelOr(kind, diffFileKindAbbreviations, '?')
}

function changeName(kind: string): string {
  return enumLabel(kind, diffFileKindLabels)
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

interface ParsedPatchLine {
  id: string
  text: string
  kind: 'addition' | 'deletion' | 'context' | 'hunk' | 'meta'
  oldLine: number | null
  newLine: number | null
  hunkHeader: string
  contentHash: string
  lineNumber: (side: ReviewCommentSide) => number | null
}

/** Parses unified diff coordinates without trusting rendered line text as an anchor. */
function parsePatch(patch: string): ParsedPatchLine[] {
  const result: ParsedPatchLine[] = []
  let oldLine = 0
  let newLine = 0
  let hunkHeader = ''
  patch.split(/\r?\n/).forEach((text, index) => {
    const hunk = text.match(/^@@ -([0-9]+)(?:,([0-9]+))? \+([0-9]+)(?:,([0-9]+))? @@/)
    if (hunk) {
      oldLine = Number(hunk[1])
      newLine = Number(hunk[3])
      hunkHeader = hunk[0]
      result.push(makeLine(text, 'hunk', null, null, hunkHeader, index))
      return
    }
    if (text.startsWith('+++') || text.startsWith('---') || text.startsWith('diff --git') || text.startsWith('index ')) {
      result.push(makeLine(text, 'meta', null, null, hunkHeader, index))
      return
    }
    if (text.startsWith('+')) {
      const line = newLine++
      result.push(makeLine(text, 'addition', null, line, hunkHeader, index))
      return
    }
    if (text.startsWith('-')) {
      const line = oldLine++
      result.push(makeLine(text, 'deletion', line, null, hunkHeader, index))
      return
    }
    if (hunkHeader) {
      const old = oldLine++
      const next = newLine++
      result.push(makeLine(text, 'context', old, next, hunkHeader, index))
      return
    }
    result.push(makeLine(text, 'meta', null, null, hunkHeader, index))
  })
  return result
}

function makeLine(text: string, kind: ParsedPatchLine['kind'], oldLine: number | null, newLine: number | null, hunkHeader: string, index: number): ParsedPatchLine {
  const content = text.length > 0 && ['+', '-', ' '].includes(text[0] ?? '') ? text.slice(1) : text
  let hash = 0
  for (let i = 0; i < content.length; i += 1) hash = ((hash << 5) - hash + content.charCodeAt(i)) | 0
  const contentHash = Math.abs(hash).toString(16).padStart(64, '0').slice(0, 64)
  return { id: `${index}:${kind}:${oldLine ?? ''}:${newLine ?? ''}`, text, kind, oldLine, newLine, hunkHeader, contentHash, lineNumber: side => side === 'OLD' ? oldLine : newLine }
}

/** Keeps large context blocks collapsed while retaining nearby changed lines. */
function visiblePatchLines(lines: ParsedPatchLine[], collapsed: Set<string>): ParsedPatchLine[] {
  const output: ParsedPatchLine[] = []
  let contextStart = -1
  const flush = (end: number) => {
    if (contextStart < 0) return
    const count = end - contextStart
    if (count > 8) {
      const key = `${lines[contextStart]?.id}:${lines[end - 1]?.id}`
      if (collapsed.has(key)) output.push(...lines.slice(contextStart, end))
      else {
        output.push(...lines.slice(contextStart, contextStart + 3))
        output.push({ ...makeLine(`··· ${count - 6} 行上下文已折叠，点击展开`, 'meta', null, null, '', contextStart), id: `collapse:${key}` })
        output.push(...lines.slice(end - 3, end))
      }
    } else output.push(...lines.slice(contextStart, end))
    contextStart = -1
  }
  lines.forEach((line, index) => {
    if (line.kind === 'context') { if (contextStart < 0) contextStart = index; return }
    flush(index); output.push(line)
  })
  flush(lines.length)
  return output.slice(0, 2000)
}

/**
 * Tokenized lines for the whole patch, indexed by the patch's own line number.
 *
 * Null until this file type's grammar has arrived, and null for good if it never does: both cases
 * render every line as a single plain token, which is what the view showed before it had colour.
 */
const syntaxLines = ref<SyntaxToken[][] | null>(null)

let syntaxRequest = 0

/**
 * Highlights files whose patch we hold in full, and nothing else.
 *
 * The conditions are the renderer's own boundaries rather than a size of my own choosing. Prism cost
 * here is linear — measured at roughly 40ms per 200KB — so the question is not whether it would
 * finish but whether the work lands on screen, and 「我们只有片段」 and 「只画前 2000 行」 both mean
 * it does not:
 *
 * - A patch we hold only part of has no full context to tokenize. Colouring a fragment reads as fact:
 *   an unterminated block comment at the cut paints the rest of the visible file as a comment.
 * - Past the render limit, most of what would be tokenized is never drawn.
 *
 * Together these bound the work to what the viewer is willing to render — at most the 2000 lines —
 * with no separate budget to keep in sync with this one.
 */
watch(
  [() => selectedFile.value?.path ?? '', selectedPatch],
  async ([path, patch]) => {
    const request = ++syntaxRequest
    syntaxLines.value = null
    const text = patch ?? ''
    if (!text || !hasSyntax(path)) return
    if (generatedFile.value || largeFile.value || patchRenderTruncated.value || selectedFile.value?.patchTruncated) return
    if (!await loadSyntax(path)) return
    // 慢一步的语言包不能覆盖成员已经切过去的那份文件。
    if (request !== syntaxRequest) return
    syntaxLines.value = syntaxTokenLines(text, path)
  },
  { immediate: true },
)

/**
 * The token line for each parsed row, keyed by the row it was parsed from.
 *
 * Row ids are `${document line index}:${kind}:${old}:${new}`, and `syntaxTokenLines` returns its lines
 * in that same document order — so the two line numberings line up without re-parsing anything. The
 * synthesised rows — the 「上下文已折叠」 toggle — have no document line and are left out, which is
 * right: their text is ours, not the member's code.
 */
const tokensByLineId = computed(() => {
  const lines = syntaxLines.value
  if (!lines) return null
  const byId = new Map<string, SyntaxToken[]>()
  for (const line of parsedLines.value) {
    const index = Number.parseInt(line.id, 10)
    const tokens = Number.isInteger(index) ? lines[index] : undefined
    if (tokens) byId.set(line.id, tokens)
  }
  return byId
})

function tokensFor(line: ParsedPatchLine): SyntaxToken[] {
  const tokens = tokensByLineId.value?.get(line.id)
  // An empty token list would leave the cell bare where the plain view showed a space.
  return tokens?.length ? tokens : plainTokenLine(line.text || ' ')
}
</script>

<template>
  <section id="coding-diff-explorer" class="diff-explorer detail-card" aria-labelledby="diff-explorer-title" data-testid="coding-diff-explorer" tabindex="-1">
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

      <div class="diff-workspace" :style="{ '--tree-width': `${treeWidth}%` }">
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
                <StatusBadge :tone="changeTone(row.file?.changeKind ?? '')" :aria-label="changeName(row.file?.changeKind ?? '')">{{ changeLabel(row.file?.changeKind ?? '') }}</StatusBadge>
                <Check v-if="viewedFiles.value.value.includes(row.path)" :size="11" aria-label="已查看" />
              </button>
            </template>
          </div>
          <p v-if="matchingFiles.length > visibleFiles.length" class="diff-tree__limit">
            已显示前 {{ visibleFiles.length }} / {{ matchingFiles.length }} 个文件，请按路径继续筛选。
          </p>
        </aside>
        <button type="button" class="diff-resize-handle" aria-label="调整文件树宽度" @pointerdown="startResize" />

        <article class="patch-view" aria-live="polite">
          <header v-if="selectedFile">
            <div>
              <strong>{{ selectedFile.path }}</strong>
              <small v-if="selectedFile.oldPath">from {{ selectedFile.oldPath }}</small>
            </div>
            <div class="patch-actions">
              <span><b>+{{ selectedFile.additions }}</b><i>-{{ selectedFile.deletions }}</i></span>
              <span class="patch-language">{{ selectedLanguage }}</span>
              <button type="button" class="patch-action" @click="toggleViewMode">{{ viewMode.value.value === 'unified' ? 'Split' : 'Unified' }}</button>
              <button type="button" class="patch-action" @click="markViewed(selectedFile.path)">标记已查看</button>
              <button type="button" class="patch-action" @click="toggleFullscreen"><Maximize2 :size="11" />全屏</button>
            </div>
          </header>

          <p v-if="reviewLocation && selectedFile?.path === reviewLocation.path" class="review-location-note" role="status">
            Review Finding 定位：{{ reviewLocation.path }} · L{{ reviewLocation.startLine }}–{{ reviewLocation.endLine }}。源代码行号由服务端 Evidence Resolver 校验；这里展示对应文件的只读 Patch。
          </p>

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
          <div v-else-if="generatedFile || largeFile" class="patch-message">
            <FileDiff :size="22" /><strong>{{ generatedFile ? '生成文件' : '大文件' }}已降级</strong>
            <span>{{ generatedFile ? '生成目录内容默认隐藏，避免噪声。' : '文件超过浏览器渲染预算，仅展示摘要。' }}</span>
          </div>
          <div v-else class="patch-code" role="region" :aria-label="`${selectedFile?.path} Patch`" tabindex="0" @keydown="handlePatchKeydown">
            <code>
              <template v-for="line in patchLines" :key="line.id">
                <span v-if="line.text.startsWith('···')" class="patch-context-toggle" @click="toggleBlock(line.id.slice(9))"><ChevronDown :size="11" />{{ line.text }}</span>
                <span v-else class="patch-line" :class="[`patch-line--${line.kind}`, { focused: focusedLine === line.id, 'patch-line--split': viewMode.value.value === 'split' }]" @click="openComment(line)">
                  <button type="button" class="line-comment-button" :aria-label="`评论第 ${line.newLine ?? line.oldLine ?? 0} 行`" @click.stop="openComment(line)">＋</button>
                  <i v-if="viewMode.value.value === 'split'">{{ line.oldLine ?? '' }}</i><i>{{ viewMode.value.value === 'split' ? line.newLine ?? '' : line.newLine ?? line.oldLine ?? '' }}</i><b><span v-for="(token, tokenIndex) in tokensFor(line)" :key="`${line.id}:${tokenIndex}`" :class="`syntax-${token.kind}`">{{ token.text }}</span></b>
                </span>
              </template>
            </code>
            <p v-if="selectedFile?.patchTruncated || patchRenderTruncated">Patch 展示已按服务端或浏览器渲染预算截断。</p>
          </div>
          <aside v-if="commentDraft || comments.length" class="review-comments" aria-label="行级 Review 评论">
            <form v-if="commentDraft" class="review-comment-form" @submit.prevent="submitComment">
              <strong>添加行级评论 · {{ commentDraft.side }} {{ commentDraft.line.lineNumber(commentDraft.side) }}</strong>
              <textarea v-model="commentText" data-testid="review-comment-input" rows="3" maxlength="4000" placeholder="写下可执行的反馈" />
              <p v-if="commentError" class="comment-error">{{ commentError }}</p>
              <div><button type="button" @click="closeComment">取消</button><button type="submit" :disabled="commentSubmitting">{{ commentSubmitting ? '提交中…' : '提交评论' }}</button></div>
            </form>
            <article v-for="comment in comments" :key="comment.id" class="review-comment" :class="{ outdated: comment.anchorState === 'OUTDATED' || comment.deleted }">
              <header><strong>{{ comment.side }} · L{{ comment.lineNumber }}</strong><small>{{ comment.anchorState === 'OUTDATED' ? 'OUTDATED' : 'ACTIVE' }}</small></header>
              <SafeMarkdown v-if="!comment.deleted" :content="comment.content" />
              <p v-else>评论已删除</p>
            </article>
          </aside>
        </article>
      </div>
    </template>
  </section>
</template>

<style scoped>
.diff-explorer { padding: 0; overflow: hidden; }.diff-heading { display: flex; min-height: 58px; align-items: center; justify-content: space-between; gap: var(--cs-space-12); padding: var(--cs-space-12) var(--cs-space-16); border-bottom: 1px solid var(--cs-border); }.diff-heading p, .diff-heading h3 { margin: 0; }.diff-heading p { color: var(--cs-text-brand); font-size: var(--cs-text-xs); font-weight: var(--cs-weight-semibold); letter-spacing: .08em; text-transform: uppercase; }.diff-heading h3 { margin-top: var(--cs-space-2); font-size: var(--cs-text-base); }.diff-heading__status { display: flex; align-items: center; gap: var(--cs-space-4); color: var(--cs-text-muted); font-size: var(--cs-text-xs); }.diff-heading__status svg { color: var(--cs-text-brand); }.diff-explorer > :deep(.state-panel) { min-height: 112px; border: 0; border-radius: 0; }.diff-stats { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); border-bottom: 1px solid var(--cs-border); background: var(--cs-surface-subtle); }.diff-stats > div { display: grid; min-width: 0; grid-template-columns: auto 1fr; align-items: center; gap: var(--cs-space-2) var(--cs-space-8); padding: var(--cs-space-8) var(--cs-space-12); border-right: 1px solid var(--cs-border); }.diff-stats > div:last-child { border-right: 0; }.diff-stats svg { grid-row: 1 / 3; color: var(--cs-text-muted); }.diff-stats span { color: var(--cs-text-muted); font-size: var(--cs-text-xs); text-transform: uppercase; }.diff-stats strong { font: var(--cs-text-sm) var(--cs-font-mono); }.diff-stats__addition strong { color: var(--cs-success); }.diff-stats__deletion strong { color: var(--cs-danger); }.diff-gap { display: flex; align-items: center; justify-content: space-between; gap: var(--cs-space-12); padding: var(--cs-space-8) var(--cs-space-12); border-bottom: 1px solid var(--cs-warning-border); background: var(--cs-warning-soft); color: var(--cs-warning); font-size: var(--cs-text-xs); }.diff-gap button, .patch-message button { display: inline-flex; align-items: center; gap: var(--cs-space-4); padding: var(--cs-space-4) var(--cs-space-8); border-radius: 6px; background: var(--cs-surface-glass); color: inherit; font-size: var(--cs-text-xs); font-weight: var(--cs-weight-semibold); cursor: pointer; }.diff-workspace { display: grid; min-height: 350px; grid-template-columns: minmax(220px, var(--tree-width)) 5px minmax(0, 1fr); }.diff-tree { min-width: 0; border-right: 1px solid var(--cs-border); background: var(--cs-surface-subtle); }.diff-resize-handle { width: 5px; padding: 0; border: 0; background: var(--cs-border); cursor: col-resize; }.diff-resize-handle:hover { background: var(--cs-brand-300); }.diff-search { display: flex; align-items: center; gap: var(--cs-space-8); margin: var(--cs-space-8); padding: 0 var(--cs-space-8); border: 1px solid var(--cs-border); border-radius: 8px; background: var(--cs-surface); color: var(--cs-text-muted); }.diff-search input { width: 100%; min-width: 0; height: 31px; background: transparent; font-size: var(--cs-text-base); outline: 0; }.diff-tree__list { max-height: 430px; overflow: auto; padding: 0 var(--cs-space-8) var(--cs-space-8); }.diff-tree__folder, .diff-tree__file { --indent: calc(var(--depth) * 12px); padding-left: calc(var(--cs-space-8) + var(--indent)); }.diff-tree__folder { display: flex; align-items: center; gap: var(--cs-space-4); min-height: 25px; color: var(--cs-text-muted); font-size: var(--cs-text-xs); font-weight: var(--cs-weight-semibold); }.diff-tree__file { display: grid; width: 100%; min-height: 29px; grid-template-columns: auto minmax(0, 1fr) auto; align-items: center; gap: var(--cs-space-4); padding-right: var(--cs-space-8); border: 1px solid transparent; border-radius: 7px; color: var(--cs-text-secondary); text-align: left; cursor: pointer; }.diff-tree__file > span { overflow: hidden; font: var(--cs-text-xs) var(--cs-font-mono); text-overflow: ellipsis; white-space: nowrap; }.diff-tree__file.selected { border-color: var(--cs-border-accent); background: var(--cs-surface-accent); color: var(--cs-text-brand-strong); }.diff-tree__file :deep(.status-badge) { min-width: 19px; justify-content: center; padding-inline: var(--cs-space-4); }.diff-tree__limit { margin: 0; padding: var(--cs-space-8) var(--cs-space-12); border-top: 1px solid var(--cs-border); color: var(--cs-text-muted); font-size: var(--cs-text-xs); line-height: var(--cs-leading-normal); }.patch-view { min-width: 0; background: var(--cs-surface-subtle); }.patch-view > header { display: flex; min-height: 49px; align-items: center; justify-content: space-between; gap: var(--cs-space-12); padding: var(--cs-space-8) var(--cs-space-12); border-bottom: 1px solid var(--cs-border); background: var(--cs-surface); }.patch-view header strong, .patch-view header small { display: block; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }.patch-view header strong { font: var(--cs-text-xs) var(--cs-font-mono); }.patch-view header small { margin-top: var(--cs-space-4); color: var(--cs-text-muted); font: var(--cs-text-xs) var(--cs-font-mono); }.patch-view header > span { display: flex; gap: var(--cs-space-8); font: var(--cs-text-xs) var(--cs-font-mono); }.patch-view header b { color: var(--cs-success); }.patch-view header i { color: var(--cs-danger); font-style: normal; }.patch-view > :deep(.state-panel) { min-height: 250px; border: 0; }.patch-message { display: grid; min-height: 250px; place-content: center; justify-items: center; gap: var(--cs-space-8); padding: var(--cs-space-24); color: var(--cs-text-muted); text-align: center; }.patch-message svg { color: var(--cs-text-brand); }.patch-message strong { color: var(--cs-text-secondary); font-size: var(--cs-text-sm); }.patch-message span { max-width: 320px; font-size: var(--cs-text-xs); line-height: var(--cs-leading-normal); }.patch-message button { margin-top: var(--cs-space-4); background: var(--cs-surface-accent-strong); color: var(--cs-text-brand); }.patch-code { max-height: 430px; overflow: auto; outline: none; }.patch-code:focus-visible { box-shadow: inset 0 0 0 2px var(--cs-focus); }.patch-code code { display: table; min-width: 100%; padding: var(--cs-space-8) 0; font: var(--cs-text-xs)/var(--cs-leading-normal) var(--cs-font-mono); }.patch-line { display: table-row; }.patch-line > i { display: table-cell; width: 1%; padding: 0 var(--cs-space-8); color: var(--cs-text-muted); font-style: normal; text-align: right; user-select: none; }.patch-line > b { display: table-cell; padding-right: var(--cs-space-12); font-weight: var(--cs-weight-regular); white-space: pre; }.patch-line--addition { background: var(--cs-diff-addition-bg); color: var(--cs-diff-addition-text); }.patch-line--deletion { background: var(--cs-diff-deletion-bg); color: var(--cs-diff-deletion-text); }.patch-line--hunk { background: var(--cs-diff-hunk-bg); color: var(--cs-diff-hunk-text); }.patch-line--meta { color: var(--cs-text-muted); }.patch-code > p { margin: 0; padding: var(--cs-space-8) var(--cs-space-12); background: var(--cs-warning-soft); color: var(--cs-warning); font-size: var(--cs-text-xs); }.sr-only { position: absolute; width: 1px; height: 1px; overflow: hidden; clip: rect(0, 0, 0, 0); white-space: nowrap; }
.review-location-note{margin:0;padding:var(--cs-space-8) var(--cs-space-12);border-bottom: 1px solid var(--cs-border-accent);background: var(--cs-surface-accent);color: var(--cs-text-brand);font-size:var(--cs-text-xs);line-height:var(--cs-leading-normal)}
.patch-actions{display:flex;align-items:center;gap:var(--cs-space-8);flex-wrap:wrap}.patch-action{display:inline-flex;align-items:center;gap:var(--cs-space-4);padding:var(--cs-space-4) var(--cs-space-8);border: 1px solid var(--cs-border);border-radius:5px;background: var(--cs-surface-subtle);color: var(--cs-text-secondary);font-size:var(--cs-text-xs);cursor:pointer}.patch-action:hover{border-color: var(--cs-border-accent-strong);color: var(--cs-text-brand)}.patch-language{padding:var(--cs-space-4) var(--cs-space-4);border-radius:4px;background: var(--cs-surface-subtle);color: var(--cs-text-muted);font-size:var(--cs-text-xs)}.patch-line{position:relative;cursor:pointer}.patch-line.focused{outline: 2px solid var(--cs-focus);outline-offset:-2px}.patch-line--split>i{min-width:32px}.line-comment-button{display:table-cell;width:18px;padding:0;border: 0;background: transparent;color: var(--cs-text-brand);font-size:var(--cs-text-sm);opacity:0;cursor:pointer}.patch-line:hover .line-comment-button,.line-comment-button:focus{opacity:1}.patch-context-toggle{display:flex;align-items:center;gap:var(--cs-space-4);padding:var(--cs-space-4) var(--cs-space-12);background: var(--cs-surface-subtle);color: var(--cs-text-brand);font-size:var(--cs-text-xs);cursor:pointer}.review-comments{display:grid;gap:var(--cs-space-8);padding:var(--cs-space-12);border-top: 1px solid var(--cs-border);background: var(--cs-surface)}.review-comment-form,.review-comment{padding:var(--cs-space-8);border: 1px solid var(--cs-border);border-radius:7px;background: var(--cs-surface-subtle)}.review-comment-form{display:grid;gap:var(--cs-space-8)}.review-comment-form strong,.review-comment header strong{font-size:var(--cs-text-xs)}.review-comment-form textarea{width:100%;padding:var(--cs-space-8);border: 1px solid var(--cs-border-strong);border-radius:5px;background: var(--cs-surface);font:var(--cs-text-base) var(--cs-font-sans);resize:vertical}.review-comment-form>div{display:flex;justify-content:flex-end;gap:var(--cs-space-4)}.review-comment-form button{padding:var(--cs-space-4) var(--cs-space-8);border: 1px solid var(--cs-border);border-radius:5px;background: var(--cs-surface);font-size:var(--cs-text-xs);cursor:pointer}.review-comment-form button[type=submit]{background: var(--cs-brand-600);color: var(--cs-text-on-dark)}.comment-error{margin:0;color: var(--cs-danger);font-size:var(--cs-text-xs)}.review-comment header{display:flex;justify-content:space-between;gap:var(--cs-space-8)}.review-comment header small{color: var(--cs-success);font-size:var(--cs-text-xs)}.review-comment.outdated{opacity:.7}.review-comment.outdated header small{color: var(--cs-warning)}.review-comment :deep(.safe-markdown){margin-top:var(--cs-space-4);font-size:var(--cs-text-xs)}
.syntax-keyword{color: var(--cs-code-keyword)}.syntax-string{color: var(--cs-code-string)}.syntax-comment{color: var(--cs-code-comment);font-style:italic}
.syntax-function{color: var(--cs-code-function)}.syntax-number{color: var(--cs-code-number)}.syntax-type{color: var(--cs-code-type)}
@media (max-width: 720px) { .diff-heading { align-items: flex-start; flex-direction: column; }.diff-stats { grid-template-columns: repeat(2, 1fr); }.diff-stats > div:nth-child(2) { border-right: 0; }.diff-stats > div:nth-child(-n+2) { border-bottom: 1px solid var(--cs-border); }.diff-workspace { grid-template-columns: 1fr; }.diff-tree { border-right: 0; border-bottom: 1px solid var(--cs-border); }.diff-resize-handle { display: none; }.diff-tree__list { max-height: 250px; }.patch-code { max-height: 460px; } }

/* Semantic overrides keep code and Diff surfaces legible in both themes. */
.patch-view { background: var(--cs-surface-subtle); }
.patch-view header b { color: var(--cs-diff-addition-text); }
.patch-view header i { color: var(--cs-diff-deletion-text); }
.patch-line > i { color: var(--cs-text-muted); }
.patch-line--addition { background: var(--cs-diff-addition-bg); color: var(--cs-diff-addition-text); }
.patch-line--deletion { background: var(--cs-diff-deletion-bg); color: var(--cs-diff-deletion-text); }
.patch-line--hunk { background: var(--cs-diff-hunk-bg); color: var(--cs-diff-hunk-text); }
.patch-code > p { color: var(--cs-warning); }
.syntax-keyword { color: var(--cs-code-keyword); }
.syntax-string { color: var(--cs-code-string); }
.syntax-comment { color: var(--cs-code-comment); }
.syntax-function { color: var(--cs-code-function); }
.syntax-number { color: var(--cs-code-number); }
.syntax-type { color: var(--cs-code-type); }
</style>
