import { apiClient, type CrewScopeApiClient } from '../../api/client'
import { parseServerSentEvents } from '../../api/sse'
import type {
  TeamObserverCancelResponse,
  TeamObserverEvidence,
  TeamObserverEvent,
  TeamObserverScope,
  TeamObserverSession,
  TeamSummary,
  TeamSummaryEntry,
} from './types'

export interface TeamObserverConnection {
  invocationId: string
  resumed: boolean
  events: AsyncIterable<TeamObserverEvent>
}

export interface TeamObserverGateway {
  createSession(scope: TeamObserverScope, signal?: AbortSignal): Promise<TeamObserverSession>
  invoke(scope: TeamObserverScope, sessionId: string, instruction: string, maxItemsPerSection: number, signal?: AbortSignal): Promise<TeamObserverConnection>
  resume(scope: TeamObserverScope, sessionId: string, invocationId: string, signal?: AbortSignal): Promise<TeamObserverConnection>
  cancel(scope: TeamObserverScope, sessionId: string, invocationId: string, signal?: AbortSignal): Promise<TeamObserverCancelResponse>
  summary(scope: TeamObserverScope, sessionId: string, invocationId: string, signal?: AbortSignal): Promise<TeamSummary>
  evidence(scope: TeamObserverScope, sessionId: string, invocationId: string, evidenceIndex: number, signal?: AbortSignal): Promise<TeamObserverEvidence>
}

/** Strict allowlist adapter for the fixed Team Observer HTTP/SSE contract. */
export class HttpTeamObserverGateway implements TeamObserverGateway {
  constructor(private readonly client: CrewScopeApiClient = apiClient) {}

  async createSession(scope: TeamObserverScope, signal?: AbortSignal): Promise<TeamObserverSession> {
    return mapSession(await this.client.request(`${root(scope)}/sessions`, { method: 'POST', signal }))
  }

  async invoke(scope: TeamObserverScope, sessionId: string, instruction: string, maxItemsPerSection: number, signal?: AbortSignal): Promise<TeamObserverConnection> {
    const response = await this.client.open(
      `${root(scope)}/sessions/${segment(sessionId)}/invocations`,
      { method: 'POST', body: { instruction, maxItemsPerSection }, signal },
      'text/event-stream',
    )
    return connection(response)
  }

  async resume(scope: TeamObserverScope, sessionId: string, invocationId: string, signal?: AbortSignal): Promise<TeamObserverConnection> {
    const response = await this.client.open(
      `${root(scope)}/sessions/${segment(sessionId)}/invocations/${segment(invocationId)}/resume`,
      { method: 'POST', signal },
      'text/event-stream',
    )
    return connection(response)
  }

  cancel(scope: TeamObserverScope, sessionId: string, invocationId: string, signal?: AbortSignal): Promise<TeamObserverCancelResponse> {
    return this.client.request(`${root(scope)}/sessions/${segment(sessionId)}/invocations/${segment(invocationId)}/cancel`, { method: 'POST', signal })
  }

  async summary(scope: TeamObserverScope, sessionId: string, invocationId: string, signal?: AbortSignal): Promise<TeamSummary> {
    return mapSummary(await this.client.get(`${root(scope)}/sessions/${segment(sessionId)}/invocations/${segment(invocationId)}/summary`, { signal }))
  }

  async evidence(scope: TeamObserverScope, sessionId: string, invocationId: string, evidenceIndex: number, signal?: AbortSignal): Promise<TeamObserverEvidence> {
    const value = record(await this.client.get(`${root(scope)}/sessions/${segment(sessionId)}/invocations/${segment(invocationId)}/evidence/${evidenceIndex}`, { signal }))
    const path = string(value.path)
    const navigationPath = evidenceNavigationPath(path, scope)
    if (value.authorized !== true || integer(value.evidenceIndex) !== evidenceIndex || !navigationPath) throw new TypeError('Unsafe Team Observer evidence response')
    return {
      evidenceIndex: integer(value.evidenceIndex), section: string(value.section), dataScope: string(value.dataScope),
      summary: string(value.summary), path, navigationPath, authorized: true,
    }
  }
}

async function connection(response: Response): Promise<TeamObserverConnection> {
  if (!response.body) throw new TypeError('Team Observer SSE body is unavailable')
  const invocationId = response.headers.get('X-CrewScope-Invocation-Id')
  if (!invocationId) throw new TypeError('Team Observer invocation identity is unavailable')
  return {
    invocationId,
    resumed: response.headers.get('X-CrewScope-Stream-Resumed') === 'true',
    events: events(response.body, invocationId),
  }
}

async function* events(body: ReadableStream<Uint8Array>, invocationId: string): AsyncGenerator<TeamObserverEvent> {
  for await (const frame of parseServerSentEvents(body)) {
    const value = record(JSON.parse(frame.data))
    const type = string(value.type)
    if (!['STARTED', 'SUMMARY_COMPLETED', 'CANCELLED', 'FAILED'].includes(type)) throw new TypeError('Invalid Team Observer event type')
    const event = {
      invocationId: string(value.invocationId), sequence: integer(value.sequence), occurredAt: string(value.occurredAt),
      type: type as TeamObserverEvent['type'], summary: value.summary == null ? null : mapSummary(value.summary),
      errorCode: value.errorCode == null ? null : string(value.errorCode),
    }
    if (event.invocationId !== invocationId) throw new TypeError('Team Observer event escaped its invocation')
    yield event
  }
}

function mapSession(input: unknown): TeamObserverSession {
  const value = record(input)
  if (value.mode !== 'READ_ONLY') throw new TypeError('Team Observer session is not read-only')
  return { sessionId: string(value.sessionId), observerProfileId: string(value.observerProfileId), mode: 'READ_ONLY', createdAt: string(value.createdAt) }
}

function mapSummary(input: unknown): TeamSummary {
  const value = record(input)
  const summary = {
    observerProfileId: string(value.observerProfileId), generatedAt: string(value.generatedAt),
    progress: entries(value.progress, 'PROGRESS'), blockers: entries(value.blockers, 'BLOCKERS'), reviewBacklog: entries(value.reviewBacklog, 'REVIEW_BACKLOG'),
    pendingConfirmations: entries(value.pendingConfirmations, 'PENDING_CONFIRMATIONS'), anomalies: entries(value.anomalies, 'ANOMALIES'),
  }
  const indexes = Object.values(summary).flatMap(value => Array.isArray(value) ? value.map(item => item.evidenceIndex) : [])
  if (new Set(indexes).size !== indexes.length || [...indexes].sort((left, right) => left - right).some((value, index) => value !== index)) {
    throw new TypeError('Invalid Team Observer evidence identity')
  }
  return summary
}

function entries(input: unknown, expectedSection: string): TeamSummaryEntry[] {
  if (!Array.isArray(input) || input.length > 50) throw new TypeError('Invalid Team Observer summary section')
  return input.map(item => {
    const value = record(item)
    const section = string(value.section)
    const dataScope = string(value.dataScope)
    const summary = string(value.summary)
    if (section !== expectedSection
      || !['TEAM_ACTIVITY', 'TEAM_INBOX_SUMMARY', 'WORK_ITEM_SUMMARY', 'TASK_SUMMARY', 'ARTIFACT_SUMMARY'].includes(dataScope)
      || summary.length < 1 || summary.length > 1_000) throw new TypeError('Invalid Team Observer summary entry')
    return { section, dataScope, summary, evidenceIndex: integer(value.evidenceIndex) }
  })
}

/** Only approved application routes may become navigable evidence. */
export function safeInternalPath(path: string): boolean {
  return path.startsWith('/') && !path.startsWith('//') && !path.includes('..') && !path.includes('\\') && !/[\s?#%]/.test(path)
}

/** Converts the exact authorized API resource path into a browser route for the active Team. */
export function evidenceNavigationPath(path: string, scope: TeamObserverScope): string | null {
  if (!safeInternalPath(path)) return null
  const prefix = `/api/v1/organizations/${scope.organizationId}/teams/${scope.teamId}`
  if (!path.startsWith(`${prefix}/`)) return null
  const suffix = path.slice(prefix.length)
  const activity = /^\/activity\/([0-9a-f-]{36})$/i.exec(suffix)
  if (activity) return target('/activity', { team: scope.teamId, event: activity[1]! })
  const inbox = /^\/inbox\/([0-9a-f-]{36})$/i.exec(suffix)
  if (inbox) return target('/inbox', { team: scope.teamId, inboxItem: inbox[1]! })
  const work = /^\/work-projects\/([0-9a-f-]{36})\/work-items\/([0-9a-f-]{36})$/i.exec(suffix)
  if (work) return target('/work', { team: scope.teamId, project: work[1]!, workItem: work[2]! })
  const task = /^\/tasks\/([0-9a-f-]{36})$/i.exec(suffix)
  if (task) return target('/work', { team: scope.teamId, task: task[1]! })
  return null
}

function target(path: string, query: Record<string, string>): string { return `${path}?${new URLSearchParams(query)}` }

function root(scope: TeamObserverScope): string {
  return `/organizations/${segment(scope.organizationId)}/teams/${segment(scope.teamId)}/team-observer`
}
function segment(value: string): string { return encodeURIComponent(value) }
function record(value: unknown): Record<string, unknown> { if (!value || typeof value !== 'object' || Array.isArray(value)) throw new TypeError('Invalid Team Observer response'); return value as Record<string, unknown> }
function string(value: unknown): string { if (typeof value !== 'string') throw new TypeError('Invalid Team Observer text'); return value }
function integer(value: unknown): number { if (!Number.isInteger(value) || Number(value) < 0) throw new TypeError('Invalid Team Observer number'); return Number(value) }
