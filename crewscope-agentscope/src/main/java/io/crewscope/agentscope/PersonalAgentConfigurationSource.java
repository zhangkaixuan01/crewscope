package io.crewscope.agentscope;

import io.crewscope.domain.workspace.AgentProfileId;
import io.crewscope.domain.conversation.AgentRuntimeSession;
import io.crewscope.application.execution.PlatformExecutionContext;

/** Loads the exact versioned runtime configuration pinned by an AgentRuntimeSession. */
@FunctionalInterface
public interface PersonalAgentConfigurationSource {

    AgentScopePersonalAgentConfiguration load(
            AgentProfileId agentProfileId, long agentProfileVersion);

    /** Loads a configuration for one invocation, allowing database-backed model binding. */
    default AgentScopePersonalAgentConfiguration load(
            AgentRuntimeSession runtimeSession, PlatformExecutionContext platformContext) {
        AgentRuntimeSession session = java.util.Objects.requireNonNull(runtimeSession, "runtimeSession");
        return load(session.agentProfileId(), session.agentProfileVersion());
    }
}
