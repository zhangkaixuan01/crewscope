package io.crewscope.application.task;

/** Fixed low-cardinality Claim outcomes permitted as metric tag values. */
public enum ClaimSchedulerMetricOutcome {
    CLAIMED,
    EMPTY,
    WAITING_RUNTIME,
    CAPABILITY_DEFERRED,
    TEAM_QUOTA,
    RUNTIME_QUOTA,
    WORKER_QUOTA,
    FAILED
}
