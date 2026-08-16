export type DiffChangeKind = 'ADDED' | 'MODIFIED' | 'DELETED' | 'RENAMED' | 'COPIED' | 'TYPE_CHANGED'
export type DiffEventKind = 'RESET' | 'DELTA'
export type DiffApplyOutcome = 'APPLIED' | 'RESET' | 'DUPLICATE' | 'GAP' | 'STALE'

export interface DiffFileProjection {
  path: string
  oldPath: string | null
  kind: DiffChangeKind
  additions: number
  deletions: number
  binary: boolean
  patchTruncated: boolean
  patchSha256: string
  patchPreview: string
}

export interface DiffStreamEvent {
  id: string
  kind: DiffEventKind
  sequence: number
  generation: number
  cursor: string
  manifestHash: string
  files: DiffFileProjection[]
  removals: string[]
}

export interface DiffStreamFixture {
  schemaVersion: 'crewscope.diff-stream.fixture/v1'
  workspaceId: string
  streamEpoch: string
  events: DiffStreamEvent[]
  deliveryOrder: string[]
  expectedOutcomes: DiffApplyOutcome[]
  expectedProjection: {
    generation: number
    cursor: string
    manifestHash: string
    paths: string[]
  }
}

export interface DiffProjectionSnapshot {
  workspaceId: string
  streamEpoch: string
  sequence: number
  generation: number
  cursor: string | null
  manifestHash: string | null
  files: DiffFileProjection[]
  waitingForReset: boolean
}

/**
 * Applies server-authoritative Diff events without decoding opaque cursors.
 * A future delta marks a gap and leaves the last complete projection unchanged.
 */
export class DiffProjection {
  private readonly files = new Map<string, DiffFileProjection>()
  private sequence = 0
  private generation = 0
  private cursor: string | null = null
  private manifestHash: string | null = null
  private waitingForReset = false

  constructor(
    private readonly workspaceId: string,
    private readonly streamEpoch: string,
  ) {}

  apply(event: DiffStreamEvent, workspaceId = this.workspaceId, streamEpoch = this.streamEpoch): DiffApplyOutcome {
    if (workspaceId !== this.workspaceId || streamEpoch !== this.streamEpoch) {
      throw new Error('Diff event scope does not match the active projection')
    }
    if (event.sequence <= this.sequence) return 'DUPLICATE'
    if (event.kind === 'DELTA' && event.sequence !== this.sequence + 1) {
      this.waitingForReset = true
      return 'GAP'
    }
    if (event.generation < this.generation) return 'STALE'

    if (event.kind === 'RESET') this.files.clear()
    for (const path of event.removals) this.files.delete(path)
    for (const file of event.files) this.files.set(file.path, structuredClone(file))
    this.sequence = event.sequence
    this.generation = event.generation
    this.cursor = event.cursor
    this.manifestHash = event.manifestHash
    this.waitingForReset = false
    return event.kind === 'RESET' ? 'RESET' : 'APPLIED'
  }

  snapshot(): DiffProjectionSnapshot {
    return {
      workspaceId: this.workspaceId,
      streamEpoch: this.streamEpoch,
      sequence: this.sequence,
      generation: this.generation,
      cursor: this.cursor,
      manifestHash: this.manifestHash,
      files: [...this.files.values()]
        // Iterate full Unicode code points; JavaScript's default comparison uses UTF-16 units.
        .sort((left, right) => compareUnicodeCodePoints(left.path, right.path))
        .map(file => structuredClone(file)),
      waitingForReset: this.waitingForReset,
    }
  }
}

export function parseDiffStreamFixture(input: unknown): DiffStreamFixture {
  if (!isRecord(input) || input.schemaVersion !== 'crewscope.diff-stream.fixture/v1') {
    throw new Error('Unsupported Diff fixture schema')
  }
  const fixture = input as unknown as DiffStreamFixture
  if (!isUuid(fixture.workspaceId) || !isUuid(fixture.streamEpoch)) throw new Error('Invalid Diff fixture scope')
  if (!Array.isArray(fixture.events) || !Array.isArray(fixture.deliveryOrder)) throw new Error('Invalid Diff fixture events')

  const eventIds = new Set<string>()
  for (const event of fixture.events) {
    if (!isRecord(event) || typeof event.id !== 'string' || eventIds.has(event.id)) {
      throw new Error('Invalid or duplicate Diff fixture event')
    }
    eventIds.add(event.id)
    if (!Number.isSafeInteger(event.sequence) || event.sequence < 1) throw new Error('Invalid Diff event sequence')
    if (!Number.isSafeInteger(event.generation) || event.generation < 0) throw new Error('Invalid Diff generation')
    if (typeof event.cursor !== 'string' || !event.cursor || !isSha256(event.manifestHash)) {
      throw new Error('Invalid Diff event identity')
    }
    if (event.kind !== 'RESET' && event.kind !== 'DELTA') throw new Error('Invalid Diff event kind')
    if (!Array.isArray(event.files) || !Array.isArray(event.removals)) throw new Error('Invalid Diff event payload')
    const paths = new Set<string>()
    for (const file of event.files) {
      if (!isRecord(file) || !isSafePath(file.path) || paths.has(file.path)) {
        throw new Error('Invalid or duplicate Diff path')
      }
      paths.add(file.path)
      if (file.oldPath !== null && !isSafePath(file.oldPath)) throw new Error('Invalid previous Diff path')
      if (!isDiffChangeKind(file.kind)) throw new Error('Invalid Diff change kind')
      if (!isSha256(file.patchSha256)) throw new Error('Invalid patch hash')
      if (!isNonNegativeInteger(file.additions) || !isNonNegativeInteger(file.deletions)) {
        throw new Error('Invalid Diff statistics')
      }
      if (typeof file.binary !== 'boolean' || typeof file.patchTruncated !== 'boolean'
        || typeof file.patchPreview !== 'string') throw new Error('Invalid Diff patch fields')
    }
    const removals = new Set<string>()
    for (const path of event.removals) {
      if (!isSafePath(path) || removals.has(path) || paths.has(path)) {
        throw new Error('Invalid, duplicate or conflicting Diff removal')
      }
      removals.add(path)
    }
  }
  if (fixture.deliveryOrder.some(id => !eventIds.has(id))) throw new Error('Unknown Diff delivery event')
  return structuredClone(fixture)
}

export function replayDiffFixture(fixture: DiffStreamFixture): {
  projection: DiffProjectionSnapshot
  outcomes: DiffApplyOutcome[]
} {
  const projection = new DiffProjection(fixture.workspaceId, fixture.streamEpoch)
  const byId = new Map(fixture.events.map(event => [event.id, event]))
  const outcomes = fixture.deliveryOrder.map(id => projection.apply(byId.get(id)!))
  return { projection: projection.snapshot(), outcomes }
}

function isRecord(input: unknown): input is Record<string, unknown> {
  return typeof input === 'object' && input !== null && !Array.isArray(input)
}

function isUuid(value: unknown): value is string {
  return typeof value === 'string' && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(value)
}

function isSha256(value: unknown): value is string {
  return typeof value === 'string' && /^[0-9a-f]{64}$/.test(value)
}

function isNonNegativeInteger(value: unknown): value is number {
  return Number.isSafeInteger(value) && (value as number) >= 0
}

function isDiffChangeKind(value: unknown): value is DiffChangeKind {
  return value === 'ADDED' || value === 'MODIFIED' || value === 'DELETED'
    || value === 'RENAMED' || value === 'COPIED' || value === 'TYPE_CHANGED'
}

function isSafePath(value: unknown): value is string {
  return typeof value === 'string'
    && value.length > 0
    && !value.startsWith('/')
    && !value.includes('\\')
    && !/[\u0000-\u001f\u007f]/u.test(value)
    && value.split('/').every(segment => segment.length > 0 && segment !== '.' && segment !== '..')
}

/** Compares strings by Unicode code point without locale-dependent collation. */
export function compareUnicodeCodePoints(left: string, right: string): number {
  let leftIndex = 0
  let rightIndex = 0
  while (leftIndex < left.length && rightIndex < right.length) {
    const leftPoint = left.codePointAt(leftIndex)!
    const rightPoint = right.codePointAt(rightIndex)!
    if (leftPoint !== rightPoint) return leftPoint - rightPoint
    leftIndex += leftPoint > 0xFFFF ? 2 : 1
    rightIndex += rightPoint > 0xFFFF ? 2 : 1
  }
  return (left.length - leftIndex) - (right.length - rightIndex)
}
