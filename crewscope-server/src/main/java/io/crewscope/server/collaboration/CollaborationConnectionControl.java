package io.crewscope.server.collaboration;

import org.springframework.web.reactive.socket.CloseStatus;

/**
 * One connection's close trigger (M11-A01), bound by the handler to {@code
 * session::close}. The fanout must be able to force a connection offline — the
 * authorization-revocation close (4403) and the sustained rate-limit close (1013) —
 * without taking a dependency on the WebSocket session type.
 */
@FunctionalInterface
public interface CollaborationConnectionControl {

  /** Requests the close; implementations must be safe to call from any thread. */
  void close(CloseStatus status);
}
