package io.crewscope.application.agent;

import java.util.Set;

public record AgentRuntimeDescriptor(String name, String version, Set<String> capabilities) {

    public AgentRuntimeDescriptor {
        capabilities = Set.copyOf(capabilities);
    }
}
