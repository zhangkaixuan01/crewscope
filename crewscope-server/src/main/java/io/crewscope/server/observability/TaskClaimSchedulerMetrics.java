package io.crewscope.server.observability;

import io.crewscope.application.task.ClaimSchedulerMetricOutcome;
import io.crewscope.application.task.ClaimSchedulerMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Pre-registers the fixed Claim outcome vocabulary without tenant or Worker identity tags. */
public final class TaskClaimSchedulerMetrics implements ClaimSchedulerMetrics {

    public static final String CLAIMS = "crewscope.task.claims";

    private final Map<ClaimSchedulerMetricOutcome, Counter> counters;

    public TaskClaimSchedulerMetrics(MeterRegistry registry) {
        MeterRegistry required = Objects.requireNonNull(registry, "registry");
        EnumMap<ClaimSchedulerMetricOutcome, Counter> registered =
                new EnumMap<>(ClaimSchedulerMetricOutcome.class);
        for (ClaimSchedulerMetricOutcome outcome : ClaimSchedulerMetricOutcome.values()) {
            registered.put(
                    outcome,
                    Counter.builder(CLAIMS)
                            .description("Durable Task Claim Scheduler outcomes")
                            .tag("outcome", outcome.name().toLowerCase(Locale.ROOT))
                            .register(required));
        }
        this.counters = Map.copyOf(registered);
    }

    @Override
    public void record(ClaimSchedulerMetricOutcome outcome, long amount) {
        if (amount < 1) {
            return;
        }
        counters.get(Objects.requireNonNull(outcome, "outcome")).increment(amount);
    }
}
