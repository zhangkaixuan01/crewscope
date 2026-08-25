package io.crewscope.domain.notification;

/** Immutable terminal conclusion for a notification delivery. */
public enum NotificationReceiptResult {
    ACCEPTED,
    FAILED_FINAL,
    INVALIDATED,
    CANCELLED
}
