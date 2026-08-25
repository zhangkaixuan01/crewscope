package io.crewscope.domain.audit;

import io.crewscope.domain.provider.ConnectionId;
import io.crewscope.domain.provider.ProviderBindingId;
import io.crewscope.domain.task.TaskFactHash;
import java.util.Objects;
import java.util.Optional;

/** Safe provider coordinates without endpoint, credential or provider response content. */
public record AuditProviderReference(
        ProviderBindingId providerBindingId,
        ConnectionId connectionId,
        Optional<TaskFactHash> externalOperationHash) {

    public AuditProviderReference {
        providerBindingId = Objects.requireNonNull(providerBindingId, "providerBindingId");
        connectionId = Objects.requireNonNull(connectionId, "connectionId");
        externalOperationHash = Objects.requireNonNull(
                externalOperationHash, "externalOperationHash");
    }
}
