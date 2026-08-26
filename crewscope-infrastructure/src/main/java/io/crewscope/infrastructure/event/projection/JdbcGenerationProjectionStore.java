package io.crewscope.infrastructure.event.projection;

import io.crewscope.domain.projection.ProjectionDefinitionVersion;
import io.crewscope.domain.projection.ProjectionGenerationLease;
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

/**
 * Commits a Generation Receipt, ordered projection effect and Checkpoint in one local transaction.
 */
@Repository
public class JdbcGenerationProjectionStore {

    private static final String CURSOR_PREFIX = "aggregate-version:";

    private final JdbcTemplate jdbcTemplate;

    public JdbcGenerationProjectionStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public ProjectionConsumptionResult consume(
            ProjectionGenerationLease lease,
            String consumerName,
            ProjectionEvent event,
            GenerationAwareProjectionHandler handler,
            Instant now) {
        ProjectionGenerationLease target = Objects.requireNonNull(lease, "lease");
        ProjectionEvent source = Objects.requireNonNull(event, "event");
        GenerationAwareProjectionHandler projector = Objects.requireNonNull(handler, "handler");
        String consumer = requireConsumerName(consumerName);
        OffsetDateTime timestamp = utc(now);

        if (!target.key().organizationId().value().equals(source.organizationId())
                || !target.key().projectionName().equals(projector.definition().name())) {
            throw new IllegalArgumentException("Projection lease, event and handler scope do not match");
        }
        if (!lockWritable(target, projector.definition().version())) {
            return ProjectionConsumptionResult.LEASE_REJECTED;
        }
        if (!insertReceipt(target, consumer, source.eventId(), timestamp)) {
            return ProjectionConsumptionResult.DUPLICATE;
        }

        GenerationCheckpoint checkpoint = lockCheckpoint(
                target,
                source.organizationId() + ":" + source.aggregateType() + ":" + source.aggregateId(),
                timestamp);
        ProjectionConsumptionResult order = order(checkpoint, source);
        if (order == ProjectionConsumptionResult.STALE) {
            return order;
        }

        projector.project(target, source);
        advanceCheckpoint(target, checkpoint, source, timestamp);
        return ProjectionConsumptionResult.APPLIED;
    }

    private boolean lockWritable(
            ProjectionGenerationLease lease, ProjectionDefinitionVersion handlerVersion) {
        ListRow row = jdbcTemplate.query(
                        """
                        SELECT definition_version, status, fencing_token
                        FROM crewscope.projection_generation
                        WHERE organization_id = ?
                          AND projection_name = ?
                          AND generation = ?
                        FOR SHARE
                        """,
                        resultSet -> resultSet.next()
                                ? new ListRow(
                                        resultSet.getLong("definition_version"),
                                        resultSet.getString("status"),
                                        resultSet.getLong("fencing_token"))
                                : null,
                        lease.key().organizationId().value(),
                        lease.key().projectionName().value(),
                        lease.key().generation().value());
        if (row != null && row.definitionVersion() != handlerVersion.value()) {
            throw new IllegalStateException(
                    "Projection handler definition version %d does not match generation version %d"
                            .formatted(handlerVersion.value(), row.definitionVersion()));
        }
        return row != null
                && (row.status().equals("ACTIVE")
                        || row.status().equals("BUILDING")
                        || row.status().equals("VALIDATING"))
                && row.fencingToken() == lease.fencingToken().value();
    }

    private boolean insertReceipt(
            ProjectionGenerationLease lease,
            String consumerName,
            UUID eventId,
            OffsetDateTime processedAt) {
        return jdbcTemplate.update(
                        """
                        INSERT INTO crewscope.projection_consumer_receipt (
                            organization_id, projection_name, generation, consumer_name,
                            domain_event_id, fencing_token, processed_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT (
                            organization_id, projection_name, generation,
                            consumer_name, domain_event_id
                        ) DO NOTHING
                        """,
                        lease.key().organizationId().value(),
                        lease.key().projectionName().value(),
                        lease.key().generation().value(),
                        consumerName,
                        eventId,
                        lease.fencingToken().value(),
                        processedAt)
                == 1;
    }

    private GenerationCheckpoint lockCheckpoint(
            ProjectionGenerationLease lease, String partitionKey, OffsetDateTime now) {
        jdbcTemplate.update(
                """
                INSERT INTO crewscope.projection_generation_checkpoint (
                    organization_id, projection_name, generation, partition_key,
                    last_event_id, last_event_cursor, last_event_occurred_at,
                    fencing_token, version, created_at, updated_at
                ) VALUES (?, ?, ?, ?, NULL, NULL, NULL, ?, 0, ?, ?)
                ON CONFLICT (
                    organization_id, projection_name, generation, partition_key
                ) DO NOTHING
                """,
                lease.key().organizationId().value(),
                lease.key().projectionName().value(),
                lease.key().generation().value(),
                partitionKey,
                lease.fencingToken().value(),
                now,
                now);
        GenerationCheckpoint checkpoint = jdbcTemplate.queryForObject(
                """
                SELECT last_event_id, last_event_cursor, last_event_occurred_at, version
                FROM crewscope.projection_generation_checkpoint
                WHERE organization_id = ?
                  AND projection_name = ?
                  AND generation = ?
                  AND partition_key = ?
                FOR UPDATE
                """,
                (resultSet, rowNumber) -> new GenerationCheckpoint(
                        partitionKey,
                        Optional.ofNullable(resultSet.getObject("last_event_id", UUID.class)),
                        Optional.ofNullable(resultSet.getString("last_event_cursor")),
                        Optional.ofNullable(resultSet.getObject(
                                "last_event_occurred_at", OffsetDateTime.class)),
                        resultSet.getLong("version")),
                lease.key().organizationId().value(),
                lease.key().projectionName().value(),
                lease.key().generation().value(),
                partitionKey);
        return Objects.requireNonNull(checkpoint, "checkpoint");
    }

    private ProjectionConsumptionResult order(
            GenerationCheckpoint checkpoint, ProjectionEvent event) {
        Optional<Long> lastVersion = checkpoint.lastEventCursor().map(this::parseCursor);
        if (lastVersion.isEmpty()) {
            if (event.aggregateVersion() != 0) {
                throw gap(event, 0);
            }
            return ProjectionConsumptionResult.APPLIED;
        }
        long committedVersion = lastVersion.orElseThrow();
        if (event.aggregateVersion() < committedVersion) {
            return ProjectionConsumptionResult.STALE;
        }
        if (event.aggregateVersion() == committedVersion) {
            int timeComparison = event.occurredAt().toOffsetDateTime().compareTo(
                    checkpoint.lastEventOccurredAt().orElseThrow());
            if (timeComparison < 0) {
                return ProjectionConsumptionResult.STALE;
            }
            if (timeComparison == 0
                    && event.eventId().toString().compareTo(
                            checkpoint.lastEventId().orElseThrow().toString()) <= 0) {
                return ProjectionConsumptionResult.STALE;
            }
            return ProjectionConsumptionResult.APPLIED;
        }
        long expected = committedVersion + 1;
        if (event.aggregateVersion() != expected) {
            throw gap(event, expected);
        }
        return ProjectionConsumptionResult.APPLIED;
    }

    private void advanceCheckpoint(
            ProjectionGenerationLease lease,
            GenerationCheckpoint checkpoint,
            ProjectionEvent event,
            OffsetDateTime now) {
        int updated = jdbcTemplate.update(
                """
                UPDATE crewscope.projection_generation_checkpoint
                SET last_event_id = ?,
                    last_event_cursor = ?,
                    last_event_occurred_at = ?,
                    fencing_token = ?,
                    version = version + 1,
                    updated_at = ?
                WHERE organization_id = ?
                  AND projection_name = ?
                  AND generation = ?
                  AND partition_key = ?
                  AND version = ?
                """,
                event.eventId(),
                CURSOR_PREFIX + event.aggregateVersion(),
                event.occurredAt().toOffsetDateTime(),
                lease.fencingToken().value(),
                now,
                lease.key().organizationId().value(),
                lease.key().projectionName().value(),
                lease.key().generation().value(),
                checkpoint.partitionKey(),
                checkpoint.version());
        if (updated != 1) {
            throw new IllegalStateException("Projection checkpoint changed while holding its lock");
        }
    }

    private long parseCursor(String cursor) {
        if (!cursor.startsWith(CURSOR_PREFIX)) {
            throw new IllegalStateException("Unsupported projection checkpoint cursor: " + cursor);
        }
        try {
            long version = Long.parseLong(cursor.substring(CURSOR_PREFIX.length()));
            if (version < 0) {
                throw new NumberFormatException("negative");
            }
            return version;
        } catch (NumberFormatException exception) {
            throw new IllegalStateException("Invalid projection checkpoint cursor: " + cursor, exception);
        }
    }

    private ProjectionGapException gap(ProjectionEvent event, long expectedVersion) {
        return new ProjectionGapException(
                "Projection %s expected aggregate version %d but received %d for event %s"
                        .formatted(
                                event.aggregateType(),
                                expectedVersion,
                                event.aggregateVersion(),
                                event.eventId()));
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

    private static OffsetDateTime utc(Instant value) {
        return Objects.requireNonNull(value, "now").atOffset(ZoneOffset.UTC);
    }

    private record ListRow(long definitionVersion, String status, long fencingToken) {}

    private record GenerationCheckpoint(
            String partitionKey,
            Optional<UUID> lastEventId,
            Optional<String> lastEventCursor,
            Optional<OffsetDateTime> lastEventOccurredAt,
            long version) {}
}
