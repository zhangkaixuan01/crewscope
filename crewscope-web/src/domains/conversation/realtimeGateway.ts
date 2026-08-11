import { apiClient, type CrewScopeApiClient } from '../../api/client'
import { parseServerSentEvents } from '../../api/sse'
import type {
  AgentCancelResponse,
  AgentInvocationInput,
  ClarificationResumeInput,
  ConversationMessageScope,
  RealtimeEventEnvelope,
} from './types'

export interface RealtimeConnection {
  invocationId: string | null
  replayed: boolean
  events: AsyncIterable<RealtimeStreamItem>
}

export interface RealtimeStreamItem {
  cursor: string | null
  event: RealtimeEventEnvelope
}

export interface ConversationRealtimeGateway {
  invoke(
    scope: ConversationMessageScope,
    input: AgentInvocationInput,
    idempotencyKey: string,
    signal?: AbortSignal,
  ): Promise<RealtimeConnection>
  resume(
    scope: ConversationMessageScope,
    invocationId: string,
    input: ClarificationResumeInput,
    idempotencyKey: string,
    signal?: AbortSignal,
  ): Promise<RealtimeConnection>
  streamEvents(
    scope: ConversationMessageScope,
    after?: string,
    signal?: AbortSignal,
  ): Promise<RealtimeConnection>
  cancel(
    scope: ConversationMessageScope,
    invocationId: string,
    reason: string,
    idempotencyKey: string,
    signal?: AbortSignal,
  ): Promise<AgentCancelResponse>
}

/** Fetch-based SSE adapter; POST invocation streams cannot use the browser EventSource API. */
export class HttpConversationRealtimeGateway implements ConversationRealtimeGateway {
  constructor(private readonly client: CrewScopeApiClient = apiClient) {}

  async invoke(
    scope: ConversationMessageScope,
    input: AgentInvocationInput,
    idempotencyKey: string,
    signal?: AbortSignal,
  ): Promise<RealtimeConnection> {
    const response = await this.client.open(
      `${root(scope)}/agent-invocations`,
      { method: 'POST', body: input, idempotencyKey, signal },
      'text/event-stream',
    )
    return connection(response, true)
  }

  async streamEvents(
    scope: ConversationMessageScope,
    after?: string,
    signal?: AbortSignal,
  ): Promise<RealtimeConnection> {
    const search = new URLSearchParams()
    if (after) search.set('after', after)
    const suffix = search.size > 0 ? `?${search.toString()}` : ''
    const response = await this.client.open(
      `${root(scope)}/events${suffix}`,
      { method: 'GET', signal },
      'text/event-stream',
    )
    return connection(response, false)
  }

  async resume(
    scope: ConversationMessageScope,
    invocationId: string,
    input: ClarificationResumeInput,
    idempotencyKey: string,
    signal?: AbortSignal,
  ): Promise<RealtimeConnection> {
    const response = await this.client.open(
      `${root(scope)}/agent-invocations/${segment(invocationId)}/resume`,
      { method: 'POST', body: input, idempotencyKey, signal },
      'text/event-stream',
    )
    return connection(response, true)
  }

  cancel(
    scope: ConversationMessageScope,
    invocationId: string,
    reason: string,
    idempotencyKey: string,
    signal?: AbortSignal,
  ): Promise<AgentCancelResponse> {
    return this.client.post(
      `${root(scope)}/agent-invocations/${encodeURIComponent(invocationId)}/cancel`,
      { reason },
      { idempotencyKey, signal },
    )
  }
}

function connection(response: Response, invocation: boolean): RealtimeConnection {
  if (!response.body) throw new TypeError('SSE response body is unavailable')
  return {
    invocationId: invocation ? response.headers.get('X-CrewScope-Invocation-Id') : null,
    replayed: response.headers.get('Idempotency-Replayed') === 'true',
    events: jsonEvents(response.body),
  }
}

async function* jsonEvents(body: ReadableStream<Uint8Array>): AsyncGenerator<RealtimeStreamItem> {
  for await (const frame of parseServerSentEvents(body)) {
    const value = JSON.parse(frame.data) as RealtimeEventEnvelope
    if (!value || typeof value !== 'object' || typeof value.eventId !== 'string' || typeof value.eventType !== 'string') {
      throw new TypeError('Invalid CrewScope realtime event')
    }
    yield { cursor: frame.id, event: value }
  }
}

function root(scope: ConversationMessageScope): string {
  return `/organizations/${segment(scope.organizationId)}/teams/${segment(scope.teamId)}/conversations/${segment(scope.conversationId)}`
}

function segment(value: string): string {
  return encodeURIComponent(value)
}
