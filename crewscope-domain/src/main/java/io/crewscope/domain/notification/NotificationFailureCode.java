package io.crewscope.domain.notification;

/** Stable non-sensitive final failure classification. */
public enum NotificationFailureCode {
    RECIPIENT_UNAVAILABLE,
    AUTHORIZATION_REVOKED,
    PROVIDER_REJECTED,
    RETRY_EXHAUSTED,
    RECONCILIATION_EXHAUSTED
}
