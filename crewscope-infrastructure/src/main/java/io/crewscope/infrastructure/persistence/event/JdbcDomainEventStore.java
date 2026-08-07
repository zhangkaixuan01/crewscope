package io.crewscope.infrastructure.persistence.event;

import io.crewscope.application.event.DomainEventStore;
import io.crewscope.application.event.json.DomainEventEnvelopeJsonCodec;
import io.crewscope.domain.shared.DomainEvent;
import io.crewscope.domain.shared.event.DomainEventEnvelope;
import io.crewscope.domain.shared.id.PrincipalId;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** PostgreSQL append adapter for the canonical DomainEvent Envelope. */
@Repository
public class JdbcDomainEventStore implements DomainEventStore {

    private final JdbcTemplate jdbcTemplate;
    private final DomainEventEnvelopeJsonCodec jsonCodec;

    public JdbcDomainEventStore(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
        this.jsonCodec = new DomainEventEnvelopeJsonCodec(
                Objects.requireNonNull(objectMapper, "objectMapper"));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void append(DomainEventEnvelope<? extends DomainEvent> event) {
        DomainEventEnvelope<? extends DomainEvent> source =
                Objects.requireNonNull(event, "event");
        jdbcTemplate.update(
                """
                INSERT INTO crewscope.domain_event (
                    event_id, event_type, schema_version,
                    organization_id, team_id, workspace_id,
                    subject_type, subject_id, aggregate_version,
                    actor_type, actor_id,
                    correlation_id, causation_id, idempotency_key,
                    occurred_at, payload
                ) VALUES (
                    ?, ?, ?,
                    ?, ?, ?,
                    ?, ?, ?,
                    ?, ?,
                    ?, ?, ?,
                    ?, CAST(? AS jsonb)
                )
                """,
                source.eventId(),
                source.eventType().value(),
                source.schemaVersion().toString(),
                source.organizationId().value(),
                source.teamId().map(id -> id.value()).orElse(null),
                source.workspaceId().map(id -> id.value()).orElse(null),
                source.aggregate().type(),
                source.aggregate().id(),
                source.aggregateVersion(),
                source.actor().type().name(),
                source.actor().id().map(PrincipalId::value).orElse(null),
                source.correlationId(),
                source.causationId().orElse(null),
                source.idempotencyKey().orElse(null),
                source.occurredAt().toOffsetDateTime(),
                jsonCodec.encodePayload(source.payload()));
    }
}
