package io.crewscope.agentscope.coding;

import io.crewscope.domain.workspace.AgentProfileId;

/** Loads the exact immutable configuration pinned by a Specialist Agent RuntimeSession. */
@FunctionalInterface
public interface CodingSpecialistConfigurationSource {

    CodingSpecialistConfiguration load(AgentProfileId agentProfileId, long version);
}
