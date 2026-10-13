package io.crewscope.domain.organization.governance;

/**
 * Lifecycle of one account deletion job. The two-step confirmation is the vocabulary itself:
 * create lands in PENDING_CONFIRMATION and only confirm(token) reaches CONFIRMED.
 */
public enum AccountDeletionJobStatus {
    PENDING_CONFIRMATION,
    CONFIRMED,
    RUNNING,
    COMPLETED,
    CANCELLED
}
