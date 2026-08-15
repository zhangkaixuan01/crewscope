package io.crewscope.application.task;

/** Best-effort observability boundary that must never change Claim transaction semantics. */
@FunctionalInterface
public interface ClaimSchedulerMetrics {

    ClaimSchedulerMetrics NOOP = (outcome, amount) -> {};

    void record(ClaimSchedulerMetricOutcome outcome, long amount);
}
