package io.crewscope.domain.identity;

/** Independent lifecycle of one login-provider binding. */
public enum LoginIdentityStatus {
    ACTIVE,
    DISABLED,
    REVOKED;

    public boolean isUsable() {
        return this == ACTIVE;
    }

    public boolean isTerminal() {
        return this == REVOKED;
    }
}
