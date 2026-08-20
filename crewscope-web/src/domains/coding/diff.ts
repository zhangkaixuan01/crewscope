import type { TaskEventItem } from '../task/types'
import type { DiffFileSummary, DiffManifestSummary } from './types'

export type DiffProjectionStatus = 'empty' | 'live' | 'snapshot' | 'reconciled' | 'gap'

export interface DiffProjection {
  status: DiffProjectionStatus
  streamEpoch: string | null
  sequence: number
  generation: number
  manifestHash: string | null
  files: DiffFileSummary[]
  additions: number
  deletions: number
  resetCount: number
}

export interface DiffTreeRow {
  key: string
  kind: 'folder' | 'file'
  name: string
  path: string
  depth: number
  file: DiffFileSummary | null
}

/**
 * Replays the safe Task stream for one attempt. RESET is authoritative for an Epoch and DELTA
 * is accepted only when it is the direct successor. Any gap falls back to the latest A04
 * snapshot instead of presenting an invented merge.
 */
export function projectWorkspaceDiff(
  events: readonly TaskEventItem[],
  executionId: string,
  workspaceId: string,
  authority: DiffManifestSummary | null,
  transportGap = false,
): DiffProjection {
  let projection = emptyProjection()
  let gap = transportGap
  for (const item of events) {
    if (item.context.taskExecutionId !== executionId) continue
    if (item.projectionGap) gap = true
    const type = item.event.eventType
    if (type !== 'WORKSPACE_DIFF_RESET' && type !== 'WORKSPACE_DIFF_DELTA') continue
    const event = readDiffEvent(item, workspaceId)
    if (!event) {
      gap = true
      continue
    }
    if (type === 'WORKSPACE_DIFF_RESET') {
      if (event.changeKind !== 'RESET' || event.upserts === null) {
        gap = true
        continue
      }
      projection = summarize({
        status: 'live', streamEpoch: event.streamEpoch, sequence: event.sequence,
        generation: event.generation, manifestHash: event.manifestHash,
        files: sortFiles(event.upserts), additions: 0, deletions: 0,
        resetCount: projection.resetCount + 1,
      })
      continue
    }
    if (event.changeKind !== 'DELTA'
      || !projection.streamEpoch
      || event.streamEpoch !== projection.streamEpoch
      || event.sequence !== projection.sequence + 1
      || event.generation < projection.generation
      || event.upserts === null
      || event.removals === null) {
      // Duplicate or late delivery is harmless; a forward discontinuity requires reconcile.
      if (projection.streamEpoch === event.streamEpoch && event.sequence <= projection.sequence) continue
      gap = true
      continue
    }
    const files = new Map(projection.files.map(file => [file.path, file]))
    for (const path of event.removals) files.delete(path)
    for (const file of event.upserts) files.set(file.path, file)
    projection = summarize({
      ...projection,
      status: 'live',
      sequence: event.sequence,
      generation: event.generation,
      manifestHash: event.manifestHash,
      files: sortFiles([...files.values()]),
    })
  }

  const snapshot = authority ? fromAuthority(authority, gap ? 'reconciled' : 'snapshot') : null
  if (gap) return snapshot ?? { ...projection, status: 'gap' }
  if (!projection.streamEpoch) return snapshot ?? projection
  if (snapshot && (snapshot.generation > projection.generation
    || (snapshot.generation === projection.generation && snapshot.manifestHash !== projection.manifestHash))) {
    return snapshot
  }
  return projection
}

/** Builds a stable semantic tree while keeping folders non-interactive and files selectable. */
export function flattenDiffTree(files: readonly DiffFileSummary[]): DiffTreeRow[] {
  const rows: DiffTreeRow[] = []
  const folders = new Set<string>()
  for (const file of sortFiles(files)) {
    const segments = file.path.split('/')
    for (let index = 0; index < segments.length - 1; index += 1) {
      const path = segments.slice(0, index + 1).join('/')
      if (folders.has(path)) continue
      folders.add(path)
      rows.push({ key: `folder:${path}`, kind: 'folder', name: segments[index]!, path, depth: index, file: null })
    }
    rows.push({
      key: `file:${file.path}`, kind: 'file', name: segments.at(-1) ?? file.path,
      path: file.path, depth: Math.max(0, segments.length - 1), file,
    })
  }
  return rows
}

/** Extracts one Git Patch block using the server-authorized manifest path as the coordinate. */
export function patchForFile(document: string, file: DiffFileSummary): string | null {
  if (file.binary) return null
  const blocks = document.split(/(?=^diff --git )/m)
  const oldPath = file.oldPath ?? file.path
  const header = `diff --git a/${oldPath} b/${file.path}`
  return blocks.find(block => {
    const firstLine = block.split('\n', 1)[0]
    const paths = gitHeaderPaths(block)
    return (paths?.oldPath === `a/${oldPath}` && paths.path === `b/${file.path}`)
      || firstLine === header
  }) ?? null
}

function gitHeaderPaths(block: string): { oldPath: string, path: string } | null {
  const line = block.split('\n', 1)[0]
  if (!line?.startsWith('diff --git ')) return null
  const tokens = gitTokens(line.slice('diff --git '.length))
  return tokens.length === 2 ? { oldPath: tokens[0]!, path: tokens[1]! } : null
}

function gitTokens(value: string): string[] {
  const tokens: string[] = []
  let index = 0
  while (index < value.length && tokens.length < 2) {
    while (value[index] === ' ') index += 1
    if (index >= value.length) break
    if (value[index] !== '"') {
      const end = value.indexOf(' ', index)
      tokens.push(value.slice(index, end < 0 ? value.length : end))
      index = end < 0 ? value.length : end + 1
      continue
    }
    let end = index + 1
    let escaped = false
    while (end < value.length) {
      const character = value[end]!
      if (!escaped && character === '"') break
      if (!escaped && character === '\\') escaped = true
      else escaped = false
      end += 1
    }
    if (end >= value.length) return []
    tokens.push(decodeGitQuoted(value.slice(index + 1, end)))
    index = end + 1
  }
  return tokens
}

function decodeGitQuoted(value: string): string {
  const bytes: number[] = []
  const plain: string[] = []
  const flushPlain = () => {
    if (!plain.length) return
    bytes.push(...new TextEncoder().encode(plain.join('')))
    plain.length = 0
  }
  for (let index = 0; index < value.length; index += 1) {
    const character = value[index]!
    if (character !== '\\') {
      plain.push(character)
      continue
    }
    flushPlain()
    const next = value[++index]
    if (next === undefined) throw new TypeError('Invalid quoted Git path')
    if (/[0-7]/.test(next)) {
      let octal = next
      while (octal.length < 3 && /[0-7]/.test(value[index + 1] ?? '')) octal += value[++index]
      bytes.push(Number.parseInt(octal, 8))
      continue
    }
    const escaped = ({ n: '\n', r: '\r', t: '\t', b: '\b', f: '\f', v: '\v', '\\': '\\', '"': '"' } as Record<string, string>)[next] ?? next
    bytes.push(...new TextEncoder().encode(escaped))
  }
  flushPlain()
  return new TextDecoder('utf-8', { fatal: true }).decode(new Uint8Array(bytes))
}

function readDiffEvent(item: TaskEventItem, workspaceId: string): {
  streamEpoch: string
  sequence: number
  generation: number
  changeKind: string
  manifestHash: string
  upserts: DiffFileSummary[] | null
  removals: string[] | null
} | null {
  const payload = item.event.payload
  if (payload.workspaceId !== workspaceId
    || typeof payload.streamEpoch !== 'string'
    || !positiveInteger(payload.sequence)
    || !positiveInteger(payload.diffGeneration)
    || typeof payload.changeKind !== 'string'
    || typeof payload.manifestHash !== 'string') return null
  return {
    streamEpoch: payload.streamEpoch,
    sequence: payload.sequence,
    generation: payload.diffGeneration,
    changeKind: payload.changeKind,
    manifestHash: payload.manifestHash,
    upserts: readFiles(payload.upserts),
    removals: readRemovals(payload.removals),
  }
}

function readFiles(value: unknown): DiffFileSummary[] | null {
  if (!Array.isArray(value)) return null
  const files: DiffFileSummary[] = []
  for (const [ordinal, candidate] of value.entries()) {
    if (!candidate || typeof candidate !== 'object' || Array.isArray(candidate)) return null
    const item = candidate as Record<string, unknown>
    if (typeof item.path !== 'string'
      || typeof item.changeType !== 'string'
      || !nonNegativeInteger(item.additions)
      || !nonNegativeInteger(item.deletions)
      || typeof item.binary !== 'boolean'
      || typeof item.patchTruncated !== 'boolean'
      || typeof item.patchSha256 !== 'string'
      || !(item.oldPath === null || item.oldPath === undefined || typeof item.oldPath === 'string')) return null
    files.push({
      ordinal, path: item.path, oldPath: typeof item.oldPath === 'string' ? item.oldPath : null,
      changeKind: item.changeType, additions: item.additions, deletions: item.deletions,
      binary: item.binary, patchTruncated: item.patchTruncated, patchHash: item.patchSha256,
    })
  }
  return files
}

function readRemovals(value: unknown): string[] | null {
  return Array.isArray(value) && value.every(item => typeof item === 'string') ? [...value] : null
}

function fromAuthority(value: DiffManifestSummary, status: 'snapshot' | 'reconciled'): DiffProjection {
  return summarize({
    status, streamEpoch: null, sequence: 0, generation: value.generation,
    manifestHash: value.manifestHash, files: sortFiles(value.files), additions: value.additions,
    deletions: value.deletions, resetCount: 0,
  })
}

function summarize(value: DiffProjection): DiffProjection {
  const files = value.files.map((file, ordinal) => ({ ...file, ordinal }))
  return {
    ...value,
    files,
    additions: files.reduce((total, file) => total + file.additions, 0),
    deletions: files.reduce((total, file) => total + file.deletions, 0),
  }
}

function emptyProjection(): DiffProjection {
  return {
    status: 'empty', streamEpoch: null, sequence: 0, generation: 0, manifestHash: null,
    files: [], additions: 0, deletions: 0, resetCount: 0,
  }
}

function sortFiles(files: readonly DiffFileSummary[]): DiffFileSummary[] {
  return [...files].sort((left, right) => left.path.localeCompare(right.path))
}

function positiveInteger(value: unknown): value is number {
  return typeof value === 'number' && Number.isSafeInteger(value) && value > 0
}

function nonNegativeInteger(value: unknown): value is number {
  return typeof value === 'number' && Number.isSafeInteger(value) && value >= 0
}
