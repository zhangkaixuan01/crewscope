package io.crewscope.domain.action;

/** Lifecycle of one exact human authorization. Expiry is evaluated against validUntil. */
public enum ConfirmationStatus {
    ACTIVE,
    CANCELLED
}
