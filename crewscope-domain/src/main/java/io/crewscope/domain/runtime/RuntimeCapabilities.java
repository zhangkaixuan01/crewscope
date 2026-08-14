package io.crewscope.domain.runtime;

import io.crewscope.domain.shared.error.DomainValidationException;
import java.util.Arrays;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** Immutable routing snapshot covering platform features, languages and build systems. */
public record RuntimeCapabilities(
        Set<RuntimeCapability> values,
        Set<String> languages,
        Set<String> buildSystems) {

    private static final int MAX_ITEMS = 100;
    private static final String KEY_PATTERN = "[a-z][a-z0-9.+_-]{0,63}";

    public RuntimeCapabilities {
        values = Set.copyOf(Objects.requireNonNull(values, "values"));
        languages = requireKeys(languages, "runtimeCapabilities.languages");
        buildSystems = requireKeys(buildSystems, "runtimeCapabilities.buildSystems");
        if (values.size() > MAX_ITEMS) {
            throw new DomainValidationException(
                    "runtimeCapabilities.values", "must not exceed 100 values");
        }
    }

    /** Compatibility constructor for feature-only M2 runtime profiles. */
    public RuntimeCapabilities(Set<RuntimeCapability> values) {
        this(values, Set.of(), Set.of());
    }

    public static RuntimeCapabilities of(RuntimeCapability... values) {
        return new RuntimeCapabilities(Set.copyOf(Arrays.asList(values)));
    }

    public static RuntimeCapabilities of(
            Set<RuntimeCapability> values, Set<String> languages, Set<String> buildSystems) {
        return new RuntimeCapabilities(values, languages, buildSystems);
    }

    public boolean supports(RuntimeCapability capability) {
        return values.contains(Objects.requireNonNull(capability, "capability"));
    }

    public boolean supportsAll(Set<RuntimeCapability> required) {
        return values.containsAll(Objects.requireNonNull(required, "required"));
    }

    public boolean supports(RuntimeCapabilities required) {
        RuntimeCapabilities target = Objects.requireNonNull(required, "required");
        return values.containsAll(target.values)
                && languages.containsAll(target.languages)
                && buildSystems.containsAll(target.buildSystems);
    }

    public boolean isSubsetOf(RuntimeCapabilities available) {
        return Objects.requireNonNull(available, "available").supports(this);
    }

    private static Set<String> requireKeys(Set<String> values, String field) {
        Set<String> required = Objects.requireNonNull(values, field).stream()
                .map(value -> Objects.requireNonNull(value, "value").strip().toLowerCase())
                .collect(Collectors.toUnmodifiableSet());
        if (required.size() > MAX_ITEMS
                || required.stream().anyMatch(value -> !value.matches(KEY_PATTERN))) {
            throw new DomainValidationException(field, "contains invalid or unsupported keys");
        }
        return required;
    }
}
