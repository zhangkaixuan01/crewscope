package io.crewscope.agentscope;

import io.agentscope.core.model.Model;
import io.agentscope.core.model.ModelRegistry;

/** Resolves a server-side model configuration key into an AgentScope Model instance. */
@FunctionalInterface
public interface AgentScopeModelResolver {

    Model resolve(String modelId);

    static AgentScopeModelResolver registry() {
        return ModelRegistry::resolve;
    }
}
