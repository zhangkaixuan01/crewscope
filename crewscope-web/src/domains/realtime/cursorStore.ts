export type DurableStream = 'TEAM' | 'CONVERSATION'

export interface CursorScope {
  organizationId: string
  teamId: string
  conversationId?: string | null
}

export interface AgUiResumeCoordinate {
  invocationId: string
  idempotencyKey: string
  eventOffset: number
}

export interface ThreeStreamCursorStore {
  getDurableCursor(stream: DurableStream, scope: CursorScope): string | null
  saveDurableCursor(stream: DurableStream, scope: CursorScope, cursor: string): void
  clearDurableCursor(stream: DurableStream, scope: CursorScope): void
  getAgUiResume(scope: CursorScope): AgUiResumeCoordinate | null
  saveAgUiResume(scope: CursorScope, coordinate: AgUiResumeCoordinate): void
  clearAgUiResume(scope: CursorScope): void
  clearScope(scope: Pick<CursorScope, 'organizationId' | 'teamId'>): void
}

const PREFIX = 'crewscope:stream:v1:'

/**
 * A browser with storage denied (some private modes) must still boot: cursors degrade to a
 * per-tab in-memory shim instead of taking the whole application down at assembly time.
 */
function safeLocalStorage(): Storage {
  try {
    return localStorage
  } catch {
    const entries = new Map<string, string>()
    return {
      get length() { return entries.size },
      clear: () => entries.clear(),
      getItem: key => entries.get(key) ?? null,
      key: index => [...entries.keys()][index] ?? null,
      removeItem: key => { entries.delete(key) },
      setItem: (key, value) => { entries.set(key, value) },
    } as Storage
  }
}

/** Scope-bound storage for two durable streams and the non-cursor AG-UI resume coordinate. */
export function createThreeStreamCursorStore(storage: Storage = safeLocalStorage()): ThreeStreamCursorStore {
  function getDurableCursor(stream: DurableStream, scope: CursorScope): string | null {
    const key = durableKey(stream, scope)
    const stored = read(storage, key)
    if (!stored) return null
    const cursor = stored.cursor
    if (typeof cursor !== 'string' || !cursor) return removeInvalid(storage, key)
    return cursor
  }

  function saveDurableCursor(stream: DurableStream, scope: CursorScope, cursor: string): void {
    if (!cursor.trim()) throw new TypeError('Durable Cursor must not be blank')
    write(storage, durableKey(stream, scope), JSON.stringify({ cursor }))
  }

  function clearDurableCursor(stream: DurableStream, scope: CursorScope): void {
    drop(storage, durableKey(stream, scope))
  }

  function getAgUiResume(scope: CursorScope): AgUiResumeCoordinate | null {
    const key = agUiKey(scope)
    const stored = read(storage, key)
    if (!stored) return null
    if (typeof stored.invocationId !== 'string'
      || typeof stored.idempotencyKey !== 'string'
      || typeof stored.eventOffset !== 'number'
      || !Number.isSafeInteger(stored.eventOffset)
      || stored.eventOffset < 0) {
      return removeInvalid(storage, key)
    }
    return {
      invocationId: stored.invocationId,
      idempotencyKey: stored.idempotencyKey,
      eventOffset: stored.eventOffset,
    }
  }

  function saveAgUiResume(scope: CursorScope, coordinate: AgUiResumeCoordinate): void {
    if (!coordinate.invocationId || !coordinate.idempotencyKey
      || !Number.isSafeInteger(coordinate.eventOffset) || coordinate.eventOffset < 0) {
      throw new TypeError('AG-UI resume coordinate is invalid')
    }
    // No SSE id or durable Cursor is admitted to the AG-UI namespace.
    write(storage, agUiKey(scope), JSON.stringify({
      invocationId: coordinate.invocationId,
      idempotencyKey: coordinate.idempotencyKey,
      eventOffset: coordinate.eventOffset,
    }))
  }

  function clearAgUiResume(scope: CursorScope): void {
    drop(storage, agUiKey(scope))
  }

  function clearScope(scope: Pick<CursorScope, 'organizationId' | 'teamId'>): void {
    const scopePrefix = `${PREFIX}${encode(scope.organizationId)}:${encode(scope.teamId)}:`
    const keys: string[] = []
    for (let index = 0; index < storage.length; index += 1) {
      const key = storage.key(index)
      if (key?.startsWith(scopePrefix)) keys.push(key)
    }
    keys.forEach(key => drop(storage, key))
  }

  return {
    getDurableCursor,
    saveDurableCursor,
    clearDurableCursor,
    getAgUiResume,
    saveAgUiResume,
    clearAgUiResume,
    clearScope,
  }
}

function durableKey(stream: DurableStream, scope: CursorScope): string {
  if (stream === 'CONVERSATION' && !scope.conversationId) {
    throw new TypeError('Conversation Cursor requires conversationId')
  }
  const suffix = stream === 'CONVERSATION' ? `:${encode(scope.conversationId!)}` : ''
  return `${scopePrefix(scope)}durable:${stream}${suffix}`
}

function agUiKey(scope: CursorScope): string {
  if (!scope.conversationId) throw new TypeError('AG-UI resume requires conversationId')
  return `${scopePrefix(scope)}resume:AG_UI:${encode(scope.conversationId)}`
}

function scopePrefix(scope: Pick<CursorScope, 'organizationId' | 'teamId'>): string {
  if (!scope.organizationId || !scope.teamId) throw new TypeError('Cursor Scope is incomplete')
  return `${PREFIX}${encode(scope.organizationId)}:${encode(scope.teamId)}:`
}

function encode(value: string): string {
  return encodeURIComponent(value)
}

function read(storage: Storage, key: string): Record<string, unknown> | null {
  const value = storage.getItem(key)
  if (!value) return null
  try {
    const parsed = JSON.parse(value) as unknown
    if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) return parsed as Record<string, unknown>
  } catch {
    // Corrupt browser state is removed below and never enters stream recovery.
  }
  return removeInvalid(storage, key)
}

function removeInvalid(storage: Storage, key: string): null {
  drop(storage, key)
  return null
}

/**
 * Writes and removals degrade instead of throwing: some private modes expose a storage that reads
 * fine but rejects every write, and a throw here would break the SSE handler mid-event. Losing
 * the durable copy only costs a re-read on the next visit — the same degradation as no storage.
 */
function write(storage: Storage, key: string, value: string): void {
  try {
    storage.setItem(key, value)
  } catch {
    // Persistence for this entry is lost; the live streams keep running.
  }
}

function drop(storage: Storage, key: string): void {
  try {
    storage.removeItem(key)
  } catch {
    // Same degradation as a rejected write.
  }
}
