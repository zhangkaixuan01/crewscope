package io.crewscope.domain.agent;

import io.crewscope.domain.shared.error.DomainValidationException;
import java.util.Objects;
import java.util.Optional;

/** Explicit primary model selection and independently validated optional fallback. */
public record AgentDirectModelBinding(
        AgentModelSelection primary,
        Optional<AgentModelSelection> fallback) {

    public AgentDirectModelBinding {
        primary = Objects.requireNonNull(primary, "primary");
        fallback = Objects.requireNonNull(fallback, "fallback");
        if (fallback.filter(primary::sameTarget).isPresent()) {
            throw new DomainValidationException(
                    "agentConfiguration.modelBinding.fallback",
                    "must differ from the primary model selection");
        }
    }

    void appendCanonical(StringBuilder target) {
        primary.appendCanonical(target, "primary");
        if (fallback.isPresent()) {
            fallback.orElseThrow().appendCanonical(target, "fallback");
        } else {
            AgentConfigurationHash.append(target, "fallback:none");
        }
    }
}
