package io.crewscope.domain.task;

import io.crewscope.domain.shared.error.DomainValidationException;
import java.util.Objects;
import java.util.Set;

/** One immutable, ordered and dependency-aware step in a published PlanVersion. */
public record PlanStep(
        String key,
        int sequence,
        String title,
        PlanStepType type,
        Set<String> dependencyKeys,
        Set<ExecutionCapability> requiredCapabilities,
        Set<String> requiredTools,
        boolean critical) {

    private static final String KEY_PATTERN = "[a-z][a-z0-9-]{0,63}";

    public PlanStep {
        key = requireKey(key);
        if (sequence < 1) {
            throw new DomainValidationException("planStep.sequence", "must be positive");
        }
        title = requireTitle(title);
        type = Objects.requireNonNull(type, "type");
        dependencyKeys = Set.copyOf(Objects.requireNonNull(dependencyKeys, "dependencyKeys"));
        if (dependencyKeys.contains(key)
                || dependencyKeys.stream().anyMatch(value -> !value.matches(KEY_PATTERN))) {
            throw new DomainValidationException(
                    "planStep.dependencyKeys", "must contain valid keys other than itself");
        }
        requiredCapabilities = Set.copyOf(
                Objects.requireNonNull(requiredCapabilities, "requiredCapabilities"));
        requiredTools = PolicySnapshot.requireKeys(
                requiredTools, "planStep.requiredTools", true);
    }

    static String requireKey(String value) {
        if (value == null || !value.matches(KEY_PATTERN)) {
            throw new DomainValidationException(
                    "planStep.key", "must be a stable lowercase key");
        }
        return value;
    }

    private static String requireTitle(String value) {
        if (value == null || value.isBlank()) {
            throw new DomainValidationException("planStep.title", "must not be blank");
        }
        String normalized = value.strip();
        if (normalized.length() > 300 || normalized.chars().anyMatch(Character::isISOControl)) {
            throw new DomainValidationException(
                    "planStep.title", "must not exceed 300 safe characters");
        }
        return normalized;
    }
}
