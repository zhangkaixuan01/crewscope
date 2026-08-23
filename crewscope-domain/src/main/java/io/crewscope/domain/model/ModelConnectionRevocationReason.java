package io.crewscope.domain.model;

/** Stable non-secret reason for the irreversible revocation of a model connection. */
public enum ModelConnectionRevocationReason {
    OWNER_REQUESTED,
    CREDENTIAL_REVOKED,
    PROVIDER_DISABLED,
    POLICY_REVOKED,
    SECURITY_INCIDENT
}
