package io.crewscope.domain.agent;

import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import java.util.Objects;
import java.util.Optional;

/** Organization or Team scope that publishes a versioned Agent model default. */
public record AgentModelDefaultScope(
        OrganizationId organizationId,
        Optional<TeamId> teamId) {

    public AgentModelDefaultScope {
        organizationId = Objects.requireNonNull(organizationId, "organizationId");
        teamId = Objects.requireNonNull(teamId, "teamId");
    }

    public static AgentModelDefaultScope organization(OrganizationId organizationId) {
        return new AgentModelDefaultScope(
                Objects.requireNonNull(organizationId, "organizationId"), Optional.empty());
    }

    public static AgentModelDefaultScope team(
            OrganizationId organizationId, TeamId teamId) {
        return new AgentModelDefaultScope(
                organizationId, Optional.of(Objects.requireNonNull(teamId, "teamId")));
    }
}
