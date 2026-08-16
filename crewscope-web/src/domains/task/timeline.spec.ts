import type { TaskEventItem } from './types'
import { latestTaskProgress, taskTimeline } from './timeline'

describe('taskTimeline', () => {
  it('preserves durable stream order and de-duplicates event and domain identities', () => {
    const items = [
      event('one', 'domain-one', 'WORKER_TASK_START_ACCEPTED', '2026-08-15T12:02:00Z', { operation: 'START', attempt: 2 }),
      event('two', 'domain-two', 'WORKER_TASK_PROGRESS_ACCEPTED', '2026-08-15T12:01:00Z', { operation: 'PROGRESS', attempt: 2, safeSummary: '正在验证', progressPercent: 40 }),
      event('two', 'domain-replay', 'WORKER_TASK_PROGRESS_ACCEPTED', '2026-08-15T12:03:00Z', { operation: 'PROGRESS' }),
      event('three', 'domain-two', 'WORKER_TASK_COMPLETE_ACCEPTED', '2026-08-15T12:04:00Z', { operation: 'COMPLETE' }),
    ]

    const entries = taskTimeline(items, 'execution-current')

    expect(entries.map(item => item.id)).toEqual(['one', 'two'])
    expect(entries.map(item => item.occurredAt)).toEqual(['2026-08-15T12:02:00Z', '2026-08-15T12:01:00Z'])
    expect(latestTaskProgress(entries)).toMatchObject({ percent: 40, summary: '正在验证', source: 'WORKER' })
  })

  it('filters another attempt and removes heartbeat and streaming-delta noise', () => {
    const items = [
      event('history', 'domain-history', 'WORKER_TASK_PROGRESS_ACCEPTED', '2026-08-15T11:00:00Z', { operation: 'PROGRESS', safeSummary: '旧 attempt' }, 'execution-old'),
      event('heartbeat', 'domain-heartbeat', 'WORKER_TASK_HEARTBEAT_ACCEPTED', '2026-08-15T12:00:00Z', { operation: 'HEARTBEAT' }),
      event('delta', 'domain-delta', 'AGENT_RUN_EVENT_RECORDED', '2026-08-15T12:01:00Z', { eventKind: 'TEXT_DELTA', safeText: 'token' }),
      event('progress', 'domain-progress', 'AGENT_RUN_EVENT_RECORDED', '2026-08-15T12:02:00Z', { eventKind: 'PROGRESS', safeText: 'Agent 正在收口', progressPercent: 75 }),
    ]

    const entries = taskTimeline(items, 'execution-current')

    expect(entries).toHaveLength(1)
    expect(entries[0]).toMatchObject({ title: 'Agent 进度已更新', summary: 'Agent 正在收口', progressPercent: 75 })
    expect(latestTaskProgress(entries)?.source).toBe('AGENT_RUN')
  })

  it('marks lease recovery and AgentRun resume without exposing unknown payloads', () => {
    const entries = taskTimeline([
      event('recovery', 'domain-recovery', 'TASK_EXECUTION_RECOVERY_STARTED', '2026-08-15T12:00:00Z', { attempt: 2, expiredPhase: 'RUN' }),
      event('resume', 'domain-resume', 'AGENT_RUN_RESUMED', '2026-08-15T12:01:00Z', { resumedSegmentSequence: 3 }),
      event('unknown', 'domain-unknown', 'FUTURE_INTERNAL_EVENT', '2026-08-15T12:02:00Z', { credential: 'secret' }),
    ], 'execution-current')

    expect(entries.slice(0, 2).every(item => item.recovery)).toBe(true)
    expect(entries[0]?.summary).toContain('RUN')
    expect(entries[2]).toMatchObject({ title: 'Task 事实已更新', summary: null })
    expect(JSON.stringify(entries)).not.toContain('secret')
  })
})

function event(
  eventId: string,
  domainEventId: string,
  eventType: string,
  occurredAt: string,
  payload: Record<string, unknown>,
  executionId = 'execution-current',
): TaskEventItem {
  return {
    cursor: `cursor-${eventId}`,
    context: { taskId: 'task', taskExecutionId: executionId, stepExecutionId: null, agentRunId: null, executionLeaseId: null },
    projectionGap: false,
    event: {
      eventId, domainEventId, streamType: 'TASK', eventType, schemaVersion: '1',
      aggregateType: 'Task', aggregateId: 'task', aggregateVersion: 1,
      correlationId: 'correlation', causationId: null, occurredAt, payload,
    },
  }
}
