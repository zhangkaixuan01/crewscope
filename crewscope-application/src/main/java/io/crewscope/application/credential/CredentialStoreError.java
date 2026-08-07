package io.crewscope.application.credential;

/** Stable safe failures shared by database and future Vault/KMS adapters. */
public enum CredentialStoreError {
    INTEGRITY_VIOLATION,
    CONFLICT,
    ACCESS_DENIED,
    NOT_FOUND,
    STORAGE_FAILURE
}
