package io.crewscope.application.identity;

/** Closed safe failures for step-up authenticated current-account mutations. */
public enum CurrentAccountMutationFailure {
    INVALID_CURRENT_PASSWORD,
    SECURITY_VERSION_CONFLICT,
    CREDENTIAL_CONFLICT,
    ACCOUNT_UNAVAILABLE
}
