package io.crewscope.domain.identity;

/** Deployment-level policy for creating new local user accounts. */
public enum RegistrationMode {
    OPEN,
    INVITE_ONLY,
    DISABLED;

    /** The invitation flag must only be true after the invitation has been validated. */
    public boolean allowsRegistration(boolean hasValidatedInvitation) {
        return switch (this) {
            case OPEN -> true;
            case INVITE_ONLY -> hasValidatedInvitation;
            case DISABLED -> false;
        };
    }

    public boolean requiresInvitation() {
        return this == INVITE_ONLY;
    }

    public boolean acceptsOpenRegistration() {
        return this == OPEN;
    }
}
