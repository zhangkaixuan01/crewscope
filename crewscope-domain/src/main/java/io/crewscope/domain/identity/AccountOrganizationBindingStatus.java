package io.crewscope.domain.identity;

/** Administrative lifecycle of an Account's access to one Organization Principal. */
public enum AccountOrganizationBindingStatus {
    ACTIVE,
    DISABLED;

    public boolean isUsable() {
        return this == ACTIVE;
    }
}
