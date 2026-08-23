package io.crewscope.domain.action;

/** Stable operator reason required for an irreversible manual conclusion. */
public enum ManualResolutionReason {
    EXTERNAL_OBJECT_VERIFIED,
    PROVIDER_AUDIT_VERIFIED,
    NO_EXTERNAL_OBJECT_VERIFIED,
    SECURITY_INCIDENT_DECISION
}
