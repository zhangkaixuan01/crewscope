package io.crewscope.infrastructure.release;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Real PostgreSQL fixed-load evidence for the M6 Team Beta release profile.
 *
 * <p>The test freezes the production time coordinates. Fixture runs use 120 discarded requests and
 * three independent 500-request rounds; Canonical runs hold the same concurrency for the complete
 * 120-second warmup and three 600-second measurement windows.
 */
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
@Execution(ExecutionMode.SAME_THREAD)
class TeamBetaFixedLoadM6Q03IntegrationTest {

    private static final String POSTGRES_IMAGE = "postgres:17-alpine";
    private static final String DATASET = "m6-team-beta-v1";
    private static final long SEED = 20260825L;
    private static final int WEB_CONCURRENCY = 10;
    private static final int TASK_CONCURRENCY = 2;
    private static final int WARMUP_SECONDS = 120;
    private static final int MEASUREMENT_SECONDS = 600;
    private static final int REPETITIONS = 3;
    private static final int WARMUP_SAMPLES = 120;
    private static final int MEASUREMENT_SAMPLES = 500;
    private static final Duration CANONICAL_REQUEST_INTERVAL = Duration.ofSeconds(1);
    // A 16 GB cloud instance normally reports less memory after hypervisor and kernel reservation.
    private static final long MINIMUM_OS_REPORTED_MEMORY_BYTES = 14L * 1024 * 1024 * 1024;
    private static final long LATENCY_TARGET_MILLIS = 2_000L;
    private static final double MAXIMUM_ERROR_RATE = 0.001D;
    private static final Path DEFAULT_EVIDENCE =
            Path.of("target", "m6-q03-load-evidence.json");

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(DockerImageName.parse(POSTGRES_IMAGE))
                    .withDatabaseName("crewscope")
                    .withUsername("crewscope")
                    .withPassword("crewscope-test")
                    .withStartupTimeout(Duration.ofMinutes(2));

    @BeforeAll
    static void createLoadSchema() throws SQLException {
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA IF NOT EXISTS m6_q03");
            statement.execute("DROP TABLE IF EXISTS m6_q03.work_request");
            statement.execute(
                    """
                    CREATE TABLE m6_q03.work_request (
                        id BIGSERIAL PRIMARY KEY,
                        repetition INTEGER NOT NULL,
                        phase VARCHAR(16) NOT NULL,
                        task_status VARCHAR(16) NOT NULL,
                        projection_status VARCHAR(16) NOT NULL,
                        ready_committed_at TIMESTAMPTZ NOT NULL,
                        event_committed_at TIMESTAMPTZ NOT NULL,
                        claimed_at TIMESTAMPTZ,
                        projected_at TIMESTAMPTZ
                    )
                    """);
            statement.execute(
                    """
                    CREATE INDEX m6_q03_ready_idx
                    ON m6_q03.work_request (repetition, phase, id)
                    WHERE task_status = 'READY'
                    """);
            statement.execute(
                    """
                    CREATE INDEX m6_q03_projection_idx
                    ON m6_q03.work_request (repetition, phase, id)
                    WHERE projection_status = 'PENDING'
                    """);
        }
    }

    @Test
    void freezesCanonicalCoordinatesAndRunsThreeIndependentMeasurementRounds() throws Exception {
        LoadLane lane = LoadLane.from(System.getProperty("m6.q03.lane", "fixture"));
        if (lane == LoadLane.CANONICAL) {
            assertTrue(canonicalEnvironment(), TeamBetaFixedLoadM6Q03IntegrationTest::canonicalFailure);
        }
        assertEquals("m6-team-beta-v1", DATASET);
        assertEquals(20260825L, SEED);
        assertEquals(10, WEB_CONCURRENCY);
        assertEquals(2, TASK_CONCURRENCY);
        assertEquals(120, WARMUP_SECONDS);
        assertEquals(600, MEASUREMENT_SECONDS);
        assertEquals(3, REPETITIONS);
        assertEquals(500, MEASUREMENT_SAMPLES);

        // Warmup exercises the identical producer/claim/project path and is deliberately excluded
        // from every measurement histogram and percentile.
        Duration warmupDuration = lane == LoadLane.CANONICAL
                ? Duration.ofSeconds(WARMUP_SECONDS) : Duration.ZERO;
        Duration measurementDuration = lane == LoadLane.CANONICAL
                ? Duration.ofSeconds(MEASUREMENT_SECONDS) : Duration.ZERO;
        WorkloadResult warmup = runWorkload(
                0, "WARMUP", WARMUP_SAMPLES, warmupDuration, lane == LoadLane.CANONICAL);
        assertSuccessful(warmup, WARMUP_SAMPLES, warmupDuration);

        List<WorkloadResult> rounds = new ArrayList<>();
        for (int repetition = 1; repetition <= REPETITIONS; repetition++) {
            int currentRepetition = repetition;
            WorkloadResult result = runWorkload(
                    repetition,
                    "MEASUREMENT",
                    MEASUREMENT_SAMPLES,
                    measurementDuration,
                    lane == LoadLane.CANONICAL);
            assertSuccessful(result, MEASUREMENT_SAMPLES, measurementDuration);
            assertTrue(
                    result.claimP95Millis() < LATENCY_TARGET_MILLIS,
                    () -> "repetition " + currentRepetition + " READY Claim P95 was "
                            + result.claimP95Millis() + "ms");
            assertTrue(
                    result.projectionP95Millis() < LATENCY_TARGET_MILLIS,
                    () -> "repetition " + currentRepetition + " Team Projection P95 was "
                            + result.projectionP95Millis() + "ms");
            assertTrue(result.errorRate() <= MAXIMUM_ERROR_RATE);
            rounds.add(result);
        }

        writeEvidence(lane, warmup, rounds);
    }

    private static WorkloadResult runWorkload(
            int repetition,
            String phase,
            int minimumSamples,
            Duration minimumDuration,
            boolean paced)
            throws Exception {
        try (Connection connection = connection(); PreparedStatement delete = connection.prepareStatement(
                "DELETE FROM m6_q03.work_request WHERE repetition = ? AND phase = ?")) {
            delete.setInt(1, repetition);
            delete.setString(2, phase);
            delete.executeUpdate();
        }

        List<Long> claimLatencies = Collections.synchronizedList(new ArrayList<>(minimumSamples));
        List<Long> projectionLatencies = Collections.synchronizedList(new ArrayList<>(minimumSamples));
        AtomicInteger produced = new AtomicInteger();
        AtomicInteger errors = new AtomicInteger();
        AtomicBoolean producersFinished = new AtomicBoolean();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(WEB_CONCURRENCY + 4);
        List<Future<?>> futures = new ArrayList<>();
        long workloadStartedNanos = System.nanoTime();
        long stopAtNanos = workloadStartedNanos + minimumDuration.toNanos();
        Duration futureTimeout = minimumDuration.plusMinutes(3);
        try {
            for (int worker = 0; worker < TASK_CONCURRENCY; worker++) {
                futures.add(executor.submit(() -> consume(
                        start,
                        producersFinished,
                        produced,
                        claimLatencies,
                        repetition,
                        phase,
                        true)));
            }
            for (int worker = 0; worker < 2; worker++) {
                futures.add(executor.submit(() -> consume(
                        start,
                        producersFinished,
                        produced,
                        projectionLatencies,
                        repetition,
                        phase,
                        false)));
            }
            CountDownLatch producerCompletion = new CountDownLatch(WEB_CONCURRENCY);
            for (int producer = 0; producer < WEB_CONCURRENCY; producer++) {
                int assigned = minimumSamples / WEB_CONCURRENCY
                        + (producer < minimumSamples % WEB_CONCURRENCY ? 1 : 0);
                futures.add(executor.submit(() -> {
                    await(start);
                    try (Connection connection = connection(); PreparedStatement insert =
                            connection.prepareStatement(
                                    """
                                    INSERT INTO m6_q03.work_request (
                                        repetition, phase, task_status, projection_status,
                                        ready_committed_at, event_committed_at
                                    ) VALUES (?, ?, 'READY', 'PENDING', clock_timestamp(), clock_timestamp())
                                    """)) {
                        int inserted = 0;
                        while (inserted < assigned || System.nanoTime() < stopAtNanos) {
                            insert.setInt(1, repetition);
                            insert.setString(2, phase);
                            insert.executeUpdate();
                            produced.incrementAndGet();
                            inserted++;
                            if (paced && System.nanoTime() < stopAtNanos) {
                                LockSupport.parkNanos(CANONICAL_REQUEST_INTERVAL.toNanos());
                            }
                        }
                    } catch (SQLException failure) {
                        errors.incrementAndGet();
                        throw new IllegalStateException("fixed-load producer failed", failure);
                    } finally {
                        producerCompletion.countDown();
                    }
                }));
            }
            start.countDown();
            assertTrue(producerCompletion.await(futureTimeout.toSeconds(), TimeUnit.SECONDS));
            producersFinished.set(true);
            for (Future<?> future : futures) {
                future.get(futureTimeout.toSeconds(), TimeUnit.SECONDS);
            }
        } finally {
            producersFinished.set(true);
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }

        List<Long> claims = List.copyOf(claimLatencies);
        List<Long> projections = List.copyOf(projectionLatencies);
        return new WorkloadResult(
                repetition,
                phase,
                produced.get(),
                errors.get(),
                Duration.ofNanos(System.nanoTime() - workloadStartedNanos).toMillis(),
                claims,
                projections,
                nearestRank(claims, 0.95D),
                nearestRank(projections, 0.95D));
    }

    private static void consume(
            CountDownLatch start,
            AtomicBoolean producersFinished,
            AtomicInteger produced,
            List<Long> latencies,
            int repetition,
            String phase,
            boolean claim) {
        await(start);
        String sql = claim
                ? """
                  WITH candidate AS (
                      SELECT id FROM m6_q03.work_request
                      WHERE repetition = ? AND phase = ? AND task_status = 'READY'
                      ORDER BY id FOR UPDATE SKIP LOCKED LIMIT 1
                  )
                  UPDATE m6_q03.work_request request
                  SET task_status = 'CLAIMED', claimed_at = clock_timestamp()
                  FROM candidate
                  WHERE request.id = candidate.id
                  RETURNING CAST(EXTRACT(EPOCH FROM
                      (request.claimed_at - request.ready_committed_at)) * 1000 AS BIGINT)
                  """
                : """
                  WITH candidate AS (
                      SELECT id FROM m6_q03.work_request
                      WHERE repetition = ? AND phase = ? AND projection_status = 'PENDING'
                      ORDER BY id FOR UPDATE SKIP LOCKED LIMIT 1
                  )
                  UPDATE m6_q03.work_request request
                  SET projection_status = 'VISIBLE', projected_at = clock_timestamp()
                  FROM candidate
                  WHERE request.id = candidate.id
                  RETURNING CAST(EXTRACT(EPOCH FROM
                      (request.projected_at - request.event_committed_at)) * 1000 AS BIGINT)
                  """;
        try (Connection connection = connection(); PreparedStatement statement =
                connection.prepareStatement(sql)) {
            while (!producersFinished.get() || latencies.size() < produced.get()) {
                statement.setInt(1, repetition);
                statement.setString(2, phase);
                try (ResultSet result = statement.executeQuery()) {
                    if (result.next()) {
                        latencies.add(Math.max(0L, result.getLong(1)));
                    } else {
                        LockSupport.parkNanos(250_000L);
                    }
                }
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("fixed-load consumer failed", failure);
        }
    }

    private static void assertSuccessful(
            WorkloadResult result, int minimumSamples, Duration minimumDuration) {
        assertTrue(result.requests() >= minimumSamples);
        assertEquals(0, result.errors());
        assertEquals(result.requests(), result.claimLatencies().size());
        assertEquals(result.requests(), result.projectionLatencies().size());
        assertTrue(result.elapsedMillis() >= minimumDuration.toMillis());
    }

    private static long nearestRank(List<Long> values, double percentile) {
        assertTrue(!values.isEmpty());
        List<Long> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int rank = (int) Math.ceil(percentile * sorted.size());
        return sorted.get(rank - 1);
    }

    private static void writeEvidence(
            LoadLane lane, WorkloadResult warmup, List<WorkloadResult> rounds)
            throws IOException {
        Path evidence = Path.of(System.getProperty("m6.q03.evidence", DEFAULT_EVIDENCE.toString()))
                .toAbsolutePath()
                .normalize();
        Files.createDirectories(evidence.getParent());
        StringBuilder json = new StringBuilder(8_192);
        json.append("{\n")
                .append("  \"formatVersion\": 1,\n")
                .append("  \"generatedAt\": \"").append(Instant.now()).append("\",\n")
                .append("  \"loadLane\": \"").append(lane).append("\",\n")
                .append("  \"implementationPath\": \"POSTGRESQL_PROTOCOL_LOAD\",\n")
                .append("  \"dataset\": \"").append(DATASET).append("\",\n")
                .append("  \"seed\": ").append(SEED).append(",\n")
                .append("  \"canonicalProfile\": {")
                .append("\"webConcurrency\":10,\"taskConcurrency\":2,")
                .append("\"warmupSeconds\":120,\"measurementSeconds\":600,")
                .append("\"repetitions\":3,\"minimumSamplesPerMetric\":500,")
                .append("\"latencyTargetMillisExclusive\":2000,\"maximumErrorRate\":0.001},\n")
                .append("  \"executionEnvironment\": {")
                .append("\"os\":\"").append(json(System.getProperty("os.name"))).append("\",")
                .append("\"arch\":\"").append(json(System.getProperty("os.arch"))).append("\",")
                .append("\"java\":\"").append(json(System.getProperty("java.version"))).append("\",")
                .append("\"javaVendor\":\"").append(json(System.getProperty("java.vendor"))).append("\",")
                .append("\"processors\":").append(Runtime.getRuntime().availableProcessors()).append(',')
                .append("\"physicalMemoryBytes\":").append(physicalMemoryBytes()).append(',')
                .append("\"diskTotalBytes\":").append(diskTotalBytes()).append(',')
                .append("\"canonicalLinuxAmd64\":").append(canonicalEnvironment()).append("},\n")
                .append("  \"warmup\": ").append(resultJson(warmup)).append(",\n")
                .append("  \"measurementRuns\": [\n");
        for (int index = 0; index < rounds.size(); index++) {
            json.append("    ").append(resultJson(rounds.get(index)));
            json.append(index + 1 == rounds.size() ? "\n" : ",\n");
        }
        json.append("  ]\n}\n");
        Files.writeString(evidence, json, StandardCharsets.UTF_8);
        System.out.println("M6-Q03 load evidence: " + evidence);
    }

    private static String resultJson(WorkloadResult result) {
        return new StringBuilder(1_024)
                .append('{')
                .append("\"repetition\":").append(result.repetition()).append(',')
                .append("\"phase\":\"").append(result.phase()).append("\",")
                .append("\"requests\":").append(result.requests()).append(',')
                .append("\"errors\":").append(result.errors()).append(',')
                .append("\"elapsedMillis\":").append(result.elapsedMillis()).append(',')
                .append("\"errorRate\":").append(String.format(Locale.ROOT, "%.6f", result.errorRate())).append(',')
                .append("\"claimSamples\":").append(result.claimLatencies().size()).append(',')
                .append("\"claimP95Millis\":").append(result.claimP95Millis()).append(',')
                .append("\"claimHistogram\":").append(histogramJson(result.claimLatencies())).append(',')
                .append("\"projectionSamples\":").append(result.projectionLatencies().size()).append(',')
                .append("\"projectionP95Millis\":").append(result.projectionP95Millis()).append(',')
                .append("\"projectionHistogram\":").append(histogramJson(result.projectionLatencies()))
                .append('}')
                .toString();
    }

    private static String histogramJson(List<Long> values) {
        Map<String, Long> buckets = new LinkedHashMap<>();
        for (String bucket : List.of("le10", "le25", "le50", "le100", "le250", "le500",
                "le1000", "le1500", "lt2000", "ge2000")) {
            buckets.put(bucket, 0L);
        }
        for (long value : values) {
            String bucket = value <= 10 ? "le10"
                    : value <= 25 ? "le25"
                    : value <= 50 ? "le50"
                    : value <= 100 ? "le100"
                    : value <= 250 ? "le250"
                    : value <= 500 ? "le500"
                    : value <= 1_000 ? "le1000"
                    : value <= 1_500 ? "le1500"
                    : value < 2_000 ? "lt2000"
                    : "ge2000";
            buckets.computeIfPresent(bucket, (ignored, count) -> count + 1);
        }
        StringBuilder json = new StringBuilder("{");
        int index = 0;
        for (Map.Entry<String, Long> entry : buckets.entrySet()) {
            if (index++ > 0) {
                json.append(',');
            }
            json.append('\"').append(entry.getKey()).append("\":").append(entry.getValue());
        }
        return json.append('}').toString();
    }

    private static boolean canonicalEnvironment() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        String vendor = System.getProperty("java.vendor", "").toLowerCase(Locale.ROOT);
        String specification = System.getProperty("java.specification.version", "");
        return os.contains("linux") && (arch.equals("amd64") || arch.equals("x86_64"))
                && Runtime.getRuntime().availableProcessors() >= 8
                && physicalMemoryBytes() >= MINIMUM_OS_REPORTED_MEMORY_BYTES
                && diskTotalBytes() >= 100L * 1024 * 1024 * 1024
                && specification.equals("17")
                && vendor.contains("eclipse adoptium");
    }

    private static String canonicalFailure() {
        return "canonical lane requires Linux amd64, 8 CPUs, a 16 GB instance with at least "
                + "14 GiB OS-reported memory, 100 GiB disk and Eclipse Temurin 17; actual "
                + "environment is "
                + System.getProperty("os.name") + '/' + System.getProperty("os.arch")
                + ", processors=" + Runtime.getRuntime().availableProcessors()
                + ", memoryBytes=" + physicalMemoryBytes()
                + ", diskBytes=" + diskTotalBytes()
                + ", java=" + System.getProperty("java.version")
                + ", vendor=" + System.getProperty("java.vendor");
    }

    private static long physicalMemoryBytes() {
        if (ManagementFactory.getOperatingSystemMXBean()
                instanceof com.sun.management.OperatingSystemMXBean operatingSystem) {
            return operatingSystem.getTotalMemorySize();
        }
        return -1L;
    }

    private static long diskTotalBytes() {
        try {
            return Files.getFileStore(Path.of(".").toAbsolutePath()).getTotalSpace();
        } catch (IOException failure) {
            return -1L;
        }
    }

    private static String json(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("fixed-load worker interrupted", interrupted);
        }
    }

    private static Connection connection() throws SQLException {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private record WorkloadResult(
            int repetition,
            String phase,
            int requests,
            int errors,
            long elapsedMillis,
            List<Long> claimLatencies,
            List<Long> projectionLatencies,
            long claimP95Millis,
            long projectionP95Millis) {

        private WorkloadResult {
            claimLatencies = List.copyOf(claimLatencies);
            projectionLatencies = List.copyOf(projectionLatencies);
        }

        double errorRate() {
            return requests == 0 ? 1D : errors / (double) requests;
        }
    }

    private enum LoadLane {
        FIXTURE,
        CANONICAL;

        private static LoadLane from(String value) {
            return switch (value.trim().toLowerCase(Locale.ROOT)) {
                case "fixture" -> FIXTURE;
                case "nightly", "release-candidate", "canonical" -> CANONICAL;
                default -> throw new IllegalArgumentException("unsupported M6-Q03 load lane: " + value);
            };
        }
    }
}
