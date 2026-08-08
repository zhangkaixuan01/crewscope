package io.crewscope.domain.provider;

/** Delegation lifecycle from a Connection owner to an allowed Binding owner. */
public enum ConnectionGrantStatus {
    ACTIVE,
    REVOKED,
    EXPIRED;

    public boolean isTerminal() {
        return this != ACTIVE;
    }
}
