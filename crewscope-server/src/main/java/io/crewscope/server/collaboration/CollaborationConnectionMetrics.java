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
  public static final String SUBSCRIPTION_DENIED = "crewscope.collaboration.subscription.denied";

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

  private final MeterRegistry meterRegistry;
  private final Counter closedSlow;

  public CollaborationConnectionMetrics(
      MeterRegistry meterRegistry, CollaborationConnectionRegistry registry) {
    this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry");
    Objects.requireNonNull(registry, "registry");
    // The gauge holds a strong reference to the registry, keeping the sample alive.
    Gauge.builder(ACTIVE, registry, CollaborationConnectionRegistry::totalConnections)
        .register(meterRegistry);
    this.closedSlow = Counter.builder(CLOSED_SLOW).register(meterRegistry);
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
}
