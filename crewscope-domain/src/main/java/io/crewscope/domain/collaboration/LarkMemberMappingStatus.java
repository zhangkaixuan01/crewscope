package io.crewscope.domain.collaboration;

/** Confirmed member-mapping lifecycle. */
public enum LarkMemberMappingStatus {
    ACTIVE,
    REVOKED,
    INVALIDATED;

    public boolean terminal() {
        return this != ACTIVE;
    }
}
