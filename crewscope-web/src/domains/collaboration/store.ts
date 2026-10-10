import { inject, reactive, readonly, type App, type InjectionKey } from 'vue'
import type { CollaborationGateway, CollaborationSocket } from './gateway'
import {
  collaborationScopeKey,
  type CollaborationInboundFrame,
  type CollaborationResourceType,
  type CollaborationScope,
  type PresenceSignalEntry,
} from './types'

/**
 * The M11 collaboration signal store (ADR-032): one WebSocket connection shared by
 * every subscribed surface, with the frozen five-phase lifecycle
 * idle/connecting/live/degraded/reconnecting.
 *
 * Contract obligations implemented here (docs/api/M11-协作信号API契约.md):
 * - welcome runtime parameters (typing window) are per-connection, never hardcoded (§1.1);
 * - ping answers pong (§1.1);
 * - reconnect = fresh handshake + replayed subscribes, all-new handles, no replay of
 *   lost deltas (§4) — presence/typing state is cleared on every disconnect;
 * - leave with connections > 0 keeps the principal present (§2.2);
 * - typing self-clears after typingWindowSeconds — the server never re-sends stopped (§2.3);
 * - resource_changed is deduplicated by monotonic max(version) per (resourceType,
 *   resourceId) (§2.4); local REST receipts feed the same table so the sender's own
 *   echo never raises the change banner.
 *
 * Degraded stance: a connection that has never reached live and fails three times in
 * a row stops reconnecting (the channel-off deployment answers every handshake with
 * close 1006 — retrying it forever is noise, §10). A connection that has been live
 * keeps reconnecting with the SSE-face backoff constants. Both exit through a new
 * acquire or an explicit retry().
 */

export type CollaborationPhase = 'idle' | 'connecting' | 'live' | 'degraded' | 'reconnecting'

export interface PresenceEntry {
  principalId: string
  displayName: string
  connections: number
}

export interface TypingEntry {
  principalId: string
  displayName: string
}

export interface ResourceChangeNotice {
  resourceType: CollaborationResourceType
  resourceId: string
  version: number
  /** Monotonic per store — lets watchers fire on repeated same-coordinate notices. */
  seq: number
}

export interface CollaborationState {
  phase: CollaborationPhase
  connectionId: string | null
  principalId: string | null
  typingWindowSeconds: number
  reconnectAttempt: number
  /** Nested arrays are declared readonly — the store hands out `readonly(state)`. */
  presenceByScope: Record<string, readonly PresenceEntry[]>
  typingByScope: Record<string, readonly TypingEntry[]>
  changeByScope: Record<string, ResourceChangeNotice>
}

export interface CollaborationStore {
  state: Readonly<CollaborationState>
  /** Page lifecycle: mount acquires, unmount releases; the first acquire connects. */
  acquire(scope: CollaborationScope): void
  release(scope: CollaborationScope): void
  /** Typing signal for the sender's own subscription on the scope (client-side anchoring). */
  signalTyping(scope: CollaborationScope, started: boolean): void
  /** Feed a local REST write receipt so the sender's own change echo is deduplicated. */
  acknowledgeVersion(resourceType: CollaborationResourceType, resourceId: string, version: number): void
  /** Manual exit from degraded (the status pill's retry action). */
  retry(): void
  /** Session boundary: closes the socket and clears every subscription and signal. */
  stop(): void
}

export const COLLABORATION_STORE: InjectionKey<CollaborationStore> = Symbol('crewscope-collaboration-store')

const RECONNECT_BASE_DELAY = 500
const RECONNECT_MAX_DELAY = 8_000
const RECONNECT_BACKOFF_CAP = 4
const DEGRADED_FAILURE_THRESHOLD = 3
const DEFAULT_TYPING_WINDOW_SECONDS = 5

interface SubscriptionRecord {
  scope: CollaborationScope
  key: string
  refCount: number
  handle: string | null
}

export function createCollaborationStore(gateway: CollaborationGateway): CollaborationStore {
  const state = reactive<CollaborationState>(initialState())
  const subscriptions = new Map<string, SubscriptionRecord>()
  const handleToKey = new Map<string, string>()
  const seenVersions = new Map<string, number>()
  const typingTimers = new Map<string, ReturnType<typeof setTimeout>>()
  const typingAnchors = new Map<string, number>()
  let socket: CollaborationSocket | null = null
  let reconnectTimer: ReturnType<typeof setTimeout> | null = null
  let generation = 0
  let everLive = false
  let consecutiveFailures = 0
  let noticeSeq = 0

  function acquire(scope: CollaborationScope): void {
    const key = collaborationScopeKey(scope)
    const record = subscriptions.get(key) ?? createRecord(scope, key)
    record.refCount += 1
    // A new page opening on a degraded channel is an explicit retry signal.
    if (state.phase === 'degraded') retry()
    else if (state.phase === 'idle') connect()
    else if (state.phase === 'live' && record.refCount === 1) sendSubscribe(record)
  }

  function release(scope: CollaborationScope): void {
    const key = collaborationScopeKey(scope)
    const record = subscriptions.get(key)
    if (!record) return
    record.refCount -= 1
    if (record.refCount > 0) return
    subscriptions.delete(key)
    // Forget the handle routing immediately: a fanout frame that races the unsubscribe
    // ack must not resurrect presence state under a scope nobody watches anymore.
    if (record.handle) {
      handleToKey.delete(record.handle)
      if (state.phase === 'live' && socket) {
        socket.send(JSON.stringify({ type: 'unsubscribe', subscriptionId: record.handle }))
      }
    }
    clearScopeSignals(key)
  }

  function signalTyping(scope: CollaborationScope, started: boolean): void {
    const key = collaborationScopeKey(scope)
    const record = subscriptions.get(key)
    if (!record?.handle || state.phase !== 'live' || !socket) return
    if (!started) {
      // No started was ever sent (focus/blur without typing) — a bare stopped would
      // only spend signal budget.
      if (!typingAnchors.has(key)) return
      typingAnchors.delete(key)
      socket.send(JSON.stringify({ type: 'typing', subscriptionId: record.handle, state: 'stopped' }))
      return
    }
    // Mirror of the server's anchored (non-sliding) window: duplicates inside the
    // window are dropped locally without moving the anchor.
    const now = Date.now()
    const anchor = typingAnchors.get(key)
    if (anchor !== undefined && now - anchor < state.typingWindowSeconds * 1_000) return
    typingAnchors.set(key, now)
    socket.send(JSON.stringify({ type: 'typing', subscriptionId: record.handle, state: 'started' }))
  }

  function acknowledgeVersion(resourceType: CollaborationResourceType, resourceId: string, version: number): void {
    const key = `${resourceType}:${resourceId}`
    seenVersions.set(key, Math.max(seenVersions.get(key) ?? 0, version))
  }

  function retry(): void {
    if (state.phase !== 'degraded') return
    consecutiveFailures = 0
    state.reconnectAttempt = 0
    connect()
  }

  function stop(): void {
    generation += 1
    closeSocket()
    subscriptions.clear()
    for (const timer of typingTimers.values()) clearTimeout(timer)
    typingTimers.clear()
    typingAnchors.clear()
    everLive = false
    consecutiveFailures = 0
    Object.assign(state, initialState())
  }

  // ------------------------------------------------------------------ connection

  function connect(): void {
    generation += 1
    closeSocket()
    state.phase = state.reconnectAttempt === 0 ? 'connecting' : 'reconnecting'
    const connectionGeneration = generation
    socket = gateway.connect({
      onMessage: text => {
        if (connectionGeneration === generation) handleFrame(text)
      },
      onClose: (code, reason) => {
        if (connectionGeneration === generation) handleClose(code, reason)
      },
    })
  }

  function handleClose(code: number, reason: string): void {
    socket = null
    // 1008 (no authenticated principal) and 4000 (principal connection limit) are
    // explicit server refusals — reconnecting would loop into the same verdict.
    const refused = code === 1008 || code === 4000
    if (refused || !everLive) {
      consecutiveFailures += 1
      if (refused || consecutiveFailures >= DEGRADED_FAILURE_THRESHOLD) {
        clearConnectionSignals()
        state.phase = 'degraded'
        return
      }
    }
    clearConnectionSignals()
    state.phase = 'reconnecting'
    scheduleReconnect()
  }

  function scheduleReconnect(): void {
    state.reconnectAttempt += 1
    const delay = Math.min(
      RECONNECT_MAX_DELAY,
      RECONNECT_BASE_DELAY * 2 ** Math.min(state.reconnectAttempt - 1, RECONNECT_BACKOFF_CAP),
    )
    const reconnectGeneration = generation
    reconnectTimer = setTimeout(() => {
      if (reconnectGeneration === generation) connect()
    }, delay)
  }

  function closeSocket(): void {
    if (reconnectTimer) clearTimeout(reconnectTimer)
    reconnectTimer = null
    socket?.close()
    socket = null
  }

  // ------------------------------------------------------------------ frames

  function handleFrame(text: string): void {
    const frame = parseInboundFrame(text)
    if (!frame) {
      console.warn('[collaboration] dropped a frame that fails the signal contract')
      return
    }
    switch (frame.type) {
      case 'welcome':
        state.connectionId = frame.connectionId
        state.principalId = frame.principalId
        state.typingWindowSeconds = frame.typingWindowSeconds
        state.phase = 'live'
        state.reconnectAttempt = 0
        consecutiveFailures = 0
        everLive = true
        for (const record of subscriptions.values()) sendSubscribe(record)
        return
      case 'ping':
        socket?.send('{"type":"pong"}')
        return
      case 'subscribed': {
        const key = collaborationScopeKey(frame.scope)
        const record = subscriptions.get(key)
        if (!record) return
        record.handle = frame.subscriptionId
        handleToKey.set(frame.subscriptionId, key)
        return
      }
      case 'unsubscribed':
        forgetHandle(frame.subscriptionId)
        return
      case 'error':
        console.warn(`[collaboration] server error frame: ${frame.code}`)
        return
      case 'presence_snapshot': {
        const key = route(frame.subscriptionId)
        if (key) state.presenceByScope[key] = frame.present.map(entry => ({ ...entry }))
        return
      }
      case 'presence_delta': {
        const key = route(frame.subscriptionId)
        if (key) mergePresenceDelta(key, frame)
        return
      }
      case 'typing': {
        const key = route(frame.subscriptionId)
        if (key) mergeTyping(key, frame.principalId, frame.displayName, frame.state === 'started')
        return
      }
      case 'resource_changed': {
        const key = route(frame.subscriptionId)
        if (key) mergeResourceChange(key, frame.scope, frame.version)
        return
      }
    }
  }

  function mergePresenceDelta(
    key: string,
    frame: { principalId: string; displayName: string; connections: number; action: 'enter' | 'leave' },
  ): void {
    const present = [...(state.presenceByScope[key] ?? [])]
    const index = present.findIndex(entry => entry.principalId === frame.principalId)
    if (frame.action === 'enter' || frame.connections > 0) {
      const next = {
        principalId: frame.principalId,
        displayName: frame.displayName,
        connections: frame.connections,
      }
      if (index >= 0) present[index] = next
      else present.push(next)
    } else if (index >= 0) {
      present.splice(index, 1)
    }
    state.presenceByScope[key] = present
  }

  function mergeTyping(key: string, principalId: string, displayName: string, started: boolean): void {
    const timerKey = `${key}|${principalId}`
    const existing = typingTimers.get(timerKey)
    if (existing) {
      clearTimeout(existing)
      typingTimers.delete(timerKey)
    }
    const typers = state.typingByScope[key]?.filter(entry => entry.principalId !== principalId) ?? []
    if (started) {
      typers.push({ principalId, displayName })
      state.typingByScope[key] = typers
      // The self-clear is the client's duty — the server never re-sends stopped (§2.3).
      typingTimers.set(timerKey, setTimeout(() => {
        typingTimers.delete(timerKey)
        state.typingByScope[key] = (state.typingByScope[key] ?? [])
          .filter(entry => entry.principalId !== principalId)
      }, state.typingWindowSeconds * 1_000))
    } else {
      state.typingByScope[key] = typers
    }
  }

  function mergeResourceChange(
    subscriptionKey: string,
    scope: CollaborationScope,
    version: number,
  ): void {
    if (!scope.resourceType || !scope.resourceId) return
    const resourceKey = `${scope.resourceType}:${scope.resourceId}`
    const seen = seenVersions.get(resourceKey) ?? 0
    if (version <= seen) return
    seenVersions.set(resourceKey, version)
    noticeSeq += 1
    state.changeByScope[subscriptionKey] = {
      resourceType: scope.resourceType,
      resourceId: scope.resourceId,
      version,
      seq: noticeSeq,
    }
  }

  function route(subscriptionId: string): string | null {
    return handleToKey.get(subscriptionId) ?? null
  }

  function forgetHandle(subscriptionId: string): void {
    const key = handleToKey.get(subscriptionId)
    handleToKey.delete(subscriptionId)
    if (!key) return
    const record = subscriptions.get(key)
    if (record?.handle === subscriptionId) record.handle = null
  }

  function sendSubscribe(record: SubscriptionRecord): void {
    if (!socket) return
    socket.send(JSON.stringify({ type: 'subscribe', scope: record.scope }))
  }

  // ------------------------------------------------------------------ teardown helpers

  /** Per-scope cleanup when the last refcount holder leaves. */
  function clearScopeSignals(key: string): void {
    delete state.presenceByScope[key]
    delete state.typingByScope[key]
    delete state.changeByScope[key]
    typingAnchors.delete(key)
    for (const timerKey of [...typingTimers.keys()]) {
      if (timerKey.startsWith(`${key}|`)) {
        clearTimeout(typingTimers.get(timerKey))
        typingTimers.delete(timerKey)
      }
    }
  }

  /** Full signal reset on disconnect — handles, presence and typing all die with the connection. */
  function clearConnectionSignals(): void {
    handleToKey.clear()
    for (const record of subscriptions.values()) record.handle = null
    state.presenceByScope = {}
    state.typingByScope = {}
    for (const timer of typingTimers.values()) clearTimeout(timer)
    typingTimers.clear()
    typingAnchors.clear()
    state.connectionId = null
    state.principalId = null
  }

  function createRecord(scope: CollaborationScope, key: string): SubscriptionRecord {
    const record: SubscriptionRecord = { scope, key, refCount: 0, handle: null }
    subscriptions.set(key, record)
    return record
  }

  return {
    state: readonly(state),
    acquire,
    release,
    signalTyping,
    acknowledgeVersion,
    retry,
    stop,
  }
}

export function installCollaborationStore(app: App, gateway: CollaborationGateway): CollaborationStore {
  const store = createCollaborationStore(gateway)
  app.provide(COLLABORATION_STORE, store)
  return store
}

export function useCollaborationStore(): CollaborationStore {
  const store = inject(COLLABORATION_STORE)
  if (!store) throw new Error('CrewScope Collaboration Store is not installed')
  return store
}

function initialState(): CollaborationState {
  return {
    phase: 'idle',
    connectionId: null,
    principalId: null,
    typingWindowSeconds: DEFAULT_TYPING_WINDOW_SECONDS,
    reconnectAttempt: 0,
    presenceByScope: {},
    typingByScope: {},
    changeByScope: {},
  }
}

// ------------------------------------------------------------------ frame parsing

const RESOURCE_TYPES: ReadonlySet<string> = new Set(['work_project', 'work_item', 'conversation'])

/**
 * Runtime shape validation against the frozen contract — a malformed frame is dropped
 * (the signal layer is lossy by design; the authoritative faces are REST/SSE).
 */
function parseInboundFrame(text: string): CollaborationInboundFrame | null {
  let raw: unknown
  try {
    raw = JSON.parse(text)
  } catch {
    return null
  }
  if (!raw || typeof raw !== 'object') return null
  const frame = raw as Record<string, unknown>
  if (typeof frame.type !== 'string') return null
  switch (frame.type) {
    case 'welcome':
      return isString(frame.connectionId) && isString(frame.principalId)
        && typeof frame.typingWindowSeconds === 'number' && typeof frame.maxInboundSignalsPerSecond === 'number'
        ? {
            type: 'welcome',
            connectionId: frame.connectionId,
            principalId: frame.principalId,
            typingWindowSeconds: frame.typingWindowSeconds,
            maxInboundSignalsPerSecond: frame.maxInboundSignalsPerSecond,
          }
        : null
    case 'ping':
      return { type: 'ping' }
    case 'subscribed':
    case 'unsubscribed': {
      if (!isString(frame.subscriptionId)) return null
      if (frame.type === 'unsubscribed') return { type: 'unsubscribed', subscriptionId: frame.subscriptionId }
      const scope = parseScope(frame.scope)
      return scope
        ? { type: 'subscribed', subscriptionId: frame.subscriptionId, scope }
        : null
    }
    case 'error':
      return isString(frame.code)
        ? { type: 'error', code: frame.code }
        : null
    case 'presence_snapshot': {
      if (!isString(frame.subscriptionId)) return null
      const scope = parseScope(frame.scope)
      if (!scope || !Array.isArray(frame.present)) return null
      const present: PresenceSignalEntry[] = []
      for (const entry of frame.present) {
        if (!entry || typeof entry !== 'object') return null
        const candidate = entry as Record<string, unknown>
        if (!isString(candidate.principalId) || !isString(candidate.displayName)
          || typeof candidate.connections !== 'number') return null
        present.push({
          principalId: candidate.principalId,
          displayName: candidate.displayName,
          connections: candidate.connections,
        })
      }
      return { type: 'presence_snapshot', subscriptionId: frame.subscriptionId, scope, present }
    }
    case 'presence_delta':
    case 'typing': {
      if (!isString(frame.subscriptionId)) return null
      const scope = parseScope(frame.scope)
      if (!scope) return null
      if (frame.type === 'typing') {
        return isString(frame.principalId) && isString(frame.displayName)
          && (frame.state === 'started' || frame.state === 'stopped')
          ? {
              type: 'typing',
              subscriptionId: frame.subscriptionId,
              scope,
              principalId: frame.principalId,
              displayName: frame.displayName,
              state: frame.state,
            }
          : null
      }
      return isString(frame.principalId) && isString(frame.displayName)
        && (frame.action === 'enter' || frame.action === 'leave')
        && typeof frame.connections === 'number'
        ? {
            type: 'presence_delta',
            subscriptionId: frame.subscriptionId,
            scope,
            action: frame.action,
            principalId: frame.principalId,
            displayName: frame.displayName,
            connections: frame.connections,
          }
        : null
    }
    case 'resource_changed': {
      if (!isString(frame.subscriptionId)) return null
      const scope = parseScope(frame.scope)
      return scope && typeof frame.version === 'number'
        ? { type: 'resource_changed', subscriptionId: frame.subscriptionId, scope, version: frame.version }
        : null
    }
    default:
      return null
  }
}

function parseScope(raw: unknown): CollaborationScope | null {
  if (!raw || typeof raw !== 'object') return null
  const scope = raw as Record<string, unknown>
  if (!isString(scope.organization) || !isString(scope.team)) return null
  if (scope.resourceType === undefined && scope.resourceId === undefined) {
    return { organization: scope.organization, team: scope.team }
  }
  if (typeof scope.resourceType !== 'string' || !RESOURCE_TYPES.has(scope.resourceType)
    || !isString(scope.resourceId)) {
    return null
  }
  return {
    organization: scope.organization,
    team: scope.team,
    resourceType: scope.resourceType as CollaborationResourceType,
    resourceId: scope.resourceId,
  }
}

function isString(value: unknown): value is string {
  return typeof value === 'string'
}
