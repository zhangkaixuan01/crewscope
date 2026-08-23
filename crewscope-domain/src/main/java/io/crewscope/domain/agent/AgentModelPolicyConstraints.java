package io.crewscope.domain.agent;

import io.crewscope.domain.model.ModelCapability;
import io.crewscope.domain.model.ModelDataRetentionMode;
import io.crewscope.domain.model.ModelRegion;
import io.crewscope.domain.shared.error.DomainValidationException;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Organization and Team model policy intersected before AgentScope model construction. */
public record AgentModelPolicyConstraints(
        Set<ModelCapability> requiredCapabilities,
        Set<ModelRegion> allowedRegions,
        Set<ModelDataRetentionMode> allowedRetentionModes,
        Optional<Duration> maximumRetention,
        boolean providerTrainingAllowed,
        long minimumContextWindowTokens,
        long minimumOutputTokens) {

    public AgentModelPolicyConstraints {
        requiredCapabilities = Set.copyOf(
                Objects.requireNonNull(requiredCapabilities, "requiredCapabilities"));
        allowedRegions = Set.copyOf(Objects.requireNonNull(allowedRegions, "allowedRegions"));
        allowedRetentionModes = Set.copyOf(
                Objects.requireNonNull(allowedRetentionModes, "allowedRetentionModes"));
        maximumRetention = Objects.requireNonNull(maximumRetention, "maximumRetention");
        if (allowedRegions.isEmpty() || allowedRetentionModes.isEmpty()) {
            throw new DomainValidationException(
                    "agentModelPolicy", "allowed Regions and retention modes must not be empty");
        }
        maximumRetention.ifPresent(value -> {
            if (value.isZero() || value.isNegative() || value.getNano() != 0) {
                throw new DomainValidationException(
                        "agentModelPolicy.maximumRetention",
                        "must use positive whole seconds");
            }
        });
        if (minimumContextWindowTokens < 1 || minimumOutputTokens < 1) {
            throw new DomainValidationException(
                    "agentModelPolicy.tokenLimits", "must be positive");
        }
    }
}
