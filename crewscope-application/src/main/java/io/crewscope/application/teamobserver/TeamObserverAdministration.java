package io.crewscope.application.teamobserver;

import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.UtcTimestamp;

/** Authorization Port for explicit lifecycle changes to the built-in Team Observer. */
public interface TeamObserverAdministration {

    void requireAgentAdministrator(
            OrganizationId organizationId,
            TeamId teamId,
            Principal actor,
            UtcTimestamp occurredAt);
}
