package io.crewscope.infrastructure.persistence.operations;

import io.crewscope.application.event.PendingOutboxEvent;
import io.crewscope.application.event.json.DomainEventEnvelopeJsonCodec;
import io.crewscope.domain.shared.DomainEvent;
import io.crewscope.domain.shared.event.AggregateReference;
import io.crewscope.domain.shared.event.DomainEventEnvelope;
import io.crewscope.domain.shared.event.EventActor;
import io.crewscope.domain.shared.event.EventActorType;
import io.crewscope.domain.shared.event.EventType;
import io.crewscope.domain.shared.event.SchemaVersion;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.infrastructure.event.projection.AuditEventProjector;
import io.crewscope.infrastructure.event.projection.ProjectionEvent;
import io.crewscope.infrastructure.persistence.event.JdbcDomainEventStore;
import io.crewscope.infrastructure.persistence.event.JdbcOutboxRepository;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** Writes one safe DomainEvent, Outbox row and append-only Audit projection atomically. */
@Component
public class AtomicOperationsEventWriter {

    private static final String AUDIT_CONSUMER = "projection:" + AuditEventProjector.PROJECTION_NAME;

    private final JdbcTemplate jdbcTemplate;
    private final JdbcDomainEventStore eventStore;
    private final JdbcOutboxRepository outboxRepository;
    private final AuditEventProjector auditProjector;
    private final DomainEventEnvelopeJsonCodec eventJson;

    public AtomicOperationsEventWriter(
            JdbcTemplate jdbcTemplate,
            JdbcDomainEventStore eventStore,
            JdbcOutboxRepository outboxRepository,
            AuditEventProjector auditProjector,
            ObjectMapper objectMapper) {
        this.jdbcTemplate = java.util.Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
        this.eventStore = java.util.Objects.requireNonNull(eventStore, "eventStore");
        this.outboxRepository = java.util.Objects.requireNonNull(outboxRepository, "outboxRepository");
        this.auditProjector = java.util.Objects.requireNonNull(auditProjector, "auditProjector");
        this.eventJson = new DomainEventEnvelopeJsonCodec(
                java.util.Objects.requireNonNull(objectMapper, "objectMapper"));
    }

    /**
     * The dispatcher receipt is written with the direct Audit row so later Outbox delivery skips
     * only this already committed consumer while all other consumers still receive the event.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID append(
            String eventType,
            String aggregateType,
            UUID aggregateId,
            OrganizationId organizationId,
            PrincipalId actorId,
            UtcTimestamp occurredAt,
            DomainEvent payload) {
        UUID eventId = UUID.randomUUID();
        DomainEventEnvelope<DomainEvent> envelope = new DomainEventEnvelope<>(
                eventId,
                EventType.from(eventType),
                SchemaVersion.V1,
                organizationId,
                Optional.empty(),
                Optional.empty(),
                new AggregateReference(aggregateType, aggregateId),
                0,
                EventActor.principal(EventActorType.USER, actorId),
                aggregateId,
                Optional.empty(),
                Optional.of("operations:" + aggregateId),
                occurredAt,
                payload);
        eventStore.append(envelope);
        outboxRepository.enqueue(PendingOutboxEvent.fromDomain(UUID.randomUUID(), envelope));

        ProjectionEvent projectionEvent = new ProjectionEvent(
                eventId,
                eventType,
                SchemaVersion.V1.toString(),
                organizationId.value(),
                Optional.empty(),
                Optional.empty(),
                aggregateType,
                aggregateId,
                0,
                EventActorType.USER,
                Optional.of(actorId.value()),
                aggregateId,
                Optional.empty(),
                occurredAt,
                eventJson.encodePayload(payload));
        auditProjector.project(projectionEvent);
        jdbcTemplate.update(
                """
                INSERT INTO crewscope.event_consumer_receipt (
                    consumer_name, domain_event_id, processed_at
                ) VALUES (?, ?, ?)
                ON CONFLICT (consumer_name, domain_event_id) DO NOTHING
                """,
                AUDIT_CONSUMER,
                eventId,
                OffsetDateTime.from(occurredAt.toOffsetDateTime()));
        return eventId;
    }
}
