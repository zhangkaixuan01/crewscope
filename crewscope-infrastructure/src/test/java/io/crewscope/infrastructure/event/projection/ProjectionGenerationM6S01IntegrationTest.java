package io.crewscope.infrastructure.event.projection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * M6-S01 PostgreSQL spike for live-captured shadow projection generations and atomic switching.
 *
 * <p>The schema and JDBC harness intentionally remain test-only. They validate the generation,
 * receipt, checkpoint, validation and fencing protocol before M6-D07/D08 and M6-E01 introduce the
 * production domain, V27 migration and supervisor.
 */
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
@Execution(ExecutionMode.SAME_THREAD)
class ProjectionGenerationM6S01IntegrationTest {

    private static final String POSTGRES_IMAGE = "postgres:17-alpine";
    private static final String PROJECTION = "team-activity-v1";
    private static final Instant BASE_TIME = Instant.parse("2026-08-25T08:00:00Z");

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(DockerImageName.parse(POSTGRES_IMAGE))
                    .withDatabaseName("crewscope")
                    .withUsername("crewscope")
                    .withPassword("crewscope-test")
                    .withStartupTimeout(Duration.ofMinutes(2));

    private UUID organizationId;
    private GenerationHarness harness;

    @BeforeEach
    void resetSpikeSchema() throws Exception {
        organizationId = UUID.randomUUID();
        harness = new GenerationHarness();
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS m6_s01 CASCADE");
            statement.execute("CREATE SCHEMA m6_s01");
            statement.execute(
                    """
                    CREATE TABLE m6_s01.source_event (
                        event_id UUID PRIMARY KEY,
                        organization_id UUID NOT NULL,
                        partition_key VARCHAR(200) NOT NULL,
                        aggregate_version BIGINT NOT NULL CHECK (aggregate_version >= 0),
                        occurred_at TIMESTAMPTZ NOT NULL,
                        payload VARCHAR(500) NOT NULL
                    )
                    """);
            statement.execute(
                    """
                    CREATE TABLE m6_s01.projection_generation (
                        organization_id UUID NOT NULL,
                        projection_name VARCHAR(160) NOT NULL,
                        generation BIGINT NOT NULL CHECK (generation > 0),
                        status VARCHAR(32) NOT NULL CHECK (
                            status IN (
                                'BUILDING', 'VALIDATING', 'ACTIVE',
                                'RETIRED', 'FAILED', 'CANCELLED'
                            )
                        ),
                        fencing_token BIGINT NOT NULL CHECK (fencing_token > 0),
                        validation_count BIGINT,
                        validation_hash CHAR(64),
                        version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
                        PRIMARY KEY (organization_id, projection_name, generation),
                        CONSTRAINT ck_m6_s01_validation_pair CHECK (
                            (validation_count IS NULL AND validation_hash IS NULL)
                            OR (validation_count IS NOT NULL AND validation_count >= 0
                                AND validation_hash ~ '^[0-9a-f]{64}$')
                        )
                    )
                    """);
            statement.execute(
                    """
                    CREATE UNIQUE INDEX ux_m6_s01_active_generation
                    ON m6_s01.projection_generation (organization_id, projection_name)
                    WHERE status = 'ACTIVE'
                    """);
            statement.execute(
                    """
                    CREATE UNIQUE INDEX ux_m6_s01_building_generation
                    ON m6_s01.projection_generation (organization_id, projection_name)
                    WHERE status IN ('BUILDING', 'VALIDATING')
                    """);
            statement.execute(
                    """
                    CREATE TABLE m6_s01.projection_pointer (
                        organization_id UUID NOT NULL,
                        projection_name VARCHAR(160) NOT NULL,
                        active_generation BIGINT NOT NULL,
                        version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
                        PRIMARY KEY (organization_id, projection_name),
                        FOREIGN KEY (organization_id, projection_name, active_generation)
                            REFERENCES m6_s01.projection_generation (
                                organization_id, projection_name, generation
                            )
                    )
                    """);
            statement.execute(
                    """
                    CREATE TABLE m6_s01.projection_checkpoint (
                        organization_id UUID NOT NULL,
                        projection_name VARCHAR(160) NOT NULL,
                        generation BIGINT NOT NULL,
                        partition_key VARCHAR(200) NOT NULL,
                        last_event_id UUID NOT NULL,
                        last_aggregate_version BIGINT NOT NULL,
                        last_event_occurred_at TIMESTAMPTZ NOT NULL,
                        version BIGINT NOT NULL DEFAULT 0,
                        PRIMARY KEY (
                            organization_id, projection_name, generation, partition_key
                        ),
                        FOREIGN KEY (organization_id, projection_name, generation)
                            REFERENCES m6_s01.projection_generation (
                                organization_id, projection_name, generation
                            )
                    )
                    """);
            statement.execute(
                    """
                    CREATE TABLE m6_s01.projection_consumer_receipt (
                        consumer_name VARCHAR(100) NOT NULL,
                        event_id UUID NOT NULL REFERENCES m6_s01.source_event(event_id),
                        PRIMARY KEY (consumer_name, event_id)
                    )
                    """);
            statement.execute(
                    """
                    CREATE TABLE m6_s01.projection_row (
                        organization_id UUID NOT NULL,
                        projection_name VARCHAR(160) NOT NULL,
                        generation BIGINT NOT NULL,
                        event_id UUID NOT NULL REFERENCES m6_s01.source_event(event_id),
                        partition_key VARCHAR(200) NOT NULL,
                        canonical_value VARCHAR(1000) NOT NULL,
                        PRIMARY KEY (organization_id, projection_name, generation, event_id),
                        FOREIGN KEY (organization_id, projection_name, generation)
                            REFERENCES m6_s01.projection_generation (
                                organization_id, projection_name, generation
                            )
                    )
                    """);
            statement.execute(
                    """
                    CREATE TABLE m6_s01.member_disposition (
                        organization_id UUID NOT NULL,
                        member_id UUID NOT NULL,
                        source_id UUID NOT NULL,
                        status VARCHAR(32) NOT NULL,
                        PRIMARY KEY (organization_id, member_id, source_id)
                    )
                    """);
        }
    }

    @Test
    void liveCaptureAndRestartedHistoricalReplayConvergeWithoutDuplicates() throws Exception {
        long active = harness.bootstrapActive();
        SourceEvent workItemCreated = harness.seed("work-item:a", 0, BASE_TIME, "created");
        SourceEvent reviewCreated = harness.seed(
                "review:b", 0, BASE_TIME.plusMillis(1), "review-created");
        harness.publishLive(workItemCreated);
        harness.publishLive(reviewCreated);
        long building = harness.startBuilding();

        SourceEvent workItemUpdated = harness.seed(
                "work-item:a", 1, BASE_TIME.plusMillis(2), "updated");
        // The active generation accepts the event. The empty building generation fails on its
        // partition gap, so the Outbox delivery remains retryable until replay catches it up.
        assertThrows(ProjectionGapException.class, () -> harness.publishLive(workItemUpdated));
        assertEquals(3, harness.rowCount(active));
        assertEquals(0, harness.rowCount(building));

        // A new harness instance represents process restart; all recovery facts are in PostgreSQL.
        GenerationHarness restarted = new GenerationHarness();
        restarted.replay(building);
        restarted.replay(building);

        assertEquals(3, restarted.rowCount(building));
        assertEquals(3, restarted.receiptCount(building));
        assertEquals(2, restarted.checkpointCount(building));
        assertEquals(restarted.sourceSnapshot(), restarted.generationSnapshot(building));
    }

    @Test
    void aggregateVersionGapRollsBackReceiptRowAndCheckpointThenReplaysInOrder() throws Exception {
        harness.bootstrapActive();
        long building = harness.startBuilding();
        SourceEvent versionOne = harness.seed(
                "work-item:gap", 1, BASE_TIME.plusSeconds(1), "v1");
        GenerationLease lease = harness.lease(building);

        assertThrows(
                ProjectionGapException.class,
                () -> harness.apply(lease, versionOne, false));
        assertEquals(0, harness.rowCount(building));
        assertEquals(0, harness.receiptCount(building));
        assertEquals(0, harness.checkpointCount(building));

        harness.seed("work-item:gap", 0, BASE_TIME, "v0");
        harness.replay(building);

        assertEquals(2, harness.rowCount(building));
        assertEquals(2, harness.receiptCount(building));
        assertEquals(1, harness.checkpointCount(building));
        assertEquals(harness.sourceSnapshot(), harness.generationSnapshot(building));
    }

    @Test
    void projectorFailureRollsBackSideEffectReceiptAndCheckpoint() throws Exception {
        harness.bootstrapActive();
        long building = harness.startBuilding();
        SourceEvent event = harness.seed("task:failure", 0, BASE_TIME, "must-rollback");
        GenerationLease lease = harness.lease(building);

        assertThrows(
                SimulatedProjectionFailure.class,
                () -> harness.apply(lease, event, true));

        assertEquals(0, harness.rowCount(building));
        assertEquals(0, harness.receiptCount(building));
        assertEquals(0, harness.checkpointCount(building));
        assertTrue(harness.apply(lease, event, false));
        assertEquals(1, harness.rowCount(building));
    }

    @Test
    void failedBuildNeverChangesOnlineGenerationOrMemberDisposition() throws Exception {
        long active = harness.bootstrapActive();
        SourceEvent event = harness.seed("review:active", 0, BASE_TIME, "active");
        harness.publishLive(event);
        UUID memberId = UUID.randomUUID();
        harness.saveDisposition(memberId, event.eventId(), "READ");
        long building = harness.startBuilding();
        harness.replay(building);

        harness.fail(building);

        assertEquals(active, harness.activeGeneration());
        assertEquals("ACTIVE", harness.status(active));
        assertEquals("FAILED", harness.status(building));
        assertEquals(1, harness.rowCount(active));
        assertEquals("READ", harness.disposition(memberId, event.eventId()));
        assertFalse(harness.switchGeneration(building));
    }

    @Test
    void atomicSwitchExpiresOldCursorAndFencesLateOldGenerationWriter() throws Exception {
        long active = harness.bootstrapActive();
        SourceEvent versionZero = harness.seed("work-item:switch", 0, BASE_TIME, "v0");
        harness.publishLive(versionZero);
        ProjectionCursor oldCursor = new ProjectionCursor(active, 1);
        long building = harness.startBuilding();
        harness.replay(building);
        assertTrue(harness.validate(building));
        GenerationLease lateOldWriter = harness.lease(active);

        assertTrue(harness.switchGeneration(building));

        assertEquals(building, harness.activeGeneration());
        assertEquals("RETIRED", harness.status(active));
        assertEquals("ACTIVE", harness.status(building));
        assertThrows(ProjectionCursorExpiredException.class, () -> harness.requireCurrent(oldCursor));
        assertEquals(new ProjectionCursor(building, 1),
                harness.requireCurrent(new ProjectionCursor(building, 1)));

        SourceEvent versionOne = harness.seed(
                "work-item:switch", 1, BASE_TIME.plusSeconds(1), "v1");
        harness.publishLive(versionOne);
        assertFalse(harness.apply(lateOldWriter, versionOne, false));
        assertEquals(1, harness.rowCount(active));
        assertEquals(2, harness.rowCount(building));
        assertNotEquals(lateOldWriter.fencingToken(), harness.generationFencing(active));
    }

    @Test
    void eventAfterValidationRequiresRevalidationBeforeSwitch() throws Exception {
        long active = harness.bootstrapActive();
        SourceEvent versionZero = harness.seed("task:barrier", 0, BASE_TIME, "v0");
        harness.publishLive(versionZero);
        long building = harness.startBuilding();
        harness.replay(building);
        assertTrue(harness.validate(building));

        SourceEvent versionOne = harness.seed(
                "task:barrier", 1, BASE_TIME.plusSeconds(1), "v1");
        harness.publishLive(versionOne);

        assertFalse(harness.switchGeneration(building));
        assertEquals(active, harness.activeGeneration());
        assertEquals("VALIDATING", harness.status(building));
        assertTrue(harness.validate(building));
        assertTrue(harness.switchGeneration(building));
        assertEquals(building, harness.activeGeneration());
    }

    private static Connection connection() throws SQLException {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private final class GenerationHarness {

        private long bootstrapActive() throws SQLException {
            try (Connection connection = connection()) {
                connection.setAutoCommit(false);
                try {
                    insertGeneration(connection, 1, "ACTIVE");
                    try (PreparedStatement statement = connection.prepareStatement(
                            """
                            INSERT INTO m6_s01.projection_pointer (
                                organization_id, projection_name, active_generation, version
                            ) VALUES (?, ?, 1, 0)
                            """)) {
                        statement.setObject(1, organizationId);
                        statement.setString(2, PROJECTION);
                        statement.executeUpdate();
                    }
                    connection.commit();
                    return 1;
                } catch (RuntimeException | SQLException failure) {
                    connection.rollback();
                    throw failure;
                }
            }
        }

        private long startBuilding() throws SQLException {
            try (Connection connection = connection()) {
                connection.setAutoCommit(false);
                try {
                    long generation = queryLong(
                                    connection,
                                    """
                                    SELECT COALESCE(MAX(generation), 0) + 1
                                    FROM m6_s01.projection_generation
                                    WHERE organization_id = ? AND projection_name = ?
                                    """,
                                    organizationId,
                                    PROJECTION)
                            .orElseThrow();
                    insertGeneration(connection, generation, "BUILDING");
                    connection.commit();
                    return generation;
                } catch (RuntimeException | SQLException failure) {
                    connection.rollback();
                    throw failure;
                }
            }
        }

        private void insertGeneration(Connection connection, long generation, String status)
                throws SQLException {
            try (PreparedStatement statement = connection.prepareStatement(
                    """
                    INSERT INTO m6_s01.projection_generation (
                        organization_id, projection_name, generation, status,
                        fencing_token, version
                    ) VALUES (?, ?, ?, ?, 1, 0)
                    """)) {
                statement.setObject(1, organizationId);
                statement.setString(2, PROJECTION);
                statement.setLong(3, generation);
                statement.setString(4, status);
                statement.executeUpdate();
            }
        }

        private SourceEvent seed(
                String partitionKey, long aggregateVersion, Instant occurredAt, String payload)
                throws SQLException {
            SourceEvent event = new SourceEvent(
                    UUID.randomUUID(), partitionKey, aggregateVersion, occurredAt, payload);
            try (Connection connection = connection();
                    PreparedStatement statement = connection.prepareStatement(
                            """
                            INSERT INTO m6_s01.source_event (
                                event_id, organization_id, partition_key,
                                aggregate_version, occurred_at, payload
                            ) VALUES (?, ?, ?, ?, ?, ?)
                            """)) {
                statement.setObject(1, event.eventId());
                statement.setObject(2, organizationId);
                statement.setString(3, event.partitionKey());
                statement.setLong(4, event.aggregateVersion());
                statement.setObject(5, utc(event.occurredAt()));
                statement.setString(6, event.payload());
                statement.executeUpdate();
            }
            return event;
        }

        private void publishLive(SourceEvent event) throws SQLException {
            for (GenerationLease lease : acceptingLeases()) {
                apply(lease, event, false);
            }
        }

        private List<GenerationLease> acceptingLeases() throws SQLException {
            try (Connection connection = connection();
                    PreparedStatement statement = connection.prepareStatement(
                            """
                            SELECT generation, fencing_token
                            FROM m6_s01.projection_generation
                            WHERE organization_id = ?
                              AND projection_name = ?
                              AND status IN ('ACTIVE', 'BUILDING', 'VALIDATING')
                            ORDER BY generation
                            """)) {
                statement.setObject(1, organizationId);
                statement.setString(2, PROJECTION);
                try (ResultSet resultSet = statement.executeQuery()) {
                    List<GenerationLease> leases = new ArrayList<>();
                    while (resultSet.next()) {
                        leases.add(new GenerationLease(
                                resultSet.getLong("generation"),
                                resultSet.getLong("fencing_token")));
                    }
                    return leases;
                }
            }
        }

        private GenerationLease lease(long generation) throws SQLException {
            try (Connection connection = connection();
                    PreparedStatement statement = connection.prepareStatement(
                            """
                            SELECT fencing_token
                            FROM m6_s01.projection_generation
                            WHERE organization_id = ?
                              AND projection_name = ?
                              AND generation = ?
                            """)) {
                statement.setObject(1, organizationId);
                statement.setString(2, PROJECTION);
                statement.setLong(3, generation);
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (!resultSet.next()) {
                        throw new IllegalStateException("Unknown projection generation " + generation);
                    }
                    return new GenerationLease(generation, resultSet.getLong("fencing_token"));
                }
            }
        }

        private void replay(long generation) throws SQLException {
            GenerationLease lease = lease(generation);
            for (SourceEvent event : sourceEvents()) {
                apply(lease, event, false);
            }
        }

        private List<SourceEvent> sourceEvents() throws SQLException {
            try (Connection connection = connection();
                    PreparedStatement statement = connection.prepareStatement(
                            """
                            SELECT event_id, partition_key, aggregate_version, occurred_at, payload
                            FROM m6_s01.source_event
                            WHERE organization_id = ?
                            ORDER BY partition_key, aggregate_version, occurred_at, event_id
                            """)) {
                statement.setObject(1, organizationId);
                try (ResultSet resultSet = statement.executeQuery()) {
                    List<SourceEvent> events = new ArrayList<>();
                    while (resultSet.next()) {
                        events.add(new SourceEvent(
                                resultSet.getObject("event_id", UUID.class),
                                resultSet.getString("partition_key"),
                                resultSet.getLong("aggregate_version"),
                                resultSet.getObject("occurred_at", OffsetDateTime.class).toInstant(),
                                resultSet.getString("payload")));
                    }
                    return events;
                }
            }
        }

        private boolean apply(
                GenerationLease lease, SourceEvent event, boolean failAfterProjection)
                throws SQLException {
            try (Connection connection = connection()) {
                connection.setAutoCommit(false);
                try {
                    if (!lockAcceptingGeneration(connection, lease)) {
                        connection.rollback();
                        return false;
                    }
                    if (!insertReceipt(connection, lease.generation(), event.eventId())) {
                        connection.commit();
                        return false;
                    }
                    Optional<Checkpoint> checkpoint = lockCheckpoint(
                            connection, lease.generation(), event.partitionKey());
                    ProjectionOrder order = order(checkpoint, event);
                    if (order == ProjectionOrder.STALE) {
                        connection.commit();
                        return true;
                    }
                    insertProjectionRow(connection, lease.generation(), event);
                    if (failAfterProjection) {
                        throw new SimulatedProjectionFailure();
                    }
                    advanceCheckpoint(connection, lease.generation(), event, checkpoint);
                    connection.commit();
                    return true;
                } catch (RuntimeException | SQLException failure) {
                    connection.rollback();
                    throw failure;
                }
            }
        }

        private boolean lockAcceptingGeneration(Connection connection, GenerationLease lease)
                throws SQLException {
            try (PreparedStatement statement = connection.prepareStatement(
                    """
                    SELECT status, fencing_token
                    FROM m6_s01.projection_generation
                    WHERE organization_id = ?
                      AND projection_name = ?
                      AND generation = ?
                    FOR SHARE
                    """)) {
                statement.setObject(1, organizationId);
                statement.setString(2, PROJECTION);
                statement.setLong(3, lease.generation());
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (!resultSet.next()) {
                        return false;
                    }
                    String status = resultSet.getString("status");
                    return List.of("ACTIVE", "BUILDING", "VALIDATING").contains(status)
                            && resultSet.getLong("fencing_token") == lease.fencingToken();
                }
            }
        }

        private boolean insertReceipt(Connection connection, long generation, UUID eventId)
                throws SQLException {
            try (PreparedStatement statement = connection.prepareStatement(
                    """
                    INSERT INTO m6_s01.projection_consumer_receipt (consumer_name, event_id)
                    VALUES (?, ?)
                    ON CONFLICT (consumer_name, event_id) DO NOTHING
                    """)) {
                statement.setString(1, consumerName(generation));
                statement.setObject(2, eventId);
                return statement.executeUpdate() == 1;
            }
        }

        private Optional<Checkpoint> lockCheckpoint(
                Connection connection, long generation, String partitionKey) throws SQLException {
            try (PreparedStatement statement = connection.prepareStatement(
                    """
                    SELECT last_event_id, last_aggregate_version,
                           last_event_occurred_at, version
                    FROM m6_s01.projection_checkpoint
                    WHERE organization_id = ?
                      AND projection_name = ?
                      AND generation = ?
                      AND partition_key = ?
                    FOR UPDATE
                    """)) {
                statement.setObject(1, organizationId);
                statement.setString(2, PROJECTION);
                statement.setLong(3, generation);
                statement.setString(4, partitionKey);
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (!resultSet.next()) {
                        return Optional.empty();
                    }
                    return Optional.of(new Checkpoint(
                            resultSet.getObject("last_event_id", UUID.class),
                            resultSet.getLong("last_aggregate_version"),
                            resultSet.getObject(
                                            "last_event_occurred_at", OffsetDateTime.class)
                                    .toInstant(),
                            resultSet.getLong("version")));
                }
            }
        }

        private ProjectionOrder order(Optional<Checkpoint> checkpoint, SourceEvent event) {
            if (checkpoint.isEmpty()) {
                if (event.aggregateVersion() != 0) {
                    throw new ProjectionGapException(
                            "Expected aggregate version 0 but received " + event.aggregateVersion());
                }
                return ProjectionOrder.APPLY;
            }
            Checkpoint current = checkpoint.orElseThrow();
            if (event.aggregateVersion() < current.aggregateVersion()) {
                return ProjectionOrder.STALE;
            }
            if (event.aggregateVersion() == current.aggregateVersion()) {
                int time = event.occurredAt().compareTo(current.occurredAt());
                if (time < 0 || (time == 0
                        && event.eventId().toString().compareTo(current.eventId().toString()) <= 0)) {
                    return ProjectionOrder.STALE;
                }
                return ProjectionOrder.APPLY;
            }
            long expected = current.aggregateVersion() + 1;
            if (event.aggregateVersion() != expected) {
                throw new ProjectionGapException(
                        "Expected aggregate version " + expected + " but received "
                                + event.aggregateVersion());
            }
            return ProjectionOrder.APPLY;
        }

        private void insertProjectionRow(Connection connection, long generation, SourceEvent event)
                throws SQLException {
            try (PreparedStatement statement = connection.prepareStatement(
                    """
                    INSERT INTO m6_s01.projection_row (
                        organization_id, projection_name, generation,
                        event_id, partition_key, canonical_value
                    ) VALUES (?, ?, ?, ?, ?, ?)
                    """)) {
                statement.setObject(1, organizationId);
                statement.setString(2, PROJECTION);
                statement.setLong(3, generation);
                statement.setObject(4, event.eventId());
                statement.setString(5, event.partitionKey());
                statement.setString(6, event.canonicalValue());
                statement.executeUpdate();
            }
        }

        private void advanceCheckpoint(
                Connection connection,
                long generation,
                SourceEvent event,
                Optional<Checkpoint> checkpoint)
                throws SQLException {
            if (checkpoint.isEmpty()) {
                try (PreparedStatement statement = connection.prepareStatement(
                        """
                        INSERT INTO m6_s01.projection_checkpoint (
                            organization_id, projection_name, generation, partition_key,
                            last_event_id, last_aggregate_version,
                            last_event_occurred_at, version
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, 0)
                        """)) {
                    statement.setObject(1, organizationId);
                    statement.setString(2, PROJECTION);
                    statement.setLong(3, generation);
                    statement.setString(4, event.partitionKey());
                    statement.setObject(5, event.eventId());
                    statement.setLong(6, event.aggregateVersion());
                    statement.setObject(7, utc(event.occurredAt()));
                    statement.executeUpdate();
                }
                return;
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    """
                    UPDATE m6_s01.projection_checkpoint
                    SET last_event_id = ?,
                        last_aggregate_version = ?,
                        last_event_occurred_at = ?,
                        version = version + 1
                    WHERE organization_id = ?
                      AND projection_name = ?
                      AND generation = ?
                      AND partition_key = ?
                      AND version = ?
                    """)) {
                statement.setObject(1, event.eventId());
                statement.setLong(2, event.aggregateVersion());
                statement.setObject(3, utc(event.occurredAt()));
                statement.setObject(4, organizationId);
                statement.setString(5, PROJECTION);
                statement.setLong(6, generation);
                statement.setString(7, event.partitionKey());
                statement.setLong(8, checkpoint.orElseThrow().version());
                if (statement.executeUpdate() != 1) {
                    throw new IllegalStateException("Projection checkpoint changed while locked");
                }
            }
        }

        private boolean validate(long generation) throws Exception {
            try (Connection connection = connection()) {
                connection.setAutoCommit(false);
                try {
                    String status = lockGenerationForUpdate(connection, generation);
                    if (!List.of("BUILDING", "VALIDATING").contains(status)) {
                        connection.rollback();
                        return false;
                    }
                    Snapshot source = sourceSnapshot(connection);
                    Snapshot projected = generationSnapshot(connection, generation);
                    if (!source.equals(projected)) {
                        connection.rollback();
                        return false;
                    }
                    try (PreparedStatement statement = connection.prepareStatement(
                            """
                            UPDATE m6_s01.projection_generation
                            SET status = 'VALIDATING',
                                validation_count = ?,
                                validation_hash = ?,
                                fencing_token = fencing_token + 1,
                                version = version + 1
                            WHERE organization_id = ?
                              AND projection_name = ?
                              AND generation = ?
                            """)) {
                        statement.setLong(1, source.count());
                        statement.setString(2, source.hash());
                        statement.setObject(3, organizationId);
                        statement.setString(4, PROJECTION);
                        statement.setLong(5, generation);
                        statement.executeUpdate();
                    }
                    connection.commit();
                    return true;
                } catch (RuntimeException | SQLException failure) {
                    connection.rollback();
                    throw failure;
                }
            }
        }

        private boolean switchGeneration(long generation) throws Exception {
            try (Connection connection = connection()) {
                connection.setAutoCommit(false);
                try {
                    Pointer pointer = lockPointer(connection);
                    Validation validation = lockValidation(connection, generation);
                    if (!"VALIDATING".equals(validation.status())) {
                        connection.rollback();
                        return false;
                    }
                    Snapshot source = sourceSnapshot(connection);
                    Snapshot projected = generationSnapshot(connection, generation);
                    if (!source.equals(projected)
                            || source.count() != validation.count()
                            || !source.hash().equals(validation.hash())) {
                        connection.rollback();
                        return false;
                    }
                    updateGenerationStatus(
                            connection, pointer.activeGeneration(), "RETIRED");
                    updateGenerationStatus(connection, generation, "ACTIVE");
                    try (PreparedStatement statement = connection.prepareStatement(
                            """
                            UPDATE m6_s01.projection_pointer
                            SET active_generation = ?, version = version + 1
                            WHERE organization_id = ?
                              AND projection_name = ?
                              AND version = ?
                            """)) {
                        statement.setLong(1, generation);
                        statement.setObject(2, organizationId);
                        statement.setString(3, PROJECTION);
                        statement.setLong(4, pointer.version());
                        if (statement.executeUpdate() != 1) {
                            throw new IllegalStateException("Projection pointer changed while locked");
                        }
                    }
                    connection.commit();
                    return true;
                } catch (RuntimeException | SQLException failure) {
                    connection.rollback();
                    throw failure;
                }
            }
        }

        private Pointer lockPointer(Connection connection) throws SQLException {
            try (PreparedStatement statement = connection.prepareStatement(
                    """
                    SELECT active_generation, version
                    FROM m6_s01.projection_pointer
                    WHERE organization_id = ? AND projection_name = ?
                    FOR UPDATE
                    """)) {
                statement.setObject(1, organizationId);
                statement.setString(2, PROJECTION);
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (!resultSet.next()) {
                        throw new IllegalStateException("Projection pointer is missing");
                    }
                    return new Pointer(
                            resultSet.getLong("active_generation"),
                            resultSet.getLong("version"));
                }
            }
        }

        private Validation lockValidation(Connection connection, long generation)
                throws SQLException {
            try (PreparedStatement statement = connection.prepareStatement(
                    """
                    SELECT status, validation_count, validation_hash
                    FROM m6_s01.projection_generation
                    WHERE organization_id = ?
                      AND projection_name = ?
                      AND generation = ?
                    FOR UPDATE
                    """)) {
                statement.setObject(1, organizationId);
                statement.setString(2, PROJECTION);
                statement.setLong(3, generation);
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (!resultSet.next()) {
                        throw new IllegalStateException("Projection generation is missing");
                    }
                    return new Validation(
                            resultSet.getString("status"),
                            resultSet.getLong("validation_count"),
                            resultSet.getString("validation_hash"));
                }
            }
        }

        private String lockGenerationForUpdate(Connection connection, long generation)
                throws SQLException {
            try (PreparedStatement statement = connection.prepareStatement(
                    """
                    SELECT status
                    FROM m6_s01.projection_generation
                    WHERE organization_id = ?
                      AND projection_name = ?
                      AND generation = ?
                    FOR UPDATE
                    """)) {
                statement.setObject(1, organizationId);
                statement.setString(2, PROJECTION);
                statement.setLong(3, generation);
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (!resultSet.next()) {
                        throw new IllegalStateException("Projection generation is missing");
                    }
                    return resultSet.getString("status");
                }
            }
        }

        private void updateGenerationStatus(
                Connection connection, long generation, String status) throws SQLException {
            try (PreparedStatement statement = connection.prepareStatement(
                    """
                    UPDATE m6_s01.projection_generation
                    SET status = ?, fencing_token = fencing_token + 1, version = version + 1
                    WHERE organization_id = ?
                      AND projection_name = ?
                      AND generation = ?
                    """)) {
                statement.setString(1, status);
                statement.setObject(2, organizationId);
                statement.setString(3, PROJECTION);
                statement.setLong(4, generation);
                statement.executeUpdate();
            }
        }

        private void fail(long generation) throws SQLException {
            try (Connection connection = connection()) {
                connection.setAutoCommit(false);
                try {
                    lockGenerationForUpdate(connection, generation);
                    updateGenerationStatus(connection, generation, "FAILED");
                    connection.commit();
                } catch (RuntimeException | SQLException failure) {
                    connection.rollback();
                    throw failure;
                }
            }
        }

        private long activeGeneration() throws SQLException {
            try (Connection connection = connection()) {
                return queryLong(
                                connection,
                                """
                                SELECT active_generation
                                FROM m6_s01.projection_pointer
                                WHERE organization_id = ? AND projection_name = ?
                                """,
                                organizationId,
                                PROJECTION)
                        .orElseThrow();
            }
        }

        private ProjectionCursor requireCurrent(ProjectionCursor cursor) throws SQLException {
            long active = activeGeneration();
            if (cursor.generation() != active) {
                throw new ProjectionCursorExpiredException(
                        "Cursor generation " + cursor.generation() + " is no longer active");
            }
            return cursor;
        }

        private String status(long generation) throws SQLException {
            try (Connection connection = connection();
                    PreparedStatement statement = connection.prepareStatement(
                            """
                            SELECT status
                            FROM m6_s01.projection_generation
                            WHERE organization_id = ?
                              AND projection_name = ?
                              AND generation = ?
                            """)) {
                statement.setObject(1, organizationId);
                statement.setString(2, PROJECTION);
                statement.setLong(3, generation);
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (!resultSet.next()) {
                        throw new IllegalStateException("Projection generation is missing");
                    }
                    return resultSet.getString("status");
                }
            }
        }

        private long generationFencing(long generation) throws SQLException {
            return lease(generation).fencingToken();
        }

        private int rowCount(long generation) throws SQLException {
            return count(
                    """
                    SELECT COUNT(*) FROM m6_s01.projection_row
                    WHERE organization_id = ? AND projection_name = ? AND generation = ?
                    """,
                    generation);
        }

        private int receiptCount(long generation) throws SQLException {
            try (Connection connection = connection();
                    PreparedStatement statement = connection.prepareStatement(
                            """
                            SELECT COUNT(*)
                            FROM m6_s01.projection_consumer_receipt
                            WHERE consumer_name = ?
                            """)) {
                statement.setString(1, consumerName(generation));
                try (ResultSet resultSet = statement.executeQuery()) {
                    resultSet.next();
                    return resultSet.getInt(1);
                }
            }
        }

        private int checkpointCount(long generation) throws SQLException {
            return count(
                    """
                    SELECT COUNT(*) FROM m6_s01.projection_checkpoint
                    WHERE organization_id = ? AND projection_name = ? AND generation = ?
                    """,
                    generation);
        }

        private int count(String sql, long generation) throws SQLException {
            try (Connection connection = connection();
                    PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setObject(1, organizationId);
                statement.setString(2, PROJECTION);
                statement.setLong(3, generation);
                try (ResultSet resultSet = statement.executeQuery()) {
                    resultSet.next();
                    return resultSet.getInt(1);
                }
            }
        }

        private Snapshot sourceSnapshot() throws Exception {
            try (Connection connection = connection()) {
                return sourceSnapshot(connection);
            }
        }

        private Snapshot sourceSnapshot(Connection connection) throws Exception {
            try (PreparedStatement statement = connection.prepareStatement(
                    """
                    SELECT event_id, partition_key, aggregate_version, occurred_at, payload
                    FROM m6_s01.source_event
                    WHERE organization_id = ?
                    ORDER BY partition_key, aggregate_version, occurred_at, event_id
                    """)) {
                statement.setObject(1, organizationId);
                try (ResultSet resultSet = statement.executeQuery()) {
                    List<String> values = new ArrayList<>();
                    while (resultSet.next()) {
                        values.add(new SourceEvent(
                                        resultSet.getObject("event_id", UUID.class),
                                        resultSet.getString("partition_key"),
                                        resultSet.getLong("aggregate_version"),
                                        resultSet.getObject(
                                                        "occurred_at", OffsetDateTime.class)
                                                .toInstant(),
                                        resultSet.getString("payload"))
                                .canonicalValue());
                    }
                    return snapshot(values);
                }
            }
        }

        private Snapshot generationSnapshot(long generation) throws Exception {
            try (Connection connection = connection()) {
                return generationSnapshot(connection, generation);
            }
        }

        private Snapshot generationSnapshot(Connection connection, long generation)
                throws Exception {
            try (PreparedStatement statement = connection.prepareStatement(
                    """
                    SELECT canonical_value
                    FROM m6_s01.projection_row
                    WHERE organization_id = ?
                      AND projection_name = ?
                      AND generation = ?
                    ORDER BY partition_key, canonical_value
                    """)) {
                statement.setObject(1, organizationId);
                statement.setString(2, PROJECTION);
                statement.setLong(3, generation);
                try (ResultSet resultSet = statement.executeQuery()) {
                    List<String> values = new ArrayList<>();
                    while (resultSet.next()) {
                        values.add(resultSet.getString("canonical_value"));
                    }
                    values.sort(String::compareTo);
                    return snapshot(values);
                }
            }
        }

        private void saveDisposition(UUID memberId, UUID sourceId, String status)
                throws SQLException {
            try (Connection connection = connection();
                    PreparedStatement statement = connection.prepareStatement(
                            """
                            INSERT INTO m6_s01.member_disposition (
                                organization_id, member_id, source_id, status
                            ) VALUES (?, ?, ?, ?)
                            """)) {
                statement.setObject(1, organizationId);
                statement.setObject(2, memberId);
                statement.setObject(3, sourceId);
                statement.setString(4, status);
                statement.executeUpdate();
            }
        }

        private String disposition(UUID memberId, UUID sourceId) throws SQLException {
            try (Connection connection = connection();
                    PreparedStatement statement = connection.prepareStatement(
                            """
                            SELECT status
                            FROM m6_s01.member_disposition
                            WHERE organization_id = ? AND member_id = ? AND source_id = ?
                            """)) {
                statement.setObject(1, organizationId);
                statement.setObject(2, memberId);
                statement.setObject(3, sourceId);
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (!resultSet.next()) {
                        throw new IllegalStateException("Member disposition is missing");
                    }
                    return resultSet.getString("status");
                }
            }
        }

        private String consumerName(long generation) {
            return "projection-generation:" + sha256(
                    organizationId + ":" + PROJECTION + ":" + generation);
        }
    }

    private static Snapshot snapshot(List<String> values) throws Exception {
        values.sort(String::compareTo);
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        for (String value : values) {
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            digest.update(Integer.toString(bytes.length).getBytes(StandardCharsets.UTF_8));
            digest.update((byte) ':');
            digest.update(bytes);
        }
        return new Snapshot(values.size(), HexFormat.of().formatHex(digest.digest()));
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static Optional<Long> queryLong(
            Connection connection, String sql, Object... arguments) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < arguments.length; index++) {
                statement.setObject(index + 1, arguments[index]);
            }
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next()
                        ? Optional.of(resultSet.getLong(1))
                        : Optional.empty();
            }
        }
    }

    private static OffsetDateTime utc(Instant value) {
        return value.atOffset(ZoneOffset.UTC);
    }

    private record SourceEvent(
            UUID eventId,
            String partitionKey,
            long aggregateVersion,
            Instant occurredAt,
            String payload) {

        private String canonicalValue() {
            return eventId + "|" + partitionKey + "|" + aggregateVersion + "|"
                    + occurredAt + "|" + payload;
        }
    }

    private record GenerationLease(long generation, long fencingToken) {}

    private record Checkpoint(
            UUID eventId, long aggregateVersion, Instant occurredAt, long version) {}

    private record Snapshot(long count, String hash) {}

    private record Pointer(long activeGeneration, long version) {}

    private record Validation(String status, long count, String hash) {}

    private record ProjectionCursor(long generation, long sequence) {}

    private enum ProjectionOrder {
        APPLY,
        STALE
    }

    private static final class ProjectionGapException extends RuntimeException {
        private ProjectionGapException(String message) {
            super(message);
        }
    }

    private static final class ProjectionCursorExpiredException extends RuntimeException {
        private ProjectionCursorExpiredException(String message) {
            super(message);
        }
    }

    private static final class SimulatedProjectionFailure extends RuntimeException {}
}
