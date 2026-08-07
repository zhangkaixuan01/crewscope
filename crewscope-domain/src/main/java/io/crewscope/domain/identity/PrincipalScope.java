package io.crewscope.domain.identity;

import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import java.util.Objects;
import java.util.Optional;

/** Organization ownership and optional Team affinity of a Principal. */
public record PrincipalScope(OrganizationId organizationId, Optional<TeamId> teamId) {

    public PrincipalScope {
        organizationId = Objects.requireNonNull(organizationId, "organizationId");
        teamId = Objects.requireNonNull(teamId, "teamId");
    }

    public static PrincipalScope organization(OrganizationId organizationId) {
        return new PrincipalScope(organizationId, Optional.empty());
    }

    public static PrincipalScope team(OrganizationId organizationId, TeamId teamId) {
        return new PrincipalScope(
                organizationId, Optional.of(Objects.requireNonNull(teamId, "teamId")));
    }
}
