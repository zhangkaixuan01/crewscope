package io.crewscope.infrastructure.persistence.observability;

import io.crewscope.application.observability.ModelUsageFactScan;
import io.crewscope.application.observability.ModelUsageRollupService;
import io.crewscope.infrastructure.event.JdbcDomainEventJsonMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Keyset scan over the canonical {@code domain_event} log for the rollup rebuild
 * (M10-F03). The event type is filtered in SQL and every returned row is rebuilt into
 * envelope JSON through {@link JdbcDomainEventJsonMapper} — the same mapper the outbox
 * publisher uses — so the rebuild consumes byte-identical envelopes to the incremental
 * consumer path.
 */
public class JdbcModelUsageFactScan implements ModelUsageFactScan {

    /** Every column JdbcDomainEventJsonMapper reads, plus the keyset cursor itself. */
    private static final String SELECTED_COLUMNS = """
            SELECT event_id, event_type, schema_version, organization_id, team_id,
                   workspace_id, subject_type, subject_id, aggregate_version, actor_type,
                   actor_id, correlation_id, causation_id, idempotency_key, occurred_at,
                   payload
            """;

    private static final String SCAN_FROM_BEGINNING = SELECTED_COLUMNS + """
            FROM crewscope.domain_event
            WHERE event_type = ?
            ORDER BY event_id
            LIMIT ?""";

    private static final String SCAN_AFTER_CURSOR = SELECTED_COLUMNS + """
             FROM crewscope.domain_event
             WHERE event_type = ? AND event_id > ?
             ORDER BY event_id
             LIMIT ?""";

    private final JdbcTemplate jdbcTemplate;
    private final JdbcDomainEventJsonMapper envelopeJsonMapper;

    public JdbcModelUsageFactScan(
            JdbcTemplate jdbcTemplate, JdbcDomainEventJsonMapper envelopeJsonMapper) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
        this.envelopeJsonMapper = Objects.requireNonNull(envelopeJsonMapper, "envelopeJsonMapper");
    }

    @Override
    public List<ScannedUsageFact> findFactsAfter(UUID afterEventId, int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
        if (afterEventId == null) {
            return jdbcTemplate.query(
                    SCAN_FROM_BEGINNING, this::row, ModelUsageRollupService.EVENT_TYPE, limit);
        }
        return jdbcTemplate.query(
                SCAN_AFTER_CURSOR, this::row,
                ModelUsageRollupService.EVENT_TYPE, afterEventId, limit);
    }

    private ScannedUsageFact row(ResultSet resultSet, int ignored) throws SQLException {
        return new ScannedUsageFact(
                resultSet.getObject("event_id", UUID.class), envelopeJsonMapper.map(resultSet));
    }
}
