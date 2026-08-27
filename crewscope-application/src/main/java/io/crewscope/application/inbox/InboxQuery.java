package io.crewscope.application.inbox;

import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.team.TeamMemberId;
import java.util.Objects;
import java.util.Optional;

/** Fully scoped current-generation member Inbox query. */
public record InboxQuery(
        OrganizationId organizationId,
        TeamId teamId,
        TeamMemberId memberId,
        InboxFilter filter,
        Optional<InboxCursor> cursor,
        int limit) {

    public InboxQuery {
        organizationId = Objects.requireNonNull(organizationId, "organizationId");
        teamId = Objects.requireNonNull(teamId, "teamId");
        memberId = Objects.requireNonNull(memberId, "memberId");
        filter = Objects.requireNonNull(filter, "filter");
        cursor = Objects.requireNonNull(cursor, "cursor");
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("limit must be between 1 and 100");
        }
    }
}
