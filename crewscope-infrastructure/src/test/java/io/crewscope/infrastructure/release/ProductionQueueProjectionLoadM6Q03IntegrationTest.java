package io.crewscope.infrastructure.release;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import io.crewscope.application.activity.CrewScopeActivityEventTypes;
import io.crewscope.application.event.PendingOutboxEvent;
import io.crewscope.application.event.publication.EventPublication;
import io.crewscope.application.inbox.CrewScopeInboxEventTypes;
import io.crewscope.application.task.TaskExecutionQueueRepository;
import io.crewscope.application.task.TaskExecutionQueueRepository.ReadyQuery;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.infrastructure.event.projection.ActivityEventProjector;
import io.crewscope.infrastructure.event.projection.GenerationAwareProjectionRunner;
import io.crewscope.infrastructure.event.projection.InboxEventProjector;
import io.crewscope.infrastructure.event.projection.JdbcGenerationProjectionStore;
import io.crewscope.infrastructure.event.projection.JdbcProjectionGenerationRegistry;
import io.crewscope.infrastructure.event.projection.NotificationIntentProjector;
import io.crewscope.infrastructure.event.projection.ProjectionEventJsonMapper;
import io.crewscope.infrastructure.persistence.taskruntime.JdbcTaskExecutionQueueRepositoryAdapter;
import io.crewscope.infrastructure.persistence.taskruntime.TaskRuntimePersistenceMapper;
import io.crewscope.infrastructure.testcontainers.AbstractPostgresRedisContainerIntegrationTest;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * Fixed-load evidence through the production READY repository and Active Generation projectors.
 *
 * <p>The protocol-only load remains a separate deterministic baseline. This test uses the migrated
 * CrewScope schema, {@link JdbcTaskExecutionQueueRepositoryAdapter}, generation receipts and the
 * real Activity/Inbox projectors so release evidence cannot substitute a synthetic table for the
 * production persistence path.
 */
@Tag("integration")
@Execution(ExecutionMode.SAME_THREAD)
@SpringBootTest(
        classes = ProductionQueueProjectionLoadM6Q03IntegrationTest.TestApplication.class,
        properties = {
            "spring.flyway.schemas=crewscope",
            "spring.flyway.default-schema=crewscope",
            "spring.flyway.create-schemas=true"
        })
class ProductionQueueProjectionLoadM6Q03IntegrationTest
        extends AbstractPostgresRedisContainerIntegrationTest {

    private static final String DATASET = "m6-team-beta-v1";
    private static final long SEED = 20260825L;
    private static final int WEB_CONCURRENCY = 10;
    private static final int TASK_CONCURRENCY = 2;
    private static final int PROJECTOR_CONCURRENCY = 2;
    private static final int WARMUP_SECONDS = 120;
    private static final int MEASUREMENT_SECONDS = 600;
    private static final int REPETITIONS = 3;
    private static final int WARMUP_SAMPLES = 120;
    private static final int MEASUREMENT_SAMPLES = 500;
    private static final Duration FIXTURE_REQUEST_INTERVAL = Duration.ofSeconds(1);
    private static final Duration CANONICAL_REQUEST_INTERVAL = Duration.ofSeconds(1);
    private static final long LATENCY_TARGET_MILLIS = 2_000L;
    private static final double MAXIMUM_ERROR_RATE = 0.001D;
    private static final String SNAPSHOT_HASH = "a".repeat(64);
    private static final Path DEFAULT_EVIDENCE =
            Path.of("target", "m6-q03-production-load-evidence.json");

    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private TaskExecutionQueueRepository queueRepository;

    private final AtomicLong sequence = new AtomicLong();
    private OrganizationId organizationId;
    private TeamId teamId;
    private UUID workspaceId;
    private UUID projectId;
    private UUID principalId;
    private UUID memberId;
    private TransactionTemplate transaction;
    private GenerationAwareProjectionRunner activityRunner;
    private GenerationAwareProjectionRunner inboxRunner;

    @BeforeEach
    void seedProductionScope() {
        jdbc.execute("TRUNCATE TABLE crewscope.organization CASCADE");
        jdbc.update(
                "DELETE FROM crewscope.projection_definition WHERE projection_name IN (?, ?)",
                ActivityEventProjector.PROJECTION_NAME.value(),
                InboxEventProjector.PROJECTION_NAME.value());
        organizationId = OrganizationId.generate();
        teamId = TeamId.generate();
        workspaceId = UUID.randomUUID();
        projectId = UUID.randomUUID();
        principalId = UUID.randomUUID();
        memberId = UUID.randomUUID();
        transaction = new TransactionTemplate(transactionManager);

        jdbc.update(
                "INSERT INTO crewscope.organization (id, name, status) VALUES (?, ?, 'ACTIVE')",
                organizationId.value(), "M6 Q03 Load Organization");
        jdbc.update(
                """
                INSERT INTO crewscope.principal (
                    id, organization_id, principal_type, display_name, visibility, status
                ) VALUES (?, ?, 'USER', 'M6 Q03 Load Member', 'ORGANIZATION', 'ACTIVE')
                """,
                principalId, organizationId.value());
        jdbc.update(
                "INSERT INTO crewscope.team (id, organization_id, name, status) "
                        + "VALUES (?, ?, ?, 'ACTIVE')",
                teamId.value(), organizationId.value(), "M6 Q03 Load Team");
        jdbc.update(
                """
                INSERT INTO crewscope.workspace (
                    id, organization_id, team_id, workspace_type, name, status
                ) VALUES (?, ?, ?, 'TEAM', 'M6 Q03 Load Workspace', 'ACTIVE')
                """,
                workspaceId, organizationId.value(), teamId.value());
        OffsetDateTime now = dbNow();
        jdbc.update(
                """
                INSERT INTO crewscope.team_member (
                    id, organization_id, team_id, user_principal_id,
                    status, join_method, joined_at, created_at, updated_at
                ) VALUES (?, ?, ?, ?, 'ACTIVE', 'BOOTSTRAP', ?, ?, ?)
                """,
                memberId, organizationId.value(), teamId.value(), principalId, now, now, now);
        jdbc.update(
                """
                INSERT INTO crewscope.work_project (
                    id, organization_id, team_id, workspace_id, project_key, name,
                    created_by_principal_id, updated_by_principal_id
                ) VALUES (?, ?, ?, ?, 'LOAD', 'M6 Q03 Load Project', ?, ?)
                """,
                projectId, organizationId.value(), teamId.value(), workspaceId,
                principalId, principalId);

        JdbcProjectionGenerationRegistry registry =
                new JdbcProjectionGenerationRegistry(jdbc, transactionManager);
        JdbcGenerationProjectionStore store = new JdbcGenerationProjectionStore(jdbc);
        ProjectionEventJsonMapper mapper = new ProjectionEventJsonMapper(objectMapper);
        activityRunner = new GenerationAwareProjectionRunner(
                new ActivityEventProjector(
                        jdbc, objectMapper, CrewScopeActivityEventTypes.reviewedRegistry()),
                registry, store, mapper, transactionManager, Clock.systemUTC());
        inboxRunner = new GenerationAwareProjectionRunner(
                new InboxEventProjector(
                        jdbc,
                        objectMapper,
                        CrewScopeInboxEventTypes.reviewedRegistry(),
                        mock(NotificationIntentProjector.class)),
                registry, store, mapper, transactionManager, Clock.systemUTC());
    }

    @Test
    void measuresProductionQueueAndActiveGenerationProjectors() throws Exception {
        LoadLane lane = LoadLane.from(System.getProperty("m6.q03.production.lane", "fixture"));
        if (lane == LoadLane.CANONICAL) {
            assertTrue(canonicalEnvironment(), ProductionQueueProjectionLoadM6Q03IntegrationTest::canonicalFailure);
        }
        assertEquals(DATASET, "m6-team-beta-v1");
        assertEquals(SEED, 20260825L);

        Duration warmupDuration = lane == LoadLane.CANONICAL
                ? Duration.ofSeconds(WARMUP_SECONDS) : Duration.ZERO;
        Duration measurementDuration = lane == LoadLane.CANONICAL
                ? Duration.ofSeconds(MEASUREMENT_SECONDS) : Duration.ZERO;
        WorkloadResult warmup = runWorkload(
                0, "WARMUP", WARMUP_SAMPLES, warmupDuration, requestInterval(lane));
        assertSuccessful(warmup, WARMUP_SAMPLES, warmupDuration);

        List<WorkloadResult> rounds = new ArrayList<>();
        for (int repetition = 1; repetition <= REPETITIONS; repetition++) {
            WorkloadResult result = runWorkload(
                    repetition,
                    "MEASUREMENT",
                    MEASUREMENT_SAMPLES,
                    measurementDuration,
                    requestInterval(lane));
            assertSuccessful(result, MEASUREMENT_SAMPLES, measurementDuration);
            assertTrue(
                    result.readyClaimP95Millis() < LATENCY_TARGET_MILLIS,
                    () -> "production READY Claim P95 was " + result.readyClaimP95Millis() + "ms");
            assertTrue(
                    result.activityP95Millis() < LATENCY_TARGET_MILLIS,
                    () -> "production Activity P95 was " + result.activityP95Millis() + "ms");
            assertTrue(
                    result.inboxP95Millis() < LATENCY_TARGET_MILLIS,
                    () -> "production Inbox P95 was " + result.inboxP95Millis() + "ms");
            assertTrue(result.errorRate() <= MAXIMUM_ERROR_RATE);
            rounds.add(result);
        }
        writeEvidence(lane, warmup, rounds);
    }

    private WorkloadResult runWorkload(
            int repetition,
            String phase,
            int minimumSamples,
            Duration minimumDuration,
            Duration requestInterval)
            throws Exception {
        List<Long> claimLatencies = Collections.synchronizedList(new ArrayList<>(minimumSamples));
        List<Long> activityLatencies = Collections.synchronizedList(new ArrayList<>(minimumSamples));
        List<Long> inboxLatencies = Collections.synchronizedList(new ArrayList<>(minimumSamples));
        BlockingQueue<EventPublication> activityEvents = new LinkedBlockingQueue<>();
        BlockingQueue<EventPublication> inboxEvents = new LinkedBlockingQueue<>();
        AtomicInteger produced = new AtomicInteger();
        AtomicInteger errors = new AtomicInteger();
        AtomicBoolean producersFinished = new AtomicBoolean();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(
                WEB_CONCURRENCY + TASK_CONCURRENCY + PROJECTOR_CONCURRENCY * 2);
        List<Future<?>> futures = new ArrayList<>();
        long startedNanos = System.nanoTime();
        long stopAtNanos = startedNanos + minimumDuration.toNanos();
        Duration futureTimeout = minimumDuration.plusMinutes(5);
        try {
            for (int worker = 0; worker < TASK_CONCURRENCY; worker++) {
                futures.add(executor.submit(() -> consumeClaims(
                        start, producersFinished, produced, claimLatencies)));
            }
            for (int worker = 0; worker < PROJECTOR_CONCURRENCY; worker++) {
                futures.add(executor.submit(() -> consumeProjection(
                        start, producersFinished, produced, activityEvents,
                        activityLatencies, activityRunner, "activity_event")));
                futures.add(executor.submit(() -> consumeProjection(
                        start, producersFinished, produced, inboxEvents,
                        inboxLatencies, inboxRunner, "inbox_item")));
            }

            CountDownLatch producerCompletion = new CountDownLatch(WEB_CONCURRENCY);
            for (int producer = 0; producer < WEB_CONCURRENCY; producer++) {
                int assigned = minimumSamples / WEB_CONCURRENCY
                        + (producer < minimumSamples % WEB_CONCURRENCY ? 1 : 0);
                futures.add(executor.submit(() -> {
                    await(start);
                    try {
                        int inserted = 0;
                        while (inserted < assigned || System.nanoTime() < stopAtNanos) {
                            EventPublication publication = createWorkUnit(repetition, phase);
                            activityEvents.add(publication);
                            inboxEvents.add(publication);
                            produced.incrementAndGet();
                            inserted++;
                            if (!requestInterval.isZero()
                                    && (inserted < assigned || System.nanoTime() < stopAtNanos)) {
                                LockSupport.parkNanos(requestInterval.toNanos());
                            }
                        }
                    } catch (RuntimeException failure) {
                        errors.incrementAndGet();
                        throw failure;
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

        return new WorkloadResult(
                repetition,
                phase,
                produced.get(),
                errors.get(),
                Duration.ofNanos(System.nanoTime() - startedNanos).toMillis(),
                List.copyOf(claimLatencies),
                List.copyOf(activityLatencies),
                List.copyOf(inboxLatencies),
                nearestRank(claimLatencies),
                nearestRank(activityLatencies),
                nearestRank(inboxLatencies));
    }

    private void consumeClaims(
            CountDownLatch start,
            AtomicBoolean producersFinished,
            AtomicInteger produced,
            List<Long> latencies) {
        await(start);
        while (!producersFinished.get() || latencies.size() < produced.get()) {
            Long latency = transaction.execute(status -> {
                OffsetDateTime now = dbNow();
                var ready = queueRepository.lockReadyBatch(new ReadyQuery(
                        organizationId,
                        Optional.of(teamId),
                        UtcTimestamp.from(now),
                        Optional.empty(),
                        1));
                if (ready.isEmpty()) {
                    return null;
                }
                UUID executionId = ready.get(0).id().value();
                int updated = jdbc.update(
                        """
                        UPDATE crewscope.task_execution
                        SET status = 'CLAIMED', last_fencing_token = 1,
                            version = version + 1, updated_at = clock_timestamp()
                        WHERE id = ? AND status = 'READY'
                        """,
                        executionId);
                if (updated != 1) {
                    throw new IllegalStateException("locked READY execution was not claimed");
                }
                return jdbc.queryForObject(
                        """
                        SELECT CAST(EXTRACT(EPOCH FROM
                            (clock_timestamp() - created_at)) * 1000 AS BIGINT)
                        FROM crewscope.task_execution WHERE id = ?
                        """,
                        Long.class,
                        executionId);
            });
            if (latency == null) {
                LockSupport.parkNanos(250_000L);
            } else {
                latencies.add(Math.max(0L, latency));
            }
        }
    }

    private void consumeProjection(
            CountDownLatch start,
            AtomicBoolean producersFinished,
            AtomicInteger produced,
            BlockingQueue<EventPublication> events,
            List<Long> latencies,
            GenerationAwareProjectionRunner runner,
            String projectionTable) {
        await(start);
        while (!producersFinished.get() || latencies.size() < produced.get()) {
            EventPublication publication;
            try {
                publication = events.poll(100, TimeUnit.MILLISECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("projection load worker interrupted", interrupted);
            }
            if (publication == null) {
                continue;
            }
            runner.consume(publication);
            String sql = projectionTable.equals("activity_event")
                    ? """
                      SELECT CAST(EXTRACT(EPOCH FROM
                          (created_at - occurred_at)) * 1000 AS BIGINT)
                      FROM crewscope.activity_event WHERE domain_event_id = ?
                      """
                    : """
                      SELECT CAST(EXTRACT(EPOCH FROM
                          (created_at - opened_at)) * 1000 AS BIGINT)
                      FROM crewscope.inbox_item
                      WHERE source_id = (
                          SELECT subject_id FROM crewscope.domain_event WHERE event_id = ?
                      )
                      """;
            Long latency = jdbc.queryForObject(sql, Long.class, publication.eventId());
            latencies.add(Math.max(0L, latency));
        }
    }

    private EventPublication createWorkUnit(int repetition, String phase) {
        EventPublication publication = transaction.execute(status -> {
            long value = sequence.incrementAndGet();
            OffsetDateTime now = dbNow();
            UUID workItemId = UUID.randomUUID();
            UUID assignmentId = UUID.randomUUID();
            UUID snapshotId = UUID.randomUUID();
            UUID taskId = UUID.randomUUID();
            UUID executionId = UUID.randomUUID();
            UUID eventId = UUID.randomUUID();
            UUID outboxId = UUID.randomUUID();
            UUID correlationId = UUID.randomUUID();
            String itemKey = "L%07d".formatted(value);

            jdbc.update(
                    """
                    INSERT INTO crewscope.work_item (
                        id, organization_id, team_id, workspace_id, project_id,
                        item_key, item_type, title, status, priority,
                        created_at, updated_at, created_by_principal_id, updated_by_principal_id
                    ) VALUES (?, ?, ?, ?, ?, ?, 'TASK', ?, 'IN_PROGRESS', 'HIGH', ?, ?, ?, ?)
                    """,
                    workItemId, organizationId.value(), teamId.value(), workspaceId, projectId,
                    itemKey, "M6 Q03 load " + value, now, now, principalId, principalId);
            jdbc.update(
                    """
                    INSERT INTO crewscope.responsibility_assignment (
                        id, organization_id, team_id, workspace_id, project_id, work_item_id,
                        role, actor_principal_id, actor_type, actor_member_id, status,
                        assigned_by_principal_id, assigned_at, accepted_at,
                        created_at, created_by_principal_id, updated_at, updated_by_principal_id
                    ) VALUES (?, ?, ?, ?, ?, ?, 'OWNER', ?, 'USER', ?, 'ACTIVE',
                              ?, ?, ?, ?, ?, ?, ?)
                    """,
                    assignmentId, organizationId.value(), teamId.value(), workspaceId,
                    projectId, workItemId, principalId, memberId, principalId,
                    now, now, now, principalId, now, principalId);
            jdbc.update(
                    """
                    INSERT INTO crewscope.task_responsibility_snapshot (
                        id, organization_id, team_id, workspace_id, project_id, work_item_id,
                        snapshot_hash, captured_at, created_at, created_by_principal_id,
                        updated_at, updated_by_principal_id
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    snapshotId, organizationId.value(), teamId.value(), workspaceId, projectId,
                    workItemId, SNAPSHOT_HASH, now, now, principalId, now, principalId);
            jdbc.update(
                    """
                    INSERT INTO crewscope.task (
                        id, organization_id, team_id, workspace_id, project_id, work_item_id,
                        source_type, source_work_item_version, responsibility_snapshot_id,
                        status, objective, acceptance_criteria, created_at,
                        created_by_principal_id, updated_at, updated_by_principal_id
                    ) VALUES (?, ?, ?, ?, ?, ?, 'WORK_ITEM', 0, ?, 'CREATED',
                              'Measure production queue', '["Claim once"]'::JSONB, ?, ?, ?, ?)
                    """,
                    taskId, organizationId.value(), teamId.value(), workspaceId, projectId,
                    workItemId, snapshotId, now, principalId, now, principalId);
            jdbc.update(
                    """
                    INSERT INTO crewscope.task_execution (
                        id, organization_id, team_id, workspace_id, project_id, task_id,
                        attempt, max_attempts, priority, not_before, status,
                        created_at, created_by_principal_id, updated_at, updated_by_principal_id
                    ) VALUES (?, ?, ?, ?, ?, ?, 1, 1, 80, ?, 'READY', ?, ?, ?, ?)
                    """,
                    executionId, organizationId.value(), teamId.value(), workspaceId, projectId,
                    taskId, now, now, principalId, now, principalId);

            Map<String, String> payload = new LinkedHashMap<>();
            payload.put("workItemId", workItemId.toString());
            payload.put("role", "OWNER");
            payload.put("actorPrincipalId", principalId.toString());
            jdbc.update(
                    """
                    INSERT INTO crewscope.domain_event (
                        event_id, event_type, schema_version, organization_id, team_id,
                        workspace_id, subject_type, subject_id, aggregate_version,
                        actor_type, actor_id, correlation_id, occurred_at, payload
                    ) VALUES (?, 'WORK_ITEM_OWNER_ASSIGNED', '1', ?, ?, ?,
                              'RESPONSIBILITY_ASSIGNMENT', ?, 0, 'USER', ?, ?, ?, CAST(? AS JSONB))
                    """,
                    eventId, organizationId.value(), teamId.value(), workspaceId,
                    assignmentId, principalId, correlationId, now, json(payload));
            String partition = organizationId + ":RESPONSIBILITY_ASSIGNMENT:" + assignmentId;
            jdbc.update(
                    """
                    INSERT INTO crewscope.outbox_event (
                        id, domain_event_id, topic, partition_key, delivery_status,
                        retry_count, created_at, version, updated_at
                    ) VALUES (?, ?, ?, ?, 'PENDING', 0, ?, 0, ?)
                    """,
                    outboxId, eventId, PendingOutboxEvent.DOMAIN_EVENTS_TOPIC,
                    partition, now, now);
            return new EventPublication(
                    outboxId,
                    eventId,
                    PendingOutboxEvent.DOMAIN_EVENTS_TOPIC,
                    partition,
                    1,
                    UtcTimestamp.from(now),
                    eventJson(eventId, assignmentId, correlationId, now, payload));
        });
        return java.util.Objects.requireNonNull(publication, "production work unit");
    }

    private String eventJson(
            UUID eventId,
            UUID assignmentId,
            UUID correlationId,
            OffsetDateTime occurredAt,
            Map<String, String> payload) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("eventId", eventId.toString());
        event.put("eventType", "WORK_ITEM_OWNER_ASSIGNED");
        event.put("schemaVersion", "1");
        event.put("organizationId", organizationId.toString());
        event.put("teamId", teamId.toString());
        event.put("workspaceId", workspaceId.toString());
        event.put("aggregateType", "RESPONSIBILITY_ASSIGNMENT");
        event.put("aggregateId", assignmentId.toString());
        event.put("aggregateVersion", 0);
        event.put("actorType", "USER");
        event.put("actorId", principalId.toString());
        event.put("correlationId", correlationId.toString());
        event.put("occurredAt", UtcTimestamp.from(occurredAt).toString());
        event.put("payload", payload);
        return json(event);
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (RuntimeException failure) {
            throw new IllegalStateException("M6-Q03 load JSON could not be encoded", failure);
        }
    }

    private OffsetDateTime dbNow() {
        return jdbc.queryForObject("SELECT clock_timestamp()", OffsetDateTime.class);
    }

    private static Duration requestInterval(LoadLane lane) {
        return lane == LoadLane.CANONICAL
                ? CANONICAL_REQUEST_INTERVAL : FIXTURE_REQUEST_INTERVAL;
    }

    private static void assertSuccessful(
            WorkloadResult result, int minimumSamples, Duration minimumDuration) {
        assertTrue(result.requests() >= minimumSamples);
        assertEquals(0, result.errors());
        assertEquals(result.requests(), result.claimLatencies().size());
        assertEquals(result.requests(), result.activityLatencies().size());
        assertEquals(result.requests(), result.inboxLatencies().size());
        assertTrue(result.elapsedMillis() >= minimumDuration.toMillis());
    }

    private static long nearestRank(List<Long> values) {
        assertTrue(!values.isEmpty());
        List<Long> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        return sorted.get((int) Math.ceil(0.95D * sorted.size()) - 1);
    }

    private static void writeEvidence(
            LoadLane lane, WorkloadResult warmup, List<WorkloadResult> rounds)
            throws IOException {
        Path evidence = Path.of(System.getProperty(
                        "m6.q03.production.evidence", DEFAULT_EVIDENCE.toString()))
                .toAbsolutePath()
                .normalize();
        Files.createDirectories(evidence.getParent());
        StringBuilder json = new StringBuilder(12_000);
        json.append("{\n")
                .append("  \"formatVersion\": 1,\n")
                .append("  \"generatedAt\": \"").append(Instant.now()).append("\",\n")
                .append("  \"loadLane\": \"").append(lane).append("\",\n")
                .append("  \"implementationPath\": \"PRODUCTION_QUEUE_ACTIVITY_INBOX\",\n")
                .append("  \"dataset\": \"").append(DATASET).append("\",\n")
                .append("  \"seed\": ").append(SEED).append(",\n")
                .append("  \"canonicalProfile\": {")
                .append("\"webConcurrency\":10,\"taskConcurrency\":2,")
                .append("\"warmupSeconds\":120,\"measurementSeconds\":600,")
                .append("\"repetitions\":3,\"minimumSamplesPerMetric\":500,")
                .append("\"latencyTargetMillisExclusive\":2000,\"maximumErrorRate\":0.001},\n")
                .append("  \"executionEnvironment\": {")
                .append("\"os\":\"").append(escape(System.getProperty("os.name"))).append("\",")
                .append("\"arch\":\"").append(escape(System.getProperty("os.arch"))).append("\",")
                .append("\"java\":\"").append(escape(System.getProperty("java.version"))).append("\",")
                .append("\"javaVendor\":\"").append(escape(System.getProperty("java.vendor"))).append("\",")
                .append("\"processors\":").append(Runtime.getRuntime().availableProcessors()).append(',')
                .append("\"physicalMemoryBytes\":").append(physicalMemoryBytes()).append(',')
                .append("\"diskTotalBytes\":").append(diskTotalBytes()).append(',')
                .append("\"canonicalLinuxAmd64\":").append(canonicalEnvironment()).append("},\n")
                .append("  \"warmup\": ").append(resultJson(warmup)).append(",\n")
                .append("  \"measurementRuns\": [\n");
        for (int index = 0; index < rounds.size(); index++) {
            json.append("    ").append(resultJson(rounds.get(index)))
                    .append(index + 1 == rounds.size() ? "\n" : ",\n");
        }
        json.append("  ]\n}\n");
        Files.writeString(evidence, json, StandardCharsets.UTF_8);
        System.out.println("M6-Q03 production load evidence: " + evidence);
    }

    private static String resultJson(WorkloadResult result) {
        return new StringBuilder(2_000)
                .append('{')
                .append("\"repetition\":").append(result.repetition()).append(',')
                .append("\"phase\":\"").append(result.phase()).append("\",")
                .append("\"requests\":").append(result.requests()).append(',')
                .append("\"errors\":").append(result.errors()).append(',')
                .append("\"elapsedMillis\":").append(result.elapsedMillis()).append(',')
                .append("\"errorRate\":")
                .append(String.format(Locale.ROOT, "%.6f", result.errorRate())).append(',')
                .append("\"readyClaimSamples\":").append(result.claimLatencies().size()).append(',')
                .append("\"readyClaimP95Millis\":").append(result.readyClaimP95Millis()).append(',')
                .append("\"readyClaimHistogram\":").append(histogramJson(result.claimLatencies())).append(',')
                .append("\"activitySamples\":").append(result.activityLatencies().size()).append(',')
                .append("\"activityP95Millis\":").append(result.activityP95Millis()).append(',')
                .append("\"activityHistogram\":").append(histogramJson(result.activityLatencies())).append(',')
                .append("\"inboxSamples\":").append(result.inboxLatencies().size()).append(',')
                .append("\"inboxP95Millis\":").append(result.inboxP95Millis()).append(',')
                .append("\"inboxHistogram\":").append(histogramJson(result.inboxLatencies()))
                .append('}')
                .toString();
    }

    private static String histogramJson(List<Long> values) {
        Map<String, Long> buckets = new LinkedHashMap<>();
        for (String bucket : List.of(
                "le10", "le25", "le50", "le100", "le250", "le500",
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
                    : value < 2_000 ? "lt2000" : "ge2000";
            buckets.computeIfPresent(bucket, (ignored, count) -> count + 1);
        }
        StringBuilder json = new StringBuilder("{");
        int index = 0;
        for (Map.Entry<String, Long> entry : buckets.entrySet()) {
            if (index++ > 0) json.append(',');
            json.append('"').append(entry.getKey()).append("\":").append(entry.getValue());
        }
        return json.append('}').toString();
    }

    private static boolean canonicalEnvironment() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        String vendor = System.getProperty("java.vendor", "").toLowerCase(Locale.ROOT);
        return os.contains("linux") && (arch.equals("amd64") || arch.equals("x86_64"))
                && Runtime.getRuntime().availableProcessors() >= 8
                && physicalMemoryBytes() >= 16L * 1024 * 1024 * 1024
                && diskTotalBytes() >= 100L * 1024 * 1024 * 1024
                && System.getProperty("java.specification.version", "").equals("17")
                && vendor.contains("eclipse adoptium");
    }

    private static String canonicalFailure() {
        return "canonical production load requires Linux amd64, 8 CPUs, 16 GiB memory, "
                + "100 GiB disk and Eclipse Temurin 17; actual environment is "
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

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("production load worker interrupted", interrupted);
        }
    }

    private record WorkloadResult(
            int repetition,
            String phase,
            int requests,
            int errors,
            long elapsedMillis,
            List<Long> claimLatencies,
            List<Long> activityLatencies,
            List<Long> inboxLatencies,
            long readyClaimP95Millis,
            long activityP95Millis,
            long inboxP95Millis) {

        private WorkloadResult {
            claimLatencies = List.copyOf(claimLatencies);
            activityLatencies = List.copyOf(activityLatencies);
            inboxLatencies = List.copyOf(inboxLatencies);
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
                default -> throw new IllegalArgumentException(
                        "unsupported M6-Q03 production load lane: " + value);
            };
        }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({
        TaskRuntimePersistenceMapper.class,
        JdbcTaskExecutionQueueRepositoryAdapter.class
    })
    static class TestApplication {}
}
