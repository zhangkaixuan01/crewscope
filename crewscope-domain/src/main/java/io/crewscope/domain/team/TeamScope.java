package io.crewscope.domain.team;

import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import java.util.Objects;

/** Immutable Organization and Team ownership shared by Team aggregates. */
public record TeamScope(OrganizationId organizationId, TeamId teamId) {

    public TeamScope {
        organizationId = Objects.requireNonNull(organizationId, "organizationId");
        teamId = Objects.requireNonNull(teamId, "teamId");
    }
}
