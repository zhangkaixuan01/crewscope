package io.crewscope.application.provider;

import io.crewscope.domain.provider.ProviderDefinition;
import io.crewscope.domain.provider.ProviderImplementation;
import io.crewscope.domain.provider.ProviderType;
import java.util.Objects;

/** Runtime-facing descriptor derived from one compatible registered Provider implementation. */
public record ProviderDescriptor(
        ProviderType type, String implementationId, String interfaceVersion, String displayName) {

    public ProviderDescriptor {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(implementationId, "implementationId");
        Objects.requireNonNull(interfaceVersion, "interfaceVersion");
        Objects.requireNonNull(displayName, "displayName");
    }

    public static ProviderDescriptor from(
            ProviderDefinition definition, ProviderImplementation implementation) {
        ProviderDefinition requiredDefinition = Objects.requireNonNull(
                definition, "definition");
        ProviderImplementation requiredImplementation = Objects.requireNonNull(
                implementation, "implementation");
        if (!requiredImplementation.supports(
                requiredDefinition, requiredImplementation.capabilities())) {
            throw new IllegalArgumentException(
                    "ProviderImplementation must be compatible with ProviderDefinition");
        }
        return new ProviderDescriptor(
                requiredDefinition.type(),
                requiredImplementation.key(),
                requiredDefinition.interfaceVersion(),
                requiredDefinition.displayName());
    }
}
