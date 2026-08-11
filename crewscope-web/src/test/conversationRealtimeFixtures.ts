import type { ConversationRealtimeGateway, RealtimeConnection } from '../domains/conversation/realtimeGateway'
import type { AgentCancelResponse, ConversationMessageScope } from '../domains/conversation/types'

/** Quiet realtime adapter for component and router tests that do not exercise streaming behavior. */
export class FixtureConversationRealtimeGateway implements ConversationRealtimeGateway {
  async invoke(): Promise<RealtimeConnection> {
    return { invocationId: crypto.randomUUID(), replayed: false, events: emptyEvents() }
  }

  async resume(): Promise<RealtimeConnection> {
    return { invocationId: crypto.randomUUID(), replayed: false, events: emptyEvents() }
  }

  async streamEvents(_scope: ConversationMessageScope, _after?: string, signal?: AbortSignal): Promise<RealtimeConnection> {
    return { invocationId: null, replayed: false, events: waitingEvents(signal) }
  }

  async cancel(_scope: ConversationMessageScope, invocationId: string): Promise<AgentCancelResponse> {
    return { invocationId, result: 'ACCEPTED', correlationId: crypto.randomUUID() }
  }
}

async function* emptyEvents() {
  // A finite empty stream represents an invocation fixture without public events.
}

async function* waitingEvents(signal?: AbortSignal) {
  await new Promise<void>(resolve => {
    if (signal?.aborted) return resolve()
    signal?.addEventListener('abort', () => resolve(), { once: true })
  })
}
