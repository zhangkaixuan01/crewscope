package io.crewscope.domain.notification;

/** Delivery lifecycle that preserves uncertainty instead of assuming a safe retry. */
public enum NotificationDeliveryStatus {
    READY,
    RUNNING,
    RETRY_WAIT,
    UNKNOWN,
    RECONCILING,
    SUCCEEDED,
    FAILED_FINAL,
    INVALIDATED,
    CANCELLED;

    public boolean terminal() {
        return this == SUCCEEDED || this == FAILED_FINAL || this == INVALIDATED || this == CANCELLED;
    }
}
