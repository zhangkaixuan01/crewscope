package io.crewscope.domain.identity;

/** Deployment-level role; Team authorization continues to come from TeamMember and TeamRole. */
public enum PlatformRole {
    USER(false),
    OPERATOR(true);

    private final boolean platformOperationsAllowed;

    PlatformRole(boolean platformOperationsAllowed) {
        this.platformOperationsAllowed = platformOperationsAllowed;
    }

    public boolean allowsPlatformOperations() {
        return platformOperationsAllowed;
    }
}
