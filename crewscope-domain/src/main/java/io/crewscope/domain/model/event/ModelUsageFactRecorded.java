package io.crewscope.domain.model.event;

import io.crewscope.domain.model.ModelConnectionId;
import io.crewscope.domain.model.ModelId;
import io.crewscope.domain.model.ModelProviderKey;
import io.crewscope.domain.model.ModelTokenUsage;
import io.crewscope.domain.model.ModelUsageFactId;
import io.crewscope.domain.model.ModelUsageRole;
import io.crewscope.domain.shared.DomainEvent;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.Objects;

/**
 * Unified model usage fact (S01 §3.9): one durable event per real Provider call attempt,
 * carrying the stable call identity (the deduplication key), the resolved connection
 * coordinates, the business role of the call and the token quadruple. Aggregation and
 * price projection belong to F03; this shape is the frozen input contract.
 *
 * <p>Token zeros mean "Provider did not report this counter" — identical to the sanitized
 * runtime usage semantics — never a claim that the call was free.
 */
public record ModelUsageFactRecorded(
        ModelUsageFactId callId,
        ModelUsageRole role,
        int attempt,
        ModelProviderKey providerKey,
        ModelId modelId,
        ModelConnectionId connectionId,
        long connectionVersion,
        ModelTokenUsage usage,
        UtcTimestamp occurredAt) implements DomainEvent {

    public ModelUsageFactRecorded {
        callId = Objects.requireNonNull(callId, "callId");
        role = Objects.requireNonNull(role, "role");
        if (attempt < 1) {
            throw new IllegalArgumentException("attempt must be positive");
        }
        providerKey = Objects.requireNonNull(providerKey, "providerKey");
        modelId = Objects.requireNonNull(modelId, "modelId");
        connectionId = Objects.requireNonNull(connectionId, "connectionId");
        if (connectionVersion < 0) {
            throw new IllegalArgumentException("connectionVersion must not be negative");
        }
        usage = Objects.requireNonNull(usage, "usage");
        occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
    }
}
