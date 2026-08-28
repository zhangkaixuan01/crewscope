package io.crewscope.domain.identity.event;

/** Fixed low-cardinality security classification; credential and account lookup reasons stay private. */
public enum AuthenticationFailureClass {
    INVALID_CREDENTIALS,
    IDENTIFIER_RATE_LIMITED,
    NETWORK_RATE_LIMITED,
    HASH_CAPACITY_EXHAUSTED,
    AUTHENTICATION_STORE_UNAVAILABLE
}
