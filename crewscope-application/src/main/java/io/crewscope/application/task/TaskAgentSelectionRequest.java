package io.crewscope.application.task;

import io.crewscope.domain.agent.AgentConfigurationRevision;
import io.crewscope.domain.workspace.AgentProfileId;
import java.util.Objects;
import java.util.Optional;

/** Member-selected Agent identity plus an optional exact configuration revision. */
public record TaskAgentSelectionRequest(
        AgentProfileId agentProfileId,
        Optional<AgentConfigurationRevision> configurationRevision) {

    public TaskAgentSelectionRequest {
        agentProfileId = Objects.requireNonNull(agentProfileId, "agentProfileId");
        configurationRevision = Objects.requireNonNull(
                configurationRevision, "configurationRevision");
    }

    public static TaskAgentSelectionRequest current(AgentProfileId agentProfileId) {
        return new TaskAgentSelectionRequest(agentProfileId, Optional.empty());
    }
}
