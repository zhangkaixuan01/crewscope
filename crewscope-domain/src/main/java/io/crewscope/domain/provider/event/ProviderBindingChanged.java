package io.crewscope.domain.provider.event;

import io.crewscope.domain.provider.ProviderBinding;
import io.crewscope.domain.provider.ProviderRegistrationStatus;
import io.crewscope.domain.provider.ProviderType;
import io.crewscope.domain.shared.DomainEvent;
import java.util.Objects;

/** Version 1 non-secret payload for an external ProviderBinding lifecycle change. */
public record ProviderBindingChanged(
        ProviderType providerType,
        ProviderRegistrationStatus status,
        boolean defaultUsage,
        boolean connectionBacked)
        implements DomainEvent {

    public ProviderBindingChanged {
        Objects.requireNonNull(providerType, "providerType");
        Objects.requireNonNull(status, "status");
    }

    public static ProviderBindingChanged from(ProviderBinding binding) {
        ProviderBinding value = Objects.requireNonNull(binding, "binding");
        return new ProviderBindingChanged(
                value.providerType(),
                value.status(),
                value.defaultUsage(),
                value.connectionId().isPresent());
    }
}
