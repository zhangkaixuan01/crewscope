package io.crewscope.application.provider;

import java.util.Objects;

public record ProviderDescriptor(
        ProviderType type, String implementationId, String interfaceVersion, String displayName) {

    public ProviderDescriptor {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(implementationId, "implementationId");
        Objects.requireNonNull(interfaceVersion, "interfaceVersion");
        Objects.requireNonNull(displayName, "displayName");
    }
}
