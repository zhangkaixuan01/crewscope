package io.crewscope.server.api;

import io.crewscope.domain.audit.AuditQueryEvent;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;

/** Public Audit fact containing only Registry-reviewed fields and safe references. */
public record AuditEventResponse(
        UUID eventId,
        String eventType,
        int sourceSchemaVersion,
        String category,
        String outcome,
        String retentionLevel,
        Instant occurredAt,
        IdentityResponse identity,
        SubjectResponse subject,
        ProviderResponse provider,
        CorrelationResponse correlation,
        Map<String, String> summary) {

    static AuditEventResponse from(AuditQueryEvent event) {
        AuditQueryEvent value = Objects.requireNonNull(event, "event");
        var identity = value.identity();
        var actor = identity.actor();
        var correlation = value.correlation();
        return new AuditEventResponse(
                value.id().value(),
                value.summary().eventType().value(),
                value.summary().sourceSchemaVersion().value(),
                value.category().name(),
                value.outcome().name(),
                value.retentionLevel().name(),
                value.occurredAt().value(),
                new IdentityResponse(
                        identity.initiatorId().map(id -> id.value()).orElse(null),
                        actor.type().name(),
                        actor.id().map(id -> id.value()).orElse(null),
                        identity.agentPrincipalId().map(id -> id.value()).orElse(null)),
                new SubjectResponse(value.subject().type(), value.subject().id()),
                value.providerReference().map(reference -> new ProviderResponse(
                                reference.providerBindingId().value(),
                                reference.connectionId().value(),
                                reference.externalOperationHash()
                                        .map(hash -> hash.value())
                                        .orElse(null)))
                        .orElse(null),
                new CorrelationResponse(
                        correlation.correlationId(),
                        correlation.causationId().orElse(null),
                        correlation.domainEventId().orElse(null)),
                Map.copyOf(new TreeMap<>(value.summary().values())));
    }

    public record IdentityResponse(
            UUID initiatorId, String actorType, UUID actorId, UUID agentPrincipalId) {}

    public record SubjectResponse(String type, UUID id) {}

    public record ProviderResponse(
            UUID providerBindingId, UUID connectionId, String externalOperationHash) {}

    public record CorrelationResponse(
            UUID correlationId, UUID causationId, UUID domainEventId) {}
}
