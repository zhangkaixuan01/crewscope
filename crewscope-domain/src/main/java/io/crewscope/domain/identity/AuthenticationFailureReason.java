package io.crewscope.domain.identity;

/** Internal fixed-cardinality authentication reason; never serialize it to anonymous clients. */
public enum AuthenticationFailureReason {
    PASSWORD_INPUT_REJECTED,
    ACCOUNT_UNKNOWN,
    ACCOUNT_LOCKED,
    ACCOUNT_DISABLED,
    ACCOUNT_ARCHIVED,
    IDENTITY_DISABLED,
    IDENTITY_REVOKED,
    CREDENTIAL_MISSING,
    CREDENTIAL_MALFORMED,
    PASSWORD_MISMATCH,
    TEMPORARILY_LOCKED,
    IDENTIFIER_RATE_LIMITED,
    NETWORK_RATE_LIMITED,
    HASH_CAPACITY_EXHAUSTED;

    public boolean isCapacityFailure() {
        return this == IDENTIFIER_RATE_LIMITED
                || this == NETWORK_RATE_LIMITED
                || this == HASH_CAPACITY_EXHAUSTED;
    }
}
