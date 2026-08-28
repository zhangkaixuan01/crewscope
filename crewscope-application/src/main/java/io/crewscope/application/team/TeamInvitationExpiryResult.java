package io.crewscope.application.team;

/** Safe bounded cleanup summary; invitation and audit facts remain durable. */
public record TeamInvitationExpiryResult(int expiredInvitations, boolean capacityLimited) {

    public TeamInvitationExpiryResult {
        if (expiredInvitations < 0) {
            throw new IllegalArgumentException("expiredInvitations must not be negative");
        }
    }
}
