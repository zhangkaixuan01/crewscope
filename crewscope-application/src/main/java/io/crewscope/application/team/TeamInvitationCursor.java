package io.crewscope.application.team;

import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.TeamInvitation;
import io.crewscope.domain.team.TeamInvitationId;
import java.util.Objects;

/** Stable keyset cursor for newest-first Team invitation management pages. */
public record TeamInvitationCursor(UtcTimestamp createdAt, TeamInvitationId invitationId) {

    public TeamInvitationCursor {
        createdAt = Objects.requireNonNull(createdAt, "createdAt");
        invitationId = Objects.requireNonNull(invitationId, "invitationId");
    }

    public static TeamInvitationCursor from(TeamInvitation invitation) {
        TeamInvitation required = Objects.requireNonNull(invitation, "invitation");
        return new TeamInvitationCursor(required.lifecycle().createdAt(), required.id());
    }
}
