package io.crewscope.domain.audit;

/** Stable low-cardinality category used by Audit Explorer filters and retention policy. */
public enum AuditEventCategory {
    IDENTITY,
    TEAM,
    WORK,
    COLLABORATION,
    EXECUTION,
    AGENT,
    MODEL,
    REVIEW,
    ACTION,
    PROVIDER,
    NOTIFICATION,
    PROJECTION,
    SECURITY,
    SYSTEM
}
