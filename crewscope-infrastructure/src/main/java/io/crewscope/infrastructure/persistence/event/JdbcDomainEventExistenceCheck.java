package io.crewscope.infrastructure.persistence.event;

import io.crewscope.application.event.DomainEventExistenceCheck;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;

/**
 * JDBC existence probe over the canonical {@code domain_event} log (M10-F03). Kept separate
 * from the append-only {@link io.crewscope.application.event.DomainEventStore} port so the
 * write side stays a pure sink.
 */
public class JdbcDomainEventExistenceCheck implements DomainEventExistenceCheck {

    private static final String EXISTS_BY_ID = """
            SELECT 1 FROM crewscope.domain_event WHERE event_id = ? LIMIT 1
            """;

    private final JdbcTemplate jdbcTemplate;

    public JdbcDomainEventExistenceCheck(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
    }

    @Override
    public boolean exists(UUID eventId) {
        return Boolean.TRUE.equals(jdbcTemplate.query(
                EXISTS_BY_ID,
                (ResultSetExtractor<Boolean>) resultSet -> resultSet.next(),
                Objects.requireNonNull(eventId, "eventId")));
    }
}
