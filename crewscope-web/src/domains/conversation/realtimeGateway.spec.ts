import { CrewScopeApiClient } from '../../api/client'
import { conversationIds } from '../../test/conversationFixtures'
import { fixtureIds } from '../../test/scopeFixtures'
import { HttpConversationRealtimeGateway } from './realtimeGateway'

const scope = {
  organizationId: fixtureIds.organization,
  teamId: fixtureIds.teamPlatform,
  conversationId: conversationIds.provider,
}

describe('HttpConversationRealtimeGateway', () => {
  it('opens a POST invocation stream with only the public message and Idempotency-Key', async () => {
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(sseResponse([
      frame('event-1', 'RUN_STARTED', envelope('event-1', 'AG_UI', 'RUN_STARTED', { segmentId: 'segment-1' })),
    ], { 'X-CrewScope-Invocation-Id': 'invocation-1' }))
    const gateway = new HttpConversationRealtimeGateway(new CrewScopeApiClient('/api/v1', fetcher))

    const connection = await gateway.invoke(scope, { message: 'Review release' }, 'invoke-key')
    const events = []
    for await (const event of connection.events) events.push(event)

    const [url, request] = fetcher.mock.calls[0]!
    expect(url).toContain(`/conversations/${conversationIds.provider}/agent-invocations`)
    expect(new Headers(request?.headers).get('Accept')).toBe('text/event-stream')
    expect(new Headers(request?.headers).get('Idempotency-Key')).toBe('invoke-key')
    expect(request?.body).toBe(JSON.stringify({ message: 'Review release' }))
    expect(connection.invocationId).toBe('invocation-1')
    expect(events[0]).toEqual(expect.objectContaining({ cursor: 'event-1', event: expect.objectContaining({ eventType: 'RUN_STARTED' }) }))
  })

  it('resumes durable events with the opaque Cursor and cancels by explicit invocation ID', async () => {
    const fetcher = vi.fn<typeof fetch>()
      .mockResolvedValueOnce(sseResponse([]))
      .mockResolvedValueOnce(new Response(JSON.stringify({ invocationId: 'invocation-1', result: 'ACCEPTED', correlationId: 'corr-1' }), { status: 202 }))
    const gateway = new HttpConversationRealtimeGateway(new CrewScopeApiClient('/api/v1', fetcher))

    await gateway.streamEvents(scope, 'opaque+/cursor')
    await gateway.cancel(scope, 'invocation-1', 'Owner requested cancellation', 'cancel-key')

    const eventUrl = new URL(String(fetcher.mock.calls[0]?.[0]), 'http://crewscope.test')
    expect(eventUrl.searchParams.get('after')).toBe('opaque+/cursor')
    expect(new Headers(fetcher.mock.calls[0]?.[1]?.headers).get('Accept')).toBe('text/event-stream')
    expect(String(fetcher.mock.calls[1]?.[0])).toContain('/agent-invocations/invocation-1/cancel')
    expect(new Headers(fetcher.mock.calls[1]?.[1]?.headers).get('Idempotency-Key')).toBe('cancel-key')
  })

  it('resumes a clarification with field-keyed answers and no runtime coordinates in the body', async () => {
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(sseResponse([], { 'X-CrewScope-Invocation-Id': 'invocation-1' }))
    const gateway = new HttpConversationRealtimeGateway(new CrewScopeApiClient('/api/v1', fetcher))

    await gateway.resume(scope, 'invocation-1', { answers: { repository: 'crewscope-java' } }, 'resume-key')

    expect(String(fetcher.mock.calls[0]?.[0])).toContain('/agent-invocations/invocation-1/resume')
    expect(fetcher.mock.calls[0]?.[1]?.body).toBe(JSON.stringify({ answers: { repository: 'crewscope-java' } }))
    expect(fetcher.mock.calls[0]?.[1]?.body).not.toContain('interruptToken')
  })
})

function envelope(eventId: string, streamType: string, eventType: string, payload: Record<string, unknown>) {
  return { eventId, domainEventId: null, streamType, eventType, schemaVersion: 'v1', aggregateType: null, aggregateId: null, aggregateVersion: null, correlationId: 'corr-1', causationId: null, occurredAt: '2026-08-11T00:00:00Z', payload }
}

function frame(id: string, event: string, data: unknown): string {
  return `id:${id}\nevent:${event}\ndata:${JSON.stringify(data)}\n\n`
}

function sseResponse(chunks: string[], headers: Record<string, string> = {}): Response {
  const encoder = new TextEncoder()
  const body = new ReadableStream<Uint8Array>({
    start(controller) {
      chunks.forEach(chunk => controller.enqueue(encoder.encode(chunk)))
      controller.close()
    },
  })
  return new Response(body, { status: 200, headers: { 'Content-Type': 'text/event-stream', ...headers } })
}
