package io.crewscope.server.collaboration;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Locale;
import java.util.Objects;

/** Low-cardinality connection admission counters with enum-only coordinates. */
public final class CollaborationConnectionMetrics {

  public static final String ACTIVE = "crewscope.collaboration.connection.active";
  public static final String REJECTED = "crewscope.collaboration.connection.rejected";
  public static final String CLOSED_SLOW = "crewscope.collaboration.connection.closed.slow";
  public static final String CLOSED_REVOKED = "crewscope.collaboration.connection.closed.revoked";
  public static final String CLOSED_RATE_LIMITED =
      "crewscope.collaboration.connection.closed.rate-limited";
  public static final String SUBSCRIPTION_DENIED = "crewscope.collaboration.subscription.denied";
  public static final String SIGNAL_EMITTED = "crewscope.collaboration.signal.emitted";
  public static final String SIGNAL_RATE_LIMITED = "crewscope.collaboration.signal.rate-limited";

  /** Rejection causes become the single counter's reason tag. */
  public enum RejectionReason {
    PRINCIPAL_LIMIT,
    HARD_LIMIT,
    PROTOCOL
  }

  /** Subscription denial causes: forbidden covers the cross-team case required by I01b. */
  public enum DenialReason {
    FORBIDDEN,
    LIMIT,
    INVALID
  }

  /** Signal frame kinds for the emitted counter — the enum keeps the tag low-cardinality. */
  public enum SignalFrameType {
    PRESENCE_SNAPSHOT,
    PRESENCE_DELTA,
    TYPING,
    RESOURCE_CHANGED
  }

  private final MeterRegistry meterRegistry;
  private final Counter closedSlow;
  private final Counter closedRevoked;
  private final Counter closedRateLimited;
  private final Counter signalRateLimited;

  public CollaborationConnectionMetrics(
      MeterRegistry meterRegistry, CollaborationConnectionRegistry registry) {
    this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry");
    Objects.requireNonNull(registry, "registry");
    // The gauge holds a strong reference to the registry, keeping the sample alive.
    Gauge.builder(ACTIVE, registry, CollaborationConnectionRegistry::totalConnections)
        .register(meterRegistry);
    this.closedSlow = Counter.builder(CLOSED_SLOW).register(meterRegistry);
    this.closedRevoked = Counter.builder(CLOSED_REVOKED).register(meterRegistry);
    this.closedRateLimited = Counter.builder(CLOSED_RATE_LIMITED).register(meterRegistry);
    this.signalRateLimited = Counter.builder(SIGNAL_RATE_LIMITED).register(meterRegistry);
  }

  public void recordRejection(RejectionReason reason) {
    Counter.builder(REJECTED)
        .tag("reason", reason.name().toLowerCase(Locale.ROOT))
        .register(meterRegistry)
        .increment();
  }

  public void recordSubscriptionDenied(DenialReason reason) {
    Counter.builder(SUBSCRIPTION_DENIED)
        .tag("reason", reason.name().toLowerCase(Locale.ROOT))
        .register(meterRegistry)
        .increment();
  }

  public void recordClosedSlow() {
    closedSlow.increment();
  }

  /** The revalidation probe denied the principal: the connection closed with 4403. */
  public void recordClosedRevoked() {
    closedRevoked.increment();
  }

  /** Sustained inbound over-budget frames: the connection closed with 1013. */
  public void recordClosedRateLimited() {
    closedRateLimited.increment();
  }

  /** One inbound signal frame was throttled (rate_limited answered, connection kept). */
  public void recordSignalRateLimited() {
    signalRateLimited.increment();
  }

  /** One signal frame reached a subscriber's outbound channel. */
  public void recordSignalEmitted(SignalFrameType frameType) {
    Counter.builder(SIGNAL_EMITTED)
        .tag("frame_type", frameType.name().toLowerCase(Locale.ROOT))
        .register(meterRegistry)
        .increment();
  }
}
