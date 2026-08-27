import { inject, reactive, readonly, type App, type InjectionKey } from 'vue'
import { parseServerSentEvents } from '../../api/sse'
import { createThreeStreamCursorStore, type ThreeStreamCursorStore } from '../realtime/cursorStore'
import { isAbort, teamOpsError, type TeamOpsErrorState } from './errors'
import { mapPublicActivity, type TeamOpsGateway } from './gateway'
import type { TeamOpsStore } from './store'
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
      for await (const frame of parseServerSentEvents(response.body)) {
        if (!current(connectionGeneration, request)) return
        if (frame.event === 'heartbeat' || !frame.data.trim()) {
          commitCursor(scope, frame.id)
          continue
        }
        const item = mapPublicActivity(JSON.parse(frame.data) as unknown)
        if (teamOps.ingestTeamActivity(scope, item, frame.id)) {
          // A malformed frame must never advance the durable recovery boundary.
          commitCursor(scope, frame.id)
          state.lastEventAt = item.occurredAt
        }
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

export function installActivityRealtimeStore(
  app: App,
  gateway: TeamOpsGateway,
  teamOps: TeamOpsStore,
  cursors: ThreeStreamCursorStore = createThreeStreamCursorStore(),
): ActivityRealtimeStore {
  const store = createActivityRealtimeStore(gateway, teamOps, cursors)
  app.provide(ACTIVITY_REALTIME_STORE, store)
  return store
}

export function useActivityRealtimeStore(): ActivityRealtimeStore {
  const store = inject(ACTIVITY_REALTIME_STORE)
  if (!store) throw new Error('CrewScope Activity Realtime Store is not installed')
  return store
}

function reconnectDelay(attempt: number): number {
  return Math.min(8_000, 500 * 2 ** Math.min(attempt, 4))
}

function initialState(): ActivityRealtimeState {
  return { phase: 'idle', resumeCursor: null, lastEventAt: null, reconnectAttempt: 0, error: null }
}
