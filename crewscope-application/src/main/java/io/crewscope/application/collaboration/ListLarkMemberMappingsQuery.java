package io.crewscope.application.collaboration;

import io.crewscope.domain.collaboration.LarkMemberMappingStatus;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import java.util.Objects;
import java.util.Optional;

/** Administrator query for one exact Team mapping page. */
public record ListLarkMemberMappingsQuery(
        OrganizationId organizationId,
        TeamId teamId,
        Optional<LarkMemberMappingStatus> status,
        Optional<LarkMemberMappingCursor> after,
        int limit,
        Principal actor) {

    public ListLarkMemberMappingsQuery {
        organizationId = Objects.requireNonNull(organizationId, "organizationId");
        teamId = Objects.requireNonNull(teamId, "teamId");
        status = Objects.requireNonNull(status, "status");
        after = Objects.requireNonNull(after, "after");
        actor = Objects.requireNonNull(actor, "actor");
        // Reuse the persistence query invariant at the public application boundary.
        new LarkMemberMappingPageRequest(organizationId, teamId, status, after, limit);
    }
}
