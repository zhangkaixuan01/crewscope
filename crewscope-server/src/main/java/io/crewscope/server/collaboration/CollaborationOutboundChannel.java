package io.crewscope.server.collaboration;

import java.util.concurrent.ArrayBlockingQueue;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.core.publisher.Sinks.EmitResult;

/**
 * Per-connection bounded outbound frame queue (M11-I01a).
 *
 * <p>The S01b probe established that Reactor Netty's WebSocket send path does not surface
 * channel writability as consumer stall on the sink, so library backpressure cannot protect
 * the node against a slow client. The explicit application-level budget here is the
 * replacement: each connection gets a fixed frame buffer, and the first frame that does not
 * fit marks the channel over budget — the handler then closes that connection (1013) while
 * every other connection keeps its own independent channel.
 */
public final class CollaborationOutboundChannel {

  /** Result of one delivery attempt. */
  public enum Delivery {
    EMITTED,
    OVER_BUDGET,
    CLOSED
  }

  private final Sinks.Many<String> frames;
  private boolean closed;

  public CollaborationOutboundChannel(int frameBufferLimit) {
    if (frameBufferLimit < 1) {
      throw new IllegalArgumentException("frameBufferLimit must be positive");
    }
    // ArrayBlockingQueue rather than Queues.get: Reactor's factory rounds small capacities up
    // to 8, which would silently inflate injected test budgets. The budget must be exact.
    this.frames =
        Sinks.many()
            .unicast()
            .onBackpressureBuffer(new ArrayBlockingQueue<String>(frameBufferLimit));
  }

  /**
   * Offers one frame. Serialized on an internal monitor because heartbeat and business emitters
   * may race; the critical section contains no blocking work.
   */
  public synchronized Delivery offer(String frame) {
    if (closed) {
      return Delivery.CLOSED;
    }
    EmitResult result = frames.tryEmitNext(frame);
    // FAIL_OVERFLOW: a subscriber is attached but the buffer is full (slow consumer).
    // FAIL_ZERO_SUBSCRIBER: no subscriber attached yet and the buffer is already full
    // (e.g. frames queued during handshake). Both mean unconfirmed frames exceed budget.
    if (result == EmitResult.FAIL_OVERFLOW || result == EmitResult.FAIL_ZERO_SUBSCRIBER) {
      return Delivery.OVER_BUDGET;
    }
    if (result == EmitResult.FAIL_TERMINATED || result == EmitResult.FAIL_CANCELLED) {
      return Delivery.CLOSED;
    }
    if (result.isFailure()) {
      // Non-serialized cannot happen under the monitor; treat any other failure as terminal.
      return Delivery.CLOSED;
    }
    return Delivery.EMITTED;
  }

  /** Frame stream the handler wires to {@code session.send()}; completes exactly once. */
  public Flux<String> stream() {
    return frames.asFlux();
  }

  /** Completes the channel; subsequent offers report {@link Delivery#CLOSED}. */
  public synchronized void close() {
    if (!closed) {
      closed = true;
      frames.tryEmitComplete();
    }
  }
}
