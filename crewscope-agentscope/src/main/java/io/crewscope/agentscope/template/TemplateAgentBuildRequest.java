package io.crewscope.agentscope.template;

import io.agentscope.core.tool.Toolkit;
import io.crewscope.agentscope.ModelToolNamePolicy;
import java.util.Objects;
import java.util.Set;

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
        if (!toolkit.getToolNames().equals(runtimeToolNames(definition))) {
            throw new IllegalArgumentException(
                    "Runtime Toolkit must exactly match the Template-enabled Tool surface");
        }
    }

    /** Exact names exposed to the model after explicit canonical-to-runtime aliasing. */
    public Set<String> runtimeToolNames() {
        return runtimeToolNames(definition);
    }

    private static Set<String> runtimeToolNames(AgentTemplateRuntimeDefinition definition) {
        return ModelToolNamePolicy.runtimeAliases(definition.enabledToolNames());
    }
}
