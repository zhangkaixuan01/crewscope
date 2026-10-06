package io.crewscope.agentscope.model;

import io.agentscope.core.model.Model;
import io.crewscope.domain.model.ModelCallAttribution;
import java.util.Objects;
import java.util.Optional;

/**
 * Primary and optional Fallback models built from one closed execution configuration. The
 * attribution coordinates travel with the instances so a caller can register the exact pair
 * into its call-scoped {@link ModelCallAttributionRegistry}; pairs without coordinates keep
 * the legacy shape (deployment env slot, tests) and stay unattributed.
 */
public record ResolvedAgentScopeModels(
        Model primary,
        Optional<Model> fallback,
        Optional<ModelCallAttribution> primaryAttribution,
        Optional<ModelCallAttribution> fallbackAttribution) {

    public ResolvedAgentScopeModels {
        primary = Objects.requireNonNull(primary, "primary");
        fallback = Objects.requireNonNull(fallback, "fallback");
        primaryAttribution = Objects.requireNonNull(primaryAttribution, "primaryAttribution");
        fallbackAttribution = Objects.requireNonNull(fallbackAttribution, "fallbackAttribution");
        if (fallbackAttribution.isPresent() && fallback.isEmpty()) {
            throw new IllegalArgumentException("fallbackAttribution requires a fallback model");
        }
    }

    /** Legacy pair without attribution coordinates (env-slot resolution and tests). */
    public ResolvedAgentScopeModels(Model primary, Optional<Model> fallback) {
        this(primary, fallback, Optional.empty(), Optional.empty());
    }
}
