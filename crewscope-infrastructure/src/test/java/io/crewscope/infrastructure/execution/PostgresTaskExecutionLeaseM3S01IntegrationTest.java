package io.crewscope.infrastructure.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
 * M3-S01 PostgreSQL spike for durable TaskExecution claim, lease, fencing and terminal races.
 *
 * <p>The schema and JDBC harness intentionally remain test-only. They prove the database protocol
 * before M3-D08 and M3-D09 introduce the production migration and adapters.
 */
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
@Execution(ExecutionMode.SAME_THREAD)
class PostgresTaskExecutionLeaseM3S01IntegrationTest {

    private static final String POSTGRES_IMAGE = "postgres:17-alpine";
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final Instant BASE_TIME = Instant.parse("2026-08-12T12:00:00Z");
    private static final SecureRandom TOKEN_RANDOM = new SecureRandom();

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(DockerImageName.parse(POSTGRES_IMAGE))
                    .withDatabaseName("crewscope")
                    .withUsername("crewscope")
                    .withPassword("crewscope-test")
                    .withStartupTimeout(Duration.ofMinutes(2));

    private final LeaseHarness harness = new LeaseHarness();

    @BeforeEach
    void resetSpikeSchema() throws Exception {
        try (Connection connection = connection()) {
            execute(connection, "CREATE SCHEMA IF NOT EXISTS m3_s01");
            execute(connection, "DROP TABLE IF EXISTS m3_s01.execution_lease");
            execute(connection, "DROP TABLE IF EXISTS m3_s01.task_execution");
            execute(connection, "DROP TABLE IF EXISTS m3_s01.runtime_quota");
            execute(
                    connection,
                    """
                    CREATE TABLE m3_s01.runtime_quota (
                        runtime_id UUID PRIMARY KEY,
                        max_active INTEGER NOT NULL CHECK (max_active > 0),
                        active_count INTEGER NOT NULL DEFAULT 0
                            CHECK (active_count >= 0 AND active_count <= max_active)
                    )
                    """);
            execute(
                    connection,
                    """
                    CREATE TABLE m3_s01.task_execution (
                        id UUID PRIMARY KEY,
                        runtime_id UUID NOT NULL REFERENCES m3_s01.runtime_quota(runtime_id),
                        attempt INTEGER NOT NULL CHECK (attempt > 0),
                        status VARCHAR(32) NOT NULL,
                        priority INTEGER NOT NULL,
                        not_before TIMESTAMPTZ NOT NULL,
                        fencing_token BIGINT NOT NULL DEFAULT 0 CHECK (fencing_token >= 0),
                        version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0)
                    )
                    """);
            execute(
                    connection,
                    """
                    CREATE TABLE m3_s01.execution_lease (
                        task_execution_id UUID PRIMARY KEY
                            REFERENCES m3_s01.task_execution(id),
                        attempt INTEGER NOT NULL,
                        runtime_id UUID NOT NULL,
                        worker_id VARCHAR(128) NOT NULL,
                        claim_token_hash CHAR(64) NOT NULL,
                        fencing_token BIGINT NOT NULL,
                        lease_version BIGINT NOT NULL DEFAULT 0,
                        heartbeat_at TIMESTAMPTZ NOT NULL,
                        expires_at TIMESTAMPTZ NOT NULL
                    )
                    """);
            execute(
                    connection,
                    """
                    CREATE INDEX m3_s01_ready_queue_idx
                    ON m3_s01.task_execution
                        (runtime_id, priority DESC, not_before, id)
                    WHERE status = 'READY'
                    """);
            execute(
                    connection,
                    """
                    CREATE INDEX m3_s01_expired_lease_idx
                    ON m3_s01.execution_lease (expires_at, task_execution_id)
                    """);
        }
    }

    @Test
    void twoWorkersCanClaimOneExecutionOnlyOnce() throws Exception {
        UUID runtimeId = seedRuntime(2);
        UUID executionId = seedExecution(runtimeId, 100);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<Optional<ClaimReceipt>> first =
                    workers.submit(() -> claimAfter(start, runtimeId, "worker-a"));
            Future<Optional<ClaimReceipt>> second =
                    workers.submit(() -> claimAfter(start, runtimeId, "worker-b"));
            start.countDown();

            Optional<ClaimReceipt> firstResult = first.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            Optional<ClaimReceipt> secondResult = second.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);

            assertEquals(1, java.util.stream.Stream.of(firstResult, secondResult)
                    .filter(Optional::isPresent)
                    .count());
            ClaimReceipt winner = firstResult.orElseGet(secondResult::orElseThrow);
            assertEquals(executionId, winner.taskExecutionId());
            assertEquals(1L, winner.fencingToken());
            assertEquals(1, quotaActive(runtimeId));
            assertEquals(1, leaseCount(executionId));
        } finally {
            workers.shutdownNow();
        }
    }

    @Test
    void twoWorkersCannotExceedRuntimeQuotaAcrossDifferentExecutions() throws Exception {
        UUID runtimeId = seedRuntime(1);
        seedExecution(runtimeId, 200);
        seedExecution(runtimeId, 100);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<Optional<ClaimReceipt>> first =
                    workers.submit(() -> claimAfter(start, runtimeId, "worker-a"));
            Future<Optional<ClaimReceipt>> second =
                    workers.submit(() -> claimAfter(start, runtimeId, "worker-b"));
            start.countDown();

            Optional<ClaimReceipt> firstResult = first.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            Optional<ClaimReceipt> secondResult = second.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);

            assertEquals(1, java.util.stream.Stream.of(firstResult, secondResult)
                    .filter(Optional::isPresent)
                    .count());
            assertEquals(1, quotaActive(runtimeId));
            assertEquals(1, claimedExecutionCount(runtimeId));
        } finally {
            workers.shutdownNow();
        }
    }

    @Test
    void skipLockedClaimsTheNextReadyExecutionWithoutWaitingForTheFirstLock() throws Exception {
        UUID runtimeId = seedRuntime(2);
        UUID first = seedExecution(runtimeId, 200);
        UUID second = seedExecution(runtimeId, 100);

        try (Connection blocker = connection()) {
            blocker.setAutoCommit(false);
            UUID locked = queryUuid(
                    blocker,
                    """
                    SELECT id
                    FROM m3_s01.task_execution
                    WHERE id = ?
                    FOR UPDATE
                    """,
                    first);
            assertEquals(first, locked);

            Optional<ClaimReceipt> claimed = harness.claim(runtimeId, "worker-b", BASE_TIME);

            assertTrue(claimed.isPresent());
            assertEquals(second, claimed.orElseThrow().taskExecutionId());
            blocker.rollback();
        }
    }

    @Test
    void runtimeQuotaBlocksASecondClaimUntilTheFirstLeaseIsReleased() throws Exception {
        UUID runtimeId = seedRuntime(1);
        UUID first = seedExecution(runtimeId, 200);
        UUID second = seedExecution(runtimeId, 100);

        ClaimReceipt firstClaim = harness.claim(runtimeId, "worker-a", BASE_TIME).orElseThrow();
        assertEquals(first, firstClaim.taskExecutionId());
        assertTrue(harness.claim(runtimeId, "worker-b", BASE_TIME).isEmpty());

        assertTrue(harness.complete(firstClaim, firstClaim.taskVersion(), BASE_TIME.plusSeconds(5)));
        ClaimReceipt secondClaim =
                harness.claim(runtimeId, "worker-b", BASE_TIME.plusSeconds(6)).orElseThrow();

        assertEquals(second, secondClaim.taskExecutionId());
        assertEquals(1, quotaActive(runtimeId));
    }

    @Test
    void storesOnlyTheClaimTokenHashAndRenewsWithoutChangingOwnershipOrFencing() throws Exception {
        UUID runtimeId = seedRuntime(1);
        UUID executionId = seedExecution(runtimeId, 100);
        ClaimReceipt claim = harness.claim(runtimeId, "worker-a", BASE_TIME).orElseThrow();

        StoredLease before = storedLease(executionId);
        assertEquals(sha256(claim.claimToken()), before.claimTokenHash());
        assertFalse(before.claimTokenHash().contains(claim.claimToken()));
        assertEquals(0L, before.leaseVersion());

        Optional<Long> renewedVersion = harness.renew(
                claim, before.leaseVersion(), BASE_TIME.plusSeconds(10), BASE_TIME.plusSeconds(90));

        assertEquals(Optional.of(1L), renewedVersion);
        assertTrue(harness
                .renew(claim, 0L, BASE_TIME.plusSeconds(11), BASE_TIME.plusSeconds(100))
                .isEmpty());
        StoredLease after = storedLease(executionId);
        assertEquals(before.workerId(), after.workerId());
        assertEquals(before.claimTokenHash(), after.claimTokenHash());
        assertEquals(before.fencingToken(), after.fencingToken());
        assertEquals(1L, after.leaseVersion());
        assertEquals(claim.taskVersion(), executionVersion(executionId));
    }

    @Test
    void expiredLeaseCanBeReclaimedAndEveryStaleCoordinateFailsClosed() throws Exception {
        UUID runtimeId = seedRuntime(1);
        UUID executionId = seedExecution(runtimeId, 100);
        ClaimReceipt oldClaim = harness.claim(runtimeId, "worker-a", BASE_TIME).orElseThrow();

        assertTrue(harness
                .renew(oldClaim, 0L, BASE_TIME.plusSeconds(61), BASE_TIME.plusSeconds(120))
                .isEmpty());
        assertTrue(harness.sweepExpired(
                oldClaim.taskExecutionId(), oldClaim.taskVersion(), oldClaim.fencingToken(), BASE_TIME.plusSeconds(61)));
        assertTrue(harness.requeueRecovered(executionId, 2L));
        ClaimReceipt current =
                harness.claim(runtimeId, "worker-b", BASE_TIME.plusSeconds(62)).orElseThrow();

        assertNotEquals(oldClaim.claimToken(), current.claimToken());
        assertEquals(oldClaim.fencingToken() + 1, current.fencingToken());
        assertTrue(harness
                .renew(oldClaim, 0L, BASE_TIME.plusSeconds(63), BASE_TIME.plusSeconds(120))
                .isEmpty());
        assertFalse(harness.complete(oldClaim, current.taskVersion(), BASE_TIME.plusSeconds(63)));
        assertFalse(harness.complete(
                current.withWorkerId("worker-a"), current.taskVersion(), BASE_TIME.plusSeconds(63)));
        assertFalse(harness.complete(
                current.withClaimToken(oldClaim.claimToken()),
                current.taskVersion(),
                BASE_TIME.plusSeconds(63)));
        assertFalse(harness.complete(
                current.withFencingToken(oldClaim.fencingToken()),
                current.taskVersion(),
                BASE_TIME.plusSeconds(63)));
        assertFalse(harness.complete(
                current.withAttempt(current.attempt() + 1),
                current.taskVersion(),
                BASE_TIME.plusSeconds(63)));
        assertFalse(harness.complete(
                current.withRuntimeId(UUID.randomUUID()),
                current.taskVersion(),
                BASE_TIME.plusSeconds(63)));
        assertFalse(harness.complete(current, current.taskVersion() - 1, BASE_TIME.plusSeconds(63)));
        assertTrue(harness.complete(current, current.taskVersion(), BASE_TIME.plusSeconds(63)));
    }

    @Test
    void completeAndSweeperRaceAllowsExactlyOneTerminalTransition() throws Exception {
        UUID runtimeId = seedRuntime(1);
        UUID executionId = seedExecution(runtimeId, 100);
        ClaimReceipt claim = harness.claim(runtimeId, "worker-a", BASE_TIME).orElseThrow();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService competitors = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> complete = competitors.submit(() -> {
                start.await();
                return harness.complete(
                        claim, claim.taskVersion(), BASE_TIME.plusSeconds(59));
            });
            Future<Boolean> sweep = competitors.submit(() -> {
                start.await();
                return harness.sweepExpired(
                        executionId,
                        claim.taskVersion(),
                        claim.fencingToken(),
                        BASE_TIME.plusSeconds(61));
            });
            start.countDown();

            boolean completed = complete.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            boolean recovered = sweep.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);

            assertNotEquals(completed, recovered);
            assertEquals(completed ? "COMPLETED" : "RECOVERING", executionStatus(executionId));
            assertEquals(0, quotaActive(runtimeId));
            assertEquals(0, leaseCount(executionId));
            assertEquals(2L, executionVersion(executionId));
        } finally {
            competitors.shutdownNow();
        }
    }

    private Optional<ClaimReceipt> claimAfter(
            CountDownLatch start, UUID runtimeId, String workerId) throws Exception {
        assertTrue(start.await(TIMEOUT.toSeconds(), TimeUnit.SECONDS));
        return harness.claim(runtimeId, workerId, BASE_TIME);
    }

    private UUID seedRuntime(int maxActive) throws Exception {
        UUID runtimeId = UUID.randomUUID();
        try (Connection connection = connection();
                PreparedStatement statement = connection.prepareStatement(
                        """
                        INSERT INTO m3_s01.runtime_quota(runtime_id, max_active, active_count)
                        VALUES (?, ?, 0)
                        """)) {
            statement.setObject(1, runtimeId);
            statement.setInt(2, maxActive);
            statement.executeUpdate();
        }
        return runtimeId;
    }

    private UUID seedExecution(UUID runtimeId, int priority) throws Exception {
        UUID executionId = UUID.randomUUID();
        try (Connection connection = connection();
                PreparedStatement statement = connection.prepareStatement(
                        """
                        INSERT INTO m3_s01.task_execution(
                            id, runtime_id, attempt, status, priority, not_before)
                        VALUES (?, ?, 1, 'READY', ?, ?)
                        """)) {
            statement.setObject(1, executionId);
            statement.setObject(2, runtimeId);
            statement.setInt(3, priority);
            statement.setObject(4, utc(BASE_TIME.minusSeconds(1)));
            statement.executeUpdate();
        }
        return executionId;
    }

    private int quotaActive(UUID runtimeId) throws Exception {
        return queryInt(
                "SELECT active_count FROM m3_s01.runtime_quota WHERE runtime_id = ?",
                runtimeId);
    }

    private int leaseCount(UUID executionId) throws Exception {
        return queryInt(
                "SELECT COUNT(*) FROM m3_s01.execution_lease WHERE task_execution_id = ?",
                executionId);
    }

    private int claimedExecutionCount(UUID runtimeId) throws Exception {
        return queryInt(
                """
                SELECT COUNT(*)
                FROM m3_s01.task_execution
                WHERE runtime_id = ? AND status = 'CLAIMED'
                """,
                runtimeId);
    }

    private long executionVersion(UUID executionId) throws Exception {
        return queryLong(
                "SELECT version FROM m3_s01.task_execution WHERE id = ?", executionId);
    }

    private String executionStatus(UUID executionId) throws Exception {
        try (Connection connection = connection();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT status FROM m3_s01.task_execution WHERE id = ?")) {
            statement.setObject(1, executionId);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                return result.getString(1);
            }
        }
    }

    private StoredLease storedLease(UUID executionId) throws Exception {
        try (Connection connection = connection();
                PreparedStatement statement = connection.prepareStatement(
                        """
                        SELECT worker_id, claim_token_hash, fencing_token, lease_version
                        FROM m3_s01.execution_lease
                        WHERE task_execution_id = ?
                        """)) {
            statement.setObject(1, executionId);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                return new StoredLease(
                        result.getString("worker_id"),
                        result.getString("claim_token_hash"),
                        result.getLong("fencing_token"),
                        result.getLong("lease_version"));
            }
        }
    }

    private int queryInt(String sql, UUID id) throws Exception {
        return Math.toIntExact(queryLong(sql, id));
    }

    private long queryLong(String sql, UUID id) throws Exception {
        try (Connection connection = connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, id);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                return result.getLong(1);
            }
        }
    }

    private static UUID queryUuid(Connection connection, String sql, UUID id) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, id);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                return result.getObject(1, UUID.class);
            }
        }
    }

    private static void execute(Connection connection, String sql) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.execute();
        }
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private static String sha256(String token) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(token.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static String newClaimToken() {
        byte[] bytes = new byte[32];
        TOKEN_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static Connection connection() throws SQLException {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private final class LeaseHarness {

        Optional<ClaimReceipt> claim(UUID runtimeId, String workerId, Instant now) throws Exception {
            try (Connection connection = connection()) {
                connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
                connection.setAutoCommit(false);
                try {
                    Candidate candidate = selectCandidate(connection, runtimeId, now);
                    if (candidate == null || !reserveQuota(connection, runtimeId)) {
                        connection.rollback();
                        return Optional.empty();
                    }

                    String claimToken = newClaimToken();
                    long fencingToken = candidate.fencingToken() + 1;
                    long taskVersion = candidate.version() + 1;
                    try (PreparedStatement update = connection.prepareStatement(
                            """
                            UPDATE m3_s01.task_execution
                            SET status = 'CLAIMED', fencing_token = ?, version = version + 1
                            WHERE id = ? AND status = 'READY' AND version = ?
                            """)) {
                        update.setLong(1, fencingToken);
                        update.setObject(2, candidate.id());
                        update.setLong(3, candidate.version());
                        if (update.executeUpdate() != 1) {
                            connection.rollback();
                            return Optional.empty();
                        }
                    }
                    try (PreparedStatement insert = connection.prepareStatement(
                            """
                            INSERT INTO m3_s01.execution_lease(
                                task_execution_id, attempt, runtime_id, worker_id,
                                claim_token_hash, fencing_token, lease_version,
                                heartbeat_at, expires_at)
                            VALUES (?, ?, ?, ?, ?, ?, 0, ?, ?)
                            """)) {
                        insert.setObject(1, candidate.id());
                        insert.setInt(2, candidate.attempt());
                        insert.setObject(3, runtimeId);
                        insert.setString(4, workerId);
                        insert.setString(5, sha256(claimToken));
                        insert.setLong(6, fencingToken);
                        insert.setObject(7, utc(now));
                        insert.setObject(8, utc(now.plusSeconds(60)));
                        insert.executeUpdate();
                    }
                    connection.commit();
                    return Optional.of(new ClaimReceipt(
                            candidate.id(),
                            candidate.attempt(),
                            runtimeId,
                            workerId,
                            claimToken,
                            fencingToken,
                            taskVersion));
                } catch (Throwable failure) {
                    connection.rollback();
                    throw failure;
                }
            }
        }

        Optional<Long> renew(
                ClaimReceipt claim, long expectedLeaseVersion, Instant now, Instant expiresAt)
                throws Exception {
            try (Connection connection = connection();
                    PreparedStatement statement = connection.prepareStatement(
                            """
                            UPDATE m3_s01.execution_lease
                            SET heartbeat_at = ?, expires_at = ?, lease_version = lease_version + 1
                            WHERE task_execution_id = ?
                              AND attempt = ?
                              AND runtime_id = ?
                              AND worker_id = ?
                              AND claim_token_hash = ?
                              AND fencing_token = ?
                              AND lease_version = ?
                              AND expires_at > ?
                            RETURNING lease_version
                            """)) {
                statement.setObject(1, utc(now));
                statement.setObject(2, utc(expiresAt));
                bindClaim(statement, 3, claim);
                statement.setLong(9, expectedLeaseVersion);
                statement.setObject(10, utc(now));
                try (ResultSet result = statement.executeQuery()) {
                    return result.next() ? Optional.of(result.getLong(1)) : Optional.empty();
                }
            }
        }

        boolean complete(ClaimReceipt claim, long expectedTaskVersion, Instant now)
                throws Exception {
            return transitionAndRelease(
                    claim.taskExecutionId(),
                    expectedTaskVersion,
                    "COMPLETED",
                    """
                    te.attempt = ?
                      AND te.runtime_id = ?
                      AND te.fencing_token = ?
                      AND EXISTS (
                          SELECT 1
                          FROM m3_s01.execution_lease lease
                          WHERE lease.task_execution_id = te.id
                            AND lease.attempt = ?
                            AND lease.runtime_id = ?
                            AND lease.worker_id = ?
                            AND lease.claim_token_hash = ?
                            AND lease.fencing_token = ?
                            AND lease.expires_at > ?
                      )
                    """,
                    statement -> {
                        statement.setInt(1, claim.attempt());
                        statement.setObject(2, claim.runtimeId());
                        statement.setLong(3, claim.fencingToken());
                        statement.setInt(4, claim.attempt());
                        statement.setObject(5, claim.runtimeId());
                        statement.setString(6, claim.workerId());
                        statement.setString(7, sha256(claim.claimToken()));
                        statement.setLong(8, claim.fencingToken());
                        statement.setObject(9, utc(now));
                    });
        }

        boolean sweepExpired(
                UUID executionId, long expectedTaskVersion, long fencingToken, Instant now)
                throws Exception {
            return transitionAndRelease(
                    executionId,
                    expectedTaskVersion,
                    "RECOVERING",
                    """
                    te.fencing_token = ?
                      AND EXISTS (
                          SELECT 1
                          FROM m3_s01.execution_lease lease
                          WHERE lease.task_execution_id = te.id
                            AND lease.fencing_token = ?
                            AND lease.expires_at <= ?
                      )
                    """,
                    statement -> {
                        statement.setLong(1, fencingToken);
                        statement.setLong(2, fencingToken);
                        statement.setObject(3, utc(now));
                    });
        }

        boolean requeueRecovered(UUID executionId, long expectedTaskVersion) throws Exception {
            try (Connection connection = connection();
                    PreparedStatement statement = connection.prepareStatement(
                            """
                            UPDATE m3_s01.task_execution
                            SET status = 'READY', version = version + 1
                            WHERE id = ? AND status = 'RECOVERING' AND version = ?
                            """)) {
                statement.setObject(1, executionId);
                statement.setLong(2, expectedTaskVersion);
                return statement.executeUpdate() == 1;
            }
        }

        private Candidate selectCandidate(Connection connection, UUID runtimeId, Instant now)
                throws Exception {
            try (PreparedStatement statement = connection.prepareStatement(
                    """
                    SELECT candidate.id, candidate.attempt,
                           candidate.fencing_token, candidate.version
                    FROM m3_s01.task_execution candidate
                    WHERE candidate.runtime_id = ?
                      AND candidate.status = 'READY'
                      AND candidate.not_before <= ?
                      AND EXISTS (
                          SELECT 1
                          FROM m3_s01.runtime_quota quota
                          WHERE quota.runtime_id = candidate.runtime_id
                            AND quota.active_count < quota.max_active
                      )
                    ORDER BY candidate.priority DESC, candidate.not_before, candidate.id
                    FOR UPDATE OF candidate SKIP LOCKED
                    LIMIT 1
                    """)) {
                statement.setObject(1, runtimeId);
                statement.setObject(2, utc(now));
                try (ResultSet result = statement.executeQuery()) {
                    if (!result.next()) {
                        return null;
                    }
                    return new Candidate(
                            result.getObject("id", UUID.class),
                            result.getInt("attempt"),
                            result.getLong("fencing_token"),
                            result.getLong("version"));
                }
            }
        }

        private boolean reserveQuota(Connection connection, UUID runtimeId) throws Exception {
            try (PreparedStatement statement = connection.prepareStatement(
                    """
                    UPDATE m3_s01.runtime_quota
                    SET active_count = active_count + 1
                    WHERE runtime_id = ? AND active_count < max_active
                    """)) {
                statement.setObject(1, runtimeId);
                return statement.executeUpdate() == 1;
            }
        }

        private boolean transitionAndRelease(
                UUID executionId,
                long expectedTaskVersion,
                String targetStatus,
                String ownershipPredicate,
                StatementBinder ownershipBinder)
                throws Exception {
            try (Connection connection = connection()) {
                connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
                connection.setAutoCommit(false);
                try {
                    UUID runtimeId;
                    String sql = """
                            UPDATE m3_s01.task_execution te
                            SET status = ?, version = version + 1
                            WHERE te.id = ?
                              AND te.status = 'CLAIMED'
                              AND te.version = ?
                              AND
                            """ + ownershipPredicate + " RETURNING te.runtime_id";
                    try (PreparedStatement statement = connection.prepareStatement(sql)) {
                        statement.setString(1, targetStatus);
                        statement.setObject(2, executionId);
                        statement.setLong(3, expectedTaskVersion);
                        ownershipBinder.bind(new OffsetPreparedStatement(statement, 3));
                        try (ResultSet result = statement.executeQuery()) {
                            if (!result.next()) {
                                connection.rollback();
                                return false;
                            }
                            runtimeId = result.getObject(1, UUID.class);
                        }
                    }
                    try (PreparedStatement delete = connection.prepareStatement(
                            "DELETE FROM m3_s01.execution_lease WHERE task_execution_id = ?")) {
                        delete.setObject(1, executionId);
                        assertEquals(1, delete.executeUpdate());
                    }
                    try (PreparedStatement quota = connection.prepareStatement(
                            """
                            UPDATE m3_s01.runtime_quota
                            SET active_count = active_count - 1
                            WHERE runtime_id = ? AND active_count > 0
                            """)) {
                        quota.setObject(1, runtimeId);
                        assertEquals(1, quota.executeUpdate());
                    }
                    connection.commit();
                    return true;
                } catch (Throwable failure) {
                    connection.rollback();
                    throw failure;
                }
            }
        }

        private void bindClaim(PreparedStatement statement, int start, ClaimReceipt claim)
                throws Exception {
            statement.setObject(start, claim.taskExecutionId());
            statement.setInt(start + 1, claim.attempt());
            statement.setObject(start + 2, claim.runtimeId());
            statement.setString(start + 3, claim.workerId());
            statement.setString(start + 4, sha256(claim.claimToken()));
            statement.setLong(start + 5, claim.fencingToken());
        }
    }

    /** Adds an index offset while keeping each ownership predicate numbered from one. */
    private static final class OffsetPreparedStatement {
        private final PreparedStatement delegate;
        private final int offset;

        private OffsetPreparedStatement(PreparedStatement delegate, int offset) {
            this.delegate = delegate;
            this.offset = offset;
        }

        void setInt(int index, int value) throws SQLException {
            delegate.setInt(index + offset, value);
        }

        void setLong(int index, long value) throws SQLException {
            delegate.setLong(index + offset, value);
        }

        void setString(int index, String value) throws SQLException {
            delegate.setString(index + offset, value);
        }

        void setObject(int index, Object value) throws SQLException {
            delegate.setObject(index + offset, value);
        }
    }

    @FunctionalInterface
    private interface StatementBinder {
        void bind(OffsetPreparedStatement statement) throws Exception;
    }

    private record Candidate(UUID id, int attempt, long fencingToken, long version) {}

    private record StoredLease(
            String workerId, String claimTokenHash, long fencingToken, long leaseVersion) {}

    private record ClaimReceipt(
            UUID taskExecutionId,
            int attempt,
            UUID runtimeId,
            String workerId,
            String claimToken,
            long fencingToken,
            long taskVersion) {

        ClaimReceipt withWorkerId(String replacement) {
            return new ClaimReceipt(
                    taskExecutionId,
                    attempt,
                    runtimeId,
                    replacement,
                    claimToken,
                    fencingToken,
                    taskVersion);
        }

        ClaimReceipt withRuntimeId(UUID replacement) {
            return new ClaimReceipt(
                    taskExecutionId,
                    attempt,
                    replacement,
                    workerId,
                    claimToken,
                    fencingToken,
                    taskVersion);
        }

        ClaimReceipt withClaimToken(String replacement) {
            return new ClaimReceipt(
                    taskExecutionId,
                    attempt,
                    runtimeId,
                    workerId,
                    replacement,
                    fencingToken,
                    taskVersion);
        }

        ClaimReceipt withFencingToken(long replacement) {
            return new ClaimReceipt(
                    taskExecutionId,
                    attempt,
                    runtimeId,
                    workerId,
                    claimToken,
                    replacement,
                    taskVersion);
        }

        ClaimReceipt withAttempt(int replacement) {
            return new ClaimReceipt(
                    taskExecutionId,
                    replacement,
                    runtimeId,
                    workerId,
                    claimToken,
                    fencingToken,
                    taskVersion);
        }
    }
}
