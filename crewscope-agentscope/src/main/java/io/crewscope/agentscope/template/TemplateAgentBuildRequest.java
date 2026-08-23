package io.crewscope.agentscope.template;

import io.agentscope.core.tool.Toolkit;
import java.util.Objects;

/** Complete safe input accepted by Personal, Team and Specialist Template factories. */
public record TemplateAgentBuildRequest(
        AgentTemplateRuntimeDefinition definition,
        TemplateAgentSessionIdentity identity,
        Toolkit toolkit) {

    public TemplateAgentBuildRequest {
        definition = Objects.requireNonNull(definition, "definition");
        identity = Objects.requireNonNull(identity, "identity");
        toolkit = Objects.requireNonNull(toolkit, "toolkit");
        identity.requireDefinition(definition);
        if (!toolkit.getToolNames().equals(definition.enabledToolNames())) {
            throw new IllegalArgumentException(
                    "Runtime Toolkit must exactly match the Template-enabled Tool surface");
        }
    }
}
