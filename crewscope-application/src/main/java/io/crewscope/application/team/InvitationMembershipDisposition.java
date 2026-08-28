package io.crewscope.application.team;

import io.crewscope.domain.team.event.TeamInvitationMembershipResult;

/** Persistence action required for a Membership selected by invitation acceptance. */
public enum InvitationMembershipDisposition {
    CREATED,
    ACTIVATED,
    REUSED;

    /** Maps the persistence action to the same stable value recorded by the acceptance event. */
    public TeamInvitationMembershipResult eventResult() {
        return TeamInvitationMembershipResult.valueOf(name());
    }
}
