package io.crewscope.application.task;

/** Optional observability sink that cannot participate in Lease transactions. */
@FunctionalInterface
public interface LeaseCoordinatorMetrics {

    void record(
            LeaseCoordinatorOperation operation,
            LeaseCoordinatorOutcome outcome,
            long amount);
}
