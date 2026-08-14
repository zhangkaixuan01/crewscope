package io.crewscope.domain.task;

/** Explicit persisted lifecycle of one Task Token grant. */
public enum TaskCredentialGrantStatus {
    ACTIVE,
    REVOKED,
    EXPIRED
}
