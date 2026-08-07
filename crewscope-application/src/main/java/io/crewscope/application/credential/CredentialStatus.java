package io.crewscope.application.credential;

/** Persisted lifecycle states for a credential envelope. */
public enum CredentialStatus {
    ACTIVE,
    ROTATING,
    REVOKED
}
