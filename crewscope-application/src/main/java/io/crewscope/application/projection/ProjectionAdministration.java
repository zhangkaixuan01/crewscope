package io.crewscope.application.projection;

import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.time.UtcTimestamp;

/** Authorization Port that requires a current active Organization administrator. */
public interface ProjectionAdministration {

    void requireOrganizationAdministrator(
            OrganizationId organizationId, Principal actor, UtcTimestamp occurredAt);
}
