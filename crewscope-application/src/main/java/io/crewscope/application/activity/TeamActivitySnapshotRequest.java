package io.crewscope.application.activity;

import io.crewscope.domain.projection.ProjectionName;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import java.util.Objects;

/** Scope-complete request for one bounded, generation-consistent Team Activity snapshot. */
public record TeamActivitySnapshotRequest(
        OrganizationId organizationId,
        TeamId teamId,
        ProjectionName projectionName,
        ActivityFilter filter,
        int limit) {

    public TeamActivitySnapshotRequest {
        organizationId = Objects.requireNonNull(organizationId, "organizationId");
        teamId = Objects.requireNonNull(teamId, "teamId");
        projectionName = Objects.requireNonNull(projectionName, "projectionName");
        filter = Objects.requireNonNull(filter, "filter");
        if (limit < 1 || limit > ActivityQuery.MAX_LIMIT) {
            throw new IllegalArgumentException(
                    "Team Activity snapshot limit must be between 1 and "
                            + ActivityQuery.MAX_LIMIT);
        }
    }
}
