package io.crewscope.application.notification;

/** Low-cardinality result of one bounded Notification Worker poll. */
public record NotificationWorkerBatchResult(
        int claimed,
        int succeeded,
        int retryScheduled,
        int uncertain,
        int failedFinal,
        int invalidated,
        int fenced) {

    public NotificationWorkerBatchResult {
        if (claimed < 0 || succeeded < 0 || retryScheduled < 0 || uncertain < 0
                || failedFinal < 0 || invalidated < 0 || fenced < 0) {
            throw new IllegalArgumentException("Notification Worker counts must not be negative");
        }
    }

    public static NotificationWorkerBatchResult empty() {
        return new NotificationWorkerBatchResult(0, 0, 0, 0, 0, 0, 0);
    }

    public NotificationWorkerBatchResult plus(NotificationWorkerBatchResult other) {
        return new NotificationWorkerBatchResult(
                claimed + other.claimed,
                succeeded + other.succeeded,
                retryScheduled + other.retryScheduled,
                uncertain + other.uncertain,
                failedFinal + other.failedFinal,
                invalidated + other.invalidated,
                fenced + other.fenced);
    }
}
