package io.crewscope.application.correlation;

import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.team.TeamMemberId;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Fully scoped query including the current member used for private Inbox relationships. */
public record CorrelationQuery(
        OrganizationId organizationId,
        TeamId teamId,
        TeamMemberId memberId,
        UUID correlationId,
        Optional<CorrelationCursor> after,
        int limit) {

    public CorrelationQuery {
        organizationId = Objects.requireNonNull(organizationId, "organizationId");
        teamId = Objects.requireNonNull(teamId, "teamId");
        memberId = Objects.requireNonNull(memberId, "memberId");
        correlationId = Objects.requireNonNull(correlationId, "correlationId");
        after = Objects.requireNonNull(after, "after");
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("limit must be between 1 and 100");
        }
        if (after.isPresent()) {
            CorrelationCursor cursor = after.orElseThrow();
            if (!cursor.organizationId().equals(organizationId)
                    || !cursor.teamId().equals(teamId)
                    || !cursor.correlationId().equals(correlationId)) {
                throw new IllegalArgumentException("Correlation cursor belongs to another scope");
            }
        }
    }
}
