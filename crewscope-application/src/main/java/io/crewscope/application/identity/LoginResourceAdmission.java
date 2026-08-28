package io.crewscope.application.identity;

/** Internal fixed-cardinality resource-window decision; anonymous APIs fold all limits together. */
public enum LoginResourceAdmission {
    ALLOWED,
    IDENTIFIER_RATE_LIMITED,
    NETWORK_RATE_LIMITED,
    IDENTIFIER_AND_NETWORK_RATE_LIMITED;

    public boolean allowed() {
        return this == ALLOWED;
    }
}
