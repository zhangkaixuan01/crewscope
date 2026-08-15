package io.crewscope.server.observability;

import io.crewscope.application.task.LeaseCoordinatorMetrics;
import io.crewscope.application.task.LeaseCoordinatorOperation;
import io.crewscope.application.task.LeaseCoordinatorOutcome;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Pre-registers fixed Lease operation/outcome pairs without ownership or tenant labels. */
public final class TaskExecutionLeaseMetrics implements LeaseCoordinatorMetrics {

    public static final String OPERATIONS = "crewscope.task.lease.operations";

    private final Map<LeaseCoordinatorOperation, Map<LeaseCoordinatorOutcome, Counter>> counters;

    public TaskExecutionLeaseMetrics(MeterRegistry registry) {
        MeterRegistry required = Objects.requireNonNull(registry, "registry");
        EnumMap<LeaseCoordinatorOperation, Map<LeaseCoordinatorOutcome, Counter>> registered =
                new EnumMap<>(LeaseCoordinatorOperation.class);
        for (LeaseCoordinatorOperation operation : LeaseCoordinatorOperation.values()) {
            EnumMap<LeaseCoordinatorOutcome, Counter> outcomes =
                    new EnumMap<>(LeaseCoordinatorOutcome.class);
            for (LeaseCoordinatorOutcome outcome : LeaseCoordinatorOutcome.values()) {
                outcomes.put(
                        outcome,
                        Counter.builder(OPERATIONS)
                                .description("Durable Task Execution Lease operations")
                                .tag("operation", operation.name().toLowerCase(Locale.ROOT))
                                .tag("outcome", outcome.name().toLowerCase(Locale.ROOT))
                                .register(required));
            }
            registered.put(operation, Map.copyOf(outcomes));
        }
        this.counters = Map.copyOf(registered);
    }

    @Override
    public void record(
            LeaseCoordinatorOperation operation,
            LeaseCoordinatorOutcome outcome,
            long amount) {
        if (amount < 1) {
            return;
        }
        counters.get(Objects.requireNonNull(operation, "operation"))
                .get(Objects.requireNonNull(outcome, "outcome"))
                .increment(amount);
    }
}
