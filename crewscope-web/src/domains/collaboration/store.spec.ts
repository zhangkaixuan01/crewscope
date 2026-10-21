// @vitest-environment node
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { CollaborationGateway, CollaborationGatewayHandlers } from './gateway'
import { createCollaborationStore } from './store'
import type { CollaborationScope } from './types'

/**
 * Assertions derive from the frozen contract (docs/api/M11-协作信号API契约.md §1–§4,
 * §10) and ADR-032 — never from observed implementation behavior.
 */

interface Connection {
  handlers: CollaborationGatewayHandlers
  sent: string[]
  closed: boolean
}

const itemScope: CollaborationScope = {
  organization: 'org-1',
  team: 'team-1',
  resourceType: 'work_item',
  resourceId: 'item-1',
}
const itemKey = 'org-1/team-1/work_item/item-1'

function harness() {
  const connections: Connection[] = []
  const gateway: CollaborationGateway = {
    connect(handlers) {
      const connection: Connection = { handlers, sent: [], closed: false }
      connections.push(connection)
      return {
        send: text => connection.sent.push(text),
        close: () => {
          connection.closed = true
        },
      }
    },
  }
  const store = createCollaborationStore(gateway)
  const latest = () => connections[connections.length - 1]
  const openLive = (welcome: object = welcomeFrame()) => {
    store.acquire(itemScope)
    latest().handlers.onMessage(JSON.stringify(welcome))
  }
  return { store, connections, latest, openLive }
}

function welcomeFrame(overrides: Record<string, unknown> = {}) {
  return {
    type: 'welcome',
    connectionId: 'conn-1',
    principalId: 'account:alice',
    typingWindowSeconds: 5,
    maxInboundSignalsPerSecond: 30,
    ...overrides,
  }
}

function ackSubscribe(handle = 'handle-1', scope: CollaborationScope = itemScope) {
  return JSON.stringify({ type: 'subscribed', subscriptionId: handle, scope })
}

function presenceDelta(handle: string, action: 'enter' | 'leave', connections: number, principalId = 'account:bob') {
  return JSON.stringify({
    type: 'presence_delta',
    subscriptionId: handle,
    scope: itemScope,
    action,
    principalId,
    displayName: 'Bob',
    connections,
  })
}

function typingFrame(handle: string, state: 'started' | 'stopped', principalId = 'account:bob') {
  return JSON.stringify({
    type: 'typing',
    subscriptionId: handle,
    scope: itemScope,
    principalId,
    displayName: 'Bob',
    state,
  })
}

function resourceChanged(handle: string, version: number) {
  return JSON.stringify({
    type: 'resource_changed',
    subscriptionId: handle,
    scope: itemScope,
    version,
  })
}

describe('CollaborationStore', () => {
  beforeEach(() => vi.useFakeTimers())
  afterEach(() => vi.useRealTimers())

  // ---------------------------------------------------------------- lifecycle (§1.1)

  it('connects on the first acquire and stores the welcome runtime parameters (never hardcoded)', () => {
    const { store, latest } = harness()
    expect(store.state.phase).toBe('idle')

    store.acquire(itemScope)
    expect(store.state.phase).toBe('connecting')

    latest().handlers.onMessage(JSON.stringify(welcomeFrame({ typingWindowSeconds: 7 })))
    expect(store.state.phase).toBe('live')
    expect(store.state.typingWindowSeconds).toBe(7)
    expect(store.state.connectionId).toBe('conn-1')
    expect(store.state.principalId).toBe('account:alice')
  })

  it('replays every active subscription after the welcome frame (§1.2 scope shape)', () => {
    const { store, latest } = harness()
    store.acquire(itemScope)
    latest().handlers.onMessage(JSON.stringify(welcomeFrame()))

    expect(latest().sent).toEqual([JSON.stringify({ type: 'subscribe', scope: itemScope })])
  })

  it('answers every ping with a pong (§1.1)', () => {
    const { store, latest, openLive } = harness()
    openLive()

    latest().handlers.onMessage('{"type":"ping"}')
    latest().handlers.onMessage('{"type":"ping"}')

    expect(latest().sent.filter(text => text === '{"type":"pong"}')).toHaveLength(2)
  })

  it('drops frames that fail the contract shape without crashing the connection', () => {
    const { store, latest, openLive } = harness()
    openLive()
    latest().handlers.onMessage('{not json')
    latest().handlers.onMessage('{"type":"welcome"}')
    latest().handlers.onMessage('{"type":"presence_snapshot","subscriptionId":"handle-1","scope":{},"present":[]}')

    expect(store.state.phase).toBe('live')
    expect(store.state.presenceByScope).toEqual({})
  })

  // ---------------------------------------------------------------- presence (§2.1/§2.2)

  it('loads the presence snapshot including itself once the handle is acknowledged', () => {
    const { store, latest, openLive } = harness()
    openLive()
    latest().handlers.onMessage(ackSubscribe('handle-1'))
    latest().handlers.onMessage(JSON.stringify({
      type: 'presence_snapshot',
      subscriptionId: 'handle-1',
      scope: itemScope,
      present: [
        { principalId: 'account:alice', displayName: 'Alice', connections: 1 },
        { principalId: 'account:bob', displayName: 'Bob', connections: 2 },
      ],
    }))

    expect(store.state.presenceByScope[itemKey]).toEqual([
      { principalId: 'account:alice', displayName: 'Alice', connections: 1 },
      { principalId: 'account:bob', displayName: 'Bob', connections: 2 },
    ])
  })

  it('keeps a principal present on leave while another connection survives, removes on connections 0 (§2.2)', () => {
    const { store, latest, openLive } = harness()
    openLive()
    latest().handlers.onMessage(ackSubscribe('handle-1'))

    latest().handlers.onMessage(presenceDelta('handle-1', 'enter', 2))
    expect(store.state.presenceByScope[itemKey]).toEqual([
      { principalId: 'account:bob', displayName: 'Bob', connections: 2 },
    ])

    latest().handlers.onMessage(presenceDelta('handle-1', 'leave', 1))
    expect(store.state.presenceByScope[itemKey]).toEqual([
      { principalId: 'account:bob', displayName: 'Bob', connections: 1 },
    ])

    latest().handlers.onMessage(presenceDelta('handle-1', 'leave', 0))
    expect(store.state.presenceByScope[itemKey]).toEqual([])
  })

  // ---------------------------------------------------------------- typing (§2.3)

  it('shows a typer on started, self-clears after the welcome window, and clears immediately on stopped', () => {
    const { store, latest, openLive } = harness()
    openLive()
    latest().handlers.onMessage(ackSubscribe('handle-1'))

    latest().handlers.onMessage(typingFrame('handle-1', 'started'))
    expect(store.state.typingByScope[itemKey]).toEqual([{ principalId: 'account:bob', displayName: 'Bob' }])

    vi.advanceTimersByTime(4_999)
    expect(store.state.typingByScope[itemKey]).toHaveLength(1)

    vi.advanceTimersByTime(1)
    // The server never re-sends stopped — the self-clear is the client's duty.
    expect(store.state.typingByScope[itemKey]).toEqual([])

    latest().handlers.onMessage(typingFrame('handle-1', 'started'))
    latest().handlers.onMessage(typingFrame('handle-1', 'stopped'))
    expect(store.state.typingByScope[itemKey]).toEqual([])
  })

  it('uses the per-connection window from welcome, not a hardcoded constant', () => {
    const { store, latest } = harness()
    store.acquire(itemScope)
    latest().handlers.onMessage(JSON.stringify(welcomeFrame({ typingWindowSeconds: 2 })))
    latest().handlers.onMessage(ackSubscribe('handle-1'))

    latest().handlers.onMessage(typingFrame('handle-1', 'started'))
    vi.advanceTimersByTime(1_999)
    expect(store.state.typingByScope[itemKey]).toHaveLength(1)
    vi.advanceTimersByTime(1)
    expect(store.state.typingByScope[itemKey]).toEqual([])
  })

  it('anchors outbound typing started to the window and always lets stopped through once started', () => {
    const { store, latest, openLive } = harness()
    openLive()
    latest().handlers.onMessage(ackSubscribe('handle-1'))

    store.signalTyping(itemScope, true)
    store.signalTyping(itemScope, true)
    expect(latest().sent.filter(text => text.includes('"typing"') && text.includes('"started"'))).toHaveLength(1)

    vi.advanceTimersByTime(5_000)
    store.signalTyping(itemScope, true)
    expect(latest().sent.filter(text => text.includes('"typing"') && text.includes('"started"'))).toHaveLength(2)

    store.signalTyping(itemScope, false)
    store.signalTyping(itemScope, true)
    expect(latest().sent.filter(text => text.includes('"typing"') && text.includes('"stopped"'))).toHaveLength(1)
    expect(latest().sent.filter(text => text.includes('"typing"') && text.includes('"started"'))).toHaveLength(3)
  })

  it('does not spend budget on a stopped that never had a started', () => {
    const { store, latest, openLive } = harness()
    openLive()
    latest().handlers.onMessage(ackSubscribe('handle-1'))

    store.signalTyping(itemScope, false)

    expect(latest().sent.filter(text => text.includes('"typing"'))).toHaveLength(0)
  })

  // ---------------------------------------------------------------- resource changes (§2.4)

  it('notifies on a new max version and drops stale and duplicate versions', () => {
    const { store, latest, openLive } = harness()
    openLive()
    latest().handlers.onMessage(ackSubscribe('handle-1'))

    latest().handlers.onMessage(resourceChanged('handle-1', 12))
    expect(store.state.changeByScope[itemKey]).toMatchObject({ version: 12, seq: 1 })

    latest().handlers.onMessage(resourceChanged('handle-1', 7))
    latest().handlers.onMessage(resourceChanged('handle-1', 12))
    expect(store.state.changeByScope[itemKey]).toMatchObject({ version: 12, seq: 1 })

    latest().handlers.onMessage(resourceChanged('handle-1', 13))
    expect(store.state.changeByScope[itemKey]).toMatchObject({ version: 13, seq: 2 })
  })

  it('suppresses the echo of a locally acknowledged write receipt', () => {
    const { store, latest, openLive } = harness()
    openLive()
    latest().handlers.onMessage(ackSubscribe('handle-1'))

    store.acknowledgeVersion('work_item', 'item-1', 13)
    latest().handlers.onMessage(resourceChanged('handle-1', 13))
    latest().handlers.onMessage(resourceChanged('handle-1', 12))

    expect(store.state.changeByScope[itemKey]).toBeUndefined()
  })

  it('ignores signal frames routed to a handle this connection never acknowledged', () => {
    const { store, latest, openLive } = harness()
    openLive()

    latest().handlers.onMessage(presenceDelta('ghost-handle', 'enter', 1))
    latest().handlers.onMessage(resourceChanged('ghost-handle', 12))

    expect(store.state.presenceByScope).toEqual({})
    expect(store.state.changeByScope).toEqual({})
  })

  // ---------------------------------------------------------------- reconnection (§4, §10)

  it('reconnects with backoff after a live connection drops and rebuilds on a fresh handshake', () => {
    const { store, connections, latest, openLive } = harness()
    openLive()
    latest().handlers.onMessage(ackSubscribe('handle-1'))
    latest().handlers.onMessage(JSON.stringify({
      type: 'presence_snapshot', subscriptionId: 'handle-1', scope: itemScope,
      present: [{ principalId: 'account:alice', displayName: 'Alice', connections: 1 }],
    }))

    latest().handlers.onClose(1006, '')
    expect(store.state.phase).toBe('reconnecting')
    // No replay: presence and typing state die with the connection.
    expect(store.state.presenceByScope).toEqual({})
    expect(store.state.typingByScope).toEqual({})
    expect(connections).toHaveLength(1)

    vi.advanceTimersByTime(500)
    expect(connections).toHaveLength(2)
    expect(store.state.phase).toBe('reconnecting')

    latest().handlers.onMessage(JSON.stringify(welcomeFrame({ connectionId: 'conn-2' })))
    expect(store.state.phase).toBe('live')
    expect(store.state.reconnectAttempt).toBe(0)
    // The replayed subscribe re-establishes the scope under an all-new handle.
    expect(latest().sent).toEqual([JSON.stringify({ type: 'subscribe', scope: itemScope })])
    latest().handlers.onMessage(ackSubscribe('handle-2'))
    latest().handlers.onMessage(resourceChanged('handle-2', 12))
    expect(store.state.changeByScope[itemKey]).toMatchObject({ version: 12 })
  })

  it('backoffs follow 500ms doubling capped at 8 seconds with the SSE-face constants', () => {
    const { store, connections, latest, openLive } = harness()
    openLive()
    const delays = [500, 1_000, 2_000, 4_000, 8_000, 8_000]
    delays.forEach((delay, index) => {
      latest().handlers.onClose(1006, '')
      vi.advanceTimersByTime(delay - 1)
      expect(connections).toHaveLength(index + 1)
      vi.advanceTimersByTime(1)
      expect(connections).toHaveLength(index + 2)
    })
    expect(store.state.reconnectAttempt).toBe(6)
  })

  it('stops reconnecting after three failures when the channel has never been live (§10 degraded stance)', () => {
    const { store, connections, latest } = harness()
    store.acquire(itemScope)
    latest().handlers.onClose(1006, '')
    vi.advanceTimersByTime(500)
    latest().handlers.onClose(1006, '')
    vi.advanceTimersByTime(1_000)
    latest().handlers.onClose(1006, '')

    expect(store.state.phase).toBe('degraded')
    vi.advanceTimersByTime(60_000)
    expect(connections).toHaveLength(3)
  })

  it('treats an explicit server refusal (1008/4000) as degraded even after live', () => {
    const { store, connections, latest, openLive } = harness()
    openLive()

    latest().handlers.onClose(4000, 'principal limit')

    expect(store.state.phase).toBe('degraded')
    vi.advanceTimersByTime(60_000)
    expect(connections).toHaveLength(1)
  })

  it('treats a revocation cut (4403) as reconnectable, not degraded: signals clear and the backoff fires (§10, M11-D02)', () => {
    const { store, connections, latest, openLive } = harness()
    openLive()
    latest().handlers.onMessage(ackSubscribe('handle-1'))
    // A live presence signal exists before the member revocation sweep cuts the socket.
    latest().handlers.onMessage(presenceDelta('handle-1', 'enter', 1))

    latest().handlers.onClose(4403, 'authorization revoked')

    // 4403 is not an explicit refusal: the channel itself stays reconnectable (PG-blip
    // tolerance), while permission enforcement lives server-side — a resubscribe for a
    // revoked membership answers forbidden_scope, never a revived old handle.
    expect(store.state.phase).toBe('reconnecting')
    expect(store.state.presenceByScope[itemKey]).toBeUndefined()
    expect(store.state.typingByScope).toEqual({})
    vi.advanceTimersByTime(499)
    expect(connections).toHaveLength(1)
    vi.advanceTimersByTime(1)
    expect(connections).toHaveLength(2)
    expect(store.state.reconnectAttempt).toBe(1)
  })

  it('leaves degraded through an explicit retry and through a fresh page acquire', () => {
    const { store, connections, latest } = harness()
    store.acquire(itemScope)
    latest().handlers.onClose(1006, '')
    vi.advanceTimersByTime(500)
    latest().handlers.onClose(1006, '')
    vi.advanceTimersByTime(1_000)
    latest().handlers.onClose(1006, '')
    expect(store.state.phase).toBe('degraded')
    expect(connections).toHaveLength(3)

    store.retry()
    expect(store.state.phase).toBe('connecting')
    latest().handlers.onClose(1006, '')
    vi.advanceTimersByTime(500)
    latest().handlers.onClose(1006, '')
    vi.advanceTimersByTime(1_000)
    latest().handlers.onClose(1006, '')
    expect(store.state.phase).toBe('degraded')

    store.acquire({ organization: 'org-1', team: 'team-1', resourceType: 'work_item', resourceId: 'item-2' })
    expect(store.state.phase).toBe('connecting')
    expect(connections).toHaveLength(7)
  })

  // ---------------------------------------------------------------- refcounting (§1.2)

  it('unsubscribes when the last holder releases and never resurrects the scope from a racing frame', () => {
    const { store, latest, openLive } = harness()
    openLive()
    latest().handlers.onMessage(ackSubscribe('handle-1'))

    store.acquire(itemScope)
    store.release(itemScope)
    store.release(itemScope)

    expect(latest().sent).toContainEqual(JSON.stringify({ type: 'unsubscribe', subscriptionId: 'handle-1' }))
    expect(store.state.presenceByScope[itemKey]).toBeUndefined()

    // A fanout frame that raced the unsubscribe ack routes to a forgotten handle.
    latest().handlers.onMessage(presenceDelta('handle-1', 'enter', 1))
    expect(store.state.presenceByScope[itemKey]).toBeUndefined()
  })

  it('keeps the subscription while any holder remains', () => {
    const { store, latest, openLive } = harness()
    openLive()
    latest().handlers.onMessage(ackSubscribe('handle-1'))

    store.acquire(itemScope)
    store.release(itemScope)

    expect(latest().sent).not.toContainEqual(JSON.stringify({ type: 'unsubscribe', subscriptionId: 'handle-1' }))
    expect(store.state.phase).toBe('live')
  })

  // ---------------------------------------------------------------- session boundary

  it('stop() closes the socket, clears everything, and ignores the close it caused', () => {
    const { store, connections, latest, openLive } = harness()
    openLive()
    latest().handlers.onMessage(ackSubscribe('handle-1'))

    store.stop()

    expect(store.state.phase).toBe('idle')
    expect(latest().closed).toBe(true)
    // The close the shutdown itself triggered must not schedule a reconnect.
    latest().handlers.onClose(1006, '')
    vi.advanceTimersByTime(60_000)
    expect(connections).toHaveLength(1)
  })
})