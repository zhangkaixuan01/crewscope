package io.crewscope.agentscope.model;

import io.agentscope.core.model.Model;
import java.util.Objects;
import java.util.Optional;

/** Primary and optional Fallback models built from one closed execution configuration. */
public record ResolvedAgentScopeModels(Model primary, Optional<Model> fallback) {

    public ResolvedAgentScopeModels {
        primary = Objects.requireNonNull(primary, "primary");
        fallback = Objects.requireNonNull(fallback, "fallback");
    }
}
