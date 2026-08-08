package io.crewscope.domain.provider;

/** External identity authorization lifecycle. */
public enum ConnectionStatus {
    ACTIVE,
    SUSPENDED,
    REVOKED,
    EXPIRED;

    public boolean isTerminal() {
        return this == REVOKED || this == EXPIRED;
    }
}
