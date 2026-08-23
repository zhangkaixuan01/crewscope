package io.crewscope.agentscope.template;

import io.agentscope.harness.agent.HarnessAgent;
import io.crewscope.domain.agent.AgentRuntimeRole;
import java.util.EnumSet;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable registry dispatching an exact TemplateVersion to its bounded runtime-role factory. */
public final class AgentTemplateRuntimeRegistry {

    private final Map<AgentRuntimeRole, TemplateAgentRuntimeFactory> factories;

    public AgentTemplateRuntimeRegistry(List<TemplateAgentRuntimeFactory> factories) {
        EnumMap<AgentRuntimeRole, TemplateAgentRuntimeFactory> indexed =
                new EnumMap<>(AgentRuntimeRole.class);
        for (TemplateAgentRuntimeFactory factory : List.copyOf(
                Objects.requireNonNull(factories, "factories"))) {
            TemplateAgentRuntimeFactory previous = indexed.putIfAbsent(
                    Objects.requireNonNull(factory, "factory").runtimeRole(), factory);
            if (previous != null) {
                throw new IllegalStateException(
                        "Duplicate Template Agent factory for runtime role " + factory.runtimeRole());
            }
        }
        if (!indexed.keySet().equals(EnumSet.allOf(AgentRuntimeRole.class))) {
            throw new IllegalStateException(
                    "Personal, Team and Specialist Template Agent factories are all required");
        }
        this.factories = Map.copyOf(indexed);
    }

    public HarnessAgent create(TemplateAgentBuildRequest request) {
        TemplateAgentBuildRequest required = Objects.requireNonNull(request, "request");
        AgentRuntimeRole role = required.definition().template().runtimeRole();
        return factories.get(role).create(required);
    }
}
