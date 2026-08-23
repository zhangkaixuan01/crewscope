package io.crewscope.domain.action;

import io.crewscope.domain.provider.ConnectionGrantId;
import io.crewscope.domain.provider.ConnectionId;
import io.crewscope.domain.provider.ProviderBindingId;
import io.crewscope.domain.provider.ProviderDefinitionId;
import io.crewscope.domain.provider.ProviderExecutionIdentity;
import io.crewscope.domain.provider.ProviderImplementationId;
import io.crewscope.domain.provider.ProviderType;
import io.crewscope.domain.task.TaskFactHash;
import java.util.Objects;

/** Pinned Provider registry, external identity, Grant and effective-access coordinates. */
public record ProviderAuthorizationReference(
        ProviderBindingId bindingId,
        long bindingVersion,
        ProviderDefinitionId definitionId,
        long definitionVersion,
        ProviderImplementationId implementationId,
        long implementationVersion,
        ProviderType providerType,
        ProviderExecutionIdentity executionIdentity,
        ConnectionId connectionId,
        long connectionVersion,
        ConnectionGrantId grantId,
        long grantVersion,
        TaskFactHash effectiveAccessHash) {

    public ProviderAuthorizationReference {
        bindingId = Objects.requireNonNull(bindingId, "bindingId");
        definitionId = Objects.requireNonNull(definitionId, "definitionId");
        implementationId = Objects.requireNonNull(implementationId, "implementationId");
        providerType = Objects.requireNonNull(providerType, "providerType");
        executionIdentity = Objects.requireNonNull(executionIdentity, "executionIdentity");
        connectionId = Objects.requireNonNull(connectionId, "connectionId");
        grantId = Objects.requireNonNull(grantId, "grantId");
        effectiveAccessHash = Objects.requireNonNull(effectiveAccessHash, "effectiveAccessHash");
        if (bindingVersion < 0 || definitionVersion < 0 || implementationVersion < 0
                || connectionVersion < 0 || grantVersion < 0) {
            throw new IllegalArgumentException("Provider authority versions must not be negative");
        }
    }
}
