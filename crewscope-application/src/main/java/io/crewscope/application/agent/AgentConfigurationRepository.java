package io.crewscope.application.agent;

import io.crewscope.domain.agent.AgentConfigurationRevision;
import io.crewscope.domain.agent.AgentConfigurationVersion;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.workspace.AgentProfileId;
import java.util.List;
import java.util.Optional;

/** Persistence Port for append-only AgentProfile configuration revisions. */
public interface AgentConfigurationRepository {

    /** Appends one revision and rejects gaps, duplicates, overwrites and cross-Profile chains. */
    AgentConfigurationVersion append(AgentConfigurationVersion configuration);

    Optional<AgentConfigurationVersion> findCurrent(
            OrganizationId organizationId, AgentProfileId agentProfileId);

    Optional<AgentConfigurationVersion> findByRevision(
            OrganizationId organizationId,
            AgentProfileId agentProfileId,
            AgentConfigurationRevision revision);

    List<AgentConfigurationVersion> findAll(
            OrganizationId organizationId, AgentProfileId agentProfileId);

    /** Returns newest-first immutable configuration history without per-binding follow-up queries. */
    List<AgentConfigurationVersion> findPage(
            OrganizationId organizationId,
            AgentProfileId agentProfileId,
            int offset,
            int limit);
}
