package io.crewscope.infrastructure.event.projection;

import io.crewscope.domain.shared.time.UtcTimestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** PostgreSQL adapter that serializes one projection partition through its checkpoint row. */
@Repository
public class JdbcProjectionCheckpointStore {

    private final JdbcTemplate jdbcTemplate;

    public JdbcProjectionCheckpointStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
    }

    /** Creates an empty position when needed and returns it with a row lock. */
    @Transactional(propagation = Propagation.MANDATORY)
    public ProjectionCheckpoint lock(
            UUID organizationId,
            String projectionName,
            String partitionKey,
            Instant now) {
        OffsetDateTime timestamp = utc(now);
        jdbcTemplate.update(
                """
                INSERT INTO crewscope.event_projection_checkpoint (
                    organization_id, projection_name, partition_key,
                    last_event_id, last_event_cursor, last_event_occurred_at,
                    version, created_at, updated_at
                ) VALUES (?, ?, ?, NULL, NULL, NULL, 0, ?, ?)
                ON CONFLICT (organization_id, projection_name, partition_key) DO NOTHING
                """,
                Objects.requireNonNull(organizationId, "organizationId"),
                projectionName,
                partitionKey,
                timestamp,
                timestamp);
        return jdbcTemplate.queryForObject(
                """
                SELECT organization_id, projection_name, partition_key,
                       last_event_id, last_event_cursor, last_event_occurred_at, version
                FROM crewscope.event_projection_checkpoint
                WHERE organization_id = ?
                  AND projection_name = ?
                  AND partition_key = ?
                FOR UPDATE
                """,
                (resultSet, rowNumber) -> new ProjectionCheckpoint(
                        resultSet.getObject("organization_id", UUID.class),
                        resultSet.getString("projection_name"),
                        resultSet.getString("partition_key"),
                        Optional.ofNullable(resultSet.getObject("last_event_id", UUID.class)),
                        Optional.ofNullable(resultSet.getString("last_event_cursor")),
                        Optional.ofNullable(resultSet.getObject(
                                        "last_event_occurred_at", OffsetDateTime.class))
                                .map(UtcTimestamp::from),
                        resultSet.getLong("version")),
                organizationId,
                projectionName,
                partitionKey);
    }

    /** Advances the locked row using its optimistic version as an additional invariant. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void advance(
            ProjectionCheckpoint checkpoint,
            ProjectionEvent event,
            String cursor,
            Instant now) {
        ProjectionCheckpoint position = Objects.requireNonNull(checkpoint, "checkpoint");
        ProjectionEvent projected = Objects.requireNonNull(event, "event");
        int updated = jdbcTemplate.update(
                """
                UPDATE crewscope.event_projection_checkpoint
                SET last_event_id = ?,
                    last_event_cursor = ?,
                    last_event_occurred_at = ?,
                    version = version + 1,
                    updated_at = ?
                WHERE organization_id = ?
                  AND projection_name = ?
                  AND partition_key = ?
                  AND version = ?
                """,
                projected.eventId(),
                cursor,
                projected.occurredAt().toOffsetDateTime(),
                utc(now),
                position.organizationId(),
                position.projectionName(),
                position.partitionKey(),
                position.version());
        if (updated != 1) {
            throw new IllegalStateException("Projection checkpoint changed while holding its lock");
        }
    }

    private static OffsetDateTime utc(Instant value) {
        return Objects.requireNonNull(value, "now").atOffset(ZoneOffset.UTC);
    }
}
