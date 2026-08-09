package io.crewscope.agentscope;

import io.crewscope.domain.workspace.AgentProfileId;

/** Loads the exact versioned runtime configuration pinned by an AgentRuntimeSession. */
@FunctionalInterface
public interface PersonalAgentConfigurationSource {

    AgentScopePersonalAgentConfiguration load(
            AgentProfileId agentProfileId, long agentProfileVersion);
}
