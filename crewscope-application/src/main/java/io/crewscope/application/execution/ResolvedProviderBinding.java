package io.crewscope.application.execution;

import io.crewscope.application.provider.ProviderBindingCandidate;
import io.crewscope.domain.provider.ConnectionGrantId;
import io.crewscope.domain.provider.ConnectionId;
import io.crewscope.domain.provider.ProviderAccessScope;
import io.crewscope.domain.provider.ProviderBindingId;
import io.crewscope.domain.provider.ProviderBindingTarget;
import io.crewscope.domain.provider.ProviderImplementationId;
import io.crewscope.domain.provider.ProviderOwner;
import io.crewscope.domain.provider.ProviderType;
import java.util.Objects;
import java.util.Optional;

/** Credential-free Provider authorization snapshot carried during one Agent invocation. */
public record ResolvedProviderBinding(
        ProviderBindingId bindingId,
        ProviderType providerType,
        ProviderBindingTarget target,
        ProviderOwner owner,
        ProviderImplementationId implementationId,
        long implementationVersion,
        Optional<ConnectionId> connectionId,
        Optional<ConnectionGrantId> connectionGrantId,
        ProviderAccessScope effectiveAccess) {

    public ResolvedProviderBinding {
        bindingId = Objects.requireNonNull(bindingId, "bindingId");
        providerType = Objects.requireNonNull(providerType, "providerType");
        target = Objects.requireNonNull(target, "target");
        owner = Objects.requireNonNull(owner, "owner");
        implementationId = Objects.requireNonNull(implementationId, "implementationId");
        connectionId = Objects.requireNonNull(connectionId, "connectionId");
        connectionGrantId = Objects.requireNonNull(connectionGrantId, "connectionGrantId");
        effectiveAccess = Objects.requireNonNull(effectiveAccess, "effectiveAccess");
        if (implementationVersion < 0) {
            throw new IllegalArgumentException("implementationVersion must not be negative");
        }
    }

    /** Copies only stable identifiers and the already narrowed access envelope. */
    public static ResolvedProviderBinding from(ProviderBindingCandidate candidate) {
        ProviderBindingCandidate required = Objects.requireNonNull(candidate, "candidate");
        return new ResolvedProviderBinding(
                required.binding().id(),
                required.binding().providerType(),
                required.binding().target(),
                required.binding().owner(),
                required.implementation().id(),
                required.implementation().version(),
                required.connection().map(connection -> connection.id()),
                required.connectionGrant().map(grant -> grant.id()),
                required.effectiveAccess());
    }
}
