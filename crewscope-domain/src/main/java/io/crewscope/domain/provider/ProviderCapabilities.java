package io.crewscope.domain.provider;

import io.crewscope.domain.shared.error.DomainValidationException;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/** Non-empty immutable capability set with subset and intersection operations. */
public record ProviderCapabilities(Set<ProviderCapability> values) {

    public ProviderCapabilities {
        Objects.requireNonNull(values, "values");
        TreeSet<ProviderCapability> normalized = new TreeSet<>(values);
        if (normalized.isEmpty()) {
            throw new DomainValidationException(
                    "providerCapabilities", "must contain at least one capability");
        }
        values = Set.copyOf(new LinkedHashSet<>(normalized));
    }

    public static ProviderCapabilities of(String... values) {
        Objects.requireNonNull(values, "values");
        return from(Arrays.asList(values));
    }

    public static ProviderCapabilities from(Collection<String> values) {
        Objects.requireNonNull(values, "values");
        return new ProviderCapabilities(values.stream()
                .map(ProviderCapability::new)
                .collect(java.util.stream.Collectors.toSet()));
    }

    public boolean includes(ProviderCapabilities required) {
        return values.containsAll(Objects.requireNonNull(required, "required").values);
    }

    public Optional<ProviderCapabilities> intersection(ProviderCapabilities other) {
        Set<ProviderCapability> intersection = new TreeSet<>(values);
        intersection.retainAll(Objects.requireNonNull(other, "other").values);
        return intersection.isEmpty()
                ? Optional.empty()
                : Optional.of(new ProviderCapabilities(intersection));
    }
}
