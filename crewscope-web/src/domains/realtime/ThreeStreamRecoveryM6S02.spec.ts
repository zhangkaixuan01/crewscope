import { describe, expect, it } from 'vitest'

type DurableStream = 'TEAM' | 'CONVERSATION'
type StreamType = DurableStream | 'AG_UI'

interface ViewScope {
  organizationId: string
  teamId: string
  conversationId: string
}

interface ProtocolEvent {
  eventId: string
  domainEventId: string | null
  streamType: StreamType
  organizationId: string
  teamId: string
  conversationId: string | null
  eventType: string
  occurredAt: string
  payload: Record<string, unknown>
}

interface DurableFrame {
  cursor: string
  event: ProtocolEvent
}

interface DurableSnapshot {
  cursor: string | null
  events: ProtocolEvent[]
}

interface DurableConnection {
  events: AsyncIterable<DurableFrame>
}

interface DurableSource {
  open(stream: DurableStream, scope: ViewScope, after: string | null): Promise<DurableConnection>
  snapshot(stream: DurableStream, scope: ViewScope): Promise<DurableSnapshot>
}

interface AguiConnection {
  invocationId: string
  segmentId: string
  events: AsyncIterable<ProtocolEvent>
}

interface AguiSource {
  replay(scope: ViewScope, idempotencyKey: string): Promise<AguiConnection>
}

/**
 * Test-only M6-S02 coordinator.
 *
 * It validates the recovery contract before M6-E05/F01 introduce the production Team stream and
 * shared frontend coordinator. Team and Conversation keep independent durable positions; AG-UI
 * replays one idempotent invocation segment and never presents its SSE id as a durable cursor.
 */
class ThreeStreamCoordinator {
  private epoch = 0
  private activeScope: ViewScope | null = null
  private readonly cursors = new Map<string, string>()
  private readonly streamEvents = new Map<DurableStream, ProtocolEvent[]>([
    ['TEAM', []],
    ['CONVERSATION', []],
  ])
  private readonly seenEventIds = new Map<StreamType, Set<string>>([
    ['TEAM', new Set()],
    ['CONVERSATION', new Set()],
    ['AG_UI', new Set()],
  ])
  private readonly durableFacts = new Map<string, ProtocolEvent>()
  private aguiText = ''

  switchScope(scope: ViewScope): void {
    this.epoch += 1
    this.activeScope = { ...scope }
    this.streamEvents.set('TEAM', [])
    this.streamEvents.set('CONVERSATION', [])
    this.seenEventIds.get('TEAM')!.clear()
    this.seenEventIds.get('CONVERSATION')!.clear()
    this.seenEventIds.get('AG_UI')!.clear()
    this.durableFacts.clear()
    this.aguiText = ''
  }

  async recoverDurable(stream: DurableStream, source: DurableSource): Promise<void> {
    const scope = this.requireScope()
    const targetEpoch = this.epoch
    let after = this.cursors.get(cursorStorageKey(stream, scope)) ?? null
    for (let attempt = 0; attempt < 8 && this.isCurrent(scope, targetEpoch); attempt += 1) {
      try {
        const connection = await source.open(stream, scope, after)
        for await (const frame of connection.events) {
          if (!this.isCurrent(scope, targetEpoch)) return
          this.validateFrame(stream, scope, frame)
          this.applyDurable(stream, frame.event)
          // A validated frame is applied or classified as a duplicate before its position is saved.
          after = frame.cursor
          this.cursors.set(cursorStorageKey(stream, scope), frame.cursor)
        }
        return
      } catch (error) {
        if (!this.isCurrent(scope, targetEpoch)) return
        if (error instanceof CursorExpired) {
          const snapshot = await source.snapshot(stream, scope)
          if (!this.isCurrent(scope, targetEpoch)) return
          this.installSnapshot(stream, scope, snapshot)
          after = snapshot.cursor
          if (after) this.cursors.set(cursorStorageKey(stream, scope), after)
          else this.cursors.delete(cursorStorageKey(stream, scope))
          continue
        }
        if (error instanceof ControlledDisconnect) continue
        throw error
      }
    }
    if (this.isCurrent(scope, targetEpoch)) throw new Error(`${stream} did not converge`)
  }

  async recoverAgui(source: AguiSource, idempotencyKey: string): Promise<void> {
    const scope = this.requireScope()
    const targetEpoch = this.epoch
    let expectedInvocation: string | null = null
    let expectedSegment: string | null = null
    for (let attempt = 0; attempt < 8 && this.isCurrent(scope, targetEpoch); attempt += 1) {
      try {
        const connection = await source.replay(scope, idempotencyKey)
        expectedInvocation ??= connection.invocationId
        expectedSegment ??= connection.segmentId
        if (connection.invocationId !== expectedInvocation || connection.segmentId !== expectedSegment) {
          throw new Error('AG-UI replay changed invocation or segment identity')
        }
        for await (const event of connection.events) {
          if (!this.isCurrent(scope, targetEpoch)) return
          this.validateAgui(scope, event)
          if (!this.remember('AG_UI', event.eventId)) continue
          if (event.eventType === 'TEXT_MESSAGE_CONTENT') {
            const delta = event.payload.delta
            if (typeof delta !== 'string') throw new Error('Invalid AG-UI text delta')
            this.aguiText += delta
          }
        }
        return
      } catch (error) {
        if (!this.isCurrent(scope, targetEpoch)) return
        if (error instanceof ControlledDisconnect) continue
        throw error
      }
    }
    if (this.isCurrent(scope, targetEpoch)) throw new Error('AG-UI did not converge')
  }

  cursor(stream: DurableStream, scope: ViewScope): string | null {
    return this.cursors.get(cursorStorageKey(stream, scope)) ?? null
  }

  events(stream: DurableStream): ProtocolEvent[] {
    return [...this.streamEvents.get(stream)!]
  }

  combinedDurableFacts(): ProtocolEvent[] {
    return [...this.durableFacts.values()].sort((left, right) =>
      left.occurredAt.localeCompare(right.occurredAt) || left.eventId.localeCompare(right.eventId),
    )
  }

  streamedText(): string {
    return this.aguiText
  }

  private installSnapshot(
    stream: DurableStream,
    scope: ViewScope,
    snapshot: DurableSnapshot,
  ): void {
    const replacement: ProtocolEvent[] = []
    const streamSeen = this.seenEventIds.get(stream)!
    streamSeen.clear()
    for (const event of snapshot.events) {
      this.validateDurableEvent(stream, scope, event)
      if (this.remember(stream, event.eventId)) replacement.push(event)
    }
    this.streamEvents.set(stream, replacement)
    this.rebuildCombinedFacts()
  }

  private applyDurable(stream: DurableStream, event: ProtocolEvent): void {
    if (!this.remember(stream, event.eventId)) return
    this.streamEvents.get(stream)!.push(event)
    this.mergeDurableFact(event)
  }

  private rebuildCombinedFacts(): void {
    this.durableFacts.clear()
    for (const stream of ['TEAM', 'CONVERSATION'] as const) {
      for (const event of this.streamEvents.get(stream)!) this.mergeDurableFact(event)
    }
  }

  private mergeDurableFact(event: ProtocolEvent): void {
    const key = event.domainEventId ?? `${event.streamType}:${event.eventId}`
    const current = this.durableFacts.get(key)
    // Conversation is the more specific representation on a combined Conversation surface.
    if (!current || (current.streamType === 'TEAM' && event.streamType === 'CONVERSATION')) {
      this.durableFacts.set(key, event)
    }
  }

  private validateFrame(stream: DurableStream, scope: ViewScope, frame: DurableFrame): void {
    if (!frame.cursor.trim()) throw new Error('Durable cursor is missing')
    this.validateDurableEvent(stream, scope, frame.event)
  }

  private validateDurableEvent(
    stream: DurableStream,
    scope: ViewScope,
    event: ProtocolEvent,
  ): void {
    if (event.streamType !== stream
      || event.organizationId !== scope.organizationId
      || event.teamId !== scope.teamId
      || (stream === 'CONVERSATION' && event.conversationId !== scope.conversationId)
      || (stream === 'TEAM' && event.conversationId !== null)) {
      throw new Error('Durable event does not belong to the active stream scope')
    }
  }

  private validateAgui(scope: ViewScope, event: ProtocolEvent): void {
    if (event.streamType !== 'AG_UI'
      || event.domainEventId !== null
      || event.organizationId !== scope.organizationId
      || event.teamId !== scope.teamId
      || event.conversationId !== scope.conversationId) {
      throw new Error('AG-UI event does not belong to the active invocation scope')
    }
  }

  private remember(stream: StreamType, eventId: string): boolean {
    const seen = this.seenEventIds.get(stream)!
    if (seen.has(eventId)) return false
    seen.add(eventId)
    return true
  }

  private requireScope(): ViewScope {
    if (!this.activeScope) throw new Error('No active scope')
    return { ...this.activeScope }
  }

  private isCurrent(scope: ViewScope, targetEpoch: number): boolean {
    return targetEpoch === this.epoch
      && this.activeScope !== null
      && scopeKey(this.activeScope) === scopeKey(scope)
  }
}

class ScriptedDurableSource implements DurableSource {
  readonly afters: Array<{ stream: DurableStream, scope: string, after: string | null }> = []
  private readonly scripts = new Map<string, Array<() => AsyncIterable<DurableFrame>>>()
  private readonly snapshots = new Map<string, DurableSnapshot>()

  enqueue(
    stream: DurableStream,
    scope: ViewScope,
    events: DurableFrame[],
    failure?: Error,
    gate?: Deferred,
  ): void {
    const factory = async function* () {
      if (gate) await gate.promise
      yield* events
      if (failure) throw failure
    }
    const key = sourceKey(stream, scope)
    this.scripts.set(key, [...(this.scripts.get(key) ?? []), factory])
  }

  setSnapshot(stream: DurableStream, scope: ViewScope, snapshot: DurableSnapshot): void {
    this.snapshots.set(sourceKey(stream, scope), snapshot)
  }

  async open(
    stream: DurableStream,
    scope: ViewScope,
    after: string | null,
  ): Promise<DurableConnection> {
    this.afters.push({ stream, scope: scopeKey(scope), after })
    const scripts = this.scripts.get(sourceKey(stream, scope)) ?? []
    const next = scripts.shift()
    if (!next) throw new Error(`Missing ${stream} connection script`)
    return { events: next() }
  }

  async snapshot(stream: DurableStream, scope: ViewScope): Promise<DurableSnapshot> {
    const snapshot = this.snapshots.get(sourceKey(stream, scope))
    if (!snapshot) throw new Error(`Missing ${stream} snapshot`)
    return snapshot
  }
}

class ScriptedAguiSource implements AguiSource {
  readonly keys: string[] = []
  private readonly scripts: Array<() => AsyncIterable<ProtocolEvent>> = []

  enqueue(events: ProtocolEvent[], failure?: Error): void {
    this.scripts.push(async function* () {
      yield* events
      if (failure) throw failure
    })
  }

  async replay(_scope: ViewScope, idempotencyKey: string): Promise<AguiConnection> {
    this.keys.push(idempotencyKey)
    const next = this.scripts.shift()
    if (!next) throw new Error('Missing AG-UI connection script')
    return { invocationId: 'invocation-1', segmentId: 'segment-1', events: next() }
  }
}

class ControlledDisconnect extends Error {}
class CursorExpired extends Error {}

class Deferred {
  private release!: () => void
  readonly promise = new Promise<void>((resolve) => { this.release = resolve })

  resolve(): void {
    this.release()
  }
}

const platform: ViewScope = {
  organizationId: 'org-1',
  teamId: 'team-platform',
  conversationId: 'conversation-platform',
}

const security: ViewScope = {
  organizationId: 'org-1',
  teamId: 'team-security',
  conversationId: 'conversation-security',
}

describe('M6-S02 three-stream recovery protocol', () => {
  it('recovers Team and Conversation independently without duplicate display or event loss', async () => {
    const coordinator = new ThreeStreamCoordinator()
    const source = new ScriptedDurableSource()
    coordinator.switchScope(platform)

    const teamOne = frame('team:g7:s1', durable('TEAM', platform, 'team-1', 'domain-1', '08:00:00'))
    const teamTwo = frame('team:g7:s2', durable('TEAM', platform, 'team-2', 'domain-2', '08:00:02'))
    const teamThree = frame('team:g7:s3', durable('TEAM', platform, 'team-3', 'domain-3', '08:00:03'))
    source.enqueue('TEAM', platform, [teamOne], new ControlledDisconnect())
    source.enqueue('TEAM', platform, [teamOne, teamTwo, teamThree])

    const conversationOne = frame(
      'conversation:p1:e1',
      durable('CONVERSATION', platform, 'conversation-1', 'domain-2', '08:00:01'),
    )
    const conversationTwo = frame(
      'conversation:p2:e2',
      durable('CONVERSATION', platform, 'conversation-2', 'domain-4', '08:00:04'),
    )
    source.enqueue('CONVERSATION', platform, [conversationOne], new ControlledDisconnect())
    source.enqueue('CONVERSATION', platform, [conversationOne, conversationTwo])

    await Promise.all([
      coordinator.recoverDurable('TEAM', source),
      coordinator.recoverDurable('CONVERSATION', source),
    ])

    expect(coordinator.events('TEAM').map(event => event.eventId)).toEqual(['team-1', 'team-2', 'team-3'])
    expect(coordinator.events('CONVERSATION').map(event => event.eventId)).toEqual([
      'conversation-1', 'conversation-2',
    ])
    expect(coordinator.cursor('TEAM', platform)).toBe('team:g7:s3')
    expect(coordinator.cursor('CONVERSATION', platform)).toBe('conversation:p2:e2')
    expect(source.afters.filter(item => item.stream === 'TEAM').map(item => item.after)).toEqual([
      null, 'team:g7:s1',
    ])
    expect(source.afters.filter(item => item.stream === 'CONVERSATION').map(item => item.after)).toEqual([
      null, 'conversation:p1:e1',
    ])
    // The same durable fact appeared in both streams. The combined Conversation surface keeps the
    // more specific representation while both stream-local histories remain complete.
    expect(coordinator.combinedDurableFacts()).toHaveLength(4)
    expect(coordinator.combinedDurableFacts().find(event => event.domainEventId === 'domain-2')?.streamType)
      .toBe('CONVERSATION')
  })

  it('replays one AG-UI segment with the same idempotency key and stream-local event ids', async () => {
    const coordinator = new ThreeStreamCoordinator()
    const source = new ScriptedAguiSource()
    coordinator.switchScope(platform)
    const first = agui(platform, 'agui-1', 'TEXT_MESSAGE_CONTENT', { delta: '第一段' })
    const second = agui(platform, 'agui-2', 'TEXT_MESSAGE_CONTENT', { delta: '第二段' })
    source.enqueue([first], new ControlledDisconnect())
    source.enqueue([first, second, agui(platform, 'agui-3', 'RUN_FINISHED', {})])

    await coordinator.recoverAgui(source, 'invoke-key-1')

    expect(source.keys).toEqual(['invoke-key-1', 'invoke-key-1'])
    expect(coordinator.streamedText()).toBe('第一段第二段')
    expect(coordinator.cursor('CONVERSATION', platform)).toBeNull()
    expect(coordinator.cursor('TEAM', platform)).toBeNull()
  })

  it('replaces only the expired Team generation with a bounded snapshot before resuming', async () => {
    const coordinator = new ThreeStreamCoordinator()
    const source = new ScriptedDurableSource()
    coordinator.switchScope(platform)
    source.enqueue('TEAM', platform, [], new CursorExpired())
    source.setSnapshot('TEAM', platform, {
      cursor: 'team:g8:s2',
      events: [
        durable('TEAM', platform, 'team-g8-1', 'domain-g8-1', '08:10:00'),
        durable('TEAM', platform, 'team-g8-2', 'domain-g8-2', '08:10:01'),
      ],
    })
    source.enqueue('TEAM', platform, [
      frame('team:g8:s3', durable('TEAM', platform, 'team-g8-3', 'domain-g8-3', '08:10:02')),
    ])

    await coordinator.recoverDurable('TEAM', source)

    expect(coordinator.events('TEAM').map(event => event.eventId)).toEqual([
      'team-g8-1', 'team-g8-2', 'team-g8-3',
    ])
    expect(coordinator.cursor('TEAM', platform)).toBe('team:g8:s3')
    expect(source.afters.filter(item => item.stream === 'TEAM').map(item => item.after)).toEqual([
      null, 'team:g8:s2',
    ])
  })

  it('drops a late frame from the previous Team after a Scope epoch changes', async () => {
    const coordinator = new ThreeStreamCoordinator()
    const source = new ScriptedDurableSource()
    const oldTeamGate = new Deferred()
    coordinator.switchScope(platform)
    source.enqueue('TEAM', platform, [
      frame('team:g7:s1', durable('TEAM', platform, 'late-platform', 'late-domain', '08:20:00')),
    ], undefined, oldTeamGate)
    const oldRecovery = coordinator.recoverDurable('TEAM', source)

    coordinator.switchScope(security)
    source.enqueue('TEAM', security, [
      frame('team:g1:s1', durable('TEAM', security, 'security-1', 'security-domain', '08:20:01')),
    ])
    await coordinator.recoverDurable('TEAM', source)
    oldTeamGate.resolve()
    await oldRecovery

    expect(coordinator.events('TEAM').map(event => event.eventId)).toEqual(['security-1'])
    expect(coordinator.cursor('TEAM', security)).toBe('team:g1:s1')
    expect(coordinator.cursor('TEAM', platform)).toBeNull()
  })

  it('uses deterministic presentation order without comparing cursors across streams', async () => {
    const coordinator = new ThreeStreamCoordinator()
    const source = new ScriptedDurableSource()
    coordinator.switchScope(platform)
    source.enqueue('TEAM', platform, [
      frame('team:g2:s99', durable('TEAM', platform, 'team-later', 'later', '09:00:02')),
    ])
    source.enqueue('CONVERSATION', platform, [
      frame('conversation:p500:e1', durable(
        'CONVERSATION', platform, 'conversation-earlier', 'earlier', '09:00:01',
      )),
    ])

    await coordinator.recoverDurable('TEAM', source)
    await coordinator.recoverDurable('CONVERSATION', source)

    expect(coordinator.combinedDurableFacts().map(event => event.eventId)).toEqual([
      'conversation-earlier', 'team-later',
    ])
    expect(Object.keys(coordinator.combinedDurableFacts()[0]!)).not.toContain('globalSequence')
    expect(coordinator.cursor('TEAM', platform)).toBe('team:g2:s99')
    expect(coordinator.cursor('CONVERSATION', platform)).toBe('conversation:p500:e1')
  })

  it('does not advance a cursor for a malformed cross-stream frame', async () => {
    const coordinator = new ThreeStreamCoordinator()
    const source = new ScriptedDurableSource()
    coordinator.switchScope(platform)
    source.enqueue('TEAM', platform, [
      frame(
        'team:g1:s1',
        durable('CONVERSATION', platform, 'wrong-stream', 'wrong-domain', '09:10:00'),
      ),
    ])

    await expect(coordinator.recoverDurable('TEAM', source)).rejects.toThrow(
      'Durable event does not belong to the active stream scope',
    )
    expect(coordinator.cursor('TEAM', platform)).toBeNull()
    expect(coordinator.events('TEAM')).toEqual([])
  })
})

function frame(cursor: string, event: ProtocolEvent): DurableFrame {
  return { cursor, event }
}

function durable(
  stream: DurableStream,
  scope: ViewScope,
  eventId: string,
  domainEventId: string,
  time: string,
): ProtocolEvent {
  return {
    eventId,
    domainEventId,
    streamType: stream,
    organizationId: scope.organizationId,
    teamId: scope.teamId,
    conversationId: stream === 'CONVERSATION' ? scope.conversationId : null,
    eventType: stream === 'TEAM' ? 'TEAM_ACTIVITY_CHANGED' : 'CONVERSATION_MESSAGE_POSTED',
    occurredAt: `2026-08-25T${time}Z`,
    payload: {},
  }
}

function agui(
  scope: ViewScope,
  eventId: string,
  eventType: string,
  payload: Record<string, unknown>,
): ProtocolEvent {
  return {
    eventId,
    domainEventId: null,
    streamType: 'AG_UI',
    organizationId: scope.organizationId,
    teamId: scope.teamId,
    conversationId: scope.conversationId,
    eventType,
    occurredAt: '2026-08-25T08:00:00Z',
    payload,
  }
}

function cursorStorageKey(stream: DurableStream, scope: ViewScope): string {
  if (stream === 'TEAM') return `TEAM:${scope.organizationId}:${scope.teamId}:default-filter`
  return `CONVERSATION:${scope.organizationId}:${scope.teamId}:${scope.conversationId}`
}

function sourceKey(stream: DurableStream, scope: ViewScope): string {
  if (stream === 'TEAM') return `${stream}:${scope.organizationId}:${scope.teamId}`
  return `${stream}:${scopeKey(scope)}`
}

function scopeKey(scope: ViewScope): string {
  return `${scope.organizationId}:${scope.teamId}:${scope.conversationId}`
}
