package io.crewscope.agentscope.template;

import io.agentscope.harness.agent.HarnessAgent;
import io.crewscope.domain.agent.AgentRuntimeRole;

/** Role-specific factory registered behind the exact Template runtime dispatch boundary. */
public interface TemplateAgentRuntimeFactory {

    AgentRuntimeRole runtimeRole();

    HarnessAgent create(TemplateAgentBuildRequest request);
}
