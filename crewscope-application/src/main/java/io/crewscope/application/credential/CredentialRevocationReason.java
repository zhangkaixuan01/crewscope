package io.crewscope.application.credential;

/** Stable reason recorded by the application audit transaction when a credential is revoked. */
public enum CredentialRevocationReason {
    USER_REQUESTED,
    CONNECTION_REVOKED,
    SECURITY_POLICY,
    ORGANIZATION_REMOVED,
    SUPERSEDED
}
