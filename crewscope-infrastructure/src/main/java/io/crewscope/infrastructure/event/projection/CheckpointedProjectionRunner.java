package io.crewscope.infrastructure.event.projection;

import io.crewscope.application.event.publication.DomainEventConsumer;
import io.crewscope.application.event.publication.EventPublication;
import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Applies one projection in aggregate order and persists its position in the same transaction. */
public class CheckpointedProjectionRunner implements DomainEventConsumer {

    private static final String CURSOR_PREFIX = "aggregate-version:";

    private final ProjectionHandler handler;
    private final String projectionName;
    private final JdbcProjectionCheckpointStore checkpointStore;
    private final ProjectionEventJsonMapper eventMapper;
    private final Clock clock;

    public CheckpointedProjectionRunner(
            ProjectionHandler handler,
            JdbcProjectionCheckpointStore checkpointStore,
            ProjectionEventJsonMapper eventMapper,
            Clock clock) {
        this.handler = Objects.requireNonNull(handler, "handler");
        String configuredName = this.handler.projectionName();
        if (configuredName == null
                || configuredName.isBlank()
                || !configuredName.equals(configuredName.strip())
                || configuredName.length() > 180) {
            throw new IllegalArgumentException(
                    "projectionName must be trimmed and contain between 1 and 180 characters");
        }
        // Capture the identity once so receipts and checkpoints cannot diverge at runtime.
        this.projectionName = configuredName;
        this.checkpointStore = Objects.requireNonNull(checkpointStore, "checkpointStore");
        this.eventMapper = Objects.requireNonNull(eventMapper, "eventMapper");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public String consumerName() {
        return "projection:" + projectionName;
    }

    /** Requires the receipt transaction opened by the idempotent event dispatcher. */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void consume(EventPublication publication) {
        EventPublication source = Objects.requireNonNull(publication, "publication");
        ProjectionEvent event = eventMapper.map(source);
        ProjectionCheckpoint checkpoint = checkpointStore.lock(
                event.organizationId(),
                projectionName,
                source.partitionKey(),
                clock.instant());

        Optional<Long> lastVersion = checkpoint.lastEventCursor().map(this::parseCursor);
        if (lastVersion.isEmpty()) {
            if (event.aggregateVersion() != 0) {
                throw gap(event, 0);
            }
        } else {
            long committedVersion = lastVersion.orElseThrow();
            if (event.aggregateVersion() < committedVersion) {
                return;
            }
            if (event.aggregateVersion() == committedVersion) {
                if (checkpoint.lastEventId().orElseThrow().equals(event.eventId())) {
                    return;
                }
                if (compareWithinVersion(event, checkpoint) <= 0) {
                    return;
                }
            } else {
                long expected = committedVersion + 1;
                if (event.aggregateVersion() != expected) {
                    throw gap(event, expected);
                }
            }
        }

        handler.project(event);
        checkpointStore.advance(
                checkpoint,
                event,
                CURSOR_PREFIX + event.aggregateVersion(),
                clock.instant());
    }

    private int compareWithinVersion(
            ProjectionEvent event, ProjectionCheckpoint checkpoint) {
        int timeComparison = event.occurredAt().compareTo(
                checkpoint.lastEventOccurredAt().orElseThrow());
        if (timeComparison != 0) {
            return timeComparison;
        }
        // Canonical UUID text has the same unsigned byte order used by PostgreSQL's UUID type.
        return event.eventId().toString().compareTo(
                checkpoint.lastEventId().orElseThrow().toString());
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
                                projectionName,
                                expectedVersion,
                                event.aggregateVersion(),
                                event.eventId()));
    }
}
