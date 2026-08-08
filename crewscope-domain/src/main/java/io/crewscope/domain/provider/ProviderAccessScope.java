package io.crewscope.domain.provider;

import java.util.Objects;
import java.util.Optional;

/** Capability and resource dimensions that must both survive authorization intersection. */
public record ProviderAccessScope(
        ProviderCapabilities capabilities, ProviderResourceScope resources) {

    public ProviderAccessScope {
        capabilities = Objects.requireNonNull(capabilities, "capabilities");
        resources = Objects.requireNonNull(resources, "resources");
    }

    public Optional<ProviderAccessScope> intersection(ProviderAccessScope other) {
        ProviderAccessScope required = Objects.requireNonNull(other, "other");
        Optional<ProviderCapabilities> capabilityIntersection =
                capabilities.intersection(required.capabilities);
        Optional<ProviderResourceScope> resourceIntersection =
                resources.intersection(required.resources);
        if (capabilityIntersection.isEmpty() || resourceIntersection.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new ProviderAccessScope(
                capabilityIntersection.orElseThrow(), resourceIntersection.orElseThrow()));
    }
}
