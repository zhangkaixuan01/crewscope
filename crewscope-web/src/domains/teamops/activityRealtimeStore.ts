import {getCurrentInstance, inject, reactive, readonly, type App, type InjectionKey} from 'vue'
import { registerStoreReset } from '../../app/storeLifecycle'
import { parseServerSentEvents, type ServerSentEventFrame } from '../../api/sse'
import { createThreeStreamCursorStore, type ThreeStreamCursorStore } from '../realtime/cursorStore'
import { teamOpsError, type TeamOpsErrorState } from './errors'
import { isAbort } from '../../api/errors'
import { HttpTeamOpsGateway, mapPublicActivity, type TeamOpsGateway } from './gateway'
import { ensureTeamOpsStore, type TeamOpsStore } from './store'
import type { ActivityFilter, TeamOpsScope } from './types'

export type ActivityRealtimePhase =
  | 'idle'
  | 'connecting'
  | 'live'
  | 'reconnecting'
  | 'offline'
  | 'forbidden'
  | 'cursor-expired'
  | 'error'

export interface ActivityRealtimeState {
  phase: ActivityRealtimePhase
  resumeCursor: string | null
  lastEventAt: string | null
  reconnectAttempt: number
  error: TeamOpsErrorState | null
}

export interface ActivityRealtimeStore {
  state: Readonly<ActivityRealtimeState>
  start(scope: TeamOpsScope, snapshotCursor?: string | null, filter?: ActivityFilter): void
  retry(snapshotCursor?: string | null): void
  setOnline(online: boolean): void
  stop(): void
}

export const ACTIVITY_REALTIME_STORE: InjectionKey<ActivityRealtimeStore> = Symbol('crewscope-activity-realtime-store')

/** Owns Team Activity SSE recovery without mixing transport frames into domain query state. */
export function createActivityRealtimeStore(
  gateway: TeamOpsGateway,
  teamOps: TeamOpsStore,
  cursors: ThreeStreamCursorStore,
): ActivityRealtimeStore {
  const state = reactive<ActivityRealtimeState>(initialState())
  let activeScope: TeamOpsScope | null = null
  let activeFilter: ActivityFilter = {}
  let snapshotCursor: string | null = null
  let online = true
  let generation = 0
  let controller: AbortController | null = null
  let reconnectTimer: ReturnType<typeof setTimeout> | null = null

  function start(scope: TeamOpsScope, nextSnapshotCursor: string | null = null, filter: ActivityFilter = {}): void {
    generation += 1
    closeTransport()
    activeScope = { ...scope }
    activeFilter = { ...filter }
    snapshotCursor = nextSnapshotCursor
    state.resumeCursor = cursors.getDurableCursor('TEAM', scope) ?? nextSnapshotCursor
    state.error = null
    state.reconnectAttempt = 0
    if (!online) {
      state.phase = 'offline'
      return
    }
    connect(generation)
  }

  function retry(nextSnapshotCursor: string | null = snapshotCursor): void {
    if (!activeScope) return
    cursors.clearDurableCursor('TEAM', activeScope)
    start(activeScope, nextSnapshotCursor, activeFilter)
  }

  function setOnline(value: boolean): void {
    if (online === value) return
    online = value
    if (!value) {
      generation += 1
      closeTransport()
      state.phase = 'offline'
      return
    }
    if (activeScope) connect(++generation)
  }

  function stop(): void {
    generation += 1
    closeTransport()
    activeScope = null
    activeFilter = {}
    snapshotCursor = null
    Object.assign(state, initialState())
  }

  function connect(connectionGeneration: number): void {
    if (!activeScope || !online || connectionGeneration !== generation) return
    closeTransport()
    controller = new AbortController()
    state.phase = state.reconnectAttempt === 0 ? 'connecting' : 'reconnecting'
    state.error = null
    void consume(connectionGeneration, controller)
  }

  async function consume(connectionGeneration: number, request: AbortController): Promise<void> {
    const scope = activeScope ? { ...activeScope } : null
    if (!scope) return
    try {
      const response = await gateway.openTeamActivity(
        scope,
        activeFilter,
        state.resumeCursor,
        request.signal,
      )
      if (!current(connectionGeneration, request)) return
      if (!response.body) throw new TypeError('Team Activity stream response has no body')
      state.phase = 'live'
      state.reconnectAttempt = 0
      // Manual iteration (instead of for-await) splits the two failure dimensions the outer
      // catch would otherwise conflate: a rejection from the iterator itself is a transport
      // cut, while a throw from the loop body is a malformed payload.
      const frames = parseServerSentEvents(response.body)
      try {
        for (;;) {
          let pending: IteratorResult<ServerSentEventFrame, void>
          try {
            pending = await frames.next()
          } catch (cause) {
            if (isAbort(cause) || !current(connectionGeneration, request)) return
            // The frozen server contract (TeamActivityStreamRevocationTest: an idle-probe
            // denial must terminate the stream) aborts the connection after the 200 is
            // committed — e.g. the revocation recheck — which the browser surfaces as the
            // body reader rejecting with a bare TypeError. That is a cut, not a malformed
            // payload: reconnect on the backoff ladder and let the fresh handshake's status
            // carry the authoritative verdict, so a revoked member's 403 lands on forbidden
            // instead of stranding the badge on a terminal error.
            scheduleReconnect(connectionGeneration, reconnectDelay(state.reconnectAttempt))
            return
          }
          if (pending.done) break
          const frame = pending.value
          if (!current(connectionGeneration, request)) return
          if (frame.event === 'heartbeat' || !frame.data.trim()) {
            commitCursor(scope, frame.id)
            continue
          }
          const payload = JSON.parse(frame.data) as unknown
          const item = mapPublicActivity(payload)
          if (teamOps.ingestTeamActivity(scope, item, frame.id)) {
            // A malformed frame must never advance the durable recovery boundary.
            commitCursor(scope, frame.id)
            state.lastEventAt = item.occurredAt
          }
        }
      } finally {
        // for-await closes its iterator on every early exit; hand-rolled iteration must do
        // the same or the abandoned generator keeps the HTTP body buffering.
        await frames.return(undefined)
      }
      if (current(connectionGeneration, request)) scheduleReconnect(connectionGeneration, 5_000)
    } catch (error) {
      if (isAbort(error) || !current(connectionGeneration, request)) return
      const classified = teamOpsError(error, '团队活动实时连接暂时不可用')
      state.error = classified
      if (classified.kind === 'forbidden') {
        state.phase = 'forbidden'
        return
      }
      if (classified.kind === 'cursor-expired') {
        cursors.clearDurableCursor('TEAM', scope)
        state.resumeCursor = null
        state.phase = 'cursor-expired'
        return
      }
      if (classified.kind === 'offline') {
        state.phase = 'offline'
      } else if (classified.kind === 'invalid-response') {
        state.phase = 'error'
        return
      } else {
        state.phase = 'reconnecting'
      }
      scheduleReconnect(connectionGeneration, reconnectDelay(state.reconnectAttempt))
    }
  }

  function scheduleReconnect(connectionGeneration: number, delay: number): void {
    if (!activeScope || !online || connectionGeneration !== generation) return
    state.reconnectAttempt += 1
    if (state.phase === 'live') state.phase = 'reconnecting'
    reconnectTimer = setTimeout(() => connect(connectionGeneration), delay)
  }

  function current(connectionGeneration: number, request: AbortController): boolean {
    return connectionGeneration === generation && request === controller && !request.signal.aborted
  }

  function commitCursor(scope: TeamOpsScope, cursor: string | null): void {
    if (!cursor) return
    state.resumeCursor = cursor
    cursors.saveDurableCursor('TEAM', scope, cursor)
  }

  function closeTransport(): void {
    controller?.abort()
    controller = null
    if (reconnectTimer) clearTimeout(reconnectTimer)
    reconnectTimer = null
  }

  return { state: readonly(state), start, retry, setOnline, stop }
}

// M13d-P01: lazily installed on the pages that consume this domain (keeps it out of the eager
// main chunk). The instance lives on the app, so revisiting a page reuses cached state; spec
// fixtures provided through app-level provide always win and are never shadowed.
export function ensureActivityRealtimeStore(app: App): ActivityRealtimeStore {
  const existing = app.runWithContext(() => inject(ACTIVITY_REALTIME_STORE, null))
  if (existing) return existing
  // main.ts used to share one gateway with the TeamOps store; the wrapper is stateless, so a
  // second instance is behaviorally identical.
  const store = createActivityRealtimeStore(new HttpTeamOpsGateway(), ensureTeamOpsStore(app), createThreeStreamCursorStore())
  app.provide(ACTIVITY_REALTIME_STORE, store)
  registerStoreReset(app, () => store.stop())
  return store
}

export function stopActivityRealtimeStoreIfActive(app: App): void {
  app.runWithContext(() => inject(ACTIVITY_REALTIME_STORE, null))?.stop()
}

export function useActivityRealtimeStore(): ActivityRealtimeStore {
  const installed = inject(ACTIVITY_REALTIME_STORE, null)
  if (installed) return installed
  // M13d-P01: fall back to lazy installation — a component declares its domain dependency
  // by calling use*, so no host page has to pre-install the store.
  const instance = getCurrentInstance()
  if (!instance) throw new Error('CrewScope Activity Realtime Store is not installed')
  return ensureActivityRealtimeStore(instance.appContext.app)
}

function reconnectDelay(attempt: number): number {
  return Math.min(8_000, 500 * 2 ** Math.min(attempt, 4))
}

function initialState(): ActivityRealtimeState {
  return { phase: 'idle', resumeCursor: null, lastEventAt: null, reconnectAttempt: 0, error: null }
}
