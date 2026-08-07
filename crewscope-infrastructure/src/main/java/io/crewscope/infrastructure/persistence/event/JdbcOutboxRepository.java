package io.crewscope.infrastructure.persistence.event;

import io.crewscope.application.event.OutboxRepository;
import io.crewscope.application.event.PendingOutboxEvent;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Creates durable PENDING publication rows without duplicating the DomainEvent payload. */
@Repository
public class JdbcOutboxRepository implements OutboxRepository {

    private final JdbcTemplate jdbcTemplate;

    public JdbcOutboxRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(PendingOutboxEvent event) {
        PendingOutboxEvent source = Objects.requireNonNull(event, "event");
        jdbcTemplate.update(
                """
                INSERT INTO crewscope.outbox_event (
                    id, domain_event_id, topic, partition_key,
                    delivery_status, retry_count, next_delivery_at,
                    created_at, delivered_at, version, updated_at
                ) VALUES (?, ?, ?, ?, 'PENDING', 0, NULL, ?, NULL, 0, ?)
                """,
                source.id(),
                source.domainEventId(),
                source.topic(),
                source.partitionKey(),
                source.createdAt().toOffsetDateTime(),
                source.createdAt().toOffsetDateTime());
    }
}
