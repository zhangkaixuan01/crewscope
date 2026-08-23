package io.crewscope.domain.agent;

import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import java.util.Objects;
import java.util.Optional;

/** Organization catalog or Team catalog boundary that publishes an Agent template key. */
public record AgentTemplatePublisherScope(
        OrganizationId organizationId, Optional<TeamId> teamId) {

    public AgentTemplatePublisherScope {
        organizationId = Objects.requireNonNull(organizationId, "organizationId");
        teamId = Objects.requireNonNull(teamId, "teamId");
    }

    public static AgentTemplatePublisherScope organization(OrganizationId organizationId) {
        return new AgentTemplatePublisherScope(organizationId, Optional.empty());
    }

    public static AgentTemplatePublisherScope team(
            OrganizationId organizationId, TeamId teamId) {
        return new AgentTemplatePublisherScope(
                organizationId, Optional.of(Objects.requireNonNull(teamId, "teamId")));
    }
}
