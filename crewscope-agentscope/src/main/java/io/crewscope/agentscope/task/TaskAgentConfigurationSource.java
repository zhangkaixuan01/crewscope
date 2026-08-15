package io.crewscope.agentscope.task;

import io.crewscope.domain.workspace.AgentProfileId;

/** Loads the exact immutable configuration version pinned by Task durable facts. */
@FunctionalInterface
public interface TaskAgentConfigurationSource {

    TaskAgentConfiguration load(AgentProfileId agentProfileId, long agentProfileVersion);
}
