package io.crewscope.application.provider;

import io.crewscope.domain.provider.ProviderBinding;
import io.crewscope.domain.provider.ProviderDefinition;
import io.crewscope.domain.provider.ProviderImplementation;
import java.util.Objects;

/** Closed set of registry and Binding facts required by one built-in Provider. */
public record ProviderFoundation(
    ProviderDefinition definition,
    ProviderImplementation implementation,
    ProviderBinding binding) {

  public ProviderFoundation {
    definition = Objects.requireNonNull(definition, "definition");
    implementation = Objects.requireNonNull(implementation, "implementation");
    binding = Objects.requireNonNull(binding, "binding");
  }
}
