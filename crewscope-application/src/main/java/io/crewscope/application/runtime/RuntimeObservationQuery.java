package io.crewscope.application.runtime;

import io.crewscope.domain.runtime.RuntimeEnvironment;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import java.util.Objects;

/** Fully scoped read request for one Team's view of an Organization Runtime environment. */
public record RuntimeObservationQuery(
        OrganizationId organizationId, TeamId teamId, RuntimeEnvironment environment) {

    public RuntimeObservationQuery {
        organizationId = Objects.requireNonNull(organizationId, "organizationId");
        teamId = Objects.requireNonNull(teamId, "teamId");
        environment = Objects.requireNonNull(environment, "environment");
    }
}
