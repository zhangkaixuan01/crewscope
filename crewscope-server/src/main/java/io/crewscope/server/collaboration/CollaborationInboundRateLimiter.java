package io.crewscope.server.collaboration;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Per-connection inbound signal budget (M11-A01, ADR-032). A lazy token bucket with
 * capacity equal to the rate absorbs legitimate bursts (the ADR quotes a 22/s inbound
 * peak; the default allowance is 30/s), while a non-sliding violation window escalates
 * sustained abuse: the first two over-limit frames inside ten seconds answer {@code
 * rate_limited} without disconnecting, the third closes the connection (1013) — one
 * burst must not trigger a reconnect storm, a persistent offender must be cut off.
 *
 * <p>One instance per connection; the handler's inbound pipeline is serialized, and the
 * monitor keeps concurrent callers (should one ever appear) on the same accounting.</p>
 */
public final class CollaborationInboundRateLimiter {

  /** Verdict of admitting one inbound signal frame. */
  public enum Verdict {
    /** Within budget: process the frame. */
    ALLOWED,
    /** Over budget but not yet sustained: answer rate_limited, keep the connection. */
    LIMITED,
    /** Over budget for the third time inside the window: close with 1013. */
    ESCALATE
  }

  static final int ESCALATION_THRESHOLD = 3;
  static final long VIOLATION_WINDOW_MILLIS = 10_000L;

  private final int permitsPerSecond;
  private double tokens;
  private Instant lastRefill;
  private long windowStartMillis;
  private int violationsInWindow;

  public CollaborationInboundRateLimiter(int permitsPerSecond, Instant now) {
    if (permitsPerSecond < 1 || permitsPerSecond > 1_000) {
      throw new IllegalArgumentException("permitsPerSecond must be between 1 and 1000");
    }
    this.permitsPerSecond = permitsPerSecond;
    // A fresh connection starts with a full bucket.
    this.tokens = permitsPerSecond;
    this.lastRefill = Objects.requireNonNull(now, "now");
    this.windowStartMillis = now.toEpochMilli();
  }

  /** Admits one signal frame against the budget; pong and other free frames never call this. */
  public synchronized Verdict acquire(Instant now) {
    refill(now);
    if (tokens >= 1.0) {
      tokens -= 1.0;
      return Verdict.ALLOWED;
    }
    return recordViolation(now);
  }

  private void refill(Instant now) {
    Duration elapsed = Duration.between(lastRefill, now);
    if (elapsed.isZero() || elapsed.isNegative()) {
      return;
    }
    tokens = Math.min(permitsPerSecond, tokens + permitsPerSecond * elapsed.toMillis() / 1000.0);
    lastRefill = now;
  }

  private Verdict recordViolation(Instant now) {
    long nowMillis = now.toEpochMilli();
    if (nowMillis - windowStartMillis >= VIOLATION_WINDOW_MILLIS) {
      windowStartMillis = nowMillis;
      violationsInWindow = 0;
    }
    violationsInWindow++;
    return violationsInWindow >= ESCALATION_THRESHOLD ? Verdict.ESCALATE : Verdict.LIMITED;
  }
}
