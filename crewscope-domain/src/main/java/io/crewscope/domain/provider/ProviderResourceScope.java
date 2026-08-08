package io.crewscope.domain.provider;

import io.crewscope.domain.shared.error.DomainValidationException;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/** Explicit external resource set or an unrestricted resource dimension. */
public record ProviderResourceScope(boolean unrestricted, Set<String> resources) {

    public static final int MAX_RESOURCE_LENGTH = 500;

    public ProviderResourceScope {
        Objects.requireNonNull(resources, "resources");
        TreeSet<String> normalized = new TreeSet<>();
        for (String resource : resources) {
            if (resource == null
                    || resource.isBlank()
                    || resource.strip().length() > MAX_RESOURCE_LENGTH) {
                throw new DomainValidationException(
                        "providerResourceScope.resources", "contains an invalid resource key");
            }
            normalized.add(resource.strip());
        }
        if (unrestricted == !normalized.isEmpty()) {
            throw new DomainValidationException(
                    "providerResourceScope",
                    "must be either unrestricted or contain explicit resources");
        }
        resources = Set.copyOf(new LinkedHashSet<>(normalized));
    }

    public static ProviderResourceScope allResources() {
        return new ProviderResourceScope(true, Set.of());
    }

    public static ProviderResourceScope of(String... resources) {
        return new ProviderResourceScope(false, Set.copyOf(Arrays.asList(resources)));
    }

    public Optional<ProviderResourceScope> intersection(ProviderResourceScope other) {
        ProviderResourceScope required = Objects.requireNonNull(other, "other");
        if (unrestricted) {
            return Optional.of(required);
        }
        if (required.unrestricted) {
            return Optional.of(this);
        }
        Set<String> intersection = new TreeSet<>(resources);
        intersection.retainAll(required.resources);
        return intersection.isEmpty()
                ? Optional.empty()
                : Optional.of(new ProviderResourceScope(false, intersection));
    }
}
