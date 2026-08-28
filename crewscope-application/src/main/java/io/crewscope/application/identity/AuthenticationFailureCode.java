package io.crewscope.application.identity;

/** Stable anonymous authentication failure codes. */
public enum AuthenticationFailureCode {
    INVALID_CREDENTIALS("invalid_credentials"),
    TOO_MANY_REQUESTS("too_many_requests");

    private final String value;

    AuthenticationFailureCode(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }
}
