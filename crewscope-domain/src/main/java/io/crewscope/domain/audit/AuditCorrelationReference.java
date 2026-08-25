package io.crewscope.domain.audit;

import io.crewscope.domain.shared.id.AggregateId;
import java.util.Optional;
import java.util.UUID;

/** Public navigation coordinates for one causal chain without Trace or request content. */
public record AuditCorrelationReference(
        UUID correlationId,
        Optional<UUID> causationId,
        Optional<UUID> domainEventId) {

    public AuditCorrelationReference {
        correlationId = AggregateId.requireValue(correlationId, "AuditCorrelation.correlationId");
        causationId = requireOptional(causationId, "AuditCorrelation.causationId");
        domainEventId = requireOptional(domainEventId, "AuditCorrelation.domainEventId");
    }

    private static Optional<UUID> requireOptional(Optional<UUID> value, String name) {
        Optional<UUID> required = java.util.Objects.requireNonNull(value, name);
        required.ifPresent(identifier -> AggregateId.requireValue(identifier, name));
        return required;
    }
}
