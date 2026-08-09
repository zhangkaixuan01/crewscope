package io.crewscope.application.execution;

import java.util.Arrays;
import java.util.Objects;
import java.util.Set;

/** Immutable capability snapshot used for routing and later PolicySnapshot pinning. */
public record RuntimeCapabilities(Set<RuntimeCapability> values) {

    public RuntimeCapabilities {
        values = Set.copyOf(Objects.requireNonNull(values, "values"));
    }

    public static RuntimeCapabilities of(RuntimeCapability... values) {
        return new RuntimeCapabilities(Set.copyOf(Arrays.asList(values)));
    }

    public boolean supports(RuntimeCapability capability) {
        return values.contains(Objects.requireNonNull(capability, "capability"));
    }

    public boolean supportsAll(Set<RuntimeCapability> required) {
        return values.containsAll(Objects.requireNonNull(required, "required"));
    }
}
