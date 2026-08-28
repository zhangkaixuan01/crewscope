package io.crewscope.domain.identity;

/** Login availability state of a deployment-level user account. */
public enum AccountStatus {
    ACTIVE,
    LOCKED,
    DISABLED,
    ARCHIVED;

    public boolean canAuthenticate() {
        return this == ACTIVE;
    }

    public boolean isTerminal() {
        return this == ARCHIVED;
    }
}
