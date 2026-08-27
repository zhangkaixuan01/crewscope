import { CrewScopeApiError } from '../../api/client'
import { createThreeStreamCursorStore } from '../realtime/cursorStore'
import type { TeamOpsGateway } from './gateway'
import { createActivityRealtimeStore } from './activityRealtimeStore'
import { createTeamOpsStore } from './store'
import type { ActivityItem, TeamOpsScope } from './types'

const platform = { organizationId: 'org-1', teamId: 'team-platform' }
const security = { organizationId: 'org-1', teamId: 'team-security' }

describe('ActivityRealtimeStore', () => {
  beforeEach(() => localStorage.clear())

  it('persists the opaque Team Cursor and de-duplicates repeated SSE facts', async () => {
    const item = activity('event-1')
    const gateway = streamGateway(async () => sse([
      { id: 'opaque+/cursor-1', event: item.eventType, data: item },
      { id: 'opaque+/cursor-2', event: item.eventType, data: item },
    ]))
    const teamOps = createTeamOpsStore({} as TeamOpsGateway)
    teamOps.activateScope(platform)
    const cursors = createThreeStreamCursorStore(localStorage)
    const realtime = createActivityRealtimeStore(gateway, teamOps, cursors)

    realtime.start(platform, 'snapshot-cursor')
    await eventually(() => teamOps.state.teamActivity.value?.length === 1)

    expect(teamOps.state.teamActivity.value?.map(value => value.eventId)).toEqual(['event-1'])
    expect(cursors.getDurableCursor('TEAM', platform)).toBe('opaque+/cursor-2')
    expect(realtime.state.lastEventAt).toBe(item.occurredAt)
    realtime.stop()
  })

  it('ignores a late stream from the previous Team after Scope changes', async () => {
    const old = deferred<Response>()
    const gateway = streamGateway(async (scope: TeamOpsScope) => scope.teamId === platform.teamId
      ? old.promise
      : sse([{ id: 'security-cursor', event: 'TASK_STARTED', data: activity('security-event') }]))
    const teamOps = createTeamOpsStore({} as TeamOpsGateway)
    const realtime = createActivityRealtimeStore(gateway, teamOps, createThreeStreamCursorStore(localStorage))

    teamOps.activateScope(platform)
    realtime.start(platform)
    teamOps.activateScope(security)
    realtime.start(security)
    old.resolve(sse([{ id: 'platform-cursor', event: 'TASK_STARTED', data: activity('platform-event') }]))
    await eventually(() => teamOps.state.teamActivity.value?.some(item => item.eventId === 'security-event') === true)

    expect(teamOps.state.teamActivity.value?.map(item => item.eventId)).toEqual(['security-event'])
    realtime.stop()
  })

  it('clears an expired durable Cursor and waits for explicit snapshot recovery', async () => {
    const cursors = createThreeStreamCursorStore(localStorage)
    cursors.saveDurableCursor('TEAM', platform, 'expired-cursor')
    const gateway = streamGateway(async () => {
      throw new CrewScopeApiError(410, {
        code: 'cursor_expired', message: 'Cursor 已过期', correlationId: 'corr-1',
        retryable: true, currentVersion: null, details: {},
      })
    })
    const teamOps = createTeamOpsStore({} as TeamOpsGateway)
    teamOps.activateScope(platform)
    const realtime = createActivityRealtimeStore(gateway, teamOps, cursors)

    realtime.start(platform)
    await eventually(() => realtime.state.phase === 'cursor-expired')

    expect(realtime.state.resumeCursor).toBeNull()
    expect(cursors.getDurableCursor('TEAM', platform)).toBeNull()
    realtime.stop()
  })

  it('does not advance the durable Cursor when an SSE Activity payload is malformed', async () => {
    const cursors = createThreeStreamCursorStore(localStorage)
    const gateway = streamGateway(async () => new Response(
      'id:invalid-frame-cursor\nevent:TASK_STARTED\ndata:{broken\n\n',
      { headers: { 'Content-Type': 'text/event-stream' } },
    ))
    const teamOps = createTeamOpsStore({} as TeamOpsGateway)
    teamOps.activateScope(platform)
    const realtime = createActivityRealtimeStore(gateway, teamOps, cursors)

    realtime.start(platform, 'snapshot-cursor')
    await eventually(() => realtime.state.phase === 'error')

    expect(realtime.state.resumeCursor).toBe('snapshot-cursor')
    expect(cursors.getDurableCursor('TEAM', platform)).toBeNull()
    expect(teamOps.state.teamActivity.value).toBeNull()
    realtime.stop()
  })
})

function streamGateway(open: (scope: TeamOpsScope) => Promise<Response>): TeamOpsGateway {
  return { openTeamActivity: open } as unknown as TeamOpsGateway
}

function activity(eventId: string): ActivityItem {
  return {
    eventId, domainEventId: `${eventId}-domain`, teamSequence: 2, eventType: 'TASK_STARTED',
    category: 'EXECUTION', visibility: 'TEAM', subject: { type: 'TASK', id: 'task-1' },
    actor: { type: 'MEMBER', principalId: 'principal-1' }, references: [],
    occurredAt: '2026-08-27T08:00:00Z', payload: { schemaName: 'task-summary', schemaVersion: 1, values: { status: 'RUNNING' } },
  }
}

function sse(frames: Array<{ id: string, event: string, data: unknown }>): Response {
  const body = frames.map(frame => `id:${frame.id}\nevent:${frame.event}\ndata:${JSON.stringify(frame.data)}\n\n`).join('')
  return new Response(body, { headers: { 'Content-Type': 'text/event-stream' } })
}

async function eventually(predicate: () => boolean): Promise<void> {
  for (let attempt = 0; attempt < 30; attempt += 1) {
    if (predicate()) return
    await new Promise(resolve => setTimeout(resolve, 5))
  }
  throw new Error('Condition was not reached')
}

function deferred<T>(): { promise: Promise<T>, resolve: (value: T) => void } {
  let resolve!: (value: T) => void
  const promise = new Promise<T>(complete => { resolve = complete })
  return { promise, resolve }
}
