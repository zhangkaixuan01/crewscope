package io.crewscope.application.collaboration;

import io.crewscope.domain.collaboration.LarkMemberMappingStatus;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import java.util.Objects;
import java.util.Optional;

/** Bounded, exact-scope mapping query; fuzzy external identity filters are intentionally absent. */
public record LarkMemberMappingPageRequest(
        OrganizationId organizationId,
        TeamId teamId,
        Optional<LarkMemberMappingStatus> status,
        Optional<LarkMemberMappingCursor> after,
        int limit) {

    public LarkMemberMappingPageRequest {
        organizationId = Objects.requireNonNull(organizationId, "organizationId");
        teamId = Objects.requireNonNull(teamId, "teamId");
        status = Objects.requireNonNull(status, "status");
        after = Objects.requireNonNull(after, "after");
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("Lark mapping page limit must be within [1, 100]");
        }
    }
}
