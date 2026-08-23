package io.crewscope.domain.model;

/** Lifecycle of one model connection independent from its health snapshot. */
public enum ModelConnectionStatus {
    ACTIVE,
    SUSPENDED,
    REVOKED;

    public boolean isTerminal() {
        return this == REVOKED;
    }
}
