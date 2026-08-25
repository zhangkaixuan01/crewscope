package io.crewscope.application.teamobserver;

import io.crewscope.domain.agent.AgentConfigurationVersion;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;

/** Resolves and verifies the current TEAM/ORGANIZATION model binding before Observer activation. */
public interface TeamObserverModelPreflight {

    void requireReady(
            OrganizationId organizationId,
            TeamId teamId,
            AgentConfigurationVersion configuration);
}
