package io.crewscope.application.team;

import io.crewscope.domain.team.TeamInvitation;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Bounded newest-first invitation page with an opaque-at-API keyset coordinate. */
public record TeamInvitationPage(
        List<TeamInvitation> invitations, Optional<TeamInvitationCursor> nextCursor) {

    public TeamInvitationPage {
        invitations = List.copyOf(Objects.requireNonNull(invitations, "invitations"));
        nextCursor = Objects.requireNonNull(nextCursor, "nextCursor");
        if (invitations.isEmpty() && nextCursor.isPresent()) {
            throw new IllegalArgumentException("Empty invitation page cannot have a cursor");
        }
    }
}
