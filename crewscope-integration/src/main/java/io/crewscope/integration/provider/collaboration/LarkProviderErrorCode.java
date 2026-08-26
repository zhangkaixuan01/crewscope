package io.crewscope.integration.provider.collaboration;

/** Stable, non-sensitive failure categories exposed by the fixed Lark Connector. */
public enum LarkProviderErrorCode {
    AUTHENTICATION_REQUIRED(false),
    PERMISSION_DENIED(false),
    RESOURCE_UNAVAILABLE(false),
    RATE_LIMITED(true),
    PROVIDER_UNAVAILABLE(true),
    INVALID_RESPONSE(false),
    IDENTITY_MISMATCH(false),
    CONNECTION_UNAVAILABLE(false),
    CREDENTIAL_UNAVAILABLE(false),
    CANCELLED(false),
    UNKNOWN_DELIVERY(true);

    private final boolean retryable;

    LarkProviderErrorCode(boolean retryable) {
        this.retryable = retryable;
    }

    public boolean retryable() {
        return retryable;
    }
}
