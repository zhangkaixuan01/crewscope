package io.crewscope.domain.action;

/** Durable scheduler state for one external side effect. */
public enum ActionDispatchStatus {
    READY,
    RUNNING,
    UNKNOWN,
    RECONCILING,
    MANUAL_REVIEW,
    SUCCEEDED,
    FAILED,
    MANUALLY_SUCCEEDED,
    MANUALLY_FAILED,
    CANCELLED;

    public boolean isTerminal() {
        return switch (this) {
            case SUCCEEDED, FAILED, MANUALLY_SUCCEEDED, MANUALLY_FAILED, CANCELLED -> true;
            default -> false;
        };
    }

    public boolean isSuccessful() {
        return this == SUCCEEDED || this == MANUALLY_SUCCEEDED;
    }
}
