package io.crewscope.infrastructure.event;

import io.crewscope.application.event.publication.DomainEventConsumer;
import io.crewscope.application.event.publication.EventPublication;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Executes a named consumer and its event receipt in one local database transaction. */
public class IdempotentEventDispatcher {

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    public IdempotentEventDispatcher(
            JdbcTemplate jdbcTemplate, PlatformTransactionManager transactionManager, Clock clock) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
        this.transactionTemplate = new TransactionTemplate(
                Objects.requireNonNull(transactionManager, "transactionManager"));
        this.transactionTemplate.setName("crewscope-idempotent-event-consumer");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Returns true only when this call performed the consumer side effect. */
    public boolean dispatch(DomainEventConsumer consumer, EventPublication publication) {
        DomainEventConsumer target = Objects.requireNonNull(consumer, "consumer");
        EventPublication event = Objects.requireNonNull(publication, "publication");
        String consumerName = requireConsumerName(target.consumerName());
        Boolean consumed = transactionTemplate.execute(status -> {
            int inserted = jdbcTemplate.update(
                    """
                    INSERT INTO crewscope.event_consumer_receipt (
                        consumer_name, domain_event_id, processed_at
                    ) VALUES (?, ?, ?)
                    ON CONFLICT (consumer_name, domain_event_id) DO NOTHING
                    """,
                    consumerName,
                    event.eventId(),
                    OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
            if (inserted == 0) {
                return false;
            }
            // The receipt rolls back with the handler side effect when the consumer fails.
            target.consume(event);
            return true;
        });
        return Boolean.TRUE.equals(consumed);
    }

    private static String requireConsumerName(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("consumerName must not be blank");
        }
        String normalized = value.strip();
        if (normalized.length() > 200) {
            throw new IllegalArgumentException("consumerName must contain at most 200 characters");
        }
        return normalized;
    }
}
