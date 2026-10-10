import type { CollaborationGateway, CollaborationGatewayHandlers } from '../domains/collaboration/gateway'
import { createCollaborationStore, type CollaborationStore } from '../domains/collaboration/store'

/**
 * A silent signal channel for page-level fixtures (M11-F01): the store connects but
 * the gateway never delivers a frame, so presence/typing stay empty and the page
 * renders exactly as before the collaboration strip existed.
 */
export class FixtureCollaborationGateway implements CollaborationGateway {
  connect() {
    return { send: () => {}, close: () => {} }
  }
}

export function createFixtureCollaborationStore(): CollaborationStore {
  return createCollaborationStore(new FixtureCollaborationGateway())
}

/**
 * A gateway that keeps the connected handlers reachable, so integration tests can
 * deliver contract frames into a real store and watch the page react.
 */
export class ScriptedCollaborationGateway implements CollaborationGateway {
  private handlers: CollaborationGatewayHandlers | null = null
  readonly sent: string[] = []

  connect(handlers: CollaborationGatewayHandlers) {
    this.handlers = handlers
    return { send: (text: string) => this.sent.push(text), close: () => {} }
  }

  deliver(frame: object): void {
    this.handlers?.onMessage(JSON.stringify(frame))
  }
}

export function createScriptedCollaborationStore(): {
  store: CollaborationStore
  gateway: ScriptedCollaborationGateway
} {
  const gateway = new ScriptedCollaborationGateway()
  return { store: createCollaborationStore(gateway), gateway }
}
