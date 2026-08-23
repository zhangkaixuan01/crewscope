package io.crewscope.application.action;

import java.time.Duration;

/** Telemetry Port for one query-only Action reconciliation attempt. */
@FunctionalInterface
public interface ActionReconciliationObserver {

    void record(
            ActionReconciliationTrace trace,
            ActionReconciliationOutcome outcome,
            Duration duration);

    default void queueHealth(ActionReconciliationHealth health) {}

    static ActionReconciliationObserver noOp() {
        return (trace, outcome, duration) -> {};
    }
}
