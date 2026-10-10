/**
 * WebSocket transport for the M11 collaboration signal channel
 * (docs/api/M11-协作信号API契约.md §1.1).
 *
 * The socket factory is injected: jsdom ships no WebSocket, so unit tests drive a
 * fake while production uses the browser constructor against the same-origin URL.
 * The gateway owns no protocol state — frame parsing and reconnection policy live
 * in the store.
 */

const COLLABORATION_WS_PATH = '/api/v1/collaboration/ws'

/** The slice of the browser WebSocket the protocol needs (a superset of what jsdom lacks). */
export interface RawCollaborationSocket {
  send(text: string): void
  close(code?: number, reason?: string): void
  onopen: (() => void) | null
  onmessage: ((event: { data: unknown }) => void) | null
  onclose: ((event: { code?: number; reason?: string }) => void) | null
  onerror: (() => void) | null
}

export type CollaborationSocketFactory = (url: string) => RawCollaborationSocket

export interface CollaborationGatewayHandlers {
  onMessage(text: string): void
  /** A failed handshake (HTTP 404/401) surfaces as close 1006 — the reconnect policy owns it. */
  onClose(code: number, reason: string): void
}

export interface CollaborationSocket {
  send(text: string): void
  close(): void
}

export interface CollaborationGateway {
  connect(handlers: CollaborationGatewayHandlers): CollaborationSocket
}

export function collaborationWebSocketUrl(protocol: string, host: string): string {
  return `${protocol === 'https:' ? 'wss:' : 'ws:'}//${host}${COLLABORATION_WS_PATH}`
}

export function createCollaborationGateway(
  options: { url?: string; socketFactory?: CollaborationSocketFactory } = {},
): CollaborationGateway {
  const url = options.url ?? collaborationWebSocketUrl(window.location.protocol, window.location.host)
  const openSocket = options.socketFactory ?? (target => new WebSocket(target) as RawCollaborationSocket)
  return {
    connect(handlers) {
      const socket = openSocket(url)
      socket.onopen = () => {}
      socket.onerror = () => {}
      socket.onmessage = event => handlers.onMessage(String(event.data))
      // Abnormal closures (network failure, rejected handshake) arrive with no code —
      // normalize to 1006, the browser-visible shape the contract's degraded stance uses.
      socket.onclose = event => handlers.onClose(event.code ?? 1006, event.reason ?? '')
      return {
        send: text => socket.send(text),
        close: () => socket.close(1000, 'client shutdown'),
      }
    },
  }
}
