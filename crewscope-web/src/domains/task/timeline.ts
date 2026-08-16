import type { SemanticTone } from '../../components/base/types'
import type { TaskEventItem } from './types'

export interface TaskTimelineEntry {
  id: string
  domainEventId: string | null
  executionId: string | null
  occurredAt: string
  title: string
  summary: string | null
  meta: string | null
  tone: SemanticTone
  progressPercent: number | null
  recovery: boolean
}

export interface TaskProgressProjection {
  percent: number | null
  summary: string | null
  occurredAt: string
  source: 'WORKER' | 'AGENT_RUN'
}

/** Maps the durable public stream to compact team-facing facts without exposing runtime coordinates. */
export function taskTimeline(events: readonly TaskEventItem[], executionId?: string | null): TaskTimelineEntry[] {
  const seenEvents = new Set<string>()
  const seenDomains = new Set<string>()
  const result: TaskTimelineEntry[] = []
  for (const item of events) {
    if (seenEvents.has(item.event.eventId)) continue
    if (item.event.domainEventId && seenDomains.has(item.event.domainEventId)) continue
    seenEvents.add(item.event.eventId)
    if (item.event.domainEventId) seenDomains.add(item.event.domainEventId)
    if (executionId && item.context.taskExecutionId && item.context.taskExecutionId !== executionId) continue
    const entry = mapEntry(item)
    if (entry) result.push(entry)
  }
  // Input order is the authoritative durable stream order. occurredAt may legitimately move
  // backwards when delayed Runtime events are committed, so it must not be used for sorting.
  return result
}

export function latestTaskProgress(entries: readonly TaskTimelineEntry[]): TaskProgressProjection | null {
  for (let index = entries.length - 1; index >= 0; index -= 1) {
    const entry = entries[index]!
    if (entry.progressPercent === null && !entry.summary) continue
    const source = entry.title.includes('Agent') ? 'AGENT_RUN' : 'WORKER'
    if (entry.progressPercent !== null || entry.title.includes('执行进度') || entry.title.includes('Agent 进度')) {
      return { percent: entry.progressPercent, summary: entry.summary, occurredAt: entry.occurredAt, source }
    }
  }
  return null
}

function mapEntry(item: TaskEventItem): TaskTimelineEntry | null {
  const type = item.event.eventType
  const payload = item.event.payload
  const base = {
    id: item.event.eventId,
    domainEventId: item.event.domainEventId,
    executionId: item.context.taskExecutionId,
    occurredAt: item.event.occurredAt,
    progressPercent: percent(payload.progressPercent),
    recovery: false,
  }
  if (type === 'WORKER_TASK_HEARTBEAT_ACCEPTED') return null
  if (type === 'TASK_DELEGATED_TO_AGENT') {
    return { ...base, title: 'Task 已委托给 Agent', summary: text(payload.objective), meta: attempt(payload), tone: 'agent' }
  }
  if (type.startsWith('MEMBER_TASK_')) {
    const operation = text(payload.operation) ?? type.replace('MEMBER_TASK_', '').replace('_ACCEPTED', '')
    const labels: Record<string, string> = { PAUSE: '已请求暂停', RESUME: '已请求恢复', CANCEL: '已请求取消', RETRY: '已创建重试' }
    const successor = integer(payload.successorAttempt)
    return {
      ...base,
      title: labels[operation] ?? '成员控制已受理',
      summary: successor === null ? null : `后继 Attempt ${successor}`,
      meta: attempt(payload),
      tone: operation === 'CANCEL' ? 'danger' : operation === 'PAUSE' ? 'warning' : 'info',
    }
  }
  if (type === 'TASK_EXECUTION_RECOVERY_STARTED') {
    return {
      ...base,
      title: '执行进入恢复',
      summary: text(payload.expiredPhase) ? `从 ${text(payload.expiredPhase)} 阶段恢复` : '正在恢复最近的耐久检查点',
      meta: attempt(payload),
      tone: 'warning',
      recovery: true,
    }
  }
  if (type === 'AGENT_RUN_RESUMED') {
    return { ...base, title: 'AgentRun 已恢复', summary: '从已确认的中断继续执行', meta: null, tone: 'info', recovery: true }
  }
  if (type.startsWith('WORKER_TASK_')) return workerEntry(type, payload, base)
  if (type === 'AGENT_RUN_EVENT_RECORDED') return agentEntry(payload, base)
  return { ...base, title: 'Task 事实已更新', summary: null, meta: null, tone: 'neutral' }
}

function workerEntry(
  type: string,
  payload: Record<string, unknown>,
  base: Pick<TaskTimelineEntry, 'id' | 'domainEventId' | 'executionId' | 'occurredAt' | 'progressPercent' | 'recovery'>,
): TaskTimelineEntry {
  const operation = text(payload.operation) ?? type.replace('WORKER_TASK_', '').replace('_ACCEPTED', '')
  const labels: Record<string, [string, SemanticTone]> = {
    PREPARE: ['Runtime 开始准备', 'info'], START: ['执行已开始', 'agent'],
    PROGRESS: ['执行进度已更新', 'info'], COMPLETE: ['执行已完成', 'success'], FAIL: ['执行失败', 'danger'],
  }
  const [title, tone] = labels[operation] ?? ['执行事实已更新', 'neutral']
  return {
    ...base,
    title,
    summary: text(payload.safeSummary) ?? failureSummary(payload),
    meta: attempt(payload),
    tone,
  }
}

function agentEntry(
  payload: Record<string, unknown>,
  base: Pick<TaskTimelineEntry, 'id' | 'domainEventId' | 'executionId' | 'occurredAt' | 'progressPercent' | 'recovery'>,
): TaskTimelineEntry | null {
  const kind = text(payload.eventKind) ?? 'UPDATED'
  if (kind === 'TEXT_DELTA' || kind === 'USAGE_REPORTED') return null
  const labels: Record<string, [string, SemanticTone]> = {
    STARTED: ['AgentRun 已开始', 'agent'], THINKING_SUMMARY: ['Agent 阶段摘要', 'info'],
    STRUCTURED_OUTPUT: ['Agent 生成结构化结果', 'info'], PLAN_CHANGED: ['执行计划已更新', 'info'],
    TOOL_STARTED: ['工具开始执行', 'neutral'], TOOL_RESULT: ['工具执行完成', boolean(payload.succeeded) === false ? 'danger' : 'success'],
    PROGRESS: ['Agent 进度已更新', 'info'], ARTIFACT_CREATED: ['执行制品已生成', 'success'],
    APPROVAL_REQUIRED: ['执行等待确认', 'warning'], STATUS_CHANGED: ['Agent 状态已变化', 'info'],
    MODEL_TRANSITION: ['模型执行已切换', 'warning'], COMPLETED: ['AgentRun 已完成', 'success'],
    PAUSED: ['AgentRun 已暂停', 'warning'], CANCELED: ['AgentRun 已取消', 'danger'], FAILED: ['AgentRun 失败', 'danger'],
  }
  const [title, tone] = labels[kind] ?? ['AgentRun 事实已更新', 'neutral']
  const name = text(payload.name)
  const safeText = text(payload.safeText)
  const status = text(payload.status)
  return {
    ...base,
    title,
    summary: safeText ?? failureSummary(payload) ?? (name ? `${name}${status ? ` · ${status}` : ''}` : status),
    meta: attempt(payload),
    tone,
  }
}

function failureSummary(payload: Record<string, unknown>): string | null {
  const nested = payload.failure
  if (nested && typeof nested === 'object' && !Array.isArray(nested)) {
    return text((nested as Record<string, unknown>).safeMessage)
      ?? text((nested as Record<string, unknown>).category)
  }
  return text(payload.failureClass) ?? text(payload.failureCode)
}

function attempt(payload: Record<string, unknown>): string | null {
  const value = integer(payload.attempt) ?? integer(payload.targetAttempt)
  return value === null ? null : `Attempt ${value}`
}

function text(value: unknown): string | null {
  return typeof value === 'string' && value.trim() ? value.trim() : null
}

function integer(value: unknown): number | null {
  return typeof value === 'number' && Number.isInteger(value) ? value : null
}

function percent(value: unknown): number | null {
  return typeof value === 'number' && Number.isInteger(value) && value >= 0 && value <= 100 ? value : null
}

function boolean(value: unknown): boolean | null {
  return typeof value === 'boolean' ? value : null
}
