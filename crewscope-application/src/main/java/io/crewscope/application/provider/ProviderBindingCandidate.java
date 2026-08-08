package io.crewscope.application.provider;

import io.crewscope.domain.provider.Connection;
import io.crewscope.domain.provider.ConnectionGrant;
import io.crewscope.domain.provider.ProviderAccessScope;
import io.crewscope.domain.provider.ProviderBinding;
import io.crewscope.domain.provider.ProviderDefinition;
import io.crewscope.domain.provider.ProviderImplementation;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.Objects;
import java.util.Optional;

/** Closed read-only candidate whose pinned registry and authorization facts are currently valid. */
public record ProviderBindingCandidate(
        ProviderBinding binding,
        ProviderDefinition definition,
        ProviderImplementation implementation,
        Optional<Connection> connection,
        Optional<ConnectionGrant> connectionGrant,
        ProviderAccessScope effectiveAccess) {

    public ProviderBindingCandidate {
        binding = Objects.requireNonNull(binding, "binding");
        definition = Objects.requireNonNull(definition, "definition");
        implementation = Objects.requireNonNull(implementation, "implementation");
        connection = Objects.requireNonNull(connection, "connection");
        connectionGrant = Objects.requireNonNull(connectionGrant, "connectionGrant");
        effectiveAccess = Objects.requireNonNull(effectiveAccess, "effectiveAccess");
    }

    /** Fails closed for disabled, revoked, expired, stale-version or mismatched facts. */
    public static ProviderBindingCandidate resolve(
            ProviderBinding binding,
            ProviderDefinition definition,
            ProviderImplementation implementation,
            Optional<Connection> connection,
            Optional<ConnectionGrant> connectionGrant,
            UtcTimestamp now) {
        ProviderBinding requiredBinding = Objects.requireNonNull(binding, "binding");
        ProviderAccessScope access = requiredBinding
                .currentAccess(definition, implementation, connection, connectionGrant, now)
                .orElseThrow(() -> new DomainValidationException(
                        "providerBindingCandidate",
                        "must contain current active and mutually compatible Provider facts"));
        return new ProviderBindingCandidate(
                requiredBinding,
                definition,
                implementation,
                connection,
                connectionGrant,
                access);
    }
}
