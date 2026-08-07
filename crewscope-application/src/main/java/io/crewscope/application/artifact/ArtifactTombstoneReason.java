package io.crewscope.application.artifact;

/** Stable reason retained in lifecycle metadata and projected into AuditEvent. */
public enum ArtifactTombstoneReason {
    RETENTION_EXPIRED,
    USER_REQUESTED,
    SECURITY_POLICY,
    ORGANIZATION_REMOVED,
    SUPERSEDED
}
