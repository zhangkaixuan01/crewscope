package io.crewscope.application.projection;

import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.time.UtcTimestamp;

/** Authorization Port that requires a current active Organization administrator. */
public interface ProjectionAdministration {

    void requireOrganizationAdministrator(
            OrganizationId organizationId, TeamAccessContext access, UtcTimestamp occurredAt);
}
