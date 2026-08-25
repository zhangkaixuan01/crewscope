package io.crewscope.domain.audit;

/** Stable conclusion of an attempted business or security operation. */
public enum AuditOutcome {
    SUCCEEDED,
    DENIED,
    FAILED
}
