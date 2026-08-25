package io.crewscope.application.collaboration;

import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.UtcTimestamp;

/** Requires an ACTIVE Team member with current PROVIDER_MANAGE permission. */
public interface LarkMappingAdministration {

    void requireProviderAdministrator(
            OrganizationId organizationId,
            TeamId teamId,
            Principal actor,
            UtcTimestamp occurredAt);
}
