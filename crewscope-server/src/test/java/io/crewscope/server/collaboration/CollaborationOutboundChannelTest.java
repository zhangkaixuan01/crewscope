package io.crewscope.server.collaboration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.crewscope.server.collaboration.CollaborationOutboundChannel.Delivery;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import org.junit.jupiter.api.Test;

/**
 * Budget semantics of the per-connection outbound channel: the frame buffer is the slow-client
 * backstop because the WebSocket send path never reports channel writability as backpressure
 * (M11-S01b finding recorded in ADR-032 and presence-ttl.md).
 */
class CollaborationOutboundChannelTest {

  @Test
  void reportsOverBudgetWhenNoSubscriberDrainsTheBuffer() {
    CollaborationOutboundChannel channel = new CollaborationOutboundChannel(2);

    assertThat(channel.offer("f1")).isEqualTo(Delivery.EMITTED);
    assertThat(channel.offer("f2")).isEqualTo(Delivery.EMITTED);
    assertThat(channel.offer("f3")).isEqualTo(Delivery.OVER_BUDGET);

    CompletableFuture<List<String>> received = channel.stream().collectList().toFuture();
    assertThat(received).isNotDone();
    channel.close();
    // Frames queued before the over-budget signal still drain in order.
    assertThat(received).isCompletedWithValue(List.of("f1", "f2"));
  }

  @Test
  void deliversInOrderToASubscriberThatJoinsAfterOffers() {
    CollaborationOutboundChannel channel = new CollaborationOutboundChannel(4);

    assertThat(channel.offer("f1")).isEqualTo(Delivery.EMITTED);
    assertThat(channel.offer("f2")).isEqualTo(Delivery.EMITTED);

    CompletableFuture<List<String>> received = channel.stream().collectList().toFuture();
    assertThat(received).isNotDone();
    channel.close();
    assertThat(received).isCompletedWithValue(List.of("f1", "f2"));
  }

  @Test
  void closingTheChannelCompletesTheStreamAndRejectsFurtherOffers() {
    CollaborationOutboundChannel channel = new CollaborationOutboundChannel(4);

    assertThat(channel.offer("f1")).isEqualTo(Delivery.EMITTED);
    CompletableFuture<List<String>> received = channel.stream().collectList().toFuture();
    channel.close();
    channel.close();

    assertThat(received).isCompletedWithValue(List.of("f1"));
    assertThat(channel.offer("f2")).isEqualTo(Delivery.CLOSED);
  }

  @Test
  void offersAfterCloseReportClosedWithoutAffectingTheSubscriber() {
    CollaborationOutboundChannel channel = new CollaborationOutboundChannel(1);

    CompletableFuture<List<String>> received = channel.stream().collectList().toFuture();
    channel.close();

    assertThat(channel.offer("f1")).isEqualTo(Delivery.CLOSED);
    assertThat(received).isCompletedWithValue(List.of());
  }

  @Test
  void streamAcceptsExactlyOneSubscriber() {
    CollaborationOutboundChannel channel = new CollaborationOutboundChannel(2);

    CompletableFuture<List<String>> first = channel.stream().collectList().toFuture();
    // The sink rejects the second subscription asynchronously (the future completes
    // exceptionally on subscribe), not by throwing at the call site.
    CompletableFuture<List<String>> second = channel.stream().collectList().toFuture();

    assertThat(second).isCompletedExceptionally()
        .failsWithin(Duration.ofMillis(0))
        .withThrowableOfType(ExecutionException.class)
        .withCauseInstanceOf(IllegalStateException.class);
    channel.close();
    assertThat(first).isCompletedWithValue(List.of());
  }

  @Test
  void constructorRejectsNonPositiveBudget() {
    assertThatThrownBy(() -> new CollaborationOutboundChannel(0))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
