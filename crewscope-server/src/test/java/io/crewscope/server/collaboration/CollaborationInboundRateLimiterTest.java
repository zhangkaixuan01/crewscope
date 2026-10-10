package io.crewscope.server.collaboration;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/** Token bucket and escalation window of the per-connection inbound budget. */
class CollaborationInboundRateLimiterTest {

  private static final Instant T0 = Instant.parse("2026-10-10T08:00:00Z");

  @Test
  void allowsBurstsUpToTheBucketCapacityThenRefillsOverTime() {
    CollaborationInboundRateLimiter limiter = new CollaborationInboundRateLimiter(5, T0);

    for (int i = 0; i < 5; i++) {
      assertEquals(CollaborationInboundRateLimiter.Verdict.ALLOWED, limiter.acquire(T0));
    }
    assertEquals(CollaborationInboundRateLimiter.Verdict.LIMITED, limiter.acquire(T0),
        "the full bucket of five is spent");

    assertEquals(CollaborationInboundRateLimiter.Verdict.ALLOWED,
        limiter.acquire(T0.plusSeconds(1)),
        "one second refills the whole budget");
  }

  @Test
  void theThirdViolationInsideTenSecondsEscalates() {
    CollaborationInboundRateLimiter limiter = new CollaborationInboundRateLimiter(1, T0);

    assertEquals(CollaborationInboundRateLimiter.Verdict.ALLOWED, limiter.acquire(T0));
    assertEquals(CollaborationInboundRateLimiter.Verdict.LIMITED, limiter.acquire(T0));
    assertEquals(CollaborationInboundRateLimiter.Verdict.LIMITED, limiter.acquire(T0));
    assertEquals(CollaborationInboundRateLimiter.Verdict.ESCALATE, limiter.acquire(T0),
        "the third violation inside the window closes the connection");
  }

  @Test
  void aNewWindowForgivesEarlierViolations() {
    CollaborationInboundRateLimiter limiter = new CollaborationInboundRateLimiter(1, T0);

    assertEquals(CollaborationInboundRateLimiter.Verdict.ALLOWED, limiter.acquire(T0));
    assertEquals(CollaborationInboundRateLimiter.Verdict.LIMITED, limiter.acquire(T0));
    assertEquals(CollaborationInboundRateLimiter.Verdict.LIMITED, limiter.acquire(T0));
    // Eleven seconds later the non-sliding window has rolled over, and the bucket refilled.
    Instant later = T0.plusSeconds(11);
    assertEquals(CollaborationInboundRateLimiter.Verdict.ALLOWED, limiter.acquire(later));
    assertEquals(CollaborationInboundRateLimiter.Verdict.LIMITED, limiter.acquire(later));
    assertEquals(CollaborationInboundRateLimiter.Verdict.LIMITED, limiter.acquire(later),
        "violations never carry across windows");
  }
}
