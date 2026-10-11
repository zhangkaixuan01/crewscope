package io.crewscope.application.github;

import io.crewscope.domain.provider.ProviderDefinition;
import io.crewscope.domain.provider.ProviderImplementation;
import java.util.Objects;

/** Built-in Provider definition and implementation resolved for one bind command. */
record GitHubProviderFoundationFacts(
        ProviderDefinition definition, ProviderImplementation implementation) {

    GitHubProviderFoundationFacts {
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(implementation, "implementation");
    }
}
